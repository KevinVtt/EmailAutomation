-- Importancia clasificada por la IA (urgencia alta en el sync). Se suma al label IMPORTANT de Gmail.
ALTER TABLE email_messages ADD COLUMN IF NOT EXISTS is_important BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_email_messages_important
    ON email_messages(user_id) WHERE is_important;
