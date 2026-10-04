# Deployment Runbook — Visitor Middleware Service

**Backend domain:** `https://api.app-cube.tech`
**Host:** `srv1845817` (`187.77.126.196`), Ubuntu, 2 vCPU / 8 GB
**Stack dir:** `/srv/vms-myboga`
**CI/CD:** GitHub Actions on `main` → GHCR → SSH deploy

---

## 1. Why this shape

The host is **not** a dedicated box for this service. It already runs four other
stacks and two native PostgreSQL clusters, so every decision below is about
staying out of their way:

| Decision | Reason |
|---|---|
| `vms-db` publishes **no** host port | Host already has native PostgreSQL on `127.0.0.1:5432` (`16-main`) and `5433` (`16-hive`). Not publishing also keeps Postgres off the internet, where UFW would otherwise be the only thing stopping it. |
| `vms-myboga-app` binds `127.0.0.1:8080` | UFW policy is `DROP` with only 22, 80, 443, 2222 open. Loopback binding means nginx is the only way in. Port 8080 was verified free. |
| Own network `vms_net` + volume `vms_pgdata` | No name or subnet collision with `bookspace_net`, `bookspace_pgdata`, `device-dashboard-hive-postgres-1`, etc. |
| nginx site file named **`vms-api.app-cube.tech`**, not `api.app-cube.tech` | See below. The filename decides which vhost becomes nginx's implicit `default_server`, and the obvious name silently took that slot away from `app-cube.tech`. |
| Cert via **Let's Encrypt DNS-01** | `certbot certonly` writes no nginx config at all. Every other subdomain on this host uses HTTP-01, but DNS-01 keeps the shared nginx untouched and works behind Cloudflare's orange cloud. |
| nginx change is **one new file** | No existing vhost is edited. `nginx -t` gates `systemctl reload` (reload, never restart, so in-flight connections survive). |
| Build runs on **GitHub's runners** | A Maven + Docker build on this 2-vCPU box would compete with 9 running containers. The deploy here is only `compose pull` + `up -d`. |
| Container runs as **uid 10001** | Internet-facing container on a shared production host. A uid is fixed so host bind mounts can be chowned to match. |
| `restart: always` + json-file log caps | Restart on crash/reboot; logs capped at 10 MB × 3 so one chatty service cannot eat the shared disk. |
| `dns:` pinned on the app container | See below — without it the JVM cannot resolve anything on this host. |

### The DNS trap (this one would have shipped a broken service)

Both the dev machine and the VPS have the same `systemd-resolved` setup:

```
nameserver 127.0.0.53
search .
```

Inside Docker, `127.0.0.53` is not reachable — Docker's embedded resolver at
`127.0.0.11` forwards to it. glibc clients cope, **`curl` from inside the
container returns 200**, and the app reports itself perfectly healthy. But the
JVM's own resolver does not cope, so every outbound call fails:

```
Failed to fetch doors from Nuveq: I/O error on GET request for
  "https://api-v2.nuveq.cloud/api/visitors/doors": null
Caused by: java.nio.channels.UnresolvedAddressException: null
```

The `null` message is what makes it hard to read. The damage is silent: the
healthcheck passes, `/actuator/health` says `UP`, the doors list is just
empty, and the card-event poller logs the same error every 10 seconds forever.

Fix — per container, so no other stack on the host is affected:

```yaml
    dns:
      - ${DOCKER_DNS_1:-1.1.1.1}
      - ${DOCKER_DNS_2:-8.8.8.8}
```

After this the startup sync succeeded on the first try (`total=6`). Changing
the Docker daemon's default DNS instead would have been the tidier fix but
would have altered name resolution for all nine existing containers, which is
exactly the kind of blast radius to avoid on a shared host.

**If doors or Nuveq calls are ever empty again, check this first.**

### The nginx default-server trap

`nginx.conf` ends with `include /etc/nginx/sites-enabled/*`, so vhosts are
loaded in **alphabetical filename order** and the first `listen 443 ssl` block
with no matching `server_name` becomes the implicit `default_server`.

`api.app-cube.tech` sorts *before* `app-cube.tech` (`api` < `app`, because
`i` < `p`). So the obvious filename made the new vhost the catch-all for every
HTTPS request whose `Host` matched nothing — including `webrtc.app-cube.tech`,
which has DNS but deliberately no vhost of its own.

The symptom was quiet and misleading: `webrtc.app-cube.tech` started returning
**500**, with a body that was unmistakably the Spring Boot app's own JSON error
format, because unmatched requests were being proxied to
`127.0.0.1:8080`. Nothing in the nginx config looked wrong and `nginx -t`
passed cleanly. Only the before/after comparison of every existing hostname
caught it.

Fix: name the file so it sorts *after* `app-cube.tech`, which restores the
original implicit default without editing a single existing file:

```
app-cube.tech            <- first, implicit default (as before)
bookspace.app-cube.tech
campaign.app-cube.tech
device-dashboard-hive-cast.com
hive-companion.web.id
vms-api.app-cube.tech    <- ours
webrtc.conf
```

The alternative — marking `app-cube.tech` explicitly `listen 443 ssl
default_server` — is more robust going forward, but it means editing an
existing vhost, so it was not done here. Worth doing if this host ever gets
more services.

**Always diff every pre-existing hostname before and after touching nginx.**

---

## 2. Host inventory (as found, do not disturb)

Containers — all must stay `Up` after any change:

```
device-dashboard-hive-app-1              0.0.0.0:4000, 0.0.0.0:5173
device-dashboard-hive-postgres-1         internal 5432
bookspace-app                            internal 3000
bookspace-db                             internal 5432
bookspace-frontend                       127.0.0.1:3050
niscaya-antrian-app                      127.0.0.1:3000
webrtc-cs-call-kiosk-app-1               127.0.0.1:3002
webrtc-cs-call-signaling-server-1        127.0.0.1:3001
webrtc-cs-call-employee-dashboard-1      127.0.0.1:3003
```

nginx sites in `/etc/nginx/sites-enabled/`: `app-cube.tech`,
`bookspace.app-cube.tech`, `campaign.app-cube.tech`,
`device-dashboard-hive-cast.com`, `hive-companion.web.id`, `webrtc.conf`.
`conf.d/websocket.conf` defines the `$connection_upgrade` map — reused, not
redefined.

Ports already taken: 22, 53, 80, 443, 2222, 3000-3004, 3050, 3400, 4000, 5173,
5432, 5433, 65529.

Cloudflare zone `app-cube.tech` (`e53b0615682d09390656b294e036f853`); every
record is orange-cloud proxied, and the existing certs are **per-subdomain
non-wildcard**, so `api.app-cube.tech` needed its own.

---

## 3. CI/CD design

`.github/workflows/ci-cd.yml`, triggered on push to `main`, on PRs to `main`,
and manually.

```
test ──────────────┐
                   ├──> build-image ──> deploy
migration-smoke ───┘
```

- **test** — `mvn verify` on H2. Fast gate only.
- **migration-smoke** — boots the real jar against a `postgres:16-alpine`
  service. This exists because `src/test/resources/application.yml` sets
  `flyway.enabled: false` and `ddl-auto: create-drop`, so the unit suite
  **never touches the SQL migrations**. It asserts:
  1. `/actuator/health` → `"status":"UP"`
  2. `Started VisitorMiddlewareApplication` in the boot log (i.e. Hibernate
     `ddl-auto: validate` accepted the migrated schema)
  3. every `flyway_schema_history` row has `success = true`
  4. `room.expire_minutes` exists — V7 was untracked and had never been run
     anywhere
- **build-image** — `docker/build-push-action` → `ghcr.io/priyayids/vms-myboga`
  tagged `latest` + `sha-<full>`, with `type=gha` layer caching.
- **deploy** — SSH as the deploy user and run `deploy/deploy.sh`, then smoke
  test `https://api.app-cube.tech/actuator/health`.

`concurrency` is **queued, not cancelled** — a half-finished release must not be
interrupted mid-deploy.

### Secrets required

| Secret | Notes |
|---|---|
| `VPS_SSH_PRIVATE_KEY` | **deploy-only key**, not the root key |
| `VPS_HOST` | `187.77.126.196` |
| `VPS_USER` | `deploy` |

The VPS host *public* keys are committed in the workflow on purpose. They are
public data, and pinning them is precisely what stops a DNS hijack from
pointing the deploy at an attacker's host. Do not replace this with
`StrictHostKeyChecking=no`.

### SSH access notes

Locally the key is `~/.ssh/id_rsa` (ED25519 despite the name) and **is
passphrase-protected**, but it is already unlocked in gnome-keyring's agent at
`/run/user/1000/gcr/ssh`. That agent refuses to sign for uid 0, so the working
invocation is:

```bash
su p4i -c "SSH_AUTH_SOCK=/run/user/1000/gcr/ssh ssh server-vps '<cmd>'"
```

`server-vps-deploy` (the `deploy` user, already a member of the `docker` group)
did **not** work out of the box — `Permission denied (publickey)` — because the
pubkey was never added to that account's `authorized_keys`. The CI deploy key
is a separate, freshly generated key pair, unrelated to the operator key.

---

## 4. First-time host setup (one-off, manual)

1. Clone into `/srv/vms-myboga`, generate `.env` with a random DB password,
   create `logs/` and `data/qr-codes` chowned to `10001:10001`:
   ```bash
   bash /srv/vms-myboga/deploy/bootstrap.sh
   ```
2. Put the Nuveq API key into `/srv/vms-myboga/.env` (`chmod 600`).

### Cloudflare DNS

```
A   api.app-cube.tech   187.77.126.196   proxied=true
```

Proxied, matching every other record in the zone.

### Certificate

```bash
apt-get install -y python3-certbot-dns-cloudflare      # additive package
install -d -m 700 /root/.secrets
printf 'dns_cloudflare_api_token = %s\n' "<token from .personalEnv>" \
  > /root/.secrets/cf-dns.ini
chmod 600 /root/.secrets/cf-dns.ini

certbot certonly --dns-cloudflare \
  --dns-cloudflare-credentials /root/.secrets/cf-dns.ini \
  -d api.app-cube.tech --non-interactive --agree-tos
```

`certonly` — no nginx installer runs, so the live nginx config is untouched. The
credentials file must **persist**: `certbot.timer` re-runs twice daily and needs
it to renew unattended.

### nginx site

Back up first, then add only the new file:

```bash
tar -czf /root/nginx-backup-$(date +%Y%m%d-%H%M%S).tgz /etc/nginx
```

`/etc/nginx/sites-available/vms-api.app-cube.tech`  — note the filename, see
the default-server trap above:

```nginx
server {
    listen 80;
    server_name api.app-cube.tech;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl;
    listen [::]:443 ssl;
    server_name api.app-cube.tech;

    ssl_certificate     /etc/letsencrypt/live/api.app-cube.tech/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.app-cube.tech/privkey.pem;
    include /etc/letsencrypt/options-ssl-nginx.conf;
    ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem;

    # The registration payload can carry a base64 webcam selfie.
    client_max_body_size 10m;

    location / {
        proxy_pass         http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_set_header   Upgrade           $http_upgrade;
        proxy_set_header   Connection        $connection_upgrade;
        proxy_read_timeout 120s;
        proxy_send_timeout 120s;
    }
}
```

```bash
ln -s /etc/nginx/sites-available/vms-api.app-cube.tech /etc/nginx/sites-enabled/
nginx -t && systemctl reload nginx
```

`nginx -t` must pass or the reload is skipped. `$connection_upgrade` comes from
the existing `/etc/nginx/conf.d/websocket.conf` map.

---

## 5. Deploy

```bash
# manual
ssh deploy@187.77.126.196 'APP_DIR=/srv/vms-myboga bash /srv/vms-myboga/deploy/deploy.sh'

# or just push to main
git push origin main
```

`deploy.sh` is idempotent, refreshes the checkout with a best-effort
`git pull --ff-only` **and then re-execs itself**, pulls the app image, brings
the stack up with `--no-build`, then blocks until the container healthcheck
reports healthy and asserts `vms-db` is healthy too. It exits non-zero with the
last 80 log lines on failure. It never touches the volume, so rolling back a
bad image keeps the data.

### Two bootstrap deadlocks hit on the way (both now fixed, both worth knowing)

**1. A deploy script that updates itself too late.** The first version did the
`chown` check *before* `git pull`. So the VPS ran the copy of `deploy.sh` from
its previous checkout, which died on the `chown` before it could fetch the
version with the `chown` fixed — a deadlock that needed a manual `git pull` to
break. `deploy.sh` now pulls first and re-execs (`VMS_DEPLOY_REEXEC`), so a
fix to the deploy script lands on the deploy that carries it.

**2. A stale GHCR token on the `deploy` account.** `/home/deploy/.docker/config.json`
held a `ghcr.io` credential from 2026-08-03. Docker prefers a stored credential
over an anonymous token, so pulls failed with `error from registry: denied`
even though the package is publicly readable — `docker pull` as **root** on the
same host worked fine at the same moment, which is what made it look like a
permissions problem rather than a credentials problem. The GHCR packages of a
public repo are anonymously pullable, so the fix was to remove the dead entry:

```bash
cp -a ~/.docker/config.json ~/.docker/config.json.stale-20261004   # kept
# then write {"auths":{}}
```

If pulls ever start failing with `denied`, check for a leftover credential
before anything else.

### Rollback

```bash
cd /srv/vms-myboga
docker tag ghcr.io/priyayids/vms-myboga:sha-<previous> ghcr.io/priyayids/vms-myboga:latest
docker compose -f docker-compose.prod.yml up -d --no-build --force-recreate app
```

---

## 6. Verification

```bash
# containers
docker compose -f /srv/vms-myboga/docker-compose.prod.yml ps

# migrations actually applied
docker exec vms-db psql -U vms_app -d visitor_bridge \
  -c "select installed_rank, version, description, success from flyway_schema_history order by 1"

# backend, loopback
curl -fsS http://127.0.0.1:8080/actuator/health

# backend, public
curl -fsS https://api.app-cube.tech/actuator/health

# Nuveq connectivity from inside the app container - this is the check that
# would have caught the DNS trap
docker exec vms-myboga-app curl -sS -o /dev/null -w '%{http_code}\n' https://api-v2.nuveq.cloud/
docker logs vms-myboga-app 2>&1 | grep -i "doors synchronization completed"

# certificate
openssl s_client -connect api.app-cube.tech:443 -servername api.app-cube.tech </dev/null 2>/dev/null \
  | openssl x509 -noout -text | grep -A1 "Subject Alternative Name"

# CORS, as the local form browser will see it
curl -si -X OPTIONS https://api.app-cube.tech/api/visitors/registration \
  -H 'Origin: http://localhost:3000' \
  -H 'Access-Control-Request-Method: POST' | grep -i 'access-control'

# logs
tail -f /srv/vms-myboga/logs/application.log
tail -f /srv/vms-myboga/logs/transactions.log
```

### Non-regression check

Run after every change and compare against the pre-change baseline:

```bash
docker ps --format '{{.Names}}|{{.Status}}|{{.Ports}}'
ss -tlnp
for h in app-cube.tech www.app-cube.tech bookspace.app-cube.tech campaign.app-cube.tech \
         device.app-cube.tech dashboard.app-cube.tech webrtc.app-cube.tech \
         device-dashboard-hive-cast.com hive-companion.web.id; do
  printf '%s %s\n' "$h" "$(curl -s -o /dev/null -w '%{http_code}' -A 'Mozilla/5.0' "https://$h/")"
done
nginx -t
```

Note: Cloudflare returns **403** to requests with a non-browser User-Agent, so
always pass `-A 'Mozilla/5.0'` or compare against a browser-like baseline.

---

## 7. Configuration reference

| Variable | Default | Notes |
|---|---|---|
| `DB_NAME` | `visitor_bridge` | |
| `DB_USERNAME` | — | required, no default; compose fails fast |
| `DB_PASSWORD` | — | required; generated by `bootstrap.sh` |
| `NUVEQ_BASE_URL` | `https://api-v2.nuveq.cloud` | |
| `NUVEQ_API_KEY` | — | required; app refuses to boot without it |
| `NUVEQ_EVENT_MODE` | `polling` | or `webhook`, needs Nuveq-side callback config |
| `NUVEQ_POLL_INTERVAL_MS` | `10000` | polling mode only |
| `APP_BASE_URL` | `https://api.app-cube.tech` | QR-code URLs are absolute |
| `GHCR_OWNER` | `priyayids` | image namespace |

---

## 8. Open items / not done

- **Repo is public.** No real secret is in it (`.env` is gitignored; the jar
  only contains `${NUVEQ_API_KEY:}` placeholders) and Actions secrets are not
  exposed to fork PRs, so the public + CI-secrets combination is workable. Still
  worth switching to private: it is the conventional pairing with holding a
  deploy key in repo secrets.
- `server-vps-deploy` still fails for the operator's own key. The CI deploy key
  is separate, so this does not block anything, but adding the operator key to
  `/home/deploy/.ssh/authorized_keys` would give a non-root deploy path.
- No branch protection on `main` yet, so anyone with push access can deploy.
  Adding a required-status-check on `test` + `migration-smoke` would make that
  safe.
- Nuveq has not been configured to call the webhook, so the deployed instance
  uses polling. Flip `NUVEQ_EVENT_MODE` only after the Nuveq-side callback URL
  `https://api.app-cube.tech/api/events/nuveq-webhook` is registered.
- The form UI (`../vms-form`) is deliberately **not** deployed and has no nginx
  site; it runs locally against `https://api.app-cube.tech`.
