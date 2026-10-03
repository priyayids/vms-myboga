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
public class RoomSyncResultDto {
    private int totalFetched;
    private int createdRooms;
    private int updatedDoors;
    private String message;
}
