-- Enviados (sección "Enviados"). Vive aparte de email_messages para no tocar los
-- contadores ni los filtros del inbox: los enviados no son correo recibido.
CREATE TABLE IF NOT EXISTS sent_messages (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider          VARCHAR(50) NOT NULL,
    provider_email_id VARCHAR(255) NOT NULL,
    thread_id         VARCHAR(255),
    to_addresses      TEXT,
    subject           TEXT,
    body_preview      TEXT,
    body_html         TEXT,
    sent_at           TIMESTAMP WITH TIME ZONE,
    fetched_at        TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id, provider_email_id)
);

CREATE INDEX IF NOT EXISTS idx_sent_messages_user_sent_at ON sent_messages(user_id, sent_at DESC);
