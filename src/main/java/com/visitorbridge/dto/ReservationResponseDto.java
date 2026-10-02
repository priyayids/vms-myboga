package com.visitorbridge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationResponseDto {
    private String registrationId;
    private boolean idempotent;
    private VisitorResponseDto checkIn;
    private VisitorResponseDto checkOut;
}
