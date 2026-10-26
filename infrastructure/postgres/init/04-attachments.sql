-- Adjuntos: metadatos del archivo y el texto extraído (para buscar por contenido).
-- has_attachment queda en FALSE en los mails ya guardados: se rellena al volver a sincronizarlos.
ALTER TABLE email_messages ADD COLUMN IF NOT EXISTS has_attachment BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE email_messages ADD COLUMN IF NOT EXISTS attachments_json TEXT;
ALTER TABLE email_messages ADD COLUMN IF NOT EXISTS attachment_text TEXT;

-- Índice parcial para la extracción de texto (10-50 mails por sync): sin él,
-- cada sync hace un seq scan sobre los ~17k mails del usuario.
CREATE INDEX IF NOT EXISTS idx_email_pending_attachment
    ON email_messages (user_id, provider)
    WHERE has_attachment = TRUE AND attachment_text IS NULL;
