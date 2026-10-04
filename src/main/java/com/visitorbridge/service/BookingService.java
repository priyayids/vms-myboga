package com.visitorbridge.service;

import com.visitorbridge.client.NuveqCreateVisitorRequest;
import com.visitorbridge.client.NuveqCreateVisitorResponse;
import com.visitorbridge.client.NuveqEventDto;
import com.visitorbridge.client.NuveqVisitorClient;
import com.visitorbridge.config.VmsProperties;
import com.visitorbridge.dto.AvailabilityResponseDto;
import com.visitorbridge.dto.AvailabilitySlotDto;
import com.visitorbridge.dto.BookingResponseDto;
import com.visitorbridge.dto.VisitorRegistrationRequest;
import com.visitorbridge.dto.WebhookProcessingResult;
import com.visitorbridge.exception.BookingConflictException;
import com.visitorbridge.exception.InvalidBookingStateException;
import com.visitorbridge.exception.ResourceNotFoundException;
import com.visitorbridge.model.Booking;
import com.visitorbridge.model.BookingStatus;
import com.visitorbridge.model.Door;
import com.visitorbridge.model.Room;
import com.visitorbridge.model.WebhookEventLog;
import com.visitorbridge.repository.BookingRepository;
import com.visitorbridge.repository.DoorRepository;
import com.visitorbridge.repository.RoomRepository;
import com.visitorbridge.repository.WebhookEventLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingRepository bookingRepository;
    private final RoomRepository roomRepository;
    private final DoorRepository doorRepository;
    private final WebhookEventLogRepository webhookEventLogRepository;
    private final NuveqVisitorClient nuveqVisitorClient;
    private final QrCodeService qrCodeService;
    private final TransactionLogger transactionLogger;
    private final VmsProperties vmsProperties;

    /**
     * Booking boundaries must land on a 5-minute mark. The availability grid is displayed
     * hourly, but the overlap check is a true interval comparison, so the finer boundary adds
     * precision without weakening double-booking protection. Restricting to whole hours made
     * the UI unusable: vms-form rounds defaults up to the next 15 minutes and operators type
     * real times like 17:10.
     */
    private static final int SLOT_GRANULARITY_MINUTES = 5;

    // --- Core: Reserve booking ---

    @Transactional
    public BookingResponseDto reserveBooking(VisitorRegistrationRequest request) {
        String baseRegId = request.getRegistrationId();
        String maskedCard = TransactionLogger.maskCardNumber(request.getCardNumber());
        transactionLogger.logTransaction("BookingService", baseRegId, "BOOKING_RECEIVED", "RECEIVED",
                "card=" + maskedCard + " roomId=" + request.getRoomId());

        // FR-1 Idempotency
        Optional<Booking> existing = bookingRepository.findByRegistrationId(baseRegId);
        if (existing.isPresent()) {
            transactionLogger.logTransaction("BookingService", baseRegId, "BOOKING_IDEMPOTENT", "EXISTING_RETURNED",
                    "status=" + existing.get().getBookingStatus());
            return mapToResponseDto(existing.get(), true);
        }

        validateVisitWindow(request);

        // Validate room and resolve doors, siteId, and liftGroupId
        Room room = roomRepository.findById(request.getRoomId())
                .orElseThrow(() -> new ResourceNotFoundException("Room not found with id: " + request.getRoomId()));

        Long siteId = request.getSiteId() != null ? request.getSiteId()
                : (room.getSiteId() != null ? room.getSiteId() : 167L);

        Long liftGroupId = request.getLiftGroupId() != null ? request.getLiftGroupId()
                : (room.getLiftGroupId() != null ? room.getLiftGroupId() : 630L);

        List<Long> allowedDoorIds = request.getAllowedDoorIds();
        if (allowedDoorIds == null || allowedDoorIds.isEmpty()) {
            List<Door> doors = doorRepository.findByRoomId(room.getId());
            if (doors.isEmpty()) {
                throw new IllegalArgumentException("The selected room (id: " + request.getRoomId() + ") has no mapped doors");
            }
            allowedDoorIds = doors.stream().map(Door::getNuveqDoorId).toList();
            request.setAllowedDoorIds(allowedDoorIds);
        }

        // Slot overlap validation
        List<Booking> overlaps = bookingRepository.findOverlappingBookings(
                request.getRoomId(),
                List.of(BookingStatus.PENDING, BookingStatus.ACTIVE),
                request.getVisitEnd(),
                request.getVisitStart()
        );
        if (!overlaps.isEmpty()) {
            transactionLogger.logTransaction("BookingService", baseRegId, "BOOKING_CONFLICT", "SLOT_OVERLAP",
                    "roomId=" + request.getRoomId());
            throw new BookingConflictException("Room is already booked for the selected time slot");
        }

        // Create booking record (PENDING)
        Booking booking = Booking.builder()
                .registrationId(baseRegId)
                .room(room)
                .visitorName(request.getFullName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .userPhoto(request.getUserPhoto())
                .vehicleNumber(request.getVehicleNumber())
                .cardNumberIn(request.getCardNumber())
                .cardNumberOut(request.getCheckOutCardNumber() != null && !request.getCheckOutCardNumber().isBlank()
                        ? request.getCheckOutCardNumber()
                        : request.getCardNumber())
                .siteId(siteId)
                .liftGroupId(liftGroupId)
                .visitStart(request.getVisitStart())
                .visitEnd(request.getVisitEnd())
                .bookingStatus(BookingStatus.PENDING)
                .build();
        booking = bookingRepository.save(booking);

        // Push CHECK_IN visitor to Nuveq
        Long credNumIn = parseCredentialNumber(booking.getCardNumberIn());
        NuveqCreateVisitorRequest reqIn = NuveqCreateVisitorRequest.builder()
                .name(request.getFullName() + "_in")
                .credentialNumber(credNumIn)
                .email(request.getEmail())
                .phone(request.getPhone())
                .userPhoto(request.getUserPhoto())
                .vehicleNumber(request.getVehicleNumber())
                .visitStart(request.getVisitStart())
                .visitEnd(request.getVisitEnd())
                .siteId(siteId)
                .liftGroupId(liftGroupId)
                .allowedDoorIds(allowedDoorIds)
                .build();

        NuveqCreateVisitorResponse respIn = nuveqVisitorClient.createVisitor(reqIn, baseRegId + "_in");
        if (respIn != null && respIn.getData() != null) {
            booking.setNuveqVisitorIdIn(respIn.getData().getVisitorId());
            booking.setNuveqRegistrationIdIn(respIn.getData().getVisitorRegistrationId());
        }

        // Push CHECK_OUT visitor to Nuveq
        Long credNumOut = parseCredentialNumber(booking.getCardNumberOut());
        NuveqCreateVisitorRequest reqOut = NuveqCreateVisitorRequest.builder()
                .name(request.getFullName() + "_out")
                .credentialNumber(credNumOut)
                .email(request.getEmail())
                .phone(request.getPhone())
                .userPhoto(request.getUserPhoto())
                .vehicleNumber(request.getVehicleNumber())
                .visitStart(request.getVisitStart())
                .visitEnd(request.getVisitEnd())
                .siteId(siteId)
                .liftGroupId(liftGroupId)
                .allowedDoorIds(allowedDoorIds)
                .build();

        NuveqCreateVisitorResponse respOut = nuveqVisitorClient.createVisitor(reqOut, baseRegId + "_out");
        if (respOut != null && respOut.getData() != null) {
            booking.setNuveqVisitorIdOut(respOut.getData().getVisitorId());
            booking.setNuveqRegistrationIdOut(respOut.getData().getVisitorRegistrationId());
        }

        // Generate QR codes (failure does NOT fail the booking)
        booking.setQrCodePathIn(qrCodeService.generateAndSave(
                booking.getCardNumberIn(),
                qrCodeService.buildFileBaseName(booking.getVisitorName(), baseRegId, "in")));
        booking.setQrCodePathOut(qrCodeService.generateAndSave(
                booking.getCardNumberOut(),
                qrCodeService.buildFileBaseName(booking.getVisitorName(), baseRegId, "out")));

        booking = bookingRepository.save(booking);

        transactionLogger.logTransaction("BookingService", baseRegId, "BOOKING_CREATED", "SUCCESS",
                "bookingId=" + booking.getId() + " in=" + booking.getNuveqVisitorIdIn() + " out=" + booking.getNuveqVisitorIdOut());

        return mapToResponseDto(booking, false);
    }

    // --- Availability ---

    @Transactional(readOnly = true)
    public AvailabilityResponseDto getAvailability(Long roomId, String date) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found with id: " + roomId));

        List<Booking> active = bookingRepository.findActiveBookingsForDate(
                roomId, List.of(BookingStatus.PENDING.name(), BookingStatus.ACTIVE.name()), date);

        ZoneId zone = ZoneId.of(vmsProperties.getBooking().getTimezone());
        int startHour = vmsProperties.getBooking().getOperatingHoursStart();
        int endHour = vmsProperties.getBooking().getOperatingHoursEnd();

        List<AvailabilitySlotDto> slots = new ArrayList<>();
        for (int hour = startHour; hour < endHour; hour++) {
            OffsetDateTime slotStart = LocalDate.parse(date).atTime(hour, 0).atZone(zone).toOffsetDateTime();
            OffsetDateTime slotEnd = slotStart.plusHours(1);

            Optional<Booking> conflicting = active.stream()
                    .filter(b -> b.getVisitStart().isBefore(slotEnd) && b.getVisitEnd().isAfter(slotStart))
                    .findFirst();

            slots.add(AvailabilitySlotDto.builder()
                    .hour(hour)
                    .available(conflicting.isEmpty())
                    .bookedBy(conflicting.map(Booking::getRegistrationId).orElse(null))
                    .build());
        }

        return AvailabilityResponseDto.builder()
                .roomId(roomId)
                .roomName(room.getCustomName())
                .date(date)
                .slots(slots)
                .build();
    }

    private void validateVisitWindow(VisitorRegistrationRequest request) {
        ZoneId zone = ZoneId.of(vmsProperties.getBooking().getTimezone());
        int startHour = vmsProperties.getBooking().getOperatingHoursStart();
        int endHour = vmsProperties.getBooking().getOperatingHoursEnd();

        OffsetDateTime visitStart = request.getVisitStart();
        OffsetDateTime visitEnd = request.getVisitEnd();

        if (visitStart == null || visitEnd == null) {
            throw new IllegalArgumentException("visitStart and visitEnd are required");
        }
        if (!visitEnd.isAfter(visitStart)) {
            throw new IllegalArgumentException("visitEnd must be after visitStart");
        }

        ZonedDateTime startLocal = visitStart.atZoneSameInstant(zone);
        ZonedDateTime endLocal = visitEnd.atZoneSameInstant(zone);

        int startMinuteOfDay = startLocal.getHour() * 60 + startLocal.getMinute();
        int endMinuteOfDay = endLocal.getHour() * 60 + endLocal.getMinute();

        if (startMinuteOfDay < startHour * 60 || startMinuteOfDay >= endHour * 60) {
            throw new IllegalArgumentException(
                    "visitStart must be within operating hours " + pad(startHour) + ":00 to " + pad(endHour) + ":00");
        }
        if (endMinuteOfDay > endHour * 60) {
            throw new IllegalArgumentException(
                    "visitEnd must not exceed operating hours " + pad(endHour) + ":00");
        }

        // Alignment guard only: the real double-booking protection is the interval overlap check.
        // The availability grid stays hourly; this is just the booking boundary.
        if (startLocal.getMinute() % SLOT_GRANULARITY_MINUTES != 0
                || endLocal.getMinute() % SLOT_GRANULARITY_MINUTES != 0) {
            throw new IllegalArgumentException(
                    "visitStart and visitEnd must align to " + SLOT_GRANULARITY_MINUTES + "-minute boundaries");
        }
    }

    private String pad(int hour) {
        return String.format("%02d", hour);
    }

    // --- Webhook Card Event Handling ---

    @Transactional
    public WebhookProcessingResult processCardEvent(NuveqEventDto event, String rawPayload) {
        String cardNumber = null;
        if (event != null) {
            if (event.getCardNo() != null) {
                cardNumber = String.valueOf(event.getCardNo());
            } else if (event.getCardId() != null) {
                cardNumber = String.valueOf(event.getCardId());
            }
        }
        String direction = (event != null) ? normalizeDirection(event.getDirection()) : null;
        return processEventWithLogging(cardNumber, direction, event, rawPayload);
    }

    /**
     * Collapses every direction vocabulary into {@code IN} / {@code OUT}.
     *
     * <p>Verified against the live Nuveq API: {@code GET /api/events} reports
     * {@code direction} as {@code "Entry"}, {@code "Exit"} or {@code "Status"}, while the
     * webhook payload and internal callers use {@code "IN"} / {@code "OUT"}. Comparing the
     * raw value against {@code "IN"} meant every genuine entry swipe fell through to the
     * unknown-card branch and was dropped, so a visitor could tap in and never be checked in.
     *
     * @return {@code "IN"}, {@code "OUT"}, or {@code null} when the event is not a card pass
     *         (for example Nuveq's {@code "Status"} controller-heartbeat rows).
     */
    static String normalizeDirection(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "in", "entry", "enter", "checkin", "check-in" -> "IN";
            case "out", "exit", "checkout", "check-out" -> "OUT";
            default -> null;
        };
    }

    @Transactional
    public WebhookProcessingResult processCardEvent(String cardNumber, String direction) {
        return processEventWithLogging(cardNumber, normalizeDirection(direction), null,
                "{\"cardNumber\":\"" + cardNumber + "\",\"direction\":\"" + direction + "\"}");
    }

    private WebhookProcessingResult processEventWithLogging(
            String cardNumber, String direction, NuveqEventDto event, String rawPayload) {

        String maskedCard = TransactionLogger.maskCardNumber(cardNumber);
        String eventType = (event != null && event.getEventName() != null) ? event.getEventName() : "CARD_SWIPE";

        if (cardNumber == null || cardNumber.isBlank()) {
            log.warn("Webhook card event missing card number: {}", rawPayload);
            recordWebhookLog(eventType, null, direction, event, null, null, "INVALID_MISSING_CARD", rawPayload);
            return WebhookProcessingResult.builder()
                    .cardNumber(null).direction(direction).matched(false).actionTaken("INVALID_MISSING_CARD").build();
        }

        // Match card_number_in (direction IN) or card_number_out (direction OUT)
        Booking target = null;
        if ("IN".equalsIgnoreCase(direction)) {
            target = bookingRepository.findByCardNumberInAndBookingStatus(cardNumber, BookingStatus.PENDING)
                    .stream().findFirst().orElse(null);
        } else if ("OUT".equalsIgnoreCase(direction)) {
            target = bookingRepository.findByCardNumberOutAndBookingStatus(cardNumber, BookingStatus.ACTIVE)
                    .stream().findFirst().orElse(null);
        }

        if (target == null) {
            // Distinguish "we do not know this card" from "this was never a card pass" —
            // Nuveq emits Status/heartbeat rows on the same feed and conflating them
            // sends operators hunting for a bad card number that does not exist.
            String action = (direction == null)
                    ? "NON_CARD_EVENT_IGNORED"
                    : "UNKNOWN_CARD_IGNORED";
            log.info("Card event ignored: result={} card={} direction={} eventName={}",
                    action, maskedCard, direction, eventType);
            transactionLogger.logTransaction("BookingEventHandler", "N/A", "CARD_EVENT", action,
                    "card=" + maskedCard + " dir=" + direction + " event=" + eventType);
            recordWebhookLog(eventType, cardNumber, direction, event, null, null, action, rawPayload);
            return WebhookProcessingResult.builder()
                    .cardNumber(cardNumber).direction(direction).matched(false).actionTaken(action).build();
        }

        if ("IN".equalsIgnoreCase(direction) && target.getBookingStatus() == BookingStatus.PENDING) {
            target.setBookingStatus(BookingStatus.ACTIVE);
            bookingRepository.save(target);
            transactionLogger.logTransaction("BookingEventHandler", target.getRegistrationId(), "CHECK_IN", "SUCCESS",
                    "bookingId=" + target.getId());
            recordWebhookLog(eventType, cardNumber, direction, event, null, target.getRegistrationId(),
                    "BOOKING_STATUS_UPDATE_ACTIVE", rawPayload);
            return WebhookProcessingResult.builder()
                    .cardNumber(cardNumber).direction(direction).matched(true)
                    .actionTaken("BOOKING_STATUS_UPDATE_ACTIVE")
                    .matchedRegistrationId(target.getRegistrationId()).build();
        }

        if ("OUT".equalsIgnoreCase(direction) && target.getBookingStatus() == BookingStatus.ACTIVE) {
            target.setBookingStatus(BookingStatus.COMPLETED);
            bookingRepository.save(target);
            transactionLogger.logTransaction("BookingEventHandler", target.getRegistrationId(), "CHECK_OUT", "SUCCESS",
                    "bookingId=" + target.getId());
            recordWebhookLog(eventType, cardNumber, direction, event, null, target.getRegistrationId(),
                    "BOOKING_STATUS_UPDATE_COMPLETED", rawPayload);
            return WebhookProcessingResult.builder()
                    .cardNumber(cardNumber).direction(direction).matched(true)
                    .actionTaken("BOOKING_STATUS_UPDATE_COMPLETED")
                    .matchedRegistrationId(target.getRegistrationId()).build();
        }

        recordWebhookLog(eventType, cardNumber, direction, event, null, target.getRegistrationId(),
                "IGNORED_INVALID_TRANSITION", rawPayload);
        return WebhookProcessingResult.builder()
                .cardNumber(cardNumber).direction(direction).matched(true)
                .actionTaken("IGNORED_INVALID_TRANSITION")
                .matchedRegistrationId(target.getRegistrationId()).build();
    }

    // --- Expiry Scheduler ---

    /**
     * Auto check-out for no-show visitors.
     *
     * <p>The grace window is counted from the booking's {@code visitStart} and is
     * configured per room ({@code room.expire_minutes}); when no IN card event
     * has arrived by {@code visitStart + expireMinutes}, the booking is expired
     * (auto check-out). The freed slot becomes bookable again because the
     * availability and overlap checks only count PENDING and ACTIVE bookings.
     *
     * <p>Rooms without a configured value (legacy rows) fall back to the global
     * {@code vms.booking.expiry-minutes}.
     */
    @Scheduled(cron = "0 * * * * *")
    @Transactional
    public void expirePendingBookings() {
        List<Booking> pending = bookingRepository.findByBookingStatus(BookingStatus.PENDING);
        if (pending.isEmpty()) {
            return;
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (Booking b : pending) {
            try {
                OffsetDateTime deadline = b.getVisitStart().plusMinutes(expireMinutesFor(b.getRoom()));
                if (!now.isAfter(deadline)) {
                    continue;
                }

                b.setBookingStatus(BookingStatus.EXPIRED);
                bookingRepository.save(b);

                releaseNuveqAndQr(b);

                transactionLogger.logTransaction("BookingExpiryScheduler", b.getRegistrationId(), "BOOKING_EXPIRED", "SUCCESS",
                        "roomId=" + (b.getRoom() != null ? b.getRoom().getId() : "N/A")
                                + " deadline=" + deadline);
            } catch (Exception e) {
                log.error("Failed to expire booking {}: {}", b.getRegistrationId(), e.getMessage(), e);
            }
        }
    }

    private int expireMinutesFor(Room room) {
        if (room != null && room.getExpireMinutes() != null && room.getExpireMinutes() > 0) {
            return room.getExpireMinutes();
        }
        return vmsProperties.getBooking().getExpiryMinutes();
    }

    @Scheduled(cron = "0 * * * * *")
    @Transactional
    public void autoCompleteActiveBookings() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<Booking> overdue = bookingRepository
                .findByBookingStatusAndVisitEndBefore(BookingStatus.ACTIVE, now);

        if (overdue.isEmpty()) {
            return;
        }

        for (Booking b : overdue) {
            try {
                b.setBookingStatus(BookingStatus.COMPLETED);
                bookingRepository.save(b);
                transactionLogger.logTransaction("BookingAutoComplete", b.getRegistrationId(),
                        "AUTO_COMPLETED", "SUCCESS",
                        "bookingId=" + b.getId() + " visitEnd=" + b.getVisitEnd());
            } catch (Exception e) {
                log.error("Failed to auto-complete booking {}: {}", b.getRegistrationId(), e.getMessage(), e);
            }
        }
    }

    // --- Helpers ---

    /**
     * Releases every external resource a booking holds: both Nuveq registrations and both
     * QR images, then clears the QR columns so the API stops advertising files that no
     * longer exist.
     *
     * <p>Shared by cancellation and expiry on purpose. Those paths drifted apart once and the
     * expiry branch left {@code qr_code_path_*} populated while the PNGs were already deleted,
     * so every subsequent {@code GET .../qr/in} resolved to a file that was gone.
     *
     * <p>QR files are removed before the Nuveq calls: an upstream outage must not leave
     * orphaned images on disk. A failure mid-way is safe to repeat — the QR delete is
     * idempotent and a null registration id is skipped.
     */
    private void releaseNuveqAndQr(Booking booking) {
        qrCodeService.deleteQrCode(booking.getQrCodePathIn());
        qrCodeService.deleteQrCode(booking.getQrCodePathOut());
        booking.setQrCodePathIn(null);
        booking.setQrCodePathOut(null);
        bookingRepository.save(booking);

        if (booking.getNuveqRegistrationIdIn() != null) {
            nuveqVisitorClient.deleteVisitorRegistration(booking.getNuveqRegistrationIdIn());
        }
        if (booking.getNuveqRegistrationIdOut() != null) {
            nuveqVisitorClient.deleteVisitorRegistration(booking.getNuveqRegistrationIdOut());
        }
    }

    private Long parseCredentialNumber(String cardNumber) {
        try {
            return Long.parseLong(cardNumber.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return (long) Math.abs(cardNumber.hashCode());
        }
    }

    private void recordWebhookLog(String eventType, String cardNumber, String direction,
                                  NuveqEventDto event, java.util.UUID visitorId, String registrationId,
                                  String actionTaken, String rawPayload) {
        try {
            WebhookEventLog logEntry = WebhookEventLog.builder()
                    .eventType(eventType).cardNumber(cardNumber).direction(direction)
                    .doorId(event != null ? event.getDoorId() : null)
                    .siteId(event != null ? event.getSiteId() : null)
                    .matchedVisitorId(visitorId)
                    .matchedRegistrationId(registrationId)
                    .actionTaken(actionTaken).rawPayload(rawPayload != null ? rawPayload : "{}")
                    .build();
            webhookEventLogRepository.save(logEntry);
        } catch (Exception ex) {
            log.error("Failed to save webhook event log: {}", ex.getMessage(), ex);
        }
    }

    private BookingResponseDto mapToResponseDto(Booking b, boolean idempotent) {
        return BookingResponseDto.builder()
                .registrationId(b.getRegistrationId())
                .idempotent(idempotent)
                .roomId(b.getRoom() != null ? b.getRoom().getId() : null)
                .roomName(b.getRoom() != null ? b.getRoom().getCustomName() : null)
                .visitorName(b.getVisitorName())
                .email(b.getEmail())
                .phone(b.getPhone())
                .vehicleNumber(b.getVehicleNumber())
                .cardNumberIn(TransactionLogger.maskCardNumber(b.getCardNumberIn()))
                .cardNumberOut(TransactionLogger.maskCardNumber(b.getCardNumberOut()))
                .allowedDoors(b.getRoom() != null
                        ? doorRepository.findByRoomId(b.getRoom().getId()).stream()
                            .map(Door::getDoorName).toList()
                        : List.of())
                .visitStart(b.getVisitStart())
                .visitEnd(b.getVisitEnd())
                .bookingStatus(b.getBookingStatus())
                .qrCodeUrlIn(b.getQrCodePathIn() != null ? qrCodeService.buildServeUrl(b.getRegistrationId(), "in") : null)
                .qrCodeUrlOut(b.getQrCodePathOut() != null ? qrCodeService.buildServeUrl(b.getRegistrationId(), "out") : null)
                .createdAt(b.getCreatedAt())
                .build();
    }

    /**
     * Cancels a booking and removes every external artefact it created: both Nuveq
     * registrations and both QR images on disk.
     *
     * <p>The booking row is kept and marked CANCELLED rather than deleted, so the audit trail
     * survives and the freed time slot becomes bookable again (the availability query only
     * counts PENDING and ACTIVE).
     *
     * @throws com.visitorbridge.exception.InvalidBookingStateException if the booking already
     *         completed, expired, or was cancelled
     */
    @Transactional
    public BookingResponseDto cancelBooking(String registrationId) {
        Booking booking = getBookingByRegistrationId(registrationId);

        if (booking.getBookingStatus() == BookingStatus.COMPLETED
                || booking.getBookingStatus() == BookingStatus.EXPIRED
                || booking.getBookingStatus() == BookingStatus.CANCELLED) {
            throw new InvalidBookingStateException(
                    "Booking " + registrationId + " is already " + booking.getBookingStatus()
                            + " and cannot be cancelled");
        }

        booking.setBookingStatus(BookingStatus.CANCELLED);
        bookingRepository.save(booking);

        releaseNuveqAndQr(booking);

        transactionLogger.logTransaction("BookingService", registrationId, "BOOKING_CANCELLED", "SUCCESS",
                "bookingId=" + booking.getId() + " qrRemoved=true");

        return mapToResponseDto(booking, true);
    }

    @Transactional(readOnly = true)
    public Booking getBookingByRegistrationId(String registrationId) {
        return bookingRepository.findByRegistrationId(registrationId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + registrationId));
    }

    @Transactional(readOnly = true)
    public BookingResponseDto getBookingResponse(String registrationId) {
        return mapToResponseDto(getBookingByRegistrationId(registrationId), true);
    }

    @Transactional(readOnly = true)
    public String getQrCodePath(String registrationId, String type) {
        Booking booking = getBookingByRegistrationId(registrationId);
        return "in".equals(type) ? booking.getQrCodePathIn() : booking.getQrCodePathOut();
    }

    @Transactional(readOnly = true)
    public List<WebhookEventLog> getRecentWebhookLogs() {
        return webhookEventLogRepository.findTop20ByOrderByReceivedAtDesc();
    }
}
