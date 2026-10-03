package com.visitorbridge.controller;

import com.visitorbridge.dto.ApiResponse;
import com.visitorbridge.dto.BookingResponseDto;
import com.visitorbridge.service.BookingService;
import com.visitorbridge.service.QrCodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;
    private final QrCodeService qrCodeService;

    @GetMapping("/{registrationId}")
    public ResponseEntity<ApiResponse<BookingResponseDto>> getBooking(
            @PathVariable("registrationId") String registrationId) {
        BookingResponseDto booking = bookingService.getBookingResponse(registrationId);
        return ResponseEntity.ok(ApiResponse.success(booking));
    }

    @DeleteMapping("/{registrationId}")
    public ResponseEntity<ApiResponse<BookingResponseDto>> cancelBooking(
            @PathVariable("registrationId") String registrationId) {
        BookingResponseDto booking = bookingService.cancelBooking(registrationId);
        return ResponseEntity.ok(ApiResponse.success(booking, "Booking cancelled and QR codes removed"));
    }

    @GetMapping("/{registrationId}/qr/{type}")
    public ResponseEntity<byte[]> getQrCode(
            @PathVariable("registrationId") String registrationId,
            @PathVariable("type") String type) {

        if (!"in".equalsIgnoreCase(type) && !"out".equalsIgnoreCase(type)) {
            return ResponseEntity.badRequest().build();
        }

        String relativePath = bookingService.getQrCodePath(registrationId, type.toLowerCase());
        byte[] png = qrCodeService.readQrCode(relativePath);

        if (png == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + registrationId + "_" + type + ".png\"")
                .body(png);
    }
}
