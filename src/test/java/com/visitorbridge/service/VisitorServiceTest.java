package com.visitorbridge.service;

import com.visitorbridge.client.NuveqCreateVisitorRequest;
import com.visitorbridge.client.NuveqCreateVisitorResponse;
import com.visitorbridge.client.NuveqVisitorClient;
import com.visitorbridge.dto.ReservationResponseDto;
import com.visitorbridge.dto.VisitorRegistrationRequest;
import com.visitorbridge.dto.VisitorResponseDto;
import com.visitorbridge.mapper.VisitorMapper;
import com.visitorbridge.model.UserType;
import com.visitorbridge.model.Visitor;
import com.visitorbridge.repository.VisitorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VisitorServiceTest {

    @Mock
    private VisitorRepository visitorRepository;

    @Mock
    private NuveqVisitorClient nuveqVisitorClient;

    @Mock
    private TransactionLogger transactionLogger;

    private VisitorMapper visitorMapper;
    private VisitorService visitorService;

    @BeforeEach
    void setUp() {
        visitorMapper = Mappers.getMapper(VisitorMapper.class);
        visitorService = new VisitorService(visitorRepository, visitorMapper, nuveqVisitorClient, transactionLogger);
    }

    private VisitorRegistrationRequest createSampleRequest(String regId, String cardNumber) {
        return VisitorRegistrationRequest.builder()
                .registrationId(regId)
                .fullName("John Doe")
                .email("john.doe@example.com")
                .phone("+628111222333")
                .userPhoto("https://example.com/photo.png")
                .vehicleNumber("B1234XYZ")
                .visitStart(OffsetDateTime.of(2026, 10, 2, 8, 0, 0, 0, ZoneOffset.UTC))
                .visitEnd(OffsetDateTime.of(2026, 10, 2, 17, 0, 0, 0, ZoneOffset.UTC))
                .siteId(10L)
                .liftGroupId(20L)
                .allowedDoorIds(List.of(1L, 2L))
                .cardNumber(cardNumber)
                .build();
    }

    @Test
    @DisplayName("Should create two instances (CHECK_IN and CHECK_OUT) and push to Nuveq on new registration")
    void testRegisterNewVisitorSuccess() {
        VisitorRegistrationRequest request = createSampleRequest("REG-001", "12345678");

        when(visitorRepository.findByRegistrationId("REG-001")).thenReturn(Collections.emptyList());
        when(visitorRepository.save(any(Visitor.class))).thenAnswer(invocation -> {
            Visitor v = invocation.getArgument(0);
            if (v.getId() == null) {
                v.setId(UUID.randomUUID());
            }
            return v;
        });

        NuveqCreateVisitorResponse nuveqResp = new NuveqCreateVisitorResponse(
                0, "Success", new NuveqCreateVisitorResponse.CreateVisitorData(1001L, 2001L)
        );
        when(nuveqVisitorClient.createVisitor(any(NuveqCreateVisitorRequest.class), eq("REG-001")))
                .thenReturn(nuveqResp);

        ReservationResponseDto response = visitorService.registerVisitor(request);

        assertThat(response).isNotNull();
        assertThat(response.isIdempotent()).isFalse();
        assertThat(response.getCheckIn()).isNotNull();
        assertThat(response.getCheckOut()).isNotNull();
        assertThat(response.getCheckIn().getUserType()).isEqualTo(UserType.CHECK_IN);
        assertThat(response.getCheckOut().getUserType()).isEqualTo(UserType.CHECK_OUT);
        assertThat(response.getCheckIn().getStatusEntry()).isFalse();
        assertThat(response.getCheckOut().getStatusEntry()).isFalse();

        // Verify Nuveq was called twice (once for check-in, once for check-out)
        verify(nuveqVisitorClient, times(2)).createVisitor(any(NuveqCreateVisitorRequest.class), eq("REG-001"));
    }

    @Test
    @DisplayName("Should return existing records idempotently on duplicate registration submission")
    void testRegisterVisitorIdempotent() {
        VisitorRegistrationRequest request = createSampleRequest("REG-002", "87654321");

        Visitor existingCheckIn = Visitor.builder()
                .registrationId("REG-002")
                .userType(UserType.CHECK_IN)
                .fullName("John Doe")
                .cardNumber("87654321")
                .allowedDoorIds("1,2")
                .statusEntry(false)
                .build();
        existingCheckIn.setId(UUID.randomUUID());

        Visitor existingCheckOut = Visitor.builder()
                .registrationId("REG-002")
                .userType(UserType.CHECK_OUT)
                .fullName("John Doe")
                .cardNumber("87654321")
                .allowedDoorIds("1,2")
                .statusEntry(false)
                .build();
        existingCheckOut.setId(UUID.randomUUID());

        when(visitorRepository.findByRegistrationId("REG-002"))
                .thenReturn(List.of(existingCheckIn, existingCheckOut));

        ReservationResponseDto response = visitorService.registerVisitor(request);

        assertThat(response).isNotNull();
        assertThat(response.isIdempotent()).isTrue();
        assertThat(response.getRegistrationId()).isEqualTo("REG-002");

        // Verify Nuveq client was NEVER called on duplicate
        verify(nuveqVisitorClient, never()).createVisitor(any(), any());
    }

    @Test
    @DisplayName("Should update statusEntry to true when card matches known visitor")
    void testProcessCardEventSuccess() {
        String card = "55556666";
        UUID checkInId = UUID.randomUUID();
        Visitor checkIn = Visitor.builder()
                .registrationId("REG-003")
                .userType(UserType.CHECK_IN)
                .cardNumber(card)
                .statusEntry(false)
                .build();
        checkIn.setId(checkInId);

        when(visitorRepository.findByCardNumber(card)).thenReturn(List.of(checkIn));
        when(visitorRepository.markStatusEntryById(eq(checkInId), any(OffsetDateTime.class))).thenReturn(1);

        boolean result = visitorService.processCardEvent(card, "IN");

        assertThat(result).isTrue();
        verify(visitorRepository).markStatusEntryById(eq(checkInId), any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("Should ignore card event when visitor is already marked entered")
    void testProcessCardEventAlreadyEntered() {
        String card = "55556666";
        Visitor checkIn = Visitor.builder()
                .registrationId("REG-004")
                .userType(UserType.CHECK_IN)
                .cardNumber(card)
                .statusEntry(true) // already entered
                .build();
        checkIn.setId(UUID.randomUUID());

        when(visitorRepository.findByCardNumber(card)).thenReturn(List.of(checkIn));

        boolean result = visitorService.processCardEvent(card, "IN");

        assertThat(result).isFalse();
        verify(visitorRepository, never()).markStatusEntryById(any(), any());
    }

    @Test
    @DisplayName("Should ignore card event when card number is unknown")
    void testProcessCardEventUnknownCard() {
        String card = "99999999";
        when(visitorRepository.findByCardNumber(card)).thenReturn(Collections.emptyList());

        boolean result = visitorService.processCardEvent(card, null);

        assertThat(result).isFalse();
        verify(visitorRepository, never()).markStatusEntryById(any(), any());
    }
}
