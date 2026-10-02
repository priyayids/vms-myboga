---
name: spring-boot-backend
description: >-
  Best practices, standards, and architecture guide for developing Spring Boot 3+ backend services using Java 21, Spring Data JPA, Flyway, MapStruct, Lombok, and structured logging.
---

# Spring Boot Backend Engineering Guide

This skill provides architectural rules, coding standards, and operational guidelines for the Java 21 / Spring Boot 3 visitor middleware service.

## Architecture & Layering

Strict layered architecture with downward-only dependencies:
```
controller  ->  service  ->  repository  ->  database (PostgreSQL)
                    |
                    +-->  client (Nuveq API Client)
```

- **Controller**: REST endpoints, Jakarta validation (`@Valid`), HTTP status codes, no business logic.
- **Service**: Business rules, orchestration, idempotency checks, transaction management (`@Transactional`).
- **Repository**: Spring Data JPA interfaces with explicit query methods and index-aware queries.
- **Model**: JPA entities inheriting from `BaseEntity` (`id`, `createdAt`, `updatedAt`).
- **DTO**: Request and response objects (records or classes with validation annotations).
- **Mapper**: MapStruct interfaces (`@Mapper(componentModel = "spring")`).
- **Client**: Dedicated HTTP client using Spring 6 `RestClient`.
- **Listener / Webhook**: Card event consumers, conditional updates for concurrency.
- **Config**: Typed `@ConfigurationProperties` classes with fail-fast validation.
- **Exception**: Domain exceptions and `@RestControllerAdvice` returning uniform `ApiResponse<T>`.

## Standards & Conventions

1. **Java 21 Modern Syntax**: Use modern records for immutable DTOs, pattern matching for `instanceof`, text blocks for queries.
2. **Lombok**: Use `@RequiredArgsConstructor` for constructor injection. Never use `@Autowired` field injection.
3. **Database & Migrations**:
   - Hibernate DDL auto is set to `validate`.
   - All schema changes must be versioned Flyway SQL scripts in `src/main/resources/db/migration/V<version>__<description>.sql`.
   - Table columns use `snake_case`, Java entities use `camelCase`.
4. **Structured Logging**:
   - Write general application logs to `logs/application.log`.
   - Write business transaction logs to `logs/transactions.log`.
   - Format: `YYYY-MM-DD HH:mm:ss.SSS | LEVEL | ClassName | registrationId=... | action=... | result=...`
   - Mask sensitive card numbers (only show last 4 digits).
5. **Idempotency**:
   - Check existing registration before inserting.
   - Resubmissions with identical `registrationId` return HTTP 200 with the existing record.
