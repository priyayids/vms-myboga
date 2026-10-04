package com.visitorbridge.controller;

import com.visitorbridge.dto.ApiResponse;
import com.visitorbridge.dto.BookingResponseDto;
import com.visitorbridge.dto.VisitorRegistrationRequest;
import com.visitorbridge.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/visitors")
@RequiredArgsConstructor
public class VisitorController {

    private final BookingService bookingService;

    @PostMapping("/registration")
    public ResponseEntity<ApiResponse<BookingResponseDto>> reserveVisitor(
            @Valid @RequestBody VisitorRegistrationRequest request) {
        log.info("Reservation received: registrationId={} roomId={}", request.getRegistrationId(), request.getRoomId());

        BookingResponseDto response = bookingService.reserveBooking(request);

        HttpStatus status = response.isIdempotent() ? HttpStatus.OK : HttpStatus.CREATED;
        String message = response.isIdempotent()
                ? "Existing reservation returned"
                : "Booking created successfully";

        return ResponseEntity.status(status).body(ApiResponse.success(response, message));
    }
}
