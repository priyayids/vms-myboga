package com.visitorbridge.service;

import com.visitorbridge.client.NuveqCreateVisitorRequest;
import com.visitorbridge.client.NuveqEventDto;
import com.visitorbridge.client.NuveqCreateVisitorResponse;
import com.visitorbridge.client.NuveqVisitorClient;
import com.visitorbridge.config.VmsProperties;
import com.visitorbridge.dto.AvailabilityResponseDto;
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
import com.visitorbridge.repository.BookingRepository;
import com.visitorbridge.repository.DoorRepository;
import com.visitorbridge.repository.RoomRepository;
import com.visitorbridge.repository.WebhookEventLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookingServiceTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private RoomRepository roomRepository;
    @Mock
    private DoorRepository doorRepository;
    @Mock
    private WebhookEventLogRepository webhookEventLogRepository;
    @Mock
    private NuveqVisitorClient nuveqVisitorClient;
    @Mock
    private QrCodeService qrCodeService;
    @Mock
    private TransactionLogger transactionLogger;

    private BookingService bookingService;
    /** Real instance — the filename-building tests must not exercise a mock. */
    private QrCodeService realQrCodeService;
    private final AtomicLong idSequence = new AtomicLong(100);

    private static final ZoneOffset WIB = ZoneOffset.ofHours(7);

    @BeforeEach
    void setUp() {
        VmsProperties props = new VmsProperties();
        realQrCodeService = new QrCodeService(props);
        bookingService = new BookingService(
                bookingRepository, roomRepository, doorRepository, webhookEventLogRepository,
                nuveqVisitorClient, qrCodeService, transactionLogger, props);
    }

    private Room sampleRoom() {
        return Room.builder().id(5L).customName("Meeting Room Alpha").siteId(167L).build();
    }

    private VisitorRegistrationRequest buildRequest(String registrationId) {
        return VisitorRegistrationRequest.builder()
                .registrationId(registrationId)
                .roomId(5L)
                .fullName("Jane Doe")
                .email("jane@example.com")
                .phone("+6281234567890")
                .visitStart(OffsetDateTime.of(2026, 10, 3, 9, 0, 0, 0, WIB))
                .visitEnd(OffsetDateTime.of(2026, 10, 3, 11, 0, 0, 0, WIB))
                .siteId(167L)
                .liftGroupId(630L)
                .cardNumber("1253646425")
                .checkOutCardNumber("1253646426")
                .build();
    }

    private Booking savedBooking(Booking source) {
        source.setId(java.util.UUID.randomUUID());
        source.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        source.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return source;
    }

    private Booking pendingBooking() {
        Booking booking = Booking.builder()
                .registrationId("REG-1")
                .visitorName("Budi Santoso")
                .cardNumberIn("987654")
                .cardNumberOut("987655")
                .room(sampleRoom())
                .siteId(167L)
                .liftGroupId(630L)
                .visitStart(OffsetDateTime.of(2026, 10, 3, 17, 30, 0, 0, WIB))
                .visitEnd(OffsetDateTime.of(2026, 10, 4, 17, 30, 0, 0, WIB))
                .bookingStatus(BookingStatus.PENDING)
                .build();
        booking.setId(java.util.UUID.randomUUID());
        return booking;
    }

    private NuveqCreateVisitorResponse nuveqResponse(long visitorId, long registrationId) {
        NuveqCreateVisitorResponse.CreateVisitorData data = new NuveqCreateVisitorResponse.CreateVisitorData();
        data.setVisitorId(visitorId);
        data.setVisitorRegistrationId(registrationId);

        NuveqCreateVisitorResponse response = new NuveqCreateVisitorResponse();
        response.setError(0);
        response.setMessage("Success");
        response.setData(data);
        return response;
    }

    private void mockHappyNuveq() {
        when(nuveqVisitorClient.createVisitor(any(NuveqCreateVisitorRequest.class), anyString()))
                .thenReturn(nuveqResponse(111L, 222L))
                .thenReturn(nuveqResponse(333L, 444L));
        // Delegate filename building to the real service; only disk I/O is mocked away.
        when(qrCodeService.buildFileBaseName(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> realQrCodeService.buildFileBaseName(
                        inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        when(qrCodeService.generateAndSave(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1) + ".png");
        when(qrCodeService.buildServeUrl(anyString(), anyString()))
                .thenAnswer(inv -> "http://localhost:8080/api/v1/bookings/" + inv.getArgument(0) + "/qr/" + inv.getArgument(1));
    }

    @Test
    @DisplayName("buildFileBaseName includes visitor name and registrationId")
    void qrFileNameUsesVisitorNameAndRegistrationId() {
        assertThat(realQrCodeService.buildFileBaseName("Jane Doe", "REG-20261005-0001", "in"))
                .isEqualTo("jane-doe-reg-20261005-0001_in");
        assertThat(realQrCodeService.buildFileBaseName("Jane Doe", "REG-20261005-0001", "out"))
                .isEqualTo("jane-doe-reg-20261005-0001_out");
    }

    @Test
    @DisplayName("buildFileBaseName produces distinct names for visitors sharing a name")
    void qrFileNameIsUniquePerRegistration() {
        String first = realQrCodeService.buildFileBaseName("John Doe", "REG-A", "in");
        String second = realQrCodeService.buildFileBaseName("John Doe", "REG-B", "in");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("buildFileBaseName neutralises path traversal in the registrationId")
    void qrFileNameRejectsPathTraversal() {
        String name = realQrCodeService.buildFileBaseName("Jane Doe", "../../etc/passwd", "in");

        assertThat(name).doesNotContain("..");
        assertThat(name).doesNotContain("/");
        assertThat(name).startsWith("jane-doe-");
    }

    @Test
    @DisplayName("buildFileBaseName falls back when the visitor name has no usable characters")
    void qrFileNameFallsBackForNonAsciiNames() {
        String name = realQrCodeService.buildFileBaseName("访客", "REG-1", "in");

        assertThat(name).startsWith("visitor-");
    }

    @Test
    @DisplayName("buildFileBaseName collapses punctuation runs and trims separators")
    void qrFileNameNormalisesPunctuation() {
        String name = realQrCodeService.buildFileBaseName("  Jane   Q.  Doe! ", "REG 1", "in");

        assertThat(name).isEqualTo("jane-q-doe-reg-1_in");
    }

    @Test
    @DisplayName("cancelBooking marks CANCELLED, clears QR paths and deletes Nuveq registrations")
    void cancelBookingCleansUpEverything() {
        Booking booking = Booking.builder()
                .registrationId("REG-CANCEL-1")
                .visitorName("Jane Doe")
                .cardNumberIn("1253646425")
                .cardNumberOut("1253646426")
                .bookingStatus(BookingStatus.PENDING)
                .qrCodePathIn("jane-doe-reg-cancel-1_in.png")
                .qrCodePathOut("jane-doe-reg-cancel-1_out.png")
                .nuveqRegistrationIdIn(222L)
                .nuveqRegistrationIdOut(444L)
                .room(sampleRoom())
                .build();
        booking.setId(java.util.UUID.randomUUID());

        when(bookingRepository.findByRegistrationId("REG-CANCEL-1")).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        BookingResponseDto response = bookingService.cancelBooking("REG-CANCEL-1");

        assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(booking.getQrCodePathIn()).isNull();
        assertThat(booking.getQrCodePathOut()).isNull();
        assertThat(response.getQrCodeUrlIn()).isNull();
        assertThat(response.getQrCodeUrlOut()).isNull();

        verify(qrCodeService).deleteQrCode("jane-doe-reg-cancel-1_in.png");
        verify(qrCodeService).deleteQrCode("jane-doe-reg-cancel-1_out.png");
        verify(nuveqVisitorClient).deleteVisitorRegistration(222L);
        verify(nuveqVisitorClient).deleteVisitorRegistration(444L);
    }

    @Test
    @DisplayName("cancelBooking rejects a COMPLETED booking")
    void cancelBookingRejectsTerminalStates() {
        Booking booking = Booking.builder()
                .registrationId("REG-CANCEL-2")
                .bookingStatus(BookingStatus.COMPLETED)
                .build();

        when(bookingRepository.findByRegistrationId("REG-CANCEL-2")).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.cancelBooking("REG-CANCEL-2"))
                .isInstanceOf(InvalidBookingStateException.class)
                .hasMessageContaining("COMPLETED");

        verify(qrCodeService, never()).deleteQrCode(anyString());
        verify(nuveqVisitorClient, never()).deleteVisitorRegistration(any());
    }

    @Test
    @DisplayName("cancelBooking throws 404-mapped exception for an unknown registrationId")
    void cancelBookingRejectsUnknownBooking() {
        when(bookingRepository.findByRegistrationId("REG-NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.cancelBooking("REG-NOPE"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("reserveBooking creates one PENDING booking and pushes two Nuveq visitors")
    void reserveCreatesSingleBookingAndTwoNuveqVisitors() {
        when(bookingRepository.findByRegistrationId("REG-1")).thenReturn(Optional.empty());
        when(roomRepository.findById(5L)).thenReturn(Optional.of(sampleRoom()));
        when(doorRepository.findByRoomId(5L)).thenReturn(List.of(
                Door.builder().id(1L).nuveqDoorId(2596L).doorName("Demo Door 1").build(),
                Door.builder().id(2L).nuveqDoorId(4904L).doorName("5601 Door1").build()
        ));
        when(bookingRepository.findOverlappingBookings(any(), any(), any(), any())).thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> savedBooking(inv.getArgument(0)));
        mockHappyNuveq();

        BookingResponseDto response = bookingService.reserveBooking(buildRequest("REG-1"));

        verify(nuveqVisitorClient, org.mockito.Mockito.times(2))
                .createVisitor(any(NuveqCreateVisitorRequest.class), anyString());
        verify(bookingRepository, org.mockito.Mockito.atLeastOnce()).save(any(Booking.class));

        assertThat(response.getRegistrationId()).isEqualTo("REG-1");
        assertThat(response.isIdempotent()).isFalse();
        assertThat(response.getBookingStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(response.getRoomId()).isEqualTo(5L);
        assertThat(response.getRoomName()).isEqualTo("Meeting Room Alpha");
        assertThat(response.getCardNumberIn()).isEqualTo("****6425");
        assertThat(response.getCardNumberOut()).isEqualTo("****6426");
        assertThat(response.getQrCodeUrlIn()).endsWith("/qr/in");
        assertThat(response.getQrCodeUrlOut()).endsWith("/qr/out");
    }

    @Test
    @DisplayName("reserveBooking stores both Nuveq IDs and both QR paths")
    void reserveStoresNuveqIdsAndQrPaths() {
        when(bookingRepository.findByRegistrationId("REG-2")).thenReturn(Optional.empty());
        when(roomRepository.findById(5L)).thenReturn(Optional.of(sampleRoom()));
        when(doorRepository.findByRoomId(5L)).thenReturn(List.of(Door.builder().nuveqDoorId(2596L).build()));
        when(bookingRepository.findOverlappingBookings(any(), any(), any(), any())).thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> savedBooking(inv.getArgument(0)));
        mockHappyNuveq();

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        bookingService.reserveBooking(buildRequest("REG-2"));
        verify(bookingRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());

        Booking persisted = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(persisted.getNuveqVisitorIdIn()).isEqualTo(111L);
        assertThat(persisted.getNuveqRegistrationIdIn()).isEqualTo(222L);
        assertThat(persisted.getNuveqVisitorIdOut()).isEqualTo(333L);
        assertThat(persisted.getNuveqRegistrationIdOut()).isEqualTo(444L);
        assertThat(persisted.getQrCodePathIn()).isEqualTo("jane-doe-reg-2_in.png");
        assertThat(persisted.getQrCodePathOut()).isEqualTo("jane-doe-reg-2_out.png");
        assertThat(persisted.getCardNumberOut()).isEqualTo("1253646426");
    }

    @Test
    @DisplayName("reserveBooking returns existing booking without calling Nuveq when registrationId repeats")
    void reserveIsIdempotent() {
        Booking existing = Booking.builder()
                .registrationId("REG-3")
                .visitorName("Jane Doe")
                .cardNumberIn("1253646425")
                .cardNumberOut("1253646426")
                .bookingStatus(BookingStatus.PENDING)
                .room(sampleRoom())
                .visitStart(OffsetDateTime.of(2026, 10, 3, 9, 0, 0, 0, WIB))
                .visitEnd(OffsetDateTime.of(2026, 10, 3, 11, 0, 0, 0, WIB))
                .build();
        existing.setId(java.util.UUID.randomUUID());
        existing.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));

        when(bookingRepository.findByRegistrationId("REG-3")).thenReturn(Optional.of(existing));

        BookingResponseDto response = bookingService.reserveBooking(buildRequest("REG-3"));

        assertThat(response.isIdempotent()).isTrue();
        assertThat(response.getBookingStatus()).isEqualTo(BookingStatus.PENDING);
        verify(nuveqVisitorClient, never()).createVisitor(any(), anyString());
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    @DisplayName("reserveBooking throws 409-mapped exception on slot overlap")
    void reserveRejectsOverlappingSlot() {
        when(bookingRepository.findByRegistrationId("REG-4")).thenReturn(Optional.empty());
        when(roomRepository.findById(5L)).thenReturn(Optional.of(sampleRoom()));
        when(doorRepository.findByRoomId(5L)).thenReturn(List.of(Door.builder().nuveqDoorId(2596L).build()));
        when(bookingRepository.findOverlappingBookings(any(), any(), any(), any()))
                .thenReturn(List.of(Booking.builder().registrationId("REG-OTHER").build()));

        assertThatThrownBy(() -> bookingService.reserveBooking(buildRequest("REG-4")))
                .isInstanceOf(BookingConflictException.class)
                .hasMessageContaining("already booked");

        verify(nuveqVisitorClient, never()).createVisitor(any(), anyString());
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    @DisplayName("expiry removes QR files and clears the QR columns")
    void expiryClearsQrColumns() {
        Booking stale = pendingBooking();
        stale.setBookingStatus(BookingStatus.PENDING);
        stale.setVisitStart(OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));
        stale.setQrCodePathIn("budi-reg_in.png");
        stale.setQrCodePathOut("budi-reg_out.png");
        stale.setNuveqRegistrationIdIn(191177L);
        stale.setNuveqRegistrationIdOut(191178L);

        when(bookingRepository.findByBookingStatus(BookingStatus.PENDING)).thenReturn(List.of(stale));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        bookingService.expirePendingBookings();

        assertThat(stale.getBookingStatus()).isEqualTo(BookingStatus.EXPIRED);
        // The API must not advertise a QR URL whose PNG was just deleted.
        assertThat(stale.getQrCodePathIn()).isNull();
        assertThat(stale.getQrCodePathOut()).isNull();
        verify(qrCodeService).deleteQrCode("budi-reg_in.png");
        verify(qrCodeService).deleteQrCode("budi-reg_out.png");
        verify(nuveqVisitorClient).deleteVisitorRegistration(191177L);
        verify(nuveqVisitorClient).deleteVisitorRegistration(191178L);
    }

    @Test
    @DisplayName("cancellation removes QR files and clears the QR columns")
    void cancellationClearsQrColumns() {
        Booking active = pendingBooking();
        active.setBookingStatus(BookingStatus.PENDING);
        active.setQrCodePathIn("budi-reg_in.png");
        active.setQrCodePathOut("budi-reg_out.png");
        active.setNuveqRegistrationIdIn(191177L);
        active.setNuveqRegistrationIdOut(191178L);

        when(bookingRepository.findByRegistrationId("REG-1")).thenReturn(Optional.of(active));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        bookingService.cancelBooking("REG-1");

        assertThat(active.getBookingStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(active.getQrCodePathIn()).isNull();
        assertThat(active.getQrCodePathOut()).isNull();
    }

    @Test
    @DisplayName("live Nuveq 'Entry' direction activates the booking")
    void liveNuveqEntryDirectionActivatesBooking() {
        // Values captured verbatim from GET /api/events?date=... on the demo tenant.
        NuveqEventDto event = new NuveqEventDto();
        event.setId(377304265L);
        event.setCardNo(987654L);
        event.setCardId(438835L);
        event.setDoorId(3523L);
        event.setDirection("Entry");
        event.setEventName("Valid access");

        when(bookingRepository.findByCardNumberInAndBookingStatus("987654", BookingStatus.PENDING))
                .thenReturn(List.of(pendingBooking()));

        WebhookProcessingResult result = bookingService.processCardEvent(event, "{}");

        assertThat(result.isMatched()).isTrue();
        assertThat(result.getMatchedRegistrationId()).isEqualTo("REG-1");
        assertThat(result.getActionTaken()).isEqualTo("BOOKING_STATUS_UPDATE_ACTIVE");
        assertThat(result.getDirection()).isEqualTo("IN");
    }

    @Test
    @DisplayName("live Nuveq 'Exit' direction completes the booking")
    void liveNuveqExitDirectionCompletesBooking() {
        NuveqEventDto event = new NuveqEventDto();
        event.setId(377304281L);
        event.setCardNo(987655L);
        event.setDoorId(3523L);
        event.setDirection("Exit");
        event.setEventName("Valid access");

        Booking active = pendingBooking();
        active.setBookingStatus(BookingStatus.ACTIVE);
        active.setCardNumberOut("987655");
        when(bookingRepository.findByCardNumberOutAndBookingStatus("987655", BookingStatus.ACTIVE))
                .thenReturn(List.of(active));

        WebhookProcessingResult result = bookingService.processCardEvent(event, "{}");

        assertThat(result.isMatched()).isTrue();
        assertThat(result.getDirection()).isEqualTo("OUT");
    }

    @Test
    @DisplayName("normalizeDirection maps every observed vocabulary to IN/OUT")
    void normalizesDirectionVocabulary() {
        assertThat(BookingService.normalizeDirection("Entry")).isEqualTo("IN");
        assertThat(BookingService.normalizeDirection("entry")).isEqualTo("IN");
        assertThat(BookingService.normalizeDirection("IN")).isEqualTo("IN");
        assertThat(BookingService.normalizeDirection("  Exit  ")).isEqualTo("OUT");
        assertThat(BookingService.normalizeDirection("OUT")).isEqualTo("OUT");
        assertThat(BookingService.normalizeDirection("check-out")).isEqualTo("OUT");
        // Controller heartbeat rows share the feed but are not card passes.
        assertThat(BookingService.normalizeDirection("Status")).isNull();
        assertThat(BookingService.normalizeDirection(null)).isNull();
        assertThat(BookingService.normalizeDirection("")).isNull();
    }

    @Test
    @DisplayName("a Status heartbeat is reported as non-card, not as an unknown card")
    void statusEventIsNotReportedAsUnknownCard() {
        NuveqEventDto event = new NuveqEventDto();
        event.setId(377301210L);
        event.setCardNo(0L);
        event.setDoorId(0L);
        event.setDirection("Status");
        event.setEventName("Controller online");

        WebhookProcessingResult result = bookingService.processCardEvent(event, "{}");

        assertThat(result.isMatched()).isFalse();
        assertThat(result.getActionTaken()).isEqualTo("NON_CARD_EVENT_IGNORED");
        verify(bookingRepository, never()).findByCardNumberInAndBookingStatus(anyString(), any());
    }

    @Test
    @DisplayName("reserveBooking rejects visit window outside operating hours")
    void reserveRejectsVisitOutsideOperatingHours() {
        VisitorRegistrationRequest request = buildRequest("REG-5");
        request.setVisitStart(OffsetDateTime.of(2026, 10, 3, 23, 0, 0, 0, WIB));
        request.setVisitEnd(OffsetDateTime.of(2026, 10, 4, 1, 0, 0, 0, WIB));

        assertThatThrownBy(() -> bookingService.reserveBooking(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operating hours");

        verify(nuveqVisitorClient, never()).createVisitor(any(), anyString());
    }

    @Test
    @DisplayName("reserveBooking accepts quarter-hour boundaries like 17:10")
    void reserveAcceptsQuarterHourBoundaries() {
        VisitorRegistrationRequest request = buildRequest("REG-QH");
        request.setVisitStart(OffsetDateTime.of(2026, 10, 3, 17, 10, 0, 0, WIB));
        request.setVisitEnd(OffsetDateTime.of(2026, 10, 4, 17, 10, 0, 0, WIB));

        when(bookingRepository.findByRegistrationId("REG-QH")).thenReturn(Optional.empty());
        when(roomRepository.findById(5L)).thenReturn(Optional.of(sampleRoom()));
        when(doorRepository.findByRoomId(5L)).thenReturn(List.of(Door.builder().nuveqDoorId(2596L).build()));
        when(bookingRepository.findOverlappingBookings(any(), any(), any(), any())).thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> savedBooking(inv.getArgument(0)));
        mockHappyNuveq();

        BookingResponseDto response = bookingService.reserveBooking(request);

        assertThat(response.getBookingStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(response.getVisitStart().getHour()).isEqualTo(17);
        assertThat(response.getVisitStart().getMinute()).isEqualTo(10);
    }

    @Test
    @DisplayName("reserveBooking rejects boundaries off the 5-minute mark")
    void reserveRejectsNonFiveMinuteBoundaries() {
        VisitorRegistrationRequest request = buildRequest("REG-BAD-QH");
        request.setVisitStart(OffsetDateTime.of(2026, 10, 3, 17, 7, 0, 0, WIB));
        request.setVisitEnd(OffsetDateTime.of(2026, 10, 4, 18, 0, 0, 0, WIB));

        assertThatThrownBy(() -> bookingService.reserveBooking(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5-minute boundaries");

        verify(nuveqVisitorClient, never()).createVisitor(any(), anyString());
    }

    @Test
    @DisplayName("reserveBooking succeeds even when QR generation fails")
    void reserveSurvivesQrFailure() {
        when(bookingRepository.findByRegistrationId("REG-6")).thenReturn(Optional.empty());
        when(roomRepository.findById(5L)).thenReturn(Optional.of(sampleRoom()));
        when(doorRepository.findByRoomId(5L)).thenReturn(List.of(Door.builder().nuveqDoorId(2596L).build()));
        when(bookingRepository.findOverlappingBookings(any(), any(), any(), any())).thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> savedBooking(inv.getArgument(0)));
        when(nuveqVisitorClient.createVisitor(any(NuveqCreateVisitorRequest.class), anyString()))
                .thenReturn(nuveqResponse(1L, 2L));
        when(qrCodeService.generateAndSave(anyString(), anyString())).thenReturn(null);
        when(qrCodeService.buildServeUrl(anyString(), anyString()))
                .thenAnswer(inv -> "http://localhost:8080/api/v1/bookings/" + inv.getArgument(0) + "/qr/" + inv.getArgument(1));

        BookingResponseDto response = bookingService.reserveBooking(buildRequest("REG-6"));

        assertThat(response.getBookingStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(response.getQrCodeUrlIn()).isNull();
        assertThat(response.getQrCodeUrlOut()).isNull();
    }

    @Test
    @DisplayName("getAvailability returns 13 slots and marks overlapping hours unavailable")
    void availabilityMarksOverlappingHours() {
        Booking active = Booking.builder()
                .registrationId("REG-7")
                .visitStart(OffsetDateTime.of(2026, 10, 3, 10, 0, 0, 0, WIB))
                .visitEnd(OffsetDateTime.of(2026, 10, 3, 12, 0, 0, 0, WIB))
                .bookingStatus(BookingStatus.ACTIVE)
                .build();

        when(roomRepository.findById(5L)).thenReturn(Optional.of(sampleRoom()));
        when(bookingRepository.findActiveBookingsForDate(any(), any(), anyString())).thenReturn(List.of(active));

        AvailabilityResponseDto availability = bookingService.getAvailability(5L, "2026-10-03");

        assertThat(availability.getSlots()).hasSize(13);
        assertThat(availability.getSlots().get(0).getHour()).isEqualTo(9);
        assertThat(availability.getSlots().get(0).isAvailable()).isTrue();
        assertThat(availability.getSlots().get(1).getHour()).isEqualTo(10);
        assertThat(availability.getSlots().get(1).isAvailable()).isFalse();
        assertThat(availability.getSlots().get(1).getBookedBy()).isEqualTo("REG-7");
        assertThat(availability.getSlots().get(2).isAvailable()).isFalse();
        assertThat(availability.getSlots().get(3).getHour()).isEqualTo(12);
        assertThat(availability.getSlots().get(3).isAvailable()).isTrue();
    }

    @Test
    @DisplayName("Card event direction=IN promotes PENDING booking to ACTIVE")
    void cardEventInActivatesBooking() {
        Booking pending = Booking.builder()
                .registrationId("REG-8")
                .cardNumberIn("1253646425")
                .cardNumberOut("1253646426")
                .bookingStatus(BookingStatus.PENDING)
                .build();

        when(bookingRepository.findByCardNumberInAndBookingStatus("1253646425", BookingStatus.PENDING))
                .thenReturn(List.of(pending));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        WebhookProcessingResult result = bookingService.processCardEvent("1253646425", "IN");

        assertThat(result.isMatched()).isTrue();
        assertThat(result.getActionTaken()).isEqualTo("BOOKING_STATUS_UPDATE_ACTIVE");
        assertThat(pending.getBookingStatus()).isEqualTo(BookingStatus.ACTIVE);
        verify(bookingRepository).save(pending);
    }

    @Test
    @DisplayName("Card event direction=OUT completes an ACTIVE booking")
    void cardEventOutCompletesBooking() {
        Booking active = Booking.builder()
                .registrationId("REG-9")
                .cardNumberIn("1253646425")
                .cardNumberOut("1253646426")
                .bookingStatus(BookingStatus.ACTIVE)
                .build();

        when(bookingRepository.findByCardNumberOutAndBookingStatus("1253646426", BookingStatus.ACTIVE))
                .thenReturn(List.of(active));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        WebhookProcessingResult result = bookingService.processCardEvent("1253646426", "OUT");

        assertThat(result.getActionTaken()).isEqualTo("BOOKING_STATUS_UPDATE_COMPLETED");
        assertThat(active.getBookingStatus()).isEqualTo(BookingStatus.COMPLETED);
    }

    @Test
    @DisplayName("Card event with unknown card number is ignored")
    void cardEventWithUnknownCardIsIgnored() {
        when(bookingRepository.findByCardNumberInAndBookingStatus("0000000000", BookingStatus.PENDING))
                .thenReturn(List.of());
        when(bookingRepository.findByCardNumberOutAndBookingStatus("0000000000", BookingStatus.ACTIVE))
                .thenReturn(List.of());

        WebhookProcessingResult result = bookingService.processCardEvent("0000000000", "IN");

        assertThat(result.isMatched()).isFalse();
        assertThat(result.getActionTaken()).isEqualTo("UNKNOWN_CARD_IGNORED");
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    @DisplayName("Repeated card event on already ACTIVE booking changes nothing")
    void repeatedCardEventIsIgnored() {
        Booking active = Booking.builder()
                .registrationId("REG-10")
                .cardNumberIn("1253646425")
                .bookingStatus(BookingStatus.ACTIVE)
                .build();

        when(bookingRepository.findByCardNumberInAndBookingStatus("1253646425", BookingStatus.PENDING))
                .thenReturn(List.of());

        WebhookProcessingResult result = bookingService.processCardEvent("1253646425", "IN");

        assertThat(result.isMatched()).isFalse();
        assertThat(active.getBookingStatus()).isEqualTo(BookingStatus.ACTIVE);
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    @DisplayName("expirePendingBookings expires overdue PENDING bookings, cleans Nuveq visitors and QR files")
    void expiryExpiresOverdueBookings() {
        Booking overdue = Booking.builder()
                .registrationId("REG-11")
                .bookingStatus(BookingStatus.PENDING)
                .visitStart(OffsetDateTime.now(ZoneOffset.UTC).minusHours(2))
                .visitEnd(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1))
                .nuveqRegistrationIdIn(222L)
                .nuveqRegistrationIdOut(444L)
                .qrCodePathIn("qr-codes/REG-11_in.png")
                .qrCodePathOut("qr-codes/REG-11_out.png")
                .room(sampleRoom())
                .build();

        Booking future = Booking.builder()
                .registrationId("REG-12")
                .bookingStatus(BookingStatus.PENDING)
                .visitStart(OffsetDateTime.now(ZoneOffset.UTC).plusHours(3))
                .visitEnd(OffsetDateTime.now(ZoneOffset.UTC).plusHours(4))
                .build();

        when(bookingRepository.findByBookingStatus(BookingStatus.PENDING)).thenReturn(List.of(overdue, future));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        bookingService.expirePendingBookings();

        assertThat(overdue.getBookingStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(future.getBookingStatus()).isEqualTo(BookingStatus.PENDING);

        verify(nuveqVisitorClient).deleteVisitorRegistration(222L);
        verify(nuveqVisitorClient).deleteVisitorRegistration(444L);
        verify(qrCodeService).deleteQrCode("qr-codes/REG-11_in.png");
        verify(qrCodeService).deleteQrCode("qr-codes/REG-11_out.png");
    }
}
