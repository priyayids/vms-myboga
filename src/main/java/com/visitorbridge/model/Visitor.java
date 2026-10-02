package com.visitorbridge.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "visitor",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_visitor_registration_user_type", columnNames = {"registration_id", "user_type"})
        }
)
public class Visitor extends BaseEntity {

    @Column(name = "registration_id", nullable = false, length = 100)
    private String registrationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_type", nullable = false, length = 20)
    private UserType userType;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "email")
    private String email;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "user_photo", columnDefinition = "TEXT")
    private String userPhoto;

    @Column(name = "vehicle_number", length = 50)
    private String vehicleNumber;

    @Column(name = "visit_start", nullable = false)
    private OffsetDateTime visitStart;

    @Column(name = "visit_end", nullable = false)
    private OffsetDateTime visitEnd;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Column(name = "lift_group_id", nullable = false)
    private Long liftGroupId;

    @Column(name = "allowed_door_ids", nullable = false)
    private String allowedDoorIds;

    @Column(name = "card_number", nullable = false, length = 100)
    private String cardNumber;

    @Column(name = "nuveq_visitor_id", length = 100)
    private String nuveqVisitorId;

    @Column(name = "nuveq_registration_id", length = 100)
    private String nuveqRegistrationId;

    @Builder.Default
    @Column(name = "status_entry", nullable = false)
    private Boolean statusEntry = false;
}
