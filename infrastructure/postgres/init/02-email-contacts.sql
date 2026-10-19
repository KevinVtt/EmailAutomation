CREATE TABLE IF NOT EXISTS email_contact (
    id               UUID PRIMARY KEY,
    user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    address          VARCHAR(320) NOT NULL,
    display_name     VARCHAR(255),
    company          VARCHAR(255),
    topic            TEXT,
    notes            TEXT,
    sent_count       INTEGER NOT NULL DEFAULT 0,
    received_count   INTEGER NOT NULL DEFAULT 0,
    last_activity_at TIMESTAMP,
    UNIQUE(user_id, address)
);

CREATE INDEX IF NOT EXISTS idx_email_contact_user_traffic
    ON email_contact(user_id, sent_count DESC, received_count DESC);
