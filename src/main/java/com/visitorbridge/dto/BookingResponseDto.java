package com.visitorbridge.dto;

import com.visitorbridge.model.BookingStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingResponseDto {
    private String registrationId;
    private boolean idempotent;
    private Long roomId;
    private String roomName;
    private String visitorName;
    private String email;
    private String phone;
    private String vehicleNumber;
    private String cardNumberIn;
    private String cardNumberOut;
    private List<String> allowedDoors;
    private OffsetDateTime visitStart;
    private OffsetDateTime visitEnd;
    private BookingStatus bookingStatus;
    private String qrCodeUrlIn;
    private String qrCodeUrlOut;
    private OffsetDateTime createdAt;
}
