package com.visitorbridge.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TransactionLogger {

    private static final Logger TX_LOGGER = LoggerFactory.getLogger("com.visitorbridge.transaction");

    public static String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.isBlank()) {
            return "****";
        }
        int len = cardNumber.length();
        if (len <= 4) {
            return "****";
        }
        return "****" + cardNumber.substring(len - 4);
    }

    public void logTransaction(String className, String registrationId, String action, String result, String details) {
        String regId = (registrationId != null && !registrationId.isBlank()) ? registrationId : "N/A";
        String extra = (details != null && !details.isBlank()) ? " | " + details : "";
        TX_LOGGER.info("{} | registrationId={} | action={} | result={}{}", className, regId, action, result, extra);
    }

    public void logTransaction(String className, String registrationId, String action, String result) {
        logTransaction(className, registrationId, action, result, null);
    }
}
