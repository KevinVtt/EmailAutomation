CREATE EXTENSION IF NOT EXISTS vector;

-- Reemplaza email_embedding (Gemini, 768 dims): ahora un mail se divide en fragmentos
-- con embeddings locales (384 dims) y texto en español para búsqueda por palabras.
DROP TABLE IF EXISTS email_embedding;

CREATE TABLE IF NOT EXISTS email_chunk (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    email_id     UUID NOT NULL REFERENCES email_messages(id) ON DELETE CASCADE,
    chunk_index  INTEGER NOT NULL,
    content      TEXT NOT NULL,
    embedding    vector(384) NOT NULL,
    search_tsv   tsvector GENERATED ALWAYS AS (to_tsvector('spanish', content)) STORED,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_email_chunk UNIQUE (email_id, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_email_chunk_user ON email_chunk(user_id);
CREATE INDEX IF NOT EXISTS idx_email_chunk_hnsw ON email_chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_email_chunk_tsv ON email_chunk USING gin (search_tsv);
