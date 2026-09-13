-- ==============================================================================
-- LANDrop Phase 15A: Authentication & User Session Schema Migration (V2)
-- Adds:
-- 1. password_hash to users table
-- 2. refresh_tokens table for persistent, revocable refresh sessions
-- ==============================================================================

-- 1. Add password_hash column to existing users table
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_hash TEXT;

-- 2. Create refresh_tokens table
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE ON UPDATE CASCADE,
    token_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 3. Indexes for fast session lookup and user cascade
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);
