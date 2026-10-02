package com.visitorbridge.exception;

public class NuveqServerException extends RuntimeException {
    public NuveqServerException(String message) {
        super(message);
    }

    public NuveqServerException(String message, Throwable cause) {
        super(message, cause);
    }
}
