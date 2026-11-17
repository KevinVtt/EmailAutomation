-- Contactos: teléfono opcional y borrado lógico (hidden) para que rebuildInbound no lo recree desde los mails.
ALTER TABLE email_contact
    ADD COLUMN IF NOT EXISTS phone VARCHAR(50),
    ADD COLUMN IF NOT EXISTS hidden BOOLEAN NOT NULL DEFAULT FALSE;
