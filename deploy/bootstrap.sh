#!/usr/bin/env bash
#
# ONE-TIME VPS bootstrap for the Visitor Middleware Service.
# Run this by hand as root on the host:
#   bash deploy/bootstrap.sh
#
# Safe to re-run. It never touches other stacks, the host nginx, or the
# native PostgreSQL clusters.
#
# The nginx site, the Let's Encrypt certificate and the Cloudflare DNS record
# are NOT created here: those are one-off and are documented in
# Agent/notes/deployment.md.

set -euo pipefail

APP_DIR="${APP_DIR:-/srv/vms-myboga}"
REPO_URL="${REPO_URL:-https://github.com/priyayids/vms-myboga.git}"
BRANCH="${BRANCH:-main}"
APP_PORT="${APP_PORT:-8080}"

if [[ $EUID -ne 0 ]]; then
  echo "FATAL: run as root." >&2
  exit 1
fi

if [[ -e "$APP_DIR" ]]; then
  echo "== $APP_DIR already exists, reusing it"
else
  echo "== cloning into $APP_DIR"
  mkdir -p "$APP_DIR"
  git clone --branch "$BRANCH" "$REPO_URL" "$APP_DIR"
fi

cd "$APP_DIR"

# Generate .env with a strong random DB password, preserving an existing one.
if [[ -f .env ]]; then
  echo "== .env exists, leaving it untouched"
else
  DB_PASSWORD="$(openssl rand -hex 24)"
  cat > .env <<EOF
DB_NAME=visitor_bridge
DB_USERNAME=vms_app
DB_PASSWORD=${DB_PASSWORD}
NUVEQ_BASE_URL=https://api-v2.nuveq.cloud
NUVEQ_API_KEY=
NUVEQ_EVENT_MODE=polling
NUVEQ_POLL_INTERVAL_MS=10000
APP_BASE_URL=https://api.app-cube.tech
GHCR_OWNER=priyayids
EOF
  chmod 600 .env
  echo "== wrote $APP_DIR/.env (set NUVEQ_API_KEY before the first deploy)"
  echo "   generated DB password: ${DB_PASSWORD}"
fi

mkdir -p logs data/qr-codes
chown -R 10001:10001 logs data/qr-codes

# Fail early rather than at deploy time.
if ! grep -qE '^NUVEQ_API_KEY=.+$' .env; then
  echo "WARN: NUVEQ_API_KEY is empty in $APP_DIR/.env - the app will refuse to start" >&2
fi

# Refuse to publish the app port on a public interface: it must stay behind
# the host nginx on loopback, because the host UFW only opens 22/80/443/2222.
if ss -ltn "sport = :${APP_PORT}" | grep -q 0.0.0.0; then
  echo "FATAL: port ${APP_PORT} is already bound on 0.0.0.0. Pick another APP_PORT." >&2
  exit 1
fi

cat <<EOF

Bootstrap done.

Next:
  1. put the Nuveq API key into ${APP_DIR}/.env
  2. bash deploy/deploy.sh          (or push to main and let CI/CD do it)
  3. confirm: curl -fsS http://127.0.0.1:${APP_PORT}/actuator/health

The nginx site for api.app-cube.tech is a separate manual step; see
Agent/notes/deployment.md in the repo.
EOF
