package com.visitorbridge.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NuveqEventDto {
    private Long id;
    private String uuid;
    private String mac;
    private Long ownerId;
    private String ownerName;
    private Long siteId;
    private String siteName;
    private Long cardId;
    private Long cardNo;
    private Long doorId;
    private String eventName;
    private Boolean alarmEvent;
    private Boolean attendance;
    private Boolean visitor;
    private String userName;
    private String direction;
    private String date;
    private String time;
    private OffsetDateTime timestamp;
}
