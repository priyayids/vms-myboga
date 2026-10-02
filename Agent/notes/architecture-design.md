# Visitor Middleware Service - Architecture & Technical Design

## Overview
The Visitor Middleware Service acts as an intermediary bridge connecting the client's visitor registration form UI and the Nuveq Cloud Access Control System (v2 Partner API).

## Core Requirements & Specifications

### 1. Registration Intake & Dual Instances
- **Endpoint**: `POST /api/v1/visitors/reserve`
- **Behavior**:
  - Validates client registration payload.
  - Ensures idempotency via `registrationId`.
  - Creates two database records: `CHECK_IN` and `CHECK_OUT`, both initialized with `statusEntry = false`.
  - Pushes both records to Nuveq `POST /api/visitors`.
  - Associates returned `nuveqVisitorId` and `nuveqRegistrationId` with the local visitor entities.

### 2. Real-Time Card Monitoring
- **Webhook Endpoint**: `POST /api/v1/events/nuveq-webhook`
- **Optional Polling Scheduler**: Polls `GET /api/events` periodically if enabled.
- **Card Matching Logic**:
  - Matches incoming `cardNo` against database active records.
  - If event direction is `IN`, marks `CHECK_IN` record `statusEntry = true`.
  - If event direction is `OUT`, marks `CHECK_OUT` record `statusEntry = true`.
  - If direction is unspecified:
    - If `CHECK_IN` has `statusEntry == false`, marks `CHECK_IN` as entered (`true`).
    - Else if `CHECK_OUT` has `statusEntry == false`, marks `CHECK_OUT` as exited (`true`).
  - Thread-safe / optimistic locking / conditional DB updates prevent race conditions and duplicate updates.

### 3. Masking & Security
- Card numbers logged as `****1234`.
- API keys, passwords, and tokens never logged.

### 4. Logging Architecture (Logback)
- `logs/application.log`: Application runtime and diagnostic logs.
- `logs/transactions.log`: Dedicated audit/transaction log for all business transactions.
- Daily rolling policy, max 10MB per file, 30 days retention.

### 5. Error & Retry Matrix
| Condition | Action / Response |
|---|---|
| Validation Error | HTTP 400 with field errors (WARN) |
| Duplicate `registrationId` | HTTP 200 with existing records |
| Nuveq 4xx | No retry, HTTP 422 with Nuveq message (ERROR) |
| Nuveq 5xx / Timeout | Exponential backoff retry (up to 3 times), then HTTP 502 (ERROR) |
| Nuveq 401 / 403 | No retry, HTTP 500 credential error (ERROR) |
| DB Failure | Rollback, HTTP 500 |
| Unknown Card Event | Ignored, logged at INFO level |
| Unhandled Exceptions | Global handler, HTTP 500, sanitized client message |
