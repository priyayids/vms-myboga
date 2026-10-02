# Visitor Middleware Service

A backend middleware service bridging the client's visitor registration form and the Nuveq Access Control API v2.

## Features
- **Registration Intake & Validation**: `POST /api/v1/visitors/reserve` validates form inputs with Jakarta Bean Validation.
- **Idempotency**: Duplicate submissions with the same `registrationId` return the existing record without creating duplicates or redundant upstream calls.
- **Dual Visitor Instances**: Automatically generates both `CHECK_IN` and `CHECK_OUT` records with `statusEntry = false`, pushing both to Nuveq Visitor API.
- **Real-Time Card Event Monitoring**: `POST /api/v1/events/nuveq-webhook` listens for card swipes, conditionally updating `statusEntry` to `true` with thread-safe DB updates.
- **Structured File Logging**: Rolling logs in `logs/application.log` and `logs/transactions.log` adhering to PRD masking and format rules.
- **Resilience**: Configurable exponential retry with backoff for upstream Nuveq calls.

## Tech Stack
- Java 21 LTS
- Spring Boot 3.3.4
- Spring Data JPA + Hibernate
- PostgreSQL 16
- Flyway Database Migrations
- MapStruct & Lombok
- Spring RestClient

## Getting Started

### 1. Requirements
- OpenJDK 21
- Maven 3.8+
- PostgreSQL 16 or Docker & Docker Compose

### 2. Configuration
Copy configuration into `.env` (or pass environment variables):
```env
DB_URL=jdbc:postgresql://localhost:5432/visitor_bridge
DB_USERNAME=postgres
DB_PASSWORD=postgres
NUVEQ_BASE_URL=https://api-v2.nuveq.cloud
NUVEQ_API_KEY=your_api_key_here
```

### 3. Run Locally
```bash
# Run Flyway migrations and start service
mvn spring-boot:run
```

### 4. Run with Docker Compose
```bash
docker compose up -d --build
```

### 5. Running Tests
```bash
mvn clean test
```

## API Documentation

### Reserve Visitor
- **URL**: `POST /api/v1/visitors/reserve`
- **Request Body**:
```json
{
  "registrationId": "REG-20261002-001",
  "fullName": "Jane Doe",
  "email": "jane.doe@example.com",
  "phone": "+628123456789",
  "userPhoto": "https://example.com/photo.jpg",
  "vehicleNumber": "B1234XYZ",
  "visitStart": "2026-10-02T08:00:00+07:00",
  "visitEnd": "2026-10-02T17:00:00+07:00",
  "siteId": 10,
  "liftGroupId": 20,
  "allowedDoorIds": [1, 2],
  "cardNumber": "1253646425"
}
```
- **Response**: HTTP 201 Created (or HTTP 200 OK on duplicate submission)

### Nuveq Event Webhook
- **URL**: `POST /api/v1/events/nuveq-webhook`
- **Request Body**:
```json
{
  "id": 1001,
  "cardNo": 1253646425,
  "direction": "IN",
  "timestamp": "2026-10-02T08:15:00+07:00"
}
```
- **Response**: HTTP 200 OK
