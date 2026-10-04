package com.visitorbridge.dto;

import jakarta.validation.constraints.NotBlank;
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
public class RoomCreateRequest {

    @NotBlank(message = "customName is required")
    private String customName;

    private Long siteId;

    private Long liftGroupId;

    /** Minutes after visitStart with no check-in before the booking auto-expires (default 15). */
    private Integer expireMinutes;

    private List<Long> doorNuveqIds;
}
