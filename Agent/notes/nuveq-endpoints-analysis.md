# Nuveq Cloud Partner API v2 - Tenant Live Configuration

## Credentials
- Header: `X-API-KEY`
- Base URL: `https://api-v2.nuveq.cloud`

## Live Tenant Resources (Confirmed)

### Site
- **Site ID**: `167`
- **Name**: "Jakarta meruya"

### Active Doors
| Door ID | Door Name | Controller ID | Site ID |
|---|---|---|---|
| `2596` | Demo Door 1 | 1273 | 167 |
| `3509` | Lift A | 1746 | 167 |
| `3523` | Rack A | 2228 | 167 |
| `4904` | 5601 Door1 | 2489 | 167 |

### Lift Groups
| Lift Group ID | Description | Number |
|---|---|---|
| `629` | No Access | 0 |
| `630` | Full Access | 1 |
| `1482` | FL 1 | 2 |
| `1483` | FL3-5-7 | 3 |
| `1922` | 123 567 | 4 |
| `2681` | MASTER | 5 |

## Endpoints

### 1. Create Visitor
- **Method**: `POST /api/visitors`
- **Payload**:
```json
{
  "name": "Visitor Name",
  "credentialNumber": 1253646425,
  "email": "visitor@example.com",
  "phone": "+628123456789",
  "userPhoto": "https://example.com/photo.png",
  "vehicleNumber": "B1234XYZ",
  "visitStart": "2026-10-02T08:00:00+07:00",
  "visitEnd": "2026-10-02T17:00:00+07:00",
  "siteId": 167,
  "liftGroupId": 630,
  "allowedDoorIds": [2596, 4904]
}
```
- **Response**:
```json
{
  "error": 0,
  "message": "Success",
  "data": {
    "visitorId": 123,
    "visitorRegistrationId": 456
  }
}
```

### 2. Events Query
- **Method**: `GET /api/events?date=YYYY-MM-DD`
- **Payload structure**:
  `cardNo` (number), `direction` ("IN" / "OUT"), `doorId`, `visitor` (true/false), `timestamp`.
