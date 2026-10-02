package com.visitorbridge.client;

import com.visitorbridge.config.NuveqProperties;
import com.visitorbridge.exception.NuveqAuthException;
import com.visitorbridge.exception.NuveqClientException;
import com.visitorbridge.exception.NuveqServerException;
import com.visitorbridge.service.TransactionLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;

@Slf4j
@Component
public class NuveqVisitorClient {

    private final RestClient restClient;
    private final NuveqProperties properties;
    private final TransactionLogger transactionLogger;

    public NuveqVisitorClient(NuveqProperties properties, TransactionLogger transactionLogger) {
        this.properties = properties;
        this.transactionLogger = transactionLogger;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());

        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("X-API-KEY", properties.getApiKey())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory)
                .build();
    }

    public NuveqCreateVisitorResponse createVisitor(NuveqCreateVisitorRequest request, String registrationId) {
        String url = "/api/visitors";
        int attempt = 0;
        int maxRetries = Math.max(1, properties.getMaxRetries());
        long backoffDelay = properties.getBackoffDelayMs();

        while (attempt < maxRetries) {
            attempt++;
            try {
                log.info("Sending create visitor request to Nuveq (attempt {}/{}) for registrationId={}",
                        attempt, maxRetries, registrationId);

                NuveqCreateVisitorResponse response = restClient.post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                            int statusCode = resp.getStatusCode().value();
                            String body = new String(resp.getBody().readAllBytes());
                            if (statusCode == 401 || statusCode == 403) {
                                transactionLogger.logTransaction("NuveqVisitorClient", registrationId, "NUVEQ_AUTH", "FAILED", "status=" + statusCode);
                                throw new NuveqAuthException("Nuveq authentication failed with status: " + statusCode);
                            }
                            transactionLogger.logTransaction("NuveqVisitorClient", registrationId, "NUVEQ_CREATE_VISITOR", "FAILED_4XX", "status=" + statusCode);
                            throw new NuveqClientException(statusCode, "Nuveq client error " + statusCode + ": " + body, body);
                        })
                        .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                            int statusCode = resp.getStatusCode().value();
                            String body = new String(resp.getBody().readAllBytes());
                            throw new HttpServerErrorException(resp.getStatusCode(), "Nuveq server error " + statusCode + ": " + body);
                        })
                        .body(NuveqCreateVisitorResponse.class);

                transactionLogger.logTransaction("NuveqVisitorClient", registrationId, "NUVEQ_CREATE_VISITOR", "SUCCESS",
                        "attempt=" + attempt);
                return response;

            } catch (NuveqAuthException | NuveqClientException ex) {
                // Do not retry client or authentication errors
                throw ex;
            } catch (HttpServerErrorException | ResourceAccessException ex) {
                log.warn("Nuveq call failed on attempt {}/{}: {}", attempt, maxRetries, ex.getMessage());
                if (attempt >= maxRetries) {
                    transactionLogger.logTransaction("NuveqVisitorClient", registrationId, "NUVEQ_CREATE_VISITOR", "RETRY_EXHAUSTED",
                            "attempts=" + attempt);
                    throw new NuveqServerException("Nuveq service unavailable after " + maxRetries + " attempts: " + ex.getMessage(), ex);
                }
                try {
                    Thread.sleep(backoffDelay * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new NuveqServerException("Interrupted while waiting for Nuveq retry", ie);
                }
            } catch (Exception ex) {
                log.error("Unexpected error contacting Nuveq: {}", ex.getMessage(), ex);
                throw new NuveqServerException("Unexpected error communicating with Nuveq: " + ex.getMessage(), ex);
            }
        }

        throw new NuveqServerException("Nuveq create visitor failed after " + maxRetries + " attempts");
    }

    public List<NuveqEventDto> fetchEvents(String date) {
        try {
            NuveqEventsResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/events")
                            .queryParam("date", date)
                            .build())
                    .retrieve()
                    .body(NuveqEventsResponse.class);

            return (response != null && response.getData() != null) ? response.getData() : List.of();
        } catch (Exception ex) {
            log.error("Failed to poll events from Nuveq for date {}: {}", date, ex.getMessage());
            return List.of();
        }
    }
}
