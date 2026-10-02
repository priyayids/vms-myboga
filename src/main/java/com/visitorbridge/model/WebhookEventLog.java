package com.visitorbridge.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "webhook_event_log")
public class WebhookEventLog {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "event_type", length = 100)
    private String eventType;

    @Column(name = "card_number", length = 100)
    private String cardNumber;

    @Column(name = "direction", length = 50)
    private String direction;

    @Column(name = "door_id")
    private Long doorId;

    @Column(name = "site_id")
    private Long siteId;

    @Column(name = "matched_visitor_id")
    private UUID matchedVisitorId;

    @Column(name = "matched_registration_id", length = 100)
    private String matchedRegistrationId;

    @Column(name = "action_taken", nullable = false, length = 100)
    private String actionTaken;

    @Column(name = "raw_payload", nullable = false, columnDefinition = "TEXT")
    private String rawPayload;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (receivedAt == null) {
            receivedAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
    }
}
