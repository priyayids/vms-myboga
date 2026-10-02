package com.visitorbridge.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visitorbridge.client.NuveqVisitorClient;
import com.visitorbridge.dto.ReservationResponseDto;
import com.visitorbridge.dto.VisitorRegistrationRequest;
import com.visitorbridge.dto.VisitorResponseDto;
import com.visitorbridge.model.UserType;
import com.visitorbridge.service.VisitorService;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    private VisitorService visitorService;

    @MockBean
    private NuveqVisitorClient nuveqVisitorClient;

    @Test
    @DisplayName("POST /api/v1/visitors/reserve - 201 Created for new registration")
    void testReserveNewVisitorSuccess() throws Exception {
        VisitorRegistrationRequest request = VisitorRegistrationRequest.builder()
                .registrationId("REG-100")
                .fullName("Alice Wonderland")
                .email("alice@example.com")
                .phone("+628123456789")
                .visitStart(OffsetDateTime.of(2026, 10, 2, 9, 0, 0, 0, ZoneOffset.UTC))
                .visitEnd(OffsetDateTime.of(2026, 10, 2, 17, 0, 0, 0, ZoneOffset.UTC))
                .siteId(1L)
                .liftGroupId(2L)
                .allowedDoorIds(List.of(10L, 20L))
                .cardNumber("1234567890")
                .build();

        VisitorResponseDto checkIn = VisitorResponseDto.builder()
                .id(UUID.randomUUID())
                .registrationId("REG-100")
                .userType(UserType.CHECK_IN)
                .fullName("Alice Wonderland")
                .statusEntry(false)
                .build();

        VisitorResponseDto checkOut = VisitorResponseDto.builder()
                .id(UUID.randomUUID())
                .registrationId("REG-100")
                .userType(UserType.CHECK_OUT)
                .fullName("Alice Wonderland")
                .statusEntry(false)
                .build();

        ReservationResponseDto mockResponse = ReservationResponseDto.builder()
                .registrationId("REG-100")
                .idempotent(false)
                .checkIn(checkIn)
                .checkOut(checkOut)
                .build();

        when(visitorService.registerVisitor(any(VisitorRegistrationRequest.class)))
                .thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/visitors/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.registrationId").value("REG-100"))
                .andExpect(jsonPath("$.data.idempotent").value(false))
                .andExpect(jsonPath("$.data.checkIn.userType").value("CHECK_IN"))
                .andExpect(jsonPath("$.data.checkOut.userType").value("CHECK_OUT"));
    }

    @Test
    @DisplayName("POST /api/v1/visitors/reserve - 200 OK for duplicate registration (Idempotent)")
    void testReserveDuplicateVisitorSuccess() throws Exception {
        VisitorRegistrationRequest request = VisitorRegistrationRequest.builder()
                .registrationId("REG-200")
                .fullName("Bob Builder")
                .visitStart(OffsetDateTime.of(2026, 10, 2, 9, 0, 0, 0, ZoneOffset.UTC))
                .visitEnd(OffsetDateTime.of(2026, 10, 2, 17, 0, 0, 0, ZoneOffset.UTC))
                .siteId(1L)
                .liftGroupId(2L)
                .allowedDoorIds(List.of(10L))
                .cardNumber("9876543210")
                .build();

        ReservationResponseDto idempotentResponse = ReservationResponseDto.builder()
                .registrationId("REG-200")
                .idempotent(true)
                .build();

        when(visitorService.registerVisitor(any(VisitorRegistrationRequest.class)))
                .thenReturn(idempotentResponse);

        mockMvc.perform(post("/api/v1/visitors/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.idempotent").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/visitors/reserve - 400 Bad Request on invalid input")
    void testReserveValidationFailure() throws Exception {
        VisitorRegistrationRequest invalidRequest = VisitorRegistrationRequest.builder()
                .registrationId("") // invalid: blank
                .fullName("")       // invalid: blank
                .email("not-an-email") // invalid: email
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
    @DisplayName("POST /api/v1/events/nuveq-webhook - 200 OK processing card event")
    void testWebhookEventSuccess() throws Exception {
        String eventPayload = """
                {
                    "id": 101,
                    "cardNo": 12345678,
                    "direction": "IN",
                    "timestamp": "2026-10-02T10:00:00Z"
                }
                """;

        when(visitorService.processCardEvent(org.mockito.ArgumentMatchers.any(com.visitorbridge.client.NuveqEventDto.class), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(com.visitorbridge.dto.WebhookProcessingResult.builder()
                        .cardNumber("12345678")
                        .matched(true)
                        .actionTaken("STATUS_ENTRY_UPDATE_CHECK_IN")
                        .build());

        mockMvc.perform(post("/api/v1/events/nuveq-webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalReceived").value(1));
    }
}
