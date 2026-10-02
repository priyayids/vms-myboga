CREATE TABLE IF NOT EXISTS webhook_event_log (
    id UUID PRIMARY KEY,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    event_type VARCHAR(100),
    card_number VARCHAR(100),
    direction VARCHAR(50),
    door_id BIGINT,
    site_id BIGINT,
    matched_visitor_id UUID,
    matched_registration_id VARCHAR(100),
    action_taken VARCHAR(100) NOT NULL,
    raw_payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_webhook_card_number ON webhook_event_log (card_number);
CREATE INDEX IF NOT EXISTS idx_webhook_received_at ON webhook_event_log (received_at DESC);
