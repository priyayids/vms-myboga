#!/bin/bash
set -e

BASE_URL="http://localhost:8080"
echo "=== 1. Checking Health Endpoint ==="
curl -s -f "$BASE_URL/actuator/health" | jq . || curl -s "$BASE_URL/actuator/health"
echo ""

echo "=== 2. Creating New Visitor Reservation ==="
RES=$(curl -s -X POST "$BASE_URL/api/v1/visitors/reserve" \
  -H "Content-Type: application/json" \
  -d '{
    "registrationId": "TEST-REG-20261002-01",
    "fullName": "Budi Santoso",
    "email": "budi@example.com",
    "phone": "+6281234567890",
    "userPhoto": "https://example.com/budi.jpg",
    "vehicleNumber": "B1234XYZ",
    "visitStart": "2026-10-02T08:00:00Z",
    "visitEnd": "2026-10-02T17:00:00Z",
    "siteId": 167,
    "liftGroupId": 630,
    "allowedDoorIds": [2596, 4904],
    "cardNumber": "88889999"
  }')
echo "$RES" | jq . || echo "$RES"
echo ""

echo "=== 3. Testing Idempotency (Resubmission of TEST-REG-20261002-01) ==="
RES2=$(curl -s -X POST "$BASE_URL/api/v1/visitors/reserve" \
  -H "Content-Type: application/json" \
  -d '{
    "registrationId": "TEST-REG-20261002-01",
    "fullName": "Budi Santoso",
    "email": "budi@example.com",
    "phone": "+6281234567890",
    "visitStart": "2026-10-02T08:00:00Z",
    "visitEnd": "2026-10-02T17:00:00Z",
    "siteId": 167,
    "liftGroupId": 630,
    "allowedDoorIds": [2596, 4904],
    "cardNumber": "88889999"
  }')
echo "$RES2" | jq . || echo "$RES2"
echo ""

echo "=== 4. Simulating Nuveq Card Swipe Event (IN) ==="
EVENT_RES=$(curl -s -X POST "$BASE_URL/api/v1/events/nuveq-webhook" \
  -H "Content-Type: application/json" \
  -d '{
    "id": 5001,
    "cardNo": 88889999,
    "direction": "IN",
    "timestamp": "2026-10-02T08:30:00Z"
  }')
echo "$EVENT_RES" | jq . || echo "$EVENT_RES"
echo ""

echo "=== 5. Checking Visitor State in Database ==="
curl -s "$BASE_URL/api/v1/visitors/registration/TEST-REG-20261002-01" | jq . || curl -s "$BASE_URL/api/v1/visitors/registration/TEST-REG-20261002-01"
echo ""

echo "=== 6. Checking Transaction Logs ==="
tail -n 10 logs/transactions.log || true
