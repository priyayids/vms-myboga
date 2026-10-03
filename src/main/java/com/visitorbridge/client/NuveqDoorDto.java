package com.visitorbridge.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class NuveqDoorDto {
    private Long id;
    private String name;
    private String description;
    private Long controllerId;
    private Integer doorNumber;
    private Long siteId;
}
