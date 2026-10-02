package com.visitorbridge.client;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class NuveqCreateVisitorResponse {

    private int error;
    private String message;
    private CreateVisitorData data;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateVisitorData {
        private Long visitorId;
        private Long visitorRegistrationId;
    }
}
