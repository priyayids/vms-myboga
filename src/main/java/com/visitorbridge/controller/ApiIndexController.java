package com.visitorbridge.controller;

import com.visitorbridge.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Landing page for the API.
 *
 * <p>Without this, hitting the bare host name returned 500: there was no
 * mapping for {@code /}, so Spring threw {@code NoResourceFoundException},
 * which the catch-all {@code @ExceptionHandler(Exception.class)} in
 * {@code GlobalExceptionHandler} turned into an internal error rather than a
 * plain 404. An index makes it obvious that the service is up and where to go
 * next.
 */
@Slf4j
@RestController
public class ApiIndexController {

    private static final String[] ENDPOINTS = {
            "GET  /actuator/health",
            "GET  /actuator/metrics",
            "POST /api/visitors/registration",
            "GET  /api/rooms",
            "GET  /api/rooms/{id}",
            "POST /api/rooms",
            "PUT  /api/rooms/{id}",
            "DELETE /api/rooms/{id}",
            "POST /api/rooms/sync",
            "GET  /api/rooms/doors",
            "GET  /api/rooms/{roomId}/availability?date=YYYY-MM-DD",
            "GET  /api/bookings/{registrationId}",
            "DELETE /api/bookings/{registrationId}",
            "GET  /api/bookings/{registrationId}/qr/{in|out}",
            "POST /api/events/nuveq-webhook",
            "GET  /api/events/logs",
    };

    private final String vmsBaseUrl;
    private final String nuveqBaseUrl;
    private final String eventMode;

    public ApiIndexController(
            @Value("${vms.base-url:unknown}") String vmsBaseUrl,
            @Value("${nuveq.base-url:unknown}") String nuveqBaseUrl,
            @Value("${nuveq.event-listener.mode:unknown}") String eventMode) {
        this.vmsBaseUrl = vmsBaseUrl;
        this.nuveqBaseUrl = nuveqBaseUrl;
        this.eventMode = eventMode;
    }

    @GetMapping({"/", ""})
    public ApiResponse<Map<String, Object>> index() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", "Visitor Middleware Service");
        data.put("status", "up");
        data.put("apiBaseUrl", vmsBaseUrl);
        data.put("nuveqBaseUrl", nuveqBaseUrl);
        data.put("cardEventMode", eventMode);
        data.put("endpoints", List.of(ENDPOINTS));
        return ApiResponse.success(data);
    }
}
