package com.visitorbridge.dto;

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
public class RoomResponseDto {
    private Long id;
    private String customName;
    private Long siteId;
    private Long liftGroupId;
    private Integer expireMinutes;
    private List<DoorResponseDto> doors;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
