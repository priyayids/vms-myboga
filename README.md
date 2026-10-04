# Visitor Middleware Service

A backend middleware service bridging the client's visitor registration form and the Nuveq Access Control API v2.

## Features
- **Registration Intake & Validation**: `POST /api/visitors/registration` validates form inputs with Jakarta Bean Validation.
- **Idempotency**: Duplicate submissions with the same `registrationId` return the existing record without creating duplicates or redundant upstream calls.
- **Dual Visitor Instances**: Automatically generates both `CHECK_IN` and `CHECK_OUT` records with `statusEntry = false`, pushing both to Nuveq Visitor API.
- **Real-Time Card Event Monitoring**: `POST /api/events/nuveq-webhook` listens for card swipes, conditionally updating `statusEntry` to `true` with thread-safe DB updates.
- **Structured File Logging**: Rolling logs in `logs/application.log` and `logs/transactions.log` adhering to PRD masking and format rules.
- **Resilience**: Configurable exponential retry with backoff for upstream Nuveq calls.

## Tech Stack
- Java 21 LTS
- Spring Boot 3.3.4
- Spring Data JPA + Hibernate
- PostgreSQL 16
- Flyway Database Migrations
- MapStruct & Lombok
- Spring RestClient

## Getting Started

### 1. Requirements
- OpenJDK 21
- Maven 3.8+
- PostgreSQL 16 or Docker & Docker Compose

### 2. Configuration
Copy configuration into `.env` (or pass environment variables). Start from the
checked-in template so you do not miss a variable:
```bash
cp .env.example .env
```
```env
DB_URL=jdbc:postgresql://localhost:5432/visitor_bridge
DB_USERNAME=postgres
DB_PASSWORD=postgres
NUVEQ_BASE_URL=https://api-v2.nuveq.cloud
NUVEQ_API_KEY=your_api_key_here
# Absolute https base, used when building QR-code URLs handed to visitors
APP_BASE_URL=https://api.app-cube.tech
```
`.env` is gitignored. Never commit it.

### 3. Run Locally
```bash
# Run Flyway migrations and start service
mvn spring-boot:run
```

### 4. Run with Docker Compose
```bash
# one-time: the image runs as uid 10001 and owns these bind mounts
sudo chown -R 10001:10001 logs data/qr-codes

docker compose up -d --build
```
Both published ports are bound to `127.0.0.1` so nothing is exposed to the LAN.

### 5. Running Tests
```bash
mvn clean test
```
The unit suite runs on H2 with Flyway disabled, so it does **not** exercise the
SQL migrations. The `migration-smoke` CI job boots the real jar against
PostgreSQL 16 to verify migrations V1-V7 apply and that Hibernate's
`ddl-auto: validate` agrees with the resulting schema.

## Deployment

Production backend lives at **https://api.app-cube.tech** on the VPS, deployed
by GitHub Actions from `main`.

| Stage | Where |
|---|---|
| `test` | GitHub runner — `mvn verify` (H2) |
| `migration-smoke` | GitHub runner + `postgres:16-alpine` — real boot, asserts Flyway V1-V7 and Hibernate schema validation |
| `build-image` | GitHub runner — `docker build`, pushed to `ghcr.io/priyayids/vms-myboga` |
| `deploy` | SSH to the VPS — `docker compose pull` + `up -d --no-build` |

The Maven and Docker builds deliberately run **off** the VPS: it is a 2-vCPU
production box already running several other stacks, so a deploy there only
pulls an image and restarts two containers.

### Host topology

| Piece | Binding | Why |
|---|---|---|
| `vms-db` (PostgreSQL 16) | no published port, private compose network | the host already runs native PostgreSQL on 5432/5433 |
| `vms-myboga-app` | `127.0.0.1:8080` | the host UFW only opens 22/80/443/2222 |
| `api.app-cube.tech` | host nginx :443 → `127.0.0.1:8080` | the only public entry point |

### Required repository secrets

| Secret | Value |
|---|---|
| `VPS_SSH_PRIVATE_KEY` | deploy-only private key (never the root key) |
| `VPS_HOST` | `187.77.126.196` |
| `VPS_USER` | the deploy user, e.g. `deploy` |

The VPS host public keys are pinned directly in `.github/workflows/ci-cd.yml`.
They are public information, and pinning them is what prevents a DNS hijack
from redirecting the deploy to an attacker.

### Manual deploy

```bash
ssh deploy@187.77.126.196
APP_DIR=/srv/vms-myboga bash /srv/vms-myboga/deploy/deploy.sh
```

`deploy/bootstrap.sh` is the one-time host setup (clone, generate `.env` with a
random DB password, create the runtime dirs). Full runbook including the
Cloudflare record, the certificate and the nginx site:
[`Agent/notes/deployment.md`](Agent/notes/deployment.md).

### Card events: polling vs webhook

`NUVEQ_EVENT_MODE` in the VPS `.env`:

- `polling` (default) — this service pulls `GET /api/events` every
  `NUVEQ_POLL_INTERVAL_MS`. Works immediately.
- `webhook` — Nuveq POSTs to `https://api.app-cube.tech/api/events/nuveq-webhook`.
  Requires the callback URL to be configured on the Nuveq side first.

## API Documentation

### Reserve Visitor
- **URL**: `POST /api/visitors/registration`
- **Request Body**:
```json
{
  "registrationId": "REG-20261002-001",
  "fullName": "Jane Doe",
  "email": "jane.doe@example.com",
  "phone": "+628123456789",
  "userPhoto": "https://example.com/photo.jpg",
  "vehicleNumber": "B1234XYZ",
  "visitStart": "2026-10-02T08:00:00+07:00",
  "visitEnd": "2026-10-02T17:00:00+07:00",
  "roomId": 10,
  "siteId": 10,
  "liftGroupId": 20,
  "allowedDoorIds": [1, 2],
  "cardNumber": "1253646425"
}
```
- **Response**: HTTP 201 Created (or HTTP 200 OK on duplicate submission)

### Room Expiry (Auto Check-Out)
Every room master record carries `expireMinutes` (default 15), configurable via `POST /api/rooms` and `PUT /api/rooms/{id}`. The scheduler checks every minute: if no check-in card event has arrived by `visitStart + expireMinutes`, the booking is auto-expired (auto check-out) — Nuveq registrations and QR codes are released and the room slot becomes available again.

### Nuveq Event Webhook
- **URL**: `POST /api/events/nuveq-webhook`
- **Request Body**:
```json
{
  "id": 1001,
  "cardNo": 1253646425,
  "direction": "IN",
  "timestamp": "2026-10-02T08:15:00+07:00"
}
```
- **Response**: HTTP 200 OK
