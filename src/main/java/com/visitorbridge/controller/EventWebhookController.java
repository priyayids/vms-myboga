package com.visitorbridge.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visitorbridge.client.NuveqEventDto;
import com.visitorbridge.dto.ApiResponse;
import com.visitorbridge.listener.CardEventListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventWebhookController {

    private final CardEventListener cardEventListener;
    private final ObjectMapper objectMapper;

    @PostMapping("/nuveq-webhook")
    public ResponseEntity<ApiResponse<String>> receiveWebhook(@RequestBody JsonNode payload) {
        log.info("Received Nuveq webhook payload: {}", payload);

        try {
            if (payload.isArray()) {
                List<NuveqEventDto> events = objectMapper.convertValue(payload, new TypeReference<List<NuveqEventDto>>() {});
                events.forEach(cardEventListener::onCardEvent);
            } else if (payload.has("data") && payload.get("data").isArray()) {
                List<NuveqEventDto> events = objectMapper.convertValue(payload.get("data"), new TypeReference<List<NuveqEventDto>>() {});
                events.forEach(cardEventListener::onCardEvent);
            } else {
                NuveqEventDto singleEvent = objectMapper.convertValue(payload, NuveqEventDto.class);
                cardEventListener.onCardEvent(singleEvent);
            }
        } catch (Exception e) {
            log.error("Error deserializing Nuveq webhook payload: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.success(null, "Invalid payload format"));
        }

        return ResponseEntity.ok(ApiResponse.success("Processed", "Webhook event processed"));
    }
}
