package com.visitorbridge.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "nuveq")
public class NuveqProperties {

    @NotBlank(message = "nuveq.base-url must not be blank")
    private String baseUrl = "https://api-v2.nuveq.cloud";

    @NotBlank(message = "nuveq.api-key must not be blank. Please supply NUVEQ_API_KEY environment variable.")
    private String apiKey;

    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(10);
    private int maxRetries = 3;
    private long backoffDelayMs = 500;

    private EventListenerConfig eventListener = new EventListenerConfig();

    @Getter
    @Setter
    public static class EventListenerConfig {
        private String mode = "webhook";
        private long pollingIntervalMs = 10000;
        /** Upper bound on remembered event ids, so a long-running poller cannot leak. */
        private int processedEventsCapacity = 5000;
    }
}
