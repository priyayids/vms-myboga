package com.visitorbridge.exception;

/**
 * Raised when an operation is not valid for the booking's current status, e.g. cancelling a
 * booking that has already completed, expired, or been cancelled.
 */
public class InvalidBookingStateException extends RuntimeException {

    public InvalidBookingStateException(String message) {
        super(message);
    }
}