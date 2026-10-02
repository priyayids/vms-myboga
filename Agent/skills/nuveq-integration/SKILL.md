---
name: nuveq-integration
description: >-
  Integration patterns, API specifications, and troubleshooting runbooks for the Nuveq Cloud Partner Access Control API v2.
---

# Nuveq Cloud Partner API v2 Integration

This skill documents integration patterns, authentication mechanisms, and request/response specifications for the Nuveq Cloud Partner API v2.

## Authentication

All requests to Nuveq API require the API key in the header:
```http
X-API-KEY: <api-key>
Content-Type: application/json
```

## Endpoints

### 1. Create Visitor
`POST /api/visitors`

**Payload:**
```json
{
  "name": "Jane Doe",
  "credentialNumber": 12345678,
  "email": "jane@example.com",
  "phone": "+6281234567890",
  "userPhoto": "https://example.com/photo.jpg",
  "vehicleNumber": "B1234XYZ",
  "visitStart": "2026-10-02T08:00:00+07:00",
  "visitEnd": "2026-10-02T17:00:00+07:00",
  "siteId": 1,
  "liftGroupId": 1,
  "allowedDoorIds": [1, 2]
}
```

**Response (201):**
```json
{
  "error": 0,
  "message": "Success",
  "data": {
    "visitorId": 101,
    "visitorRegistrationId": 202
  }
}
```

### 2. Events & Webhooks
- **Webhook Endpoint**: `POST /api/webhooks` with `{ "enable": true, "webhookLink": "..." }`
- **Events Polling**: `GET /api/events?date=YYYY-MM-DD`
- **Event Object Schema**:
  - `cardNo` (or `cardId`): Card number to match against local database.
  - `doorId`: Door identifier where card event occurred.
  - `direction`: "IN" or "OUT".
  - `timestamp`: Event timestamp.

## Error Handling & Retry Policies

- **HTTP 4xx (Client error)**: Invalid payload or validation failure. Log as ERROR with Nuveq response body. Do not retry. Return HTTP 422 to client.
- **HTTP 401 / 403 (Auth failure)**: Invalid API key. Log as ERROR. Do not retry. Return HTTP 500 to client.
- **HTTP 5xx / Network Timeout**: Transient server error. Retry up to 3 times with exponential backoff (e.g., 500ms, 1000ms, 2000ms). If all fail, return HTTP 502 Bad Gateway.
