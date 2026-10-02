package com.visitorbridge.controller;

import com.visitorbridge.dto.ApiResponse;
import com.visitorbridge.dto.ReservationResponseDto;
import com.visitorbridge.dto.VisitorRegistrationRequest;
import com.visitorbridge.dto.VisitorResponseDto;
import com.visitorbridge.service.VisitorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/visitors")
@RequiredArgsConstructor
public class VisitorController {

    private final VisitorService visitorService;

    @PostMapping("/reserve")
    public ResponseEntity<ApiResponse<ReservationResponseDto>> reserveVisitor(
            @Valid @RequestBody VisitorRegistrationRequest request) {
        ReservationResponseDto response = visitorService.registerVisitor(request);
        HttpStatus status = response.isIdempotent() ? HttpStatus.OK : HttpStatus.CREATED;
        String message = response.isIdempotent()
                ? "Existing reservation returned"
                : "Visitor reservation created successfully";
        return ResponseEntity.status(status).body(ApiResponse.success(response, message));
    }

    @GetMapping("/registration/{registrationId}")
    public ResponseEntity<ApiResponse<List<VisitorResponseDto>>> getVisitors(
            @PathVariable("registrationId") String registrationId) {
        List<VisitorResponseDto> list = visitorService.getVisitorsByRegistrationId(registrationId);
        return ResponseEntity.ok(ApiResponse.success(list));
    }
}
