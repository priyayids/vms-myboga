# Agent Notes — Change Log & Session History
**Project**: vms-myboga (Visitor Middleware Service)  
**Maintained by**: AI Agent

All AI-related notes, decisions, and session logs are kept in this `/Agent` folder only.

---

## 2026-10-03 — Implementation Session #1 (Backend Build: Bookings + QR + Expiry)

### Goal
Execute Ph-1 → Ph-6 and Ph-8 of `notes/implementation-plan.md`: replace the dual-`Visitor`
model with a unified `bookings` table, add time-slot availability, QR codes, an expiry
scheduler, and Nuveq-driven status transitions.

### Decisions Made

| Decision | Rationale |
|----------|-----------|
| Renamed `visitor` → `visitors_legacy` instead of dropping it | Migration stays reversible; legacy rows preserved for rollback/audit |
| Removed the entire legacy visitor stack (7 files) | V5 renamed the table the `Visitor` entity mapped to. With `ddl-auto: validate` the app would not start. Keeping a dead dual-visitor flow served no purpose |
| Kept reserve on `POST /api/v1/visitors/reserve` | `UI-requirement.md` (the frontend contract) specifies that path — do not churn the client contract |
| Moved QR serving to `/api/v1/bookings/**` | Plan Ph-3b; keeps `/visitors` a write-only reservation surface |
| Skipped `BookingMapper` (MapStruct) | The response DTO needs masked cards, door-name resolution, and URL building — not a pure field copy. Hand-mapping in the service is honest; a generated mapper would be a shell with `@AfterMapping` noise |
| `card_number_out` defaults to `card_number_in` when absent | Keeps the CHECK_OUT event matchable even when the client only has one card |
| QR encodes the raw `credentialNumber` (card number) | That is the value the door controller reads from the QR scan |
| QR failure is non-fatal (WARN, booking still succeeds) | QR is a presentation concern; losing it must not strand a real Nuveq credential |
| Expiry = 15-min grace on `visit_start` | Matches PRD; a late arrival still works |
| Availability from a booking query, not a room status flag | No denormalised state to drift out of sync |

### Deviations From Plan

1. **`findActiveBookingsForDate` uses a native query, not JPQL.** JPQL has no `AT TIME ZONE`,
   so the Asia/Jakarta day boundary could not be expressed. Native SQL with
   `(visit_start AT TIME ZONE 'Asia/Jakarta')::date = CAST(:date AS date)`.
2. **Card lookups use explicit `@Query`.** Derived names like `findByCardNumberInAndBookingStatus`
   are parsed as property `cardNumber` + operator `In`, failing with
   `PropertyReferenceException: No property 'cardNumber' found`.
3. **`processCardEvent(String, String)` returns `WebhookProcessingResult`**, not `boolean`, so the
   webhook controller and the listener share one contract.

### Bugs Found and Fixed in Pre-Existing Code

**`NuveqVisitorClient` returned 502 instead of 500 on Nuveq 401/403.**
`SimpleClientHttpRequestFactory` uses `HttpURLConnection`, which throws
`HttpRetryException: cannot retry due to server authentication, in streaming mode` when the
server answers 401. The exception fired inside `retrieve()` *before* our `onStatus` handler could
map it, so the documented error matrix ("401/403 → HTTP 500, no retry") never applied.
Fix: switched to `JdkClientHttpRequestFactory` backed by a `java.net.http.HttpClient`.
Verified: 401 now logs `NUVEQ_AUTH | result=FAILED | status=401` and returns HTTP 500 with
**no retry**.

### Files Created This Session
- `src/main/java/com/visitorbridge/exception/InvalidBookingStateException.java` — cancel on a terminal status
- `src/main/resources/db/migration/V4__create_bookings_table.sql` — `bookings` table + 4 indexes
- `src/main/resources/db/migration/V5__cleanup_visitors_table.sql` — `visitor` → `visitors_legacy`
- `src/main/java/com/visitorbridge/model/BookingStatus.java`
- `src/main/java/com/visitorbridge/model/Booking.java`
- `src/main/java/com/visitorbridge/repository/BookingRepository.java`
- `src/main/java/com/visitorbridge/service/BookingService.java` — reserve / availability / card events / expiry
- `src/main/java/com/visitorbridge/service/QrCodeService.java` — ZXing generate/read/delete
- `src/main/java/com/visitorbridge/controller/BookingController.java` — GET booking + QR images
- `src/main/java/com/visitorbridge/config/VmsProperties.java` — `vms.booking.*`, `vms.qr.*`
- `src/main/java/com/visitorbridge/exception/BookingConflictException.java` — maps to HTTP 409
- `src/main/java/com/visitorbridge/dto/BookingResponseDto.java`
- `src/main/java/com/visitorbridge/dto/AvailabilitySlotDto.java`
- `src/main/java/com/visitorbridge/dto/AvailabilityResponseDto.java`
- `src/test/java/com/visitorbridge/service/BookingServiceTest.java` — 12 tests

### Files Deleted This Session
`model/Visitor.java`, `model/UserType.java`, `mapper/VisitorMapper.java`,
`repository/VisitorRepository.java`, `service/VisitorService.java`,
`dto/VisitorResponseDto.java`, `dto/ReservationResponseDto.java`,
`src/test/.../VisitorServiceTest.java`

### Files Modified This Session
- `pom.xml` — ZXing `core` + `javase` 3.5.3
- `application.yml` — `vms.booking.*`, `vms.qr.*`
- `VisitorMiddlewareApplication.java` — register `VmsProperties`
- `VisitorController.java` — delegates to `BookingService`, 201/200 split
- `RoomController.java` — `GET /{roomId}/availability`
- `EventWebhookController.java`, `WebhookCardEventListener.java` — route to `BookingService`
- `NuveqVisitorClient.java` — `deleteVisitorRegistration`, `JdkClientHttpRequestFactory`
- `GlobalExceptionHandler.java` — 409 handler
- `Dockerfile` — `mkdir -p /app/logs /app/data/qr-codes`
- `docker-compose.yml` — mount `./data/qr-codes`
- `.gitignore` — ignore `data/qr-codes/*.png`
- `VisitorControllerTest.java` (rewritten), `RoomControllerTest.java` (+ availability test)

### Verification Performed
- `mvn test` — **29/29 green**
- Booted against a real PostgreSQL 16: all 5 Flyway migrations applied, Hibernate
  `ddl-auto: validate` passed, app started clean
- Full live flow against a mock Nuveq API:
  - reserve → 201, two Nuveq visitors, two valid 300×300 PNGs
  - duplicate registrationId → 200 + `idempotent: true`, no extra Nuveq calls
  - overlapping slot → 409; non-overlapping → 201; 23:00 start → 400 (outside 09:00–22:00)
  - availability → 13 slots with correct `bookedBy`; slots free again after COMPLETED
  - webhook IN → ACTIVE, repeated IN → ignored, OUT → COMPLETED, unknown card → ignored
  - expiry scheduler → EXPIRED, both Nuveq registrations deleted, both PNGs deleted, slot freed
  - QR endpoint → 200 `image/png`; bad `type` → 400; unknown booking → 404

### Files Updated This Session
- `Agent/notes/implementation-plan.md` — progress tracker now reflects real state
- `Agent/AGENT-LOG.md` — this entry

### Remaining
- **Ph-7 (vms-form client work)** — hour picker, 409 handling, QR confirmation screen.
  Lives in the frontend repo; the API contract it needs is in `UI-requirement.md` and is now
  live and verified.

---

## 2026-10-03 — Implementation Session #2 (QR Filenames + Cancel/Delete)

### Requested
1. Store QR images in the project directory "somewhere safe" (user suggested `src/main/resources`)
2. Name each QR file after the visitor
3. Delete the QR when the visitor is deleted — needed for testing

### Pushback accepted: NOT `src/main/resources`
Generated images written into `src/main/resources` get packaged into the jar by Maven, so every
QR created during testing is baked into the next build artifact. It also puts runtime writes
inside the source tree, where git and the IDE will fight over them. `data/qr-codes` at the
project root gives the same "visible in the project directory" property with none of that.
Documented the reasoning inline in `application.yml` so nobody relocates it later.

### Decision: filename = `{visitor-slug}-{registration-id}_{in|out}.png`
Example: `jane-doe-reg-20261005-0001_in.png`

The registrationId is **not** optional decoration. Visitor names are not unique, and because
`card_number_out` falls back to `card_number_in`, two visitors named "John Doe" colliding on
one filename would hand the second guest the first guest's credential. The unique registrationId
makes collisions impossible while keeping the name readable for manual testing.

Both parts are sanitised to `[a-z0-9-]` because `registrationId` is client-supplied — a value
like `../../etc` would otherwise be a path-traversal write. `generateAndSave` also normalises
the resolved path and refuses to write outside the storage directory as a second layer.
Non-ASCII names (e.g. Chinese) slugify to empty and fall back to `visitor-`.

### Decision: `DELETE /api/v1/bookings/{registrationId}` cancels rather than hard-deletes
Sets `CANCELLED`, deletes both QR files, deletes both Nuveq registrations, nulls the QR columns.
The row is kept for audit, and the slot becomes bookable again because availability only counts
`PENDING`/`ACTIVE`. Cancelling a `COMPLETED`/`EXPIRED`/`CANCELLED` booking returns 409 via
`InvalidBookingStateException` — those artefacts are already cleaned up or already consumed.

QR files are deleted **before** the Nuveq calls, so a Nuveq outage cannot leave files orphaned.

### Files Modified This Session
- `QrCodeService` — `buildFileBaseName`, slugify/sanitise, traversal guard, legacy prefix handling
- `BookingService` — reserve uses visitor-aware filenames; new `cancelBooking`
- `BookingController` — `DELETE /{registrationId}`
- `GlobalExceptionHandler` — 409 handler for `InvalidBookingStateException`
- `application.yml` — documented why storage is not under `src/main/resources`
- `BookingServiceTest` (+8 tests), `VisitorControllerTest` (+2 tests) → **39 tests green**

### Backward compatibility
`resolve()` still strips a leading `qr-codes/`, so bookings written before this change
(whose DB column holds `qr-codes/<name>.png`) stay readable. New rows store a bare filename.

### Verified live
Postgres 16 + mock Nuveq: filenames land as `data/qr-codes/jane-doe-<regId>_in.png`;
`DELETE` removed both PNGs from disk, nulled both DB columns, and issued both Nuveq
`DELETE /api/visitors/registrations/{id}` calls; the freed slot returned to the availability
grid; re-cancelling returned 409.

---

## 2026-10-03 — Planning Session #3 (QR Code Feature)

### Decision: Generate QR code from `credentialNumber` after visitor creation

**Trigger**: After `POST /api/v1/visitors/reserve` creates both Nuveq visitors, immediately generate 2 QR PNG files.

**What is encoded**: The raw card number (`cardNumberIn` / `cardNumberOut`) — the same value used as `credentialNumber` in the Nuveq API payload. This is the credential the door reader matches.

**Storage**: Local filesystem, Docker volume-mounted at `./data/qr-codes/` → `/app/data/qr-codes/`  
**DB fields added**: `qr_code_path_in` VARCHAR(500), `qr_code_path_out` VARCHAR(500) — included in V4 migration  
**Library**: Google ZXing 3.5.3 (`core` + `javase`)  
**Serve endpoint**: `GET /api/v1/bookings/{registrationId}/qr/{in|out}` → `image/png`  
**Failure policy**: IOException → log WARN, do NOT fail booking  
**Expiry cleanup**: Delete PNG files when `booking_status → EXPIRED`

### Files Updated This Session
- `Agent/notes/architecture-design.md` — Added QR Code Generation section
- `Agent/notes/implementation-plan.md` — Added Ph-3b tasks, updated V4 SQL (QR columns), Phase 3b detail, Docker + config changes
- `Agent/AGENT-LOG.md` — This entry

---

## 2026-10-03 — Planning Session #2 (Time-Slot Booking + Q&A)

### Decisions Made

**Q5 — Time-slot based booking**: ✅ CONFIRMED  
Goal is hourly time-slot booking. Users cannot double-book a room for the same hour.  
- Operating hours: **09:00–22:00** (+07:00 / WIB)  
- Slot granularity: **1 hour**  
- Multi-hour bookings allowed (e.g. 09:00–12:00)  
- Unavailable slots shown as disabled in the UI with "Already booked" tooltip  
- **New `bookings` table** is required — replaces the old dual `visitors` table pattern

**Q3 & Q4 — Expiry & Nuveq cleanup**: ✅ CONFIRMED  
On booking expiry (15-min no-show):
1. Delete Nuveq CHECK_IN visitor via `DELETE /api/visitors/registrations/{nuveq_registration_id_in}`
2. Delete Nuveq CHECK_OUT visitor via `DELETE /api/visitors/registrations/{nuveq_registration_id_out}`
3. Confirmed: Nuveq OpenAPI spec (`nuveq-openapi.json`) includes this endpoint: `VisitorsController_deleteVisitorRegistration`
4. Nuveq does NOT need to know about room status — that's our system's concern

**Q6 — Room Master Auth**: ⬜ DEFERRED  
Leave room master endpoints unauthenticated for now. Add auth in a future iteration.

### Architecture Changes from Session #1 → #2

| Before | After |
|--------|-------|
| `visitors` table with `CHECK_IN` / `CHECK_OUT` rows | Single `bookings` table with both credential fields |
| `rooms.status` boolean flag | No status flag — availability computed from bookings query |
| Simple boolean `statusEntry` | `booking_status` enum: `PENDING/ACTIVE/COMPLETED/EXPIRED/CANCELLED` |
| GET /rooms returns availability | New `GET /rooms/{id}/availability?date=...` endpoint |
| No time-slot concept | 13 hourly slots per day, 09:00–22:00 |

### Files Updated This Session
- `Agent/notes/architecture-design.md` — Full rewrite with new domain model
- `Agent/notes/implementation-plan.md` — **NEW** — phased task tracker with SQL, queries, configs
- `Agent/UI-requirement.md` — Updated with hour picker, availability endpoint, 409 flow

---

## 2026-10-02 — Planning Session #1 (Initial Architecture)

### Decisions Made
- Middleware pattern: vms-form → vms-myboga → Nuveq (credentials hidden server-side)
- Dual visitor instances (CHECK_IN / CHECK_OUT) per booking
- 15-minute expiry grace window
- Nuveq webhook as primary event source
- No admin auth for Room Master (deferred)

### Files Created This Session
- `Agent/notes/architecture-design.md` — Initial architecture doc
- `Agent/notes/nuveq-endpoints-analysis.md` — Nuveq live tenant config
- `Agent/UI-requirement.md` — Initial UI spec

---

## Key Reference Files

| File | Purpose |
|------|---------|
| `Agent/notes/architecture-design.md` | **PRIMARY** — domain model, flows, sequences |
| `Agent/notes/implementation-plan.md` | Task tracker, DB SQL, phase details |
| `Agent/notes/nuveq-endpoints-analysis.md` | Nuveq tenant IDs (site, doors, lift groups) |
| `Agent/notes/nuveq-openapi.json` | Full Nuveq OpenAPI spec (confirmed endpoints) |
| `Agent/UI-requirement.md` | Frontend spec, API contracts, UX flows |
| `Agent/skills/nuveq-integration/SKILL.md` | Nuveq auth, endpoints, retry policy |
| `Agent/skills/spring-boot-backend/SKILL.md` | Spring Boot architecture rules |
| `Agent/skills/visitor-service-testing/SKILL.md` | Testing patterns |

## 2026-10-03 — Implementation Session #4 (Frontend Slot Picker & Backend Auto-Complete)

### Completed
1. **Frontend (`vms-form`)**:
   - Added `getAvailability(roomId, date)` to `js/api.js`.
   - Added HTTP 409 `SLOT_CONFLICT` handling in `submitReservation()` in `js/api.js`.
   - Updated `_generateMockSuccess()` to return flat `BookingResponseDto` structure.
   - Replaced datetime-local inputs in `index.html` with Date Picker + 13-slot hour button grid (09:00 - 22:00) with visual range highlighting, booked slot disabling, and tooltips.
   - Updated `js/app.js` with `loadSlotGrid()`, `renderSlotButtons()`, `onSlotClick()`, `highlightSlots()`, and `updateSlotDisplay()`.
2. **Backend (`vms-myboga`)**:
   - Added `findByBookingStatusAndVisitEndBefore()` in `BookingRepository.java`.
   - Added `@Scheduled(cron = "0 * * * * *")` method `autoCompleteActiveBookings()` in `BookingService.java` to transition `ACTIVE` bookings past `visit_end` to `COMPLETED` so slots don't stay held indefinitely.
   - Rebuilt backend jar and deployed as daemon process.
3. **End-to-End Verification**:
   - Verified reservation -> slots marked unavailable in availability grid.
   - Verified simulated Nuveq scan (Entry/Exit) -> webhook transitions `PENDING` -> `ACTIVE` -> `COMPLETED`.
   - Verified freed slots immediately become available again on check-out/completion.

---

## 2026-10-03 — Implementation Session #5 (Room Selection Flow & UI Form Cleanup)

### Goal
Clean schema flow for site, room, and door master data so the client form only uses Room selection and never exposes Nuveq infrastructure configurations (site ID, lift group ID, internal doors).

### Backend Changes (`vms-myboga`)
1. **Flyway Migration `V6__add_lift_group_id_to_room.sql`**:
   - Added `lift_group_id BIGINT DEFAULT 630 NOT NULL` to `rooms` table.
2. **Room Entity & DTOs**:
   - Updated `Room`, `RoomResponseDto`, `RoomCreateRequest`, and `RoomUpdateRequest` to persist and return `liftGroupId`.
3. **Reservation Request & Service**:
   - In `VisitorRegistrationRequest`: `roomId` is `@NotNull`. Client does not need to send `siteId`, `liftGroupId`, or `allowedDoorIds`.
   - In `BookingService.reserveBooking`: `siteId`, `liftGroupId`, and `allowedDoorIds` are resolved automatically from the selected `Room` and its mapped `Door` entities.
4. **Verification**:
   - Executed `mvn test`: 52/52 tests green.
   - Packaged and restarted backend daemon on port 8080.

### Frontend Changes (`vms-form`)
1. **`index.html`**:
   - Moved `roomId` dropdown to **Step 1** directly above the Date Picker and Slot Grid.
   - Removed `siteId` and `liftGroupId` dropdowns, `roomDoorPreview`, and manual door selection checkboxes from Step 2.
   - Updated Step 2 to only ask for **RFID Card Number**, **Check-Out Card Number** (optional), and **Visitor Photo**.
   - Updated Step 3 review table and digital pass to showcase Room name and schedule cleanly without raw Nuveq infrastructure IDs.
   - Updated Room Master modal & table to support `liftGroupId` (default: 630).
2. **`js/config.js`**:
   - Removed obsolete default site ID, lift group ID, and door IDs from client configuration.
3. **`js/app.js`**:
   - Removed obsolete manual door rendering and dropdown population.
   - `loadRoomDropdown()` populates `dom.roomId` on Step 1 and reloads slot grid availability on room change.
   - `validateStep1()` requires `fullName`, `roomId`, `visitDatePicker`, and slot selection.
   - `validateStep2()` only validates card numbers.
   - `constructPayload()` sends clean payload: `registrationId`, `fullName`, `email`, `phone`, `userPhoto`, `vehicleNumber`, `roomId`, `cardNumber`, `checkOutCardNumber`, `visitStart`, `visitEnd`.
4. **`js/room-master.js`**:
   - Added `liftGroupId` input and table column to Room Master admin module.

