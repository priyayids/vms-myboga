package com.visitorbridge.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visitorbridge.client.NuveqEventDto;
import com.visitorbridge.client.NuveqVisitorClient;
import com.visitorbridge.dto.BookingResponseDto;
import com.visitorbridge.dto.VisitorRegistrationRequest;
import com.visitorbridge.dto.WebhookProcessingResult;
import com.visitorbridge.exception.BookingConflictException;
import com.visitorbridge.exception.InvalidBookingStateException;
import com.visitorbridge.model.BookingStatus;
import com.visitorbridge.service.BookingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class VisitorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private BookingService bookingService;

    @MockBean
    private NuveqVisitorClient nuveqVisitorClient;

    private VisitorRegistrationRequest buildRequest(String registrationId) {
        return VisitorRegistrationRequest.builder()
                .registrationId(registrationId)
                .roomId(5L)
                .fullName("Alice Wonderland")
                .email("alice@example.com")
                .phone("+628123456789")
                .visitStart(OffsetDateTime.of(2026, 10, 2, 9, 0, 0, 0, ZoneOffset.ofHours(7)))
                .visitEnd(OffsetDateTime.of(2026, 10, 2, 11, 0, 0, 0, ZoneOffset.ofHours(7)))
                .siteId(167L)
                .liftGroupId(630L)
                .allowedDoorIds(List.of(2596L, 4904L))
                .cardNumber("1234567890")
                .checkOutCardNumber("1234567891")
                .build();
    }

    @Test
    @DisplayName("POST /api/v1/visitors/reserve - 201 Created for new registration")
    void testReserveNewBookingSuccess() throws Exception {
        BookingResponseDto mockResponse = BookingResponseDto.builder()
                .registrationId("REG-100")
                .idempotent(false)
                .roomId(5L)
                .roomName("Meeting Room Alpha")
                .visitorName("Alice Wonderland")
                .cardNumberIn("****7890")
                .cardNumberOut("****7891")
                .allowedDoors(List.of("Demo Door 1", "5601 Door1"))
                .bookingStatus(BookingStatus.PENDING)
                .qrCodeUrlIn("http://localhost:8080/api/v1/bookings/REG-100/qr/in")
                .qrCodeUrlOut("http://localhost:8080/api/v1/bookings/REG-100/qr/out")
                .build();

        when(bookingService.reserveBooking(any(VisitorRegistrationRequest.class))).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/visitors/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest("REG-100"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.registrationId").value("REG-100"))
                .andExpect(jsonPath("$.data.idempotent").value(false))
                .andExpect(jsonPath("$.data.bookingStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.cardNumberIn").value("****7890"))
                .andExpect(jsonPath("$.data.qrCodeUrlIn").value("http://localhost:8080/api/v1/bookings/REG-100/qr/in"));
    }

    @Test
    @DisplayName("POST /api/v1/visitors/reserve - 200 OK for duplicate registration (Idempotent)")
    void testReserveDuplicateBookingSuccess() throws Exception {
        BookingResponseDto idempotentResponse = BookingResponseDto.builder()
                .registrationId("REG-200")
                .idempotent(true)
                .bookingStatus(BookingStatus.PENDING)
                .build();

        when(bookingService.reserveBooking(any(VisitorRegistrationRequest.class))).thenReturn(idempotentResponse);

        mockMvc.perform(post("/api/v1/visitors/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest("REG-200"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.idempotent").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/visitors/reserve - 409 Conflict on slot overlap")
    void testReserveSlotConflict() throws Exception {
        when(bookingService.reserveBooking(any(VisitorRegistrationRequest.class)))
                .thenThrow(new BookingConflictException("Room is already booked for the selected time slot"));

        mockMvc.perform(post("/api/v1/visitors/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest("REG-300"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Room is already booked for the selected time slot"));
    }

    @Test
    @DisplayName("POST /api/v1/visitors/reserve - 400 Bad Request on invalid input")
    void testReserveValidationFailure() throws Exception {
        VisitorRegistrationRequest invalidRequest = VisitorRegistrationRequest.builder()
                .registrationId("")
                .fullName("")
                .email("not-an-email")
                .build();

        mockMvc.perform(post("/api/v1/visitors/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    @DisplayName("DELETE /api/v1/bookings/{id} - 200 OK and QR urls cleared")
    void testCancelBooking() throws Exception {
        BookingResponseDto cancelled = BookingResponseDto.builder()
                .registrationId("REG-100")
                .bookingStatus(BookingStatus.CANCELLED)
                .qrCodeUrlIn(null)
                .qrCodeUrlOut(null)
                .build();

        when(bookingService.cancelBooking("REG-100")).thenReturn(cancelled);

        mockMvc.perform(delete("/api/v1/bookings/REG-100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.bookingStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.data.qrCodeUrlIn").doesNotExist());
    }

    @Test
    @DisplayName("DELETE /api/v1/bookings/{id} - 409 when booking already completed")
    void testCancelBookingRejectsTerminalState() throws Exception {
        when(bookingService.cancelBooking("REG-100"))
                .thenThrow(new InvalidBookingStateException("Booking REG-100 is already COMPLETED and cannot be cancelled"));

        mockMvc.perform(delete("/api/v1/bookings/REG-100"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("already COMPLETED")));
    }

    @Test
    @DisplayName("POST /api/v1/events/nuveq-webhook - 200 OK processing check-in card event")
    void testWebhookEventSuccess() throws Exception {
        String eventPayload = """
                {
                    "id": 101,
                    "cardNo": 12345678,
                    "direction": "IN",
                    "timestamp": "2026-10-02T10:00:00Z"
                }
                """;

        when(bookingService.processCardEvent(any(NuveqEventDto.class), anyString()))
                .thenReturn(WebhookProcessingResult.builder()
                        .cardNumber("12345678")
                        .matched(true)
                        .actionTaken("BOOKING_STATUS_UPDATE_ACTIVE")
                        .matchedRegistrationId("REG-100")
                        .build());

        mockMvc.perform(post("/api/v1/events/nuveq-webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalReceived").value(1))
                .andExpect(jsonPath("$.data.matchedVisitors").value(1));
    }
}
