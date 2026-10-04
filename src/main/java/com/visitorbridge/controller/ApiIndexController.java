package com.visitorbridge.controller;

import com.visitorbridge.config.NuveqProperties;
import com.visitorbridge.config.VmsProperties;
import com.visitorbridge.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
public class ApiIndexController {

    private static final List<String> ENDPOINTS = List.of(
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
            "GET  /api/events/logs"
    );

    // The already-bound configuration beans, not @Value. Reading the values
    // through the same objects the rest of the app uses means a renamed
    // property cannot leave this endpoint quietly reporting "unknown" - and
    // the defaults declared on VmsProperties/NuveqProperties always apply.
    private final VmsProperties vmsProperties;
    private final NuveqProperties nuveqProperties;

    @GetMapping({"/", ""})
    public ApiResponse<Map<String, Object>> index() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", "Visitor Middleware Service");
        data.put("status", "up");
        data.put("apiBaseUrl", vmsProperties.getQr().getBaseServeUrl());
        data.put("qrStoragePath", vmsProperties.getQr().getStoragePath());
        data.put("nuveqBaseUrl", nuveqProperties.getBaseUrl());
        data.put("cardEventMode", nuveqProperties.getEventListener().getMode());
        data.put("operatingHours", vmsProperties.getBooking().getOperatingHoursStart()
                + "-" + vmsProperties.getBooking().getOperatingHoursEnd()
                + " " + vmsProperties.getBooking().getTimezone());
        data.put("endpoints", ENDPOINTS);
        return ApiResponse.success(data);
    }
}
