package com.visitorbridge.service;

import com.visitorbridge.client.NuveqCreateVisitorRequest;
import com.visitorbridge.client.NuveqCreateVisitorResponse;
import com.visitorbridge.client.NuveqEventDto;
import com.visitorbridge.client.NuveqVisitorClient;
import com.visitorbridge.dto.ReservationResponseDto;
import com.visitorbridge.dto.VisitorRegistrationRequest;
import com.visitorbridge.dto.VisitorResponseDto;
import com.visitorbridge.dto.WebhookProcessingResult;
import com.visitorbridge.mapper.VisitorMapper;
import com.visitorbridge.model.UserType;
import com.visitorbridge.model.Visitor;
import com.visitorbridge.model.WebhookEventLog;
import com.visitorbridge.repository.VisitorRepository;
import com.visitorbridge.repository.WebhookEventLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisitorService {

    private final VisitorRepository visitorRepository;
    private final WebhookEventLogRepository webhookEventLogRepository;
    private final VisitorMapper visitorMapper;
    private final NuveqVisitorClient nuveqVisitorClient;
    private final TransactionLogger transactionLogger;

    @Transactional
    public ReservationResponseDto registerVisitor(VisitorRegistrationRequest request) {
        String regId = request.getRegistrationId();
        String maskedCard = TransactionLogger.maskCardNumber(request.getCardNumber());

        transactionLogger.logTransaction("RegistrationService", regId, "REGISTRATION_RECEIVED", "RECEIVED",
                "card=" + maskedCard);

        // FR-1 Idempotency Check
        List<Visitor> existingList = visitorRepository.findByRegistrationId(regId);
        if (!existingList.isEmpty()) {
            transactionLogger.logTransaction("RegistrationService", regId, "REGISTRATION_IDEMPOTENT", "EXISTING_RETURNED",
                    "found=" + existingList.size());

            Visitor checkIn = existingList.stream()
                    .filter(v -> v.getUserType() == UserType.CHECK_IN)
                    .findFirst()
                    .orElse(existingList.get(0));

            Visitor checkOut = existingList.stream()
                    .filter(v -> v.getUserType() == UserType.CHECK_OUT)
                    .findFirst()
                    .orElse(null);

            return ReservationResponseDto.builder()
                    .registrationId(regId)
                    .idempotent(true)
                    .checkIn(visitorMapper.toDto(checkIn))
                    .checkOut(checkOut != null ? visitorMapper.toDto(checkOut) : null)
                    .build();
        }

        // FR-2 Create two visitor instances (CHECK_IN & CHECK_OUT)
        Visitor checkInEntity = visitorMapper.toEntity(request, UserType.CHECK_IN);
        Visitor checkOutEntity = visitorMapper.toEntity(request, UserType.CHECK_OUT);

        checkInEntity = visitorRepository.save(checkInEntity);
        checkOutEntity = visitorRepository.save(checkOutEntity);

        // Convert card number to numeric credentialNumber for Nuveq API
        Long credentialNum;
        try {
            credentialNum = Long.parseLong(request.getCardNumber().replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            credentialNum = (long) Math.abs(request.getCardNumber().hashCode());
        }

        NuveqCreateVisitorRequest nuveqReq = NuveqCreateVisitorRequest.builder()
                .name(request.getFullName())
                .credentialNumber(credentialNum)
                .email(request.getEmail())
                .phone(request.getPhone())
                .userPhoto(request.getUserPhoto())
                .vehicleNumber(request.getVehicleNumber())
                .visitStart(request.getVisitStart())
                .visitEnd(request.getVisitEnd())
                .siteId(request.getSiteId())
                .liftGroupId(request.getLiftGroupId())
                .allowedDoorIds(request.getAllowedDoorIds())
                .build();

        // Push Check-In instance to Nuveq
        NuveqCreateVisitorResponse checkInResp = nuveqVisitorClient.createVisitor(nuveqReq, regId);
        if (checkInResp != null && checkInResp.getData() != null) {
            checkInEntity.setNuveqVisitorId(String.valueOf(checkInResp.getData().getVisitorId()));
            checkInEntity.setNuveqRegistrationId(String.valueOf(checkInResp.getData().getVisitorRegistrationId()));
            checkInEntity = visitorRepository.save(checkInEntity);
        }

        // Push Check-Out instance to Nuveq
        NuveqCreateVisitorResponse checkOutResp = nuveqVisitorClient.createVisitor(nuveqReq, regId);
        if (checkOutResp != null && checkOutResp.getData() != null) {
            checkOutEntity.setNuveqVisitorId(String.valueOf(checkOutResp.getData().getVisitorId()));
            checkOutEntity.setNuveqRegistrationId(String.valueOf(checkOutResp.getData().getVisitorRegistrationId()));
            checkOutEntity = visitorRepository.save(checkOutEntity);
        }

        transactionLogger.logTransaction("RegistrationService", regId, "CREATE_VISITOR", "SUCCESS",
                "checkInId=" + checkInEntity.getId() + " checkOutId=" + checkOutEntity.getId());

        return ReservationResponseDto.builder()
                .registrationId(regId)
                .idempotent(false)
                .checkIn(visitorMapper.toDto(checkInEntity))
                .checkOut(visitorMapper.toDto(checkOutEntity))
                .build();
    }

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
        String direction = (event != null) ? event.getDirection() : null;
        return processEventWithLogging(cardNumber, direction, event, rawPayload);
    }

    @Transactional
    public boolean processCardEvent(String cardNumber, String direction) {
        WebhookProcessingResult result = processEventWithLogging(cardNumber, direction, null,
                "{\"cardNumber\":\"" + cardNumber + "\",\"direction\":\"" + direction + "\"}");
        return result.isMatched() && result.getActionTaken().startsWith("STATUS_ENTRY_UPDATE");
    }

    private WebhookProcessingResult processEventWithLogging(
            String cardNumber, String direction, NuveqEventDto event, String rawPayload) {

        String maskedCard = TransactionLogger.maskCardNumber(cardNumber);
        String eventType = (event != null && event.getEventName() != null) ? event.getEventName() : "CARD_SWIPE";

        if (cardNumber == null || cardNumber.isBlank()) {
            log.warn("Webhook card event missing card number: {}", rawPayload);
            recordWebhookLog(eventType, null, direction, event, null, null, "INVALID_MISSING_CARD", rawPayload);
            return WebhookProcessingResult.builder()
                    .cardNumber(null)
                    .direction(direction)
                    .matched(false)
                    .actionTaken("INVALID_MISSING_CARD")
                    .build();
        }

        List<Visitor> visitors = visitorRepository.findByCardNumber(cardNumber);

        if (visitors.isEmpty()) {
            log.info("Card event ignored: unknown card number {}", maskedCard);
            transactionLogger.logTransaction("CardEventProcessor", "N/A", "CARD_EVENT", "IGNORED_UNKNOWN_CARD",
                    "card=" + maskedCard);
            recordWebhookLog(eventType, cardNumber, direction, event, null, null, "UNKNOWN_CARD_IGNORED", rawPayload);

            return WebhookProcessingResult.builder()
                    .cardNumber(cardNumber)
                    .direction(direction)
                    .matched(false)
                    .actionTaken("UNKNOWN_CARD_IGNORED")
                    .build();
        }

        // Match target instance by direction or first unentered
        Visitor target = null;
        if ("OUT".equalsIgnoreCase(direction)) {
            target = visitors.stream()
                    .filter(v -> v.getUserType() == UserType.CHECK_OUT)
                    .findFirst()
                    .orElse(null);
        } else if ("IN".equalsIgnoreCase(direction)) {
            target = visitors.stream()
                    .filter(v -> v.getUserType() == UserType.CHECK_IN)
                    .findFirst()
                    .orElse(null);
        }

        if (target == null) {
            Optional<Visitor> unenteredCheckIn = visitors.stream()
                    .filter(v -> v.getUserType() == UserType.CHECK_IN && !Boolean.TRUE.equals(v.getStatusEntry()))
                    .findFirst();

            if (unenteredCheckIn.isPresent()) {
                target = unenteredCheckIn.get();
            } else {
                Optional<Visitor> unenteredCheckOut = visitors.stream()
                        .filter(v -> v.getUserType() == UserType.CHECK_OUT && !Boolean.TRUE.equals(v.getStatusEntry()))
                        .findFirst();
                target = unenteredCheckOut.orElse(null);
            }
        }

        if (target == null) {
            log.debug("Card event ignored: all visitor instances already have statusEntry=true for card {}", maskedCard);
            transactionLogger.logTransaction("CardEventProcessor", visitors.get(0).getRegistrationId(),
                    "CARD_EVENT", "IGNORED_ALREADY_ENTERED", "card=" + maskedCard);
            recordWebhookLog(eventType, cardNumber, direction, event, null, visitors.get(0).getRegistrationId(),
                    "IGNORED_ALREADY_ENTERED", rawPayload);

            return WebhookProcessingResult.builder()
                    .cardNumber(cardNumber)
                    .direction(direction)
                    .matched(true)
                    .actionTaken("IGNORED_ALREADY_ENTERED")
                    .matchedRegistrationId(visitors.get(0).getRegistrationId())
                    .build();
        }

        if (Boolean.TRUE.equals(target.getStatusEntry())) {
            log.debug("Card event ignored: target instance {} already entered for card {}", target.getUserType(), maskedCard);
            recordWebhookLog(eventType, cardNumber, direction, event, target.getId(), target.getRegistrationId(),
                    "IGNORED_ALREADY_ENTERED", rawPayload);

            return WebhookProcessingResult.builder()
                    .cardNumber(cardNumber)
                    .direction(direction)
                    .matched(true)
                    .actionTaken("IGNORED_ALREADY_ENTERED")
                    .matchedRegistrationId(target.getRegistrationId())
                    .matchedUserType(target.getUserType().name())
                    .build();
        }

        // Thread-safe conditional update
        int updated = visitorRepository.markStatusEntryById(target.getId(), OffsetDateTime.now(ZoneOffset.UTC));
        String action = "STATUS_ENTRY_UPDATE_" + target.getUserType();

        if (updated > 0) {
            log.info("Visitor statusEntry updated to true for card {} (type={}, regId={})",
                    maskedCard, target.getUserType(), target.getRegistrationId());
            transactionLogger.logTransaction("CardEventProcessor", target.getRegistrationId(),
                    "STATUS_ENTRY_UPDATE", "SUCCESS",
                    "userType=" + target.getUserType() + " card=" + maskedCard);

            recordWebhookLog(eventType, cardNumber, direction, event, target.getId(), target.getRegistrationId(),
                    action, rawPayload);

            return WebhookProcessingResult.builder()
                    .cardNumber(cardNumber)
                    .direction(direction)
                    .matched(true)
                    .actionTaken(action)
                    .matchedRegistrationId(target.getRegistrationId())
                    .matchedUserType(target.getUserType().name())
                    .build();
        }

        recordWebhookLog(eventType, cardNumber, direction, event, target.getId(), target.getRegistrationId(),
                "CONCURRENCY_SKIPPED", rawPayload);

        return WebhookProcessingResult.builder()
                .cardNumber(cardNumber)
                .direction(direction)
                .matched(true)
                .actionTaken("CONCURRENCY_SKIPPED")
                .matchedRegistrationId(target.getRegistrationId())
                .matchedUserType(target.getUserType().name())
                .build();
    }

    private void recordWebhookLog(String eventType, String cardNumber, String direction,
                                  NuveqEventDto event, java.util.UUID visitorId, String registrationId,
                                  String actionTaken, String rawPayload) {
        try {
            WebhookEventLog logEntry = WebhookEventLog.builder()
                    .eventType(eventType)
                    .cardNumber(cardNumber)
                    .direction(direction)
                    .doorId(event != null ? event.getDoorId() : null)
                    .siteId(event != null ? event.getSiteId() : null)
                    .matchedVisitorId(visitorId)
                    .matchedRegistrationId(registrationId)
                    .actionTaken(actionTaken)
                    .rawPayload(rawPayload != null ? rawPayload : "{}")
                    .build();
            webhookEventLogRepository.save(logEntry);
        } catch (Exception ex) {
            log.error("Failed to save webhook event log to database: {}", ex.getMessage(), ex);
        }
    }

    @Transactional(readOnly = true)
    public List<VisitorResponseDto> getVisitorsByRegistrationId(String registrationId) {
        return visitorRepository.findByRegistrationId(registrationId).stream()
                .map(visitorMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WebhookEventLog> getRecentWebhookLogs() {
        return webhookEventLogRepository.findTop20ByOrderByReceivedAtDesc();
    }
}
