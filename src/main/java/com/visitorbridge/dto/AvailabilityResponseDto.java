package com.visitorbridge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvailabilityResponseDto {
    private Long roomId;
    private String roomName;
    private String date;
    private List<AvailabilitySlotDto> slots;
}
