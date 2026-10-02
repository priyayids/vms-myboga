package com.visitorbridge.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visitorbridge.client.NuveqEventDto;
import com.visitorbridge.dto.ApiResponse;
import com.visitorbridge.dto.WebhookProcessingResult;
import com.visitorbridge.model.WebhookEventLog;
import com.visitorbridge.service.VisitorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventWebhookController {

    private final VisitorService visitorService;
    private final ObjectMapper objectMapper;

    @PostMapping("/nuveq-webhook")
    public ResponseEntity<ApiResponse<Map<String, Object>>> receiveWebhook(@RequestBody JsonNode payload) {
        String rawJson = payload.toString();
        log.info(">>> INCOMING NUVEQ WEBHOOK EVENT >>> {}", rawJson);

        List<WebhookProcessingResult> results = new ArrayList<>();

        try {
            if (payload.isArray()) {
                List<NuveqEventDto> events = objectMapper.convertValue(payload, new TypeReference<List<NuveqEventDto>>() {});
                for (NuveqEventDto event : events) {
                    WebhookProcessingResult res = visitorService.processCardEvent(event, rawJson);
                    if (res != null) results.add(res);
                }
            } else if (payload.has("data") && payload.get("data").isArray()) {
                List<NuveqEventDto> events = objectMapper.convertValue(payload.get("data"), new TypeReference<List<NuveqEventDto>>() {});
                for (NuveqEventDto event : events) {
                    WebhookProcessingResult res = visitorService.processCardEvent(event, rawJson);
                    if (res != null) results.add(res);
                }
            } else {
                NuveqEventDto singleEvent = objectMapper.convertValue(payload, NuveqEventDto.class);
                WebhookProcessingResult res = visitorService.processCardEvent(singleEvent, rawJson);
                if (res != null) results.add(res);
            }
        } catch (Exception e) {
            log.error("Failed to parse incoming Nuveq webhook payload: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.success(
                    Map.of("error", "Invalid payload format: " + e.getMessage()),
                    "Webhook failed"
            ));
        }

        long matchedCount = results.stream().filter(WebhookProcessingResult::isMatched).count();

        Map<String, Object> summary = Map.of(
                "totalReceived", results.size(),
                "matchedVisitors", matchedCount,
                "results", results
        );

        return ResponseEntity.ok(ApiResponse.success(summary, "Webhook event processed and logged"));
    }

    @GetMapping("/logs")
    public ResponseEntity<ApiResponse<List<WebhookEventLog>>> getRecentWebhookLogs() {
        List<WebhookEventLog> logs = visitorService.getRecentWebhookLogs();
        return ResponseEntity.ok(ApiResponse.success(logs));
    }
}
