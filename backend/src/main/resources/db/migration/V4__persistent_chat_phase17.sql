-- ==============================================================================
-- LANDrop Cloud Database Foundation - Schema Migration V4
-- Phase 17: Persistent Team Chat Backend
--
-- Supports:
-- 1. edited_at on messages for message editing
-- 2. deleted_at on messages for soft message deletion
-- 3. Composite index for forward chronological pagination
-- ==============================================================================

ALTER TABLE messages ADD COLUMN IF NOT EXISTS edited_at TIMESTAMPTZ;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_messages_channel_chronological ON messages(channel_id, created_at ASC, id ASC);
