package com.visitorbridge.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "vms")
public class VmsProperties {

    private Booking booking = new Booking();
    private Qr qr = new Qr();

    @Getter
    @Setter
    public static class Booking {
        private int expiryMinutes = 15;
        private int operatingHoursStart = 9;
        private int operatingHoursEnd = 22;
        private String timezone = "Asia/Jakarta";
    }

    @Getter
    @Setter
    public static class Qr {
        private String storagePath = "./data/qr-codes";
        private String baseServeUrl = "http://localhost:8080";
    }
}
