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
        String baseRegId = request.getRegistrationId();
        String cleanBaseId = baseRegId.replaceAll("(?i)_(in|out)$", "");
        String regIdIn = cleanBaseId + "_in";
        String regIdOut = cleanBaseId + "_out";

        String maskedCard = TransactionLogger.maskCardNumber(request.getCardNumber());
        transactionLogger.logTransaction("RegistrationService", baseRegId, "REGISTRATION_RECEIVED", "RECEIVED",
                "card=" + maskedCard);

        // FR-1 Idempotency Check: search by both suffixed IDs and base ID
        List<Visitor> existingList = visitorRepository.findByRegistrationIdIn(List.of(regIdIn, regIdOut, baseRegId));
        if (!existingList.isEmpty()) {
            transactionLogger.logTransaction("RegistrationService", baseRegId, "REGISTRATION_IDEMPOTENT", "EXISTING_RETURNED",
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
                    .registrationId(baseRegId)
                    .idempotent(true)
                    .checkIn(visitorMapper.toDto(checkIn))
                    .checkOut(checkOut != null ? visitorMapper.toDto(checkOut) : null)
                    .build();
        }

        // FR-2 Create two distinct visitor instances with _in and _out on registrationId and name
        String nameIn = request.getFullName() + "_in";
        String nameOut = request.getFullName() + "_out";

        String cardIn = request.getCardNumber();
        String cardOut = (request.getCheckOutCardNumber() != null && !request.getCheckOutCardNumber().isBlank())
                ? request.getCheckOutCardNumber()
                : request.getCardNumber();

        Visitor checkInEntity = visitorMapper.toEntity(request, UserType.CHECK_IN);
        checkInEntity.setRegistrationId(regIdIn);
        checkInEntity.setFullName(nameIn);
        checkInEntity.setCardNumber(cardIn);

        Visitor checkOutEntity = visitorMapper.toEntity(request, UserType.CHECK_OUT);
        checkOutEntity.setRegistrationId(regIdOut);
        checkOutEntity.setFullName(nameOut);
        checkOutEntity.setCardNumber(cardOut);

        checkInEntity = visitorRepository.save(checkInEntity);
        checkOutEntity = visitorRepository.save(checkOutEntity);

        // Convert card numbers to numeric credentialNumber for Nuveq API
        Long credNumIn = parseCredentialNumber(cardIn);
        Long credNumOut = parseCredentialNumber(cardOut);

        // 1. Push Check-In instance to Nuveq
        NuveqCreateVisitorRequest nuveqReqIn = NuveqCreateVisitorRequest.builder()
                .name(nameIn)
                .credentialNumber(credNumIn)
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

        NuveqCreateVisitorResponse checkInResp = nuveqVisitorClient.createVisitor(nuveqReqIn, regIdIn);
        if (checkInResp != null && checkInResp.getData() != null) {
            checkInEntity.setNuveqVisitorId(String.valueOf(checkInResp.getData().getVisitorId()));
            checkInEntity.setNuveqRegistrationId(String.valueOf(checkInResp.getData().getVisitorRegistrationId()));
            checkInEntity = visitorRepository.save(checkInEntity);
        }

        // 2. Push Check-Out instance to Nuveq
        NuveqCreateVisitorRequest nuveqReqOut = NuveqCreateVisitorRequest.builder()
                .name(nameOut)
                .credentialNumber(credNumOut)
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

        NuveqCreateVisitorResponse checkOutResp = nuveqVisitorClient.createVisitor(nuveqReqOut, regIdOut);
        if (checkOutResp != null && checkOutResp.getData() != null) {
            checkOutEntity.setNuveqVisitorId(String.valueOf(checkOutResp.getData().getVisitorId()));
            checkOutEntity.setNuveqRegistrationId(String.valueOf(checkOutResp.getData().getVisitorRegistrationId()));
            checkOutEntity = visitorRepository.save(checkOutEntity);
        }

        transactionLogger.logTransaction("RegistrationService", baseRegId, "CREATE_VISITOR", "SUCCESS",
                "checkInId=" + checkInEntity.getId() + " (" + regIdIn + ") checkOutId=" + checkOutEntity.getId() + " (" + regIdOut + ")");

        return ReservationResponseDto.builder()
                .registrationId(baseRegId)
                .idempotent(false)
                .checkIn(visitorMapper.toDto(checkInEntity))
                .checkOut(visitorMapper.toDto(checkOutEntity))
                .build();
    }

    private Long parseCredentialNumber(String cardNumber) {
        try {
            return Long.parseLong(cardNumber.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return (long) Math.abs(cardNumber.hashCode());
        }
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

        // Match target instance: check direction, userName suffix (_in / _out), or first unentered
        Visitor target = null;
        String userName = (event != null) ? event.getUserName() : null;

        if ("OUT".equalsIgnoreCase(direction) || (userName != null && userName.toLowerCase().endsWith("_out"))) {
            target = visitors.stream()
                    .filter(v -> v.getUserType() == UserType.CHECK_OUT)
                    .findFirst()
                    .orElse(null);
        } else if ("IN".equalsIgnoreCase(direction) || (userName != null && userName.toLowerCase().endsWith("_in"))) {
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
        return visitorRepository.findAllByBaseRegistrationId(registrationId).stream()
                .map(visitorMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WebhookEventLog> getRecentWebhookLogs() {
        return webhookEventLogRepository.findTop20ByOrderByReceivedAtDesc();
    }
}
