# PRD: Visitor Middleware Service

| | |
|---|---|
| **Status** | Draft v1 |
| **Stack** | Java, Spring Boot, Spring Data JPA, PostgreSQL, Flyway |
| **Integrates** | Client registration form (frontend) and Nuveq Access Control API (visitor endpoints only) |

---

Note : make 1 folder named Agent, store all ai doc, notes, skill, mcp all related to AI on that, so my project stay clean

## 1. Purpose

A backend middleware that sits between the client's visitor registration form and the Nuveq access control system.

1. Receives and validates registration data from the client's form.
2. Stores it in our own database with an extra `statusEntry` flag.
3. Pushes it to the Nuveq Visitor API as two visitor instances (check-in and check-out).
4. Listens to real-time card events from Nuveq and marks the visitor as "entered" when the card matches.

## 2. Scope

**In scope**
- Registration intake, validation, persistence
- Nuveq Visitor API integration (create visitor, check-in and check-out instances)
- Real-time card event monitoring and `statusEntry` update
- Structured file logging, error handling, configuration and secrets setup
- Database migrations

**Out of scope (for now)**
- Frontend UI (owned by the client)
- Nuveq endpoints other than Visitor
- Authentication and user management for the middleware's own admin users

## 3. Tech Stack

| Concern | Choice | Why |
|---|---|---|
| Language | Java 21 LTS (or the newest LTS you are comfortable with) | Long-term support, modern features such as records |
| Framework | Spring Boot (latest stable 3.x or 4.x, check start.spring.io) | Standard, mature, strong ecosystem |
| ORM | Spring Data JPA with Hibernate | Maps Java objects to tables so you write less SQL |
| Database | PostgreSQL latest stable major (use the official Docker image, pin the major version) | Reliable, strong JSON and indexing support |
| Migration | Flyway | SQL-based, versioned, simple. Liquibase is the alternative if you need XML/YAML changelogs |
| Boilerplate | Lombok | Removes getters, setters and constructors |
| Mapping | MapStruct | Converts entity to DTO without hand-written code (keeps it DRY) |
| HTTP client | Spring `RestClient` | Modern, synchronous, simple. Use `WebClient` only if you need reactive |
| Retry | Spring Retry or Resilience4j | Retries failed Nuveq calls |
| Logging | SLF4J with Logback | Default in Spring Boot, supports rolling files |
| Tests | JUnit 5, Mockito, Testcontainers | Real PostgreSQL in tests |
| Build | Maven  | Your preference |

Technical terms used above:
- **ORM** (Object-Relational Mapping): lets you work with Java objects instead of writing SQL for every query.
- **DTO** (Data Transfer Object): a plain object used only to carry data in and out of the API, kept separate from the database entity.
- **Migration**: a versioned script that changes the database structure, so every environment ends up with the same schema.

## 4. Architecture

Layered structure with one responsibility per layer. Dependencies point downward only.

```
controller  ->  service  ->  repository  ->  database
                   |
                   +-->  client (Nuveq)
```

```
src/main/java/com/yourcompany/visitorbridge
├── controller        REST endpoints, no business logic
├── service           business rules and orchestration
├── repository        Spring Data JPA interfaces
├── model             JPA entities and enums
├── dto               request and response objects
├── mapper            MapStruct mappers (entity <-> dto)
├── client            Nuveq API client and its DTOs
├── listener          real-time card event listener
├── config            properties classes, beans
└── exception         custom exceptions and global handler

src/main/resources
├── application.yml
├── application-local.yml
├── logback-spring.xml
└── db/migration      V1__create_visitor_table.sql, V2__...
```

**DRY rules**
- One `BaseEntity` with `id`, `createdAt`, `updatedAt` reused by all entities.
- One `@RestControllerAdvice` handles all exceptions and returns the same error shape.
- One generic `ApiResponse<T>` wrapper for all responses.
- Nuveq calls go through a single client class, never directly from services.
- Configuration is bound to typed `@ConfigurationProperties` classes, not scattered `@Value` annotations.

**Code conventions**
- camelCase for Java fields and JSON properties. Database columns use snake_case through Spring's default naming strategy, so the mapping stays automatic.
- Self-explanatory names instead of comments. No commented-out code, no narration comments.
- Constructor injection with Lombok `@RequiredArgsConstructor`.

## 5. Data Model

### Visitor

| Field | Type | Notes |
|---|---|---|
| id | UUID | Primary key |
| registrationId | String | Groups the check-in and check-out records from one form submission |
| userType | String | Flag that identifies the instance, for example `CHECK_IN` or `CHECK_OUT` |
| fullName, contact fields | String | Final list comes from the client's form contract |
| cardNumber | String | Unique per active visit, indexed for fast lookup |
| nuveqVisitorId | String | ID returned by Nuveq after creation |
| **statusEntry** | **boolean** | **Default `false`. Set to `true` when the card is seen on a real-time event** |
| createdAt, updatedAt | Timestamp | From `BaseEntity` |

Migration `V1` creates the table with `status_entry boolean not null default false`, a unique constraint on the idempotency key (`registrationId` plus `userType`), and an index on `card_number`.

## 6. Functional Requirements

### FR-1 Receive registration
- Expose POST reserve API for the client's  Make Costume endpoint, dont expose the nuvex endpoint create something like "reserve" in it for reserve registration from the client side 
- Validate with Jakarta Bean Validation annotations (`@NotBlank`, `@Email`, etc.). Invalid input returns HTTP 400 with field-level messages.
- Duplicate submissions (same `registrationId`) return the existing result instead of creating new records. This property is called **idempotency**: repeating the same request has the same effect as sending it once.

### FR-2 Create visitor instances
- Build a `Visitor` object from the validated request.
- Create two instances with `statusEntry = false`: one with `userType = CHECK_IN`, one with `userType = CHECK_OUT`.
- Save both in one database transaction.
- Send both to the Nuveq Visitor API and store the returned `nuveqVisitorId`.

### FR-3 Real-time card monitoring
- Receive real-time card events from Nuveq.
- For each event, look up a visitor by `cardNumber`.
  - Found and `statusEntry = false`: set to `true`, save, log.
  - Found and already `true`: ignore and log at debug level.
  - Not found: ignore and log at info level.
- The update must be safe if the same event arrives twice or two events arrive at once (use a conditional update or optimistic locking).

### FR-4 Logging
- Every transaction writes one log line: registration received, validation result, Nuveq request and response status, card event handled, `statusEntry` change.
- Logs are written to `logs/` inside the project directory, with a rolling policy (daily, size-capped, 30 days retention).
- Two files: `logs/application.log` for general output and `logs/transactions.log` for business events.
- Format: `2026-10-02 14:03:21.123 | INFO | RegistrationService | registrationId=abc123 | action=CREATE_VISITOR | result=SUCCESS`
- Never log the API key, passwords, or full card numbers (mask to the last 4 digits).
- `logs/` is added to `.gitignore`.

### FR-5 Error handling

| Case | Behaviour |
|---|---|
| Invalid form input | HTTP 400, field errors, logged as WARN |
| Duplicate registration | HTTP 200 with the existing record |
| Nuveq 4xx (bad data) | No retry, HTTP 422, logged as ERROR with Nuveq's message |
| Nuveq 5xx or timeout | Retry with backoff (3 attempts), then mark failed and return HTTP 502 |
| Nuveq 401 or 403 | HTTP 500 internally, ERROR log (credential problem), no retry |
| Database failure | Transaction rolls back, HTTP 500 |
| Unknown card event | Ignore, log |
| Unexpected exception | Caught by the global handler, HTTP 500, generic message to the client, full detail in the log |

All error responses share one JSON shape: `timestamp`, `status`, `error`, `message`, `path`.

## 7. Configuration and Secrets

### Where to put credentials
- **Never** hardcode them in source code or commit them to Git.
- **Local development:** a `.env` file in the project root, listed in `.gitignore`. Dont make or commit Commit a `.env.example`.
- **Production:** a secret manager or the platform's secret store (Docker secrets, Kubernetes Secrets, HashiCorp Vault, AWS Secrets Manager).

### What "credential injection" means
Your code does not contain the secret. The environment around the app (Docker, Kubernetes, CI/CD, or your own shell) **injects** the value at startup as an environment variable or a mounted file. Spring Boot then reads it into your properties. The same code runs in every environment, and only the injected values differ.

Spring Boot maps an environment variable `NUVEQ_API_KEY` to the property `nuveq.api-key` automatically (this is called *relaxed binding*).

### `application.yml`

```yaml
spring:
  config:
    import: optional:file:.env[.properties]
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true

nuveq:
  base-url: ${NUVEQ_BASE_URL}
  api-key: ${NUVEQ_API_KEY}
  connect-timeout: 5s
  read-timeout: 10s
```

`ddl-auto: validate` makes Hibernate only check the schema; Flyway is the only thing allowed to change it.

### `.env` (local only, never committed)

```
DB_URL=jdbc:postgresql://localhost:5432/visitor_bridge
DB_USERNAME=postgres
DB_PASSWORD=change-me
NUVEQ_BASE_URL=https://api-v2.nuveq.cloud
NUVEQ_API_KEY=<your dev key>
```

### Rules
- Use a separate key for dev, staging and production.
- Rotate any key that has been shared in chat, email or a ticket.
- Fail fast: the app refuses to start if a required variable is missing.

## 8. Nuveq Integration

The Nuveq documentation at `https://api-v2.nuveq.cloud/#/` loads dynamically in the browser, so the endpoint paths, request fields, auth header name and card event mechanism **must be confirmed from the Visitor section of that page** before implementation. Do not guess them.

Design so that these unknowns stay isolated:
- `NuveqVisitorClient` is the only class that knows Nuveq's endpoints and payloads.
- `CardEventListener` is an interface with one implementation chosen after confirming how Nuveq delivers events:
  1. **Webhook:** Nuveq calls our endpoint. Preferred, since it is simplest and truly real-time.
  2. **Polling:** a `@Scheduled` job asks Nuveq for new events every few seconds.
  3. **WebSocket or SSE stream:** if Nuveq offers one.

## 9. Non-Functional Requirements

- Registration endpoint responds in under 2 seconds under normal load.
- Stateless service, runnable as a container (Dockerfile plus `docker-compose.yml` with PostgreSQL).
- Health endpoint through Spring Actuator (`/actuator/health`).
- Unit tests for services, integration tests with Testcontainers for repositories and the card event flow.

## 10. Acceptance Criteria

- A valid form submission creates two `Visitor` rows (`CHECK_IN`, `CHECK_OUT`), both with `statusEntry = false`, and two Nuveq visitors.
- Resubmitting the same registration creates no duplicates.
- A card event with a known card number sets `statusEntry = true` once. A repeated event changes nothing.
- A card event with an unknown card number changes nothing.
- Every transaction appears in `logs/transactions.log` in the defined format, with card numbers masked.
- The app starts only when all required environment variables are present.
- All expected errors in section 6 return the documented status and body.

## 11. Open Questions

1. Confirm that "2 instances" means two Visitor records in Nuveq per registration, one for check-in and one for check-out, distinguished by `userType`.
Yes
2. What fields does the client's form send? (Needed for the final entity and validation rules.)
For now lets expect as same as visitor field 
3. How does Nuveq deliver real-time card events: webhook, polling or stream?
check the dock api 
4. Does the check-out instance also get its own `statusEntry` update when the card is seen, or only the check-in instance?
YES, that mean they cekout 
5. How is the form delivered to us: direct HTTP call from the frontend, or another system forwarding it?
http post from UI i think, just json data

## 12. Milestones

1. Project skeleton, Docker Compose with PostgreSQL, Flyway `V1`, configuration and logging.
2. Registration endpoint, validation, persistence, global error handling.
3. Nuveq client and visitor creation with retry.
4. Card event listener and `statusEntry` update.
5. Tests, hardening, deployment setup.
