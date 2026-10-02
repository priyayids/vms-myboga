package com.visitorbridge.exception;

import lombok.Getter;

@Getter
public class NuveqClientException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    public NuveqClientException(int statusCode, String message, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }
}
