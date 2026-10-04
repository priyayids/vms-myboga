# Implementation Plan — VMS-Myboga
**Last Updated**: 2026-10-03  
**Status**: 🟢 BACKEND COMPLETE — Ph-1 → Ph-6, Ph-8 done · Ph-7 (vms-form client) not started

---

## Progress Tracker

| Phase | Task | Status | Notes |
|-------|------|--------|-------|
| **Ph-1** | DB Migration: Drop old visitors table / create bookings table | ✅ DONE | `V5__cleanup_visitors_table.sql` renames `visitor` → `visitors_legacy` (not dropped, rollback-safe) |
| **Ph-1** | DB Migration: Add `bookings` table with QR fields | ✅ DONE | `V4__create_bookings_table.sql` includes `qr_code_path_in/out` |
| **Ph-1** | DB Migration: Index on `bookings(room_id, visit_start, visit_end, booking_status)` | ✅ DONE | `idx_bookings_slot` + `idx_bookings_expiry`, `idx_bookings_card_in/out` |
| **Ph-2** | `Booking` JPA entity + `BookingStatus` enum | ✅ DONE | `model/Booking.java`, `model/BookingStatus.java` |
| **Ph-2** | `BookingRepository` with slot-overlap query | ✅ DONE | JPQL for overlap; **native query** for date-scoped availability (JPQL has no `AT TIME ZONE`); explicit `@Query` for `cardNumberIn/Out` lookups (derived names collide with `In` keyword) |
| **Ph-2** | ~~`BookingMapper` (MapStruct)~~ | ⛔ SKIPPED | Response DTOs need masked card numbers + door-name resolution + QR URL building — not a pure field copy, so mapping lives in `BookingService.mapToResponseDto` |
| **Ph-3** | `BookingService.reserve()` — validate + insert + call Nuveq x2 | ✅ DONE | `reserveBooking`: window validation → overlap check → Nuveq IN/OUT → QR → single insert |
| **Ph-3** | `BookingService.getAvailability()` — slot grid for a date | ✅ DONE | 13 slots, 09:00–22:00, configurable via `vms.booking.*` |
| **Ph-3** | `VisitorController` — `POST /api/visitors/registration` uses BookingService | ✅ DONE | 201 Created new / 200 OK idempotent; renamed from `/reserve` and version prefix dropped (2026-10-04) |
| **Ph-3** | `RoomController` — add `GET /rooms/{id}/availability` | ✅ DONE | `GET /api/rooms/{roomId}/availability?date=YYYY-MM-DD` |
| **Ph-3b** | Add ZXing dependency to `pom.xml` | ✅ DONE | `com.google.zxing:core` + `javase` 3.5.3 via `${zxing.version}` |
| **Ph-3b** | `QrCodeService` — generate PNG from credentialNumber string | ✅ DONE | 300×300, EC level M, margin 2, path from `vms.qr.storage-path` |
| **Ph-3b** | Integrate QR generation into `BookingService.reserve()` after Nuveq calls | ✅ DONE | Failure logs WARN and **does not** fail the booking |
| **Ph-3b** | Store `qr_code_path_in` / `qr_code_path_out` in bookings table | ✅ DONE | Relative path (`qr-codes/{regId}_in.png`) |
| **Ph-3b** | `GET /api/bookings/{registrationId}/qr/in` — serve QR image | ✅ DONE | `BookingController`; `image/png`, `inline` disposition |
| **Ph-3b** | `GET /api/bookings/{registrationId}/qr/out` — serve QR image | ✅ DONE | Same handler, `type={in|out}`; 400 on other values, 404 if file/booking missing |
| **Ph-3b** | Include `qrCodeUrlIn` / `qrCodeUrlOut` in reserve response DTO | ✅ DONE | Built from `vms.qr.base-serve-url`; null when QR failed |
| **Ph-4** | `WebhookEventHandler` — update to match on `card_number_in/out` fields | ✅ DONE | `BookingService.processCardEvent`; `WebhookCardEventListener` + `EventWebhookController` both routed to it |
| **Ph-4** | Webhook CHECK_IN → `booking_status = ACTIVE` | ✅ DONE | `direction=IN` + `card_number_in` + status `PENDING` |
| **Ph-4** | Webhook CHECK_OUT → `booking_status = COMPLETED` | ✅ DONE | `direction=OUT` + `card_number_out` + status `ACTIVE`; repeat events ignored |
| **Ph-5** | `BookingExpiryScheduler` — runs every minute | ✅ DONE | `@Scheduled(cron = "0 * * * * *")` on `BookingService.expirePendingBookings` |
| **Ph-5** | Expiry: set `booking_status = EXPIRED` | ✅ DONE | Grace period `vms.booking.expiry-minutes` (default 15) |
| **Ph-5** | Expiry: call Nuveq `DELETE /api/visitors/registrations/{id}` x2 | ✅ DONE | `NuveqVisitorClient.deleteVisitorRegistration(Long)` added |
| **Ph-5** | Expiry: delete QR PNG files from disk (optional cleanup) | ✅ DONE | `QrCodeService.deleteQrCode(path)` per booking |
| **Ph-5** | Expiry: log to `transactions.log` | ✅ DONE | `action=BOOKING_EXPIRED \| result=SUCCESS` |
| **Ph-6** | `GET /rooms/{roomId}/availability` response — 13-slot array | ✅ DONE | Verified live: 13 slots, correct `bookedBy` on overlaps |
| **Ph-7** | vms-form: date picker → availability fetch | ⬜ TODO | Client side — separate repo |
| **Ph-7** | vms-form: hour picker — disable booked slots with tooltip | ⬜ TODO | Client side — separate repo |
| **Ph-7** | vms-form: 409 conflict handling | ⬜ TODO | Client side — separate repo |
| **Ph-7** | vms-form: confirmation screen — show QR images from `qrCodeUrlIn/Out` | ⬜ TODO | Client side — separate repo |
| **Ph-8** | End-to-end test: book → check-in → check-out | ✅ DONE | Verified live against PG 16 + mock Nuveq: `PENDING → ACTIVE → COMPLETED`, repeat event = no-op |
| **Ph-8** | End-to-end test: book → expire → slot freed + QR cleaned up | ✅ DONE | Scheduler flipped status, deleted both Nuveq registrations + both PNGs, slot released |

### Additional work not in original plan
| Task | Status | Notes |
|------|--------|-------|
| Remove legacy dual-`Visitor` stack | ✅ DONE | `Visitor`, `UserType`, `VisitorMapper`, `VisitorRepository`, `VisitorService`, `VisitorResponseDto`, `ReservationResponseDto` deleted — required because V5 renamed the table their entity mapped to (`ddl-auto: validate` would fail) |
| Add `VmsProperties` config binding | ✅ DONE | `vms.booking.*`, `vms.qr.*`; registered in `VisitorMiddlewareApplication` |
| Add `BookingConflictException` → HTTP 409 | ✅ DONE | New handler in `GlobalExceptionHandler` |
| Fix Nuveq 401 handling | ✅ DONE | `SimpleClientHttpRequestFactory` threw JDK `HttpRetryException` on 401, bypassing the error handler and returning 502. Switched to `JdkClientHttpRequestFactory`; 401 now → `NuveqAuthException` → HTTP 500, no retry (matches error matrix) |
| Docker / `.gitignore` for QR storage | ✅ DONE | `Dockerfile` creates `/app/data/qr-codes`, compose mounts `./data/qr-codes`, `.gitignore` excludes `data/qr-codes/*.png` |
| Test suite | ✅ DONE | 29 tests green: `BookingServiceTest` (12), `RoomServiceTest` (5), `TransactionLoggerTest` (1), `VisitorControllerTest` (5), `RoomControllerTest` (6) |

**Status Key**: ⬜ TODO · 🔄 IN PROGRESS · ✅ DONE · ❌ BLOCKED · ⛔ SKIPPED (with reason)

---

## Phase Details

### Phase 1 — Database Migrations

**Files to create** (Flyway SQL in `src/main/resources/db/migration/`):

#### `V4__create_bookings_table.sql`
```sql
CREATE TABLE bookings (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    registration_id           VARCHAR(64) NOT NULL UNIQUE,
    room_id                   BIGINT NOT NULL REFERENCES rooms(id),
    visitor_name              VARCHAR(255) NOT NULL,
    email                     VARCHAR(255),
    phone                     VARCHAR(50),
    user_photo                TEXT,
    vehicle_number            VARCHAR(50),
    card_number_in            VARCHAR(64) NOT NULL,
    card_number_out           VARCHAR(64) NOT NULL,
    site_id                   BIGINT NOT NULL,
    lift_group_id             BIGINT NOT NULL,
    visit_start               TIMESTAMPTZ NOT NULL,
    visit_end                 TIMESTAMPTZ NOT NULL,
    booking_status            VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                              CHECK (booking_status IN ('PENDING','ACTIVE','COMPLETED','EXPIRED','CANCELLED')),
    nuveq_visitor_id_in       BIGINT,
    nuveq_registration_id_in  BIGINT,
    nuveq_visitor_id_out      BIGINT,
    nuveq_registration_id_out BIGINT,
    qr_code_path_in           VARCHAR(500),  -- e.g.: qr-codes/REG-20261003-8921_in.png
    qr_code_path_out          VARCHAR(500),  -- e.g.: qr-codes/REG-20261003-8921_out.png
    created_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON COLUMN bookings.qr_code_path_in  IS 'Filesystem path of QR PNG for CHECK_IN credential (cardNumberIn)';
COMMENT ON COLUMN bookings.qr_code_path_out IS 'Filesystem path of QR PNG for CHECK_OUT credential (cardNumberOut)';

-- Slot overlap check: fast query for availability
CREATE INDEX idx_bookings_slot ON bookings(room_id, visit_start, visit_end, booking_status);

-- Expiry scheduler query: find PENDING past grace period
CREATE INDEX idx_bookings_expiry ON bookings(booking_status, visit_start)
  WHERE booking_status = 'PENDING';

-- Card event matching
CREATE INDEX idx_bookings_card_in  ON bookings(card_number_in,  booking_status);
CREATE INDEX idx_bookings_card_out ON bookings(card_number_out, booking_status);

COMMENT ON TABLE  bookings IS 'Time-slot booking records linking visitors to rooms via Nuveq access control';
COMMENT ON COLUMN bookings.booking_status IS 'PENDING=awaiting checkin, ACTIVE=checked in, COMPLETED=checked out, EXPIRED=no-show, CANCELLED=admin cancel';
```

#### `V5__cleanup_visitors_table.sql`
> Decision: The old `visitors` table (CHECK_IN/CHECK_OUT split records) is replaced by the unified `bookings` table. If the old table exists, drop or rename it.
```sql
-- Only if old visitors table exists from previous setup
-- DROP TABLE IF EXISTS visitors CASCADE;
-- Or rename for backup: ALTER TABLE visitors RENAME TO visitors_legacy;
```

---

### Phase 3 — BookingService Logic

#### Slot Overlap Query (JPQL / native SQL)
```sql
SELECT COUNT(*) FROM bookings
WHERE room_id = :roomId
  AND booking_status IN ('PENDING', 'ACTIVE')
  AND visit_start < :requestedEnd
  AND visit_end   > :requestedStart
```
If count > 0 → **HTTP 409 Conflict**.

#### Availability Slot Grid (for `GET /rooms/{id}/availability`)
```sql
-- Get all active bookings for the room on the given date
SELECT visit_start, visit_end FROM bookings
WHERE room_id = :roomId
  AND booking_status IN ('PENDING', 'ACTIVE')
  AND DATE(visit_start AT TIME ZONE 'Asia/Jakarta') = :date
```
Backend iterates hours 9–22, marks each hour unavailable if any booking overlaps it.

---

### Phase 5 — Expiry Scheduler

```
Cron: "0 * * * * *"   (every minute, second=0)

Config property:
  vms.booking.expiry-minutes=15
  vms.booking.operating-hours-start=9
  vms.booking.operating-hours-end=22
  vms.booking.timezone=Asia/Jakarta
```

**Query**: Find all bookings where:
- `booking_status = 'PENDING'`
- `visit_start <= NOW() - expiry_minutes`

**For each expired booking**:
1. `UPDATE bookings SET booking_status='EXPIRED' WHERE id=...`
2. `DELETE Nuveq /api/visitors/registrations/{nuveq_registration_id_in}` (retry 3×)
3. `DELETE Nuveq /api/visitors/registrations/{nuveq_registration_id_out}` (retry 3×)
4. Log to `transactions.log`

---

### Phase 7 — vms-form UI Updates

#### New: Date Picker → Room Availability
When user selects a room AND a date:
```js
GET /api/rooms/{roomId}/availability?date=YYYY-MM-DD
```
Render 13 hour-buttons (9 AM → 10 PM).  
Disabled button shows tooltip: `"Already booked (REG-...)"`.

#### Hour Selection Logic
- User clicks start hour → clock icon appears
- User clicks end hour (must be ≥ start+1) → range highlights
- `visitStart = selected_date + startHour + ":00:00+07:00"`
- `visitEnd   = selected_date + endHour   + ":00:00+07:00"`

#### 409 Conflict Error
```js
if (response.status === 409) {
  showBanner("This room is already booked for the selected time slot. Please choose a different time.");
}
```

#### Confirmation Screen — Display QR Codes
On successful `POST /api/visitors/registration`, response includes `qrCodeUrlIn` / `qrCodeUrlOut`:
```js
// Render QR image
<img src="/api/bookings/REG-20261003-8921/qr/in" alt="Check-In QR" />
<img src="/api/bookings/REG-20261003-8921/qr/out" alt="Check-Out QR" />
```

---

### Phase 3b — QR Code Generation

#### Library: Google ZXing (standard Java QR library)

**pom.xml additions:**
```xml
<!-- QR Code Generation -->
<dependency>
    <groupId>com.google.zxing</groupId>
    <artifactId>core</artifactId>
    <version>3.5.3</version>
</dependency>
<dependency>
    <groupId>com.google.zxing</groupId>
    <artifactId>javase</artifactId>
    <version>3.5.3</version>
</dependency>
```

#### QR Code — What to Encode
The QR encodes the **raw `credentialNumber`** (= card number as string) that was registered with Nuveq.
- CHECK_IN QR → encodes `cardNumberIn`
- CHECK_OUT QR → encodes `cardNumberOut`

When a door reader scans the QR, it reads the number and matches it to the Nuveq credential.

#### Storage Strategy: Local Filesystem Volume
```
Project root (host):   ./data/qr-codes/
In Docker container:   /app/data/qr-codes/
DB stored path:        qr-codes/REG-20261003-8921_in.png   ← relative to base dir
Served via:            GET /api/bookings/{regId}/qr/in
```

**Why not store as base64 in DB?**
- PNG files on disk are fast to serve as `image/png` binary response
- DB stays lean (no large BLOBs)
- Easy to clean up expired files with `Files.deleteIfExists()`
- Docker volume = persisted across container restarts

#### `QrCodeService` — Pseudo-implementation
```java
@Service
public class QrCodeService {

    @Value("${vms.qr.storage-path:/app/data/qr-codes}")
    private String storagePath;

    public String generateAndSave(String credentialNumber, String filename) throws IOException {
        Map<EncodeHintType, Object> hints = Map.of(
            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN, 2
        );
        BitMatrix matrix = new QRCodeWriter().encode(
            credentialNumber, BarcodeFormat.QR_CODE, 300, 300, hints
        );
        Path dir  = Path.of(storagePath);
        Files.createDirectories(dir);
        Path file = dir.resolve(filename + ".png");
        MatrixToImageWriter.writeToPath(matrix, "PNG", file);
        return "qr-codes/" + filename + ".png"; // relative path stored in DB
    }
}
```

#### `BookingController` — QR Serve Endpoint
```java
@GetMapping("/api/bookings/{registrationId}/qr/{type}")
public ResponseEntity<Resource> getQrCode(
        @PathVariable String registrationId,
        @PathVariable String type) {   // "in" or "out"

    Booking booking = bookingRepository.findByRegistrationId(registrationId)
        .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

    String path = type.equals("in") ? booking.getQrCodePathIn() : booking.getQrCodePathOut();
    if (path == null) return ResponseEntity.notFound().build();

    Path file = Path.of(qrStorageBasePath, path.replace("qr-codes/", ""));
    Resource resource = new FileSystemResource(file);
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_PNG)
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getFileName() + "\"")
        .body(resource);
}
```

#### Call Order in `BookingService.reserve()`
```
1. Validate slot availability
2. INSERT booking (status=PENDING, qr fields null)
3. POST Nuveq → visitor_in  → save nuveq_visitor_id_in, nuveq_registration_id_in
4. POST Nuveq → visitor_out → save nuveq_visitor_id_out, nuveq_registration_id_out
5. QrCodeService.generateAndSave(cardNumberIn,  "{regId}_in")  → save qr_code_path_in
6. QrCodeService.generateAndSave(cardNumberOut, "{regId}_out") → save qr_code_path_out
7. UPDATE booking with all Nuveq IDs + QR paths
8. Return response DTO with qrCodeUrlIn / qrCodeUrlOut
```

> If QR generation fails (IOException), log WARN but do NOT fail the reservation. The booking is valid; QR can be regenerated on demand later.

#### Expiry Cleanup
```java
// On booking expiry:
Files.deleteIfExists(Path.of(storagePath, booking.getQrCodePathIn().replace("qr-codes/", "")));
Files.deleteIfExists(Path.of(storagePath, booking.getQrCodePathOut().replace("qr-codes/", "")));
```

---

## Nuveq API Reference (Used Endpoints)

| Method | Endpoint | Purpose |
|--------|----------|---------|
| `POST` | `/api/visitors` | Create visitor (CHECK_IN or CHECK_OUT) |
| `DELETE` | `/api/visitors/registrations/{id}` | Delete visitor registration (expiry/cancel) |
| `POST` | `/api/webhooks` | Register webhook URL |
| `GET` | `/api/events?date=YYYY-MM-DD` | Poll events (fallback to webhook) |

---

## Configuration Properties

```yaml
# application.yml additions:

nuveq:
  base-url: ${NUVEQ_BASE_URL:https://api-v2.nuveq.cloud}
  api-key: ${NUVEQ_API_KEY:}
  connect-timeout: 5s
  read-timeout: 10s
  max-retries: 3
  backoff-delay-ms: 500

vms:
  booking:
    expiry-minutes: 15
    operating-hours-start: 9
    operating-hours-end: 22
    timezone: Asia/Jakarta
  qr:
    storage-path: ${QR_STORAGE_PATH:/app/data/qr-codes}
    base-serve-url: ${APP_BASE_URL:http://localhost:8080}
```

## Docker Changes

**`docker-compose.yml`** — add volume mount for QR codes:
```yaml
app:
  volumes:
    - ./logs:/app/logs
    - ./data/qr-codes:/app/data/qr-codes   # NEW — persists QR PNG files
```

**`Dockerfile`** — add directory creation:
```dockerfile
RUN mkdir -p /app/logs /app/data/qr-codes
```

**.gitignore** — exclude generated QR files:
```
data/qr-codes/*.png
```
