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
public class DoorResponseDto {
    private Long id;
    private Long nuveqDoorId;
    private String doorName;
    private Integer doorNumber;
    private Long controllerId;
    private Long siteId;
    private Long roomId;
    private String roomCustomName;
}
