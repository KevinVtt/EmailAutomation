-- Cursor de sincronización incremental de Gmail (users.history.list).
-- Se guarda por token; si falta o venció (404), el sync cae al fetch por fecha.
ALTER TABLE oauth_tokens ADD COLUMN IF NOT EXISTS history_id VARCHAR(64);
