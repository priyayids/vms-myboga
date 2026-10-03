package com.visitorbridge.model;

import jakarta.persistence.*;
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
    name = "bookings",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_bookings_registration_id", columnNames = "registration_id")
    }
)
public class Booking extends BaseEntity {

    @Column(name = "registration_id", nullable = false, length = 64)
    private String registrationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private Room room;

    @Column(name = "visitor_name", nullable = false)
    private String visitorName;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "user_photo", columnDefinition = "TEXT")
    private String userPhoto;

    @Column(name = "vehicle_number", length = 50)
    private String vehicleNumber;

    @Column(name = "card_number_in", nullable = false, length = 64)
    private String cardNumberIn;

    @Column(name = "card_number_out", nullable = false, length = 64)
    private String cardNumberOut;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Column(name = "lift_group_id", nullable = false)
    private Long liftGroupId;

    @Column(name = "visit_start", nullable = false)
    private OffsetDateTime visitStart;

    @Column(name = "visit_end", nullable = false)
    private OffsetDateTime visitEnd;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "booking_status", nullable = false, length = 20)
    private BookingStatus bookingStatus = BookingStatus.PENDING;

    @Column(name = "nuveq_visitor_id_in")
    private Long nuveqVisitorIdIn;

    @Column(name = "nuveq_registration_id_in")
    private Long nuveqRegistrationIdIn;

    @Column(name = "nuveq_visitor_id_out")
    private Long nuveqVisitorIdOut;

    @Column(name = "nuveq_registration_id_out")
    private Long nuveqRegistrationIdOut;

    @Column(name = "qr_code_path_in", length = 500)
    private String qrCodePathIn;

    @Column(name = "qr_code_path_out", length = 500)
    private String qrCodePathOut;
}
