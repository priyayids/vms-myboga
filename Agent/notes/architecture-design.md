# Visitor Middleware Service — Architecture & Technical Design
**Last Updated**: 2026-10-03  
**Status**: ✅ COMPLETE PLAN — Ready for Implementation

---

## Overview
The Visitor Middleware Service acts as a secure intermediary bridge between:
- **Client**: `vms-form` (Booking Room dashboard / visitor registration UI)
- **Access Control**: Nuveq Cloud Partner API v2

Nuveq credentials are **never exposed to the client**. All Nuveq calls are server-to-server only.

---

## System Architecture

```
[ vms-form (Browser) ]
       |
       |  HTTPS — calls our middleware only
       v
[ vms-myboga — Spring Boot 3 / Java 21 / Port 8080 ]
       |                   |                    |
       v                   v                    v
[ PostgreSQL 16 ]  [ Nuveq Partner API v2 ]  [ Scheduler ]
                    (server-to-server only)   (@Scheduled)
```

### Layered Architecture (downward only)
```
Controller  →  Service  →  Repository  →  DB (PostgreSQL)
                  |
                  +→  NuveqClient (RestClient, X-API-KEY internal)
                  +→  BookingExpiryScheduler
                  +→  WebhookEventHandler
```

---

## Domain Model — 3 Core Tables

### 1. `rooms` (Master Room)
Represents a physical bookable room mapped to Nuveq doors.

| Column | Type | Notes |
|--------|------|-------|
| `id` | BIGSERIAL PK | — |
| `custom_name` | VARCHAR(255) | Human-readable room name |
| `site_id` | BIGINT | Nuveq site ID (e.g. 167) |
| `created_at` | TIMESTAMPTZ | Auto |
| `updated_at` | TIMESTAMPTZ | Auto |

> Note: Room-level `status` boolean is **NOT needed** anymore. Availability is computed from `bookings` table time-slot overlap query.

### 2. `bookings` (NEW — Time-Slot Booking Record)
The central booking entity. One booking = one reserved time slot for one room.

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | — |
| `registration_id` | VARCHAR(64) UNIQUE | Client-generated idempotency key (e.g. `REG-20261003-XXXX`) |
| `room_id` | BIGINT FK → rooms | Which room |
| `visitor_name` | VARCHAR(255) | Full name |
| `email` | VARCHAR(255) | Optional |
| `phone` | VARCHAR(50) | Optional |
| `user_photo` | TEXT | URL |
| `vehicle_number` | VARCHAR(50) | Optional |
| `card_number_in` | VARCHAR(64) | CHECK_IN credential card |
| `card_number_out` | VARCHAR(64) | CHECK_OUT credential card (can equal card_number_in) |
| `site_id` | BIGINT | Nuveq site |
| `lift_group_id` | BIGINT | Nuveq lift group |
| `visit_start` | TIMESTAMPTZ | Must be on an allowed hour slot (09–22) |
| `visit_end` | TIMESTAMPTZ | Must be after visit_start |
| `booking_status` | VARCHAR(20) | See enum below |
| `nuveq_visitor_id_in` | BIGINT | Returned by Nuveq after CHECK_IN visitor creation |
| `nuveq_registration_id_in` | BIGINT | Returned by Nuveq |
| `nuveq_visitor_id_out` | BIGINT | Returned by Nuveq after CHECK_OUT visitor creation |
| `nuveq_registration_id_out` | BIGINT | Returned by Nuveq |
| `created_at` | TIMESTAMPTZ | Auto |
| `updated_at` | TIMESTAMPTZ | Auto |

#### `booking_status` Enum Values
| Value | Meaning |
|-------|---------|
| `PENDING` | Booked but visitor has not checked in yet (within grace period) |
| `ACTIVE` | Visitor has checked in via card swipe |
| `COMPLETED` | Visitor has checked out via card swipe |
| `EXPIRED` | No check-in within 15-min grace window — slot freed, Nuveq visitors deleted |
| `CANCELLED` | Manually cancelled (future admin feature) |

### 3. `doors` (Room ↔ Door Mapping)
Links rooms to Nuveq physical doors.

| Column | Type | Notes |
|--------|------|-------|
| `id` | BIGSERIAL PK | — |
| `nuveq_door_id` | BIGINT UNIQUE | Nuveq's door ID |
| `name` | VARCHAR(255) | Door display name |
| `door_number` | INT | — |
| `controller_id` | BIGINT | Nuveq controller ID |
| `site_id` | BIGINT | Nuveq site ID |
| `room_id` | BIGINT FK → rooms NULL | Null = unmapped |
| `created_at` | TIMESTAMPTZ | Auto |
| `updated_at` | TIMESTAMPTZ | Auto |

---

## Time-Slot Booking Model

### Allowed Booking Hours
- **Operating window**: 09:00 – 22:00 (WIB / +07:00)
- **Slot granularity**: 1 hour (09–10, 10–11, …, 21–22)
- **Max duration**: User selects start hour + end hour within the window
- **Multi-hour bookings**: Allowed (e.g. 09:00–12:00 = 3-hour block)

### Slot Availability Query Logic
When a user selects a room + date, the UI calls:
```
GET /api/v1/rooms/{roomId}/availability?date=2026-10-03
```
Backend returns an array of 13 hour slots (9–22) with availability status:
```json
{
  "roomId": 5,
  "date": "2026-10-03",
  "slots": [
    { "hour": 9,  "available": true },
    { "hour": 10, "available": false, "bookedBy": "REG-20261003-001" },
    { "hour": 11, "available": false, "bookedBy": "REG-20261003-001" },
    { "hour": 12, "available": true },
    ...
  ]
}
```
A slot is **unavailable** if any active booking (`PENDING` or `ACTIVE` status) overlaps that hour for the room on that date.

The UI disables unavailable hour buttons and shows a tooltip: *"Already booked"*.

---

## Core API Endpoints

### Booking Flow
| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/visitors/reserve` | Create time-slot booking + push 2 visitors to Nuveq |
| `GET` | `/api/v1/rooms` | List all rooms (no real-time status — use availability endpoint) |
| `GET` | `/api/v1/rooms/{id}` | Get single room |
| `GET` | `/api/v1/rooms/{roomId}/availability?date=YYYY-MM-DD` | **NEW** — Get hourly slot availability for a date |
| `POST` | `/api/v1/events/nuveq-webhook` | Receive Nuveq card swipe events |

### Room Master Admin
| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/rooms` | List rooms with doors |
| `POST` | `/api/v1/rooms` | Create room |
| `PUT` | `/api/v1/rooms/{id}` | Update room |
| `DELETE` | `/api/v1/rooms/{id}` | Delete room |
| `GET` | `/api/v1/rooms/doors` | List all Nuveq doors with mapping |
| `POST` | `/api/v1/rooms/sync` | Sync doors from Nuveq |

---

## Full Booking Flow (Sequence)

1. User opens booking form → selects **room** → selects **date**
2. UI calls `GET /api/v1/rooms/{roomId}/availability?date=...`
3. UI renders hour picker: disabled hours show as booked
4. User picks available start/end hour, fills personal info + 2 card numbers
5. UI submits `POST /api/v1/visitors/reserve`
6. Middleware validates:
   - Required fields
   - `visitStart` / `visitEnd` within 09:00–22:00
   - No slot overlap with existing `PENDING`/`ACTIVE` bookings for that room+time
7. Middleware creates `booking` record with `booking_status = PENDING`
8. Middleware calls Nuveq `POST /api/visitors` for CHECK_IN visitor
9. Middleware calls Nuveq `POST /api/visitors` for CHECK_OUT visitor
10. Saves `nuveq_visitor_id_in/out` and `nuveq_registration_id_in/out` into booking
11. Returns HTTP 201 with full booking summary to UI
12. UI shows visitor pass / confirmation screen

---

## Expiry Flow (15-Minute No-Show)

- **Trigger**: Spring `@Scheduled(cron = "0 * * * * *")` — every minute
- **Condition**: `booking_status = PENDING` AND `visit_start <= NOW() - 15min`
- **Actions**:
  1. Set `booking_status = EXPIRED`
  2. Call Nuveq `DELETE /api/visitors/registrations/{nuveq_registration_id_in}` → remove CHECK_IN visitor
  3. Call Nuveq `DELETE /api/visitors/registrations/{nuveq_registration_id_out}` → remove CHECK_OUT visitor
  4. Log to `transactions.log`: `action=EXPIRED | registrationId=... | roomId=...`
- **Result**: The room's time slot is now free again (available in availability query)

---

## Webhook Event Flow (Nuveq → Middleware)

Nuveq pushes card events to: `POST /api/v1/events/nuveq-webhook`

Event fields: `cardNo`, `direction` (IN/OUT), `doorId`, `timestamp`

### CHECK-IN Event (direction=IN):
1. Find booking by `card_number_in = cardNo` AND `booking_status = PENDING`
2. Set `booking_status = ACTIVE`
3. Log: `action=CHECK_IN | registrationId=...`

### CHECK-OUT Event (direction=OUT):
1. Find booking by `card_number_out = cardNo` AND `booking_status = ACTIVE`
2. Set `booking_status = COMPLETED`
3. Log: `action=CHECK_OUT | registrationId=...`

> Room slot availability recalculates automatically — no `rooms.status` flag needed.

---

## Error & Retry Matrix

| Condition | Action / Response |
|---|---|
| Slot overlap (booking conflict) | HTTP 409 Conflict `"Room is already booked for this time slot"` |
| Validation Error | HTTP 400 with field errors (WARN) |
| Duplicate `registrationId` | HTTP 200 with existing booking (idempotent) |
| Nuveq 4xx | No retry, HTTP 422 with Nuveq message (ERROR) |
| Nuveq 5xx / Timeout | Exponential backoff retry (up to 3×: 500ms/1s/2s), then HTTP 502 (ERROR) |
| Nuveq 401 / 403 | No retry, HTTP 500 credential error (ERROR) |
| DB Failure | Rollback all, HTTP 500 |
| Unknown Card Event | Ignored, logged at INFO level |
| Unhandled Exceptions | Global handler, HTTP 500, sanitized client message |

---

## Logging Architecture (Logback)

- `logs/application.log`: Application runtime and diagnostic logs
- `logs/transactions.log`: Dedicated audit/transaction log

**Format**: `YYYY-MM-DD HH:mm:ss.SSS | LEVEL | ClassName | registrationId=... | action=... | result=...`

**Sensitive data masking**:
- Card numbers: `****XXXX` (last 4 digits only)
- API keys, passwords: never logged

**Rolling policy**: Daily, max 10MB per file, 30 days retention

---

## QR Code Generation

### When
After both Nuveq visitors are created (step 8 of booking flow), the backend generates two QR PNG files before returning the response.

### What to Encode
Each QR encodes the **`credentialNumber`** (= card number string) submitted to Nuveq:
- **CHECK_IN QR** → encodes `cardNumberIn` → stored as `{registrationId}_in.png`
- **CHECK_OUT QR** → encodes `cardNumberOut` → stored as `{registrationId}_out.png`

This is the same number the Nuveq door reader uses to match the credential.

### Storage
```
Host path (Docker volume):    ./data/qr-codes/
Container path:               /app/data/qr-codes/
DB column (relative):         qr_code_path_in  = "qr-codes/REG-..._in.png"
                              qr_code_path_out = "qr-codes/REG-..._out.png"
```

### Served Via
```
GET /api/v1/bookings/{registrationId}/qr/in    → image/png
GET /api/v1/bookings/{registrationId}/qr/out   → image/png
```
The response DTO includes `qrCodeUrlIn` and `qrCodeUrlOut` pointing to these endpoints.

### Library: Google ZXing 3.5.3
- `com.google.zxing:core` — encode logic
- `com.google.zxing:javase` — `MatrixToImageWriter` for PNG output
- Size: 300×300px, error correction: M, margin: 2 modules

### Failure Handling
QR generation errors (`IOException`) are logged at WARN level but do **not** fail the booking. The reservation succeeds regardless. QR can be regenerated later if needed.

### Lifecycle
- **Created**: immediately after Nuveq visitor creation in `BookingService.reserve()`
- **Deleted**: when `booking_status` transitions to `EXPIRED` (optional cleanup via `Files.deleteIfExists()`)
- **Retained**: for `COMPLETED` bookings (audit trail / visitor history)

---

## Security Boundaries

| Layer | What's Protected | How |
|-------|-----------------|-----|
| Client (vms-form) | No Nuveq credentials | Middleware URL only in config.js |
| Middleware | API key in env var | `${NUVEQ_API_KEY}` via `.env` / Docker |
| Nuveq calls | Server-to-server only | Spring `RestClient` sets `X-API-KEY` internally |
| Logs | Card numbers masked | `****XXXX` last 4 digits |
| Idempotency | No duplicate registrations | `registration_id` UNIQUE constraint in DB |
| Slot conflict | No double-booking | DB query check before INSERT |
