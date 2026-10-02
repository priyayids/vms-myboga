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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisitorService {

    private final VisitorRepository visitorRepository;
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
    public boolean processCardEvent(String cardNumber, String direction) {
        String maskedCard = TransactionLogger.maskCardNumber(cardNumber);
        List<Visitor> visitors = visitorRepository.findByCardNumber(cardNumber);

        if (visitors.isEmpty()) {
            log.info("Card event ignored: unknown card number {}", maskedCard);
            transactionLogger.logTransaction("CardEventProcessor", "N/A", "CARD_EVENT", "IGNORED_UNKNOWN_CARD",
                    "card=" + maskedCard);
            return false;
        }

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

        // If direction not specified or matched entity not found, pick first unentered
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
            return false;
        }

        if (Boolean.TRUE.equals(target.getStatusEntry())) {
            log.debug("Card event ignored: target instance {} already entered for card {}", target.getUserType(), maskedCard);
            return false;
        }

        // Thread-safe conditional update
        int updated = visitorRepository.markStatusEntryById(target.getId(), java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        if (updated > 0) {
            log.info("Visitor statusEntry updated to true for card {} (type={})", maskedCard, target.getUserType());
            transactionLogger.logTransaction("CardEventProcessor", target.getRegistrationId(),
                    "STATUS_ENTRY_UPDATE", "SUCCESS",
                    "userType=" + target.getUserType() + " card=" + maskedCard);
            return true;
        }

        return false;
    }

    @Transactional(readOnly = true)
    public List<VisitorResponseDto> getVisitorsByRegistrationId(String registrationId) {
        return visitorRepository.findByRegistrationId(registrationId).stream()
                .map(visitorMapper::toDto)
                .toList();
    }
}
