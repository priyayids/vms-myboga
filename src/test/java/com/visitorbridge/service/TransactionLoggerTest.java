package com.visitorbridge.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionLoggerTest {

    @Test
    @DisplayName("Should mask card numbers properly, keeping only last 4 digits")
    void testMaskCardNumber() {
        assertThat(TransactionLogger.maskCardNumber("1234567890")).isEqualTo("****7890");
        assertThat(TransactionLogger.maskCardNumber("987654321")).isEqualTo("****4321");
        assertThat(TransactionLogger.maskCardNumber("1234")).isEqualTo("****");
        assertThat(TransactionLogger.maskCardNumber("123")).isEqualTo("****");
        assertThat(TransactionLogger.maskCardNumber("")).isEqualTo("****");
        assertThat(TransactionLogger.maskCardNumber(null)).isEqualTo("****");
    }
}
