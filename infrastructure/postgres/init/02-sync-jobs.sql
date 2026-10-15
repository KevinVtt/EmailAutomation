-- Sync jobs tracking table
CREATE TABLE IF NOT EXISTS sync_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider VARCHAR(50) NOT NULL,
    mode VARCHAR(20) NOT NULL,          -- 'incremental' | 'full'
    status VARCHAR(20) NOT NULL,        -- 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
    fetched INTEGER DEFAULT 0,
    total INTEGER DEFAULT 0,
    error_message TEXT,
    started_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sync_jobs_user_status ON sync_jobs(user_id, status);
