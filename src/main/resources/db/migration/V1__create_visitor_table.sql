CREATE TABLE IF NOT EXISTS visitor (
    id UUID PRIMARY KEY,
    registration_id VARCHAR(100) NOT NULL,
    user_type VARCHAR(20) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    phone VARCHAR(50),
    user_photo TEXT,
    vehicle_number VARCHAR(50),
    visit_start TIMESTAMP WITH TIME ZONE NOT NULL,
    visit_end TIMESTAMP WITH TIME ZONE NOT NULL,
    site_id BIGINT NOT NULL,
    lift_group_id BIGINT NOT NULL,
    allowed_door_ids VARCHAR(255) NOT NULL,
    card_number VARCHAR(100) NOT NULL,
    nuveq_visitor_id VARCHAR(100),
    nuveq_registration_id VARCHAR(100),
    status_entry BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_visitor_registration_user_type UNIQUE (registration_id, user_type)
);

CREATE INDEX IF NOT EXISTS idx_visitor_card_number ON visitor (card_number);
CREATE INDEX IF NOT EXISTS idx_visitor_registration_id ON visitor (registration_id);
