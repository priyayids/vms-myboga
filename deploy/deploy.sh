#!/usr/bin/env bash
#
# Runs ON THE VPS, invoked over SSH by .github/workflows/deploy.yml.
# Idempotent: safe to re-run, does not touch the database volume.
#
# Only this app's own project is touched. Other stacks on the host
# (bookspace, webrtc-cs-call, device-dashboard-hive, niscaya) and the host
# nginx are never referenced here.

set -euo pipefail

APP_DIR="${APP_DIR:-/srv/vms-myboga}"
COMPOSE_FILE="docker-compose.prod.yml"
IMAGE="ghcr.io/${GHCR_OWNER:-priyayids}/vms-myboga:latest"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"

cd "$APP_DIR"

if [[ ! -f ".env" ]]; then
  echo "FATAL: $APP_DIR/.env is missing. Run deploy/bootstrap.sh on the host first." >&2
  exit 1
fi

# .env holds the DB password and the Nuveq API key.
chmod 600 .env

# The image runs as uid/gid 10001. These are bind mounts, so Docker does not
# create them with the right ownership for us. Only root can chown, and CI
# deploys as the unprivileged `deploy` user, so bootstrap.sh does this once as
# root and here we only verify.
mkdir -p logs data/qr-codes
if [[ $EUID -eq 0 ]]; then
  chown -R 10001:10001 logs data/qr-codes
else
  for d in logs data/qr-codes; do
    owner="$(stat -c '%u:%g' "$d")"
    if [[ "$owner" != "10001:10001" ]]; then
      echo "WARN: $d is owned by $owner, expected 10001:10001." >&2
      echo "      Run as root once: chown -R 10001:10001 $APP_DIR/logs $APP_DIR/data/qr-codes" >&2
    fi
  done
fi

# Refresh the compose files themselves so compose-file changes in a commit
# reach the host too. Best effort: the deploy must not depend on git working
# (a private repo or an expired token should still allow an image deploy).
if git rev-parse --git-dir >/dev/null 2>&1; then
  if git pull --ff-only --quiet origin "${DEPLOY_BRANCH:-main}"; then
    echo "compose files updated to $(git rev-parse --short HEAD)"
  else
    echo "WARN: git pull failed, continuing with the compose files already on disk" >&2
  fi
fi

echo "--- rolling update ---"
previous_id="$(docker compose -f "$COMPOSE_FILE" ps -q app 2>/dev/null || true)"
previous_image="$(docker inspect -f '{{.Image}}' "$previous_id" 2>/dev/null || echo none)"

docker compose -f "$COMPOSE_FILE" pull app
docker compose -f "$COMPOSE_FILE" up -d --no-build

echo "--- waiting for ${IMAGE} to report healthy (timeout ${HEALTH_TIMEOUT}s) ---"
deadline=$(( SECONDS + HEALTH_TIMEOUT ))
status="starting"
while (( SECONDS < deadline )); do
  status="$(docker inspect \
    -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
    "$(docker compose -f "$COMPOSE_FILE" ps -q app)" 2>/dev/null || echo missing)"
  case "$status" in
    healthy|running)
      echo "app is ${status}"
      break
      ;;
    unhealthy|exited|dead)
      echo "app became ${status}; last 80 log lines:" >&2
      docker compose -f "$COMPOSE_FILE" logs --tail 80 app >&2
      exit 1
      ;;
  esac
  sleep 5
done

if [[ "$status" != "healthy" && "$status" != "running" ]]; then
  echo "FATAL: app still '${status}' after ${HEALTH_TIMEOUT}s. Previous image was ${previous_image}." >&2
  docker compose -f "$COMPOSE_FILE" logs --tail 80 app >&2
  exit 1
fi

# The db container must never be left unhealthy; Flyway would have failed the
# app boot instead, so a quick assert is enough.
db_status="$(docker inspect -f '{{.State.Health.Status}}' vms-db 2>/dev/null || echo missing)"
echo "db is ${db_status}"
if [[ "$db_status" != "healthy" ]]; then
  echo "FATAL: vms-db is ${db_status}" >&2
  exit 1
fi

echo "--- deployed ---"
docker compose -f "$COMPOSE_FILE" ps
