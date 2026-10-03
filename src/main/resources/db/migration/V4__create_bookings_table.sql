CREATE TABLE IF NOT EXISTS bookings (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    registration_id           VARCHAR(64) NOT NULL UNIQUE,
    room_id                   BIGINT REFERENCES room(id) ON DELETE SET NULL,
    visitor_name              VARCHAR(255) NOT NULL,
    email                     VARCHAR(255),
    phone                     VARCHAR(50),
    user_photo                TEXT,
    vehicle_number            VARCHAR(50),
    card_number_in            VARCHAR(64) NOT NULL,
    card_number_out           VARCHAR(64) NOT NULL,
    site_id                   BIGINT NOT NULL,
    lift_group_id             BIGINT NOT NULL,
    visit_start               TIMESTAMP WITH TIME ZONE NOT NULL,
    visit_end                 TIMESTAMP WITH TIME ZONE NOT NULL,
    booking_status            VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                              CHECK (booking_status IN ('PENDING','ACTIVE','COMPLETED','EXPIRED','CANCELLED')),
    nuveq_visitor_id_in       BIGINT,
    nuveq_registration_id_in  BIGINT,
    nuveq_visitor_id_out      BIGINT,
    nuveq_registration_id_out BIGINT,
    qr_code_path_in           VARCHAR(500),
    qr_code_path_out          VARCHAR(500),
    created_at                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE bookings IS 'Time-slot booking records linking visitors to rooms via Nuveq access control';
COMMENT ON COLUMN bookings.booking_status IS 'PENDING=awaiting checkin, ACTIVE=checked in, COMPLETED=checked out, EXPIRED=no-show, CANCELLED=admin cancel';

CREATE INDEX IF NOT EXISTS idx_bookings_slot ON bookings(room_id, visit_start, visit_end, booking_status);
CREATE INDEX IF NOT EXISTS idx_bookings_expiry ON bookings(booking_status, visit_start) WHERE booking_status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_bookings_card_in  ON bookings(card_number_in,  booking_status);
CREATE INDEX IF NOT EXISTS idx_bookings_card_out ON bookings(card_number_out, booking_status);
