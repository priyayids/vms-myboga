---
name: visitor-service-testing
description: >-
  Testing guidelines, Mockito patterns, Testcontainers setup, and API verification procedures for the visitor middleware service.
---

# Visitor Service Testing & Verification

This skill covers end-to-end and unit testing procedures for the Visitor Middleware Service.

## Testing Architecture

1. **Unit Tests (`@ExtendWith(MockitoExtension.class)`)**:
   - `VisitorServiceTest`: Verify idempotency, validation, dual-instance creation (`CHECK_IN` and `CHECK_OUT`), error mapping.
   - `CardEventListenerTest`: Verify event handling, `statusEntry` state transitions, unknown card handling, concurrent/duplicate event handling.
   - `NuveqClientTest`: Mock RestClient responses (200, 4xx, 5xx, timeout) and verify retry behavior.

2. **Integration Tests (`@SpringBootTest`, `@AutoConfigureMockMvc`)**:
   - Test `/api/v1/visitors/reserve` endpoint.
   - Test Webhook event endpoint `/api/v1/events/nuveq-webhook`.
   - Test GlobalExceptionHandler format.
   - Test Flyway migration execution against real PostgreSQL or H2/Testcontainers.

## Running Tests

Execute with Maven:
```bash
mvn test
mvn verify
```
