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
public class WebhookProcessingResult {
    private String cardNumber;
    private String direction;
    private boolean matched;
    private String actionTaken;
    private String matchedRegistrationId;
    private String matchedUserType;
}
