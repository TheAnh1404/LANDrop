-- ==============================================================================
-- LANDrop Server Authentication & Device Enrollment - Schema Migration V7
-- FOUNDATION-04A: Sessions Enhancement & Persistent Device Enrollment
-- ==============================================================================

-- 1. Enhance refresh_tokens table with last_seen_at, revoked_at, device_id
ALTER TABLE refresh_tokens
    ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMPTZ DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS device_id VARCHAR(100) DEFAULT NULL;

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_device_id ON refresh_tokens(device_id);

-- 2. Enhance devices table with client_instance_id, platform, client_version, status
ALTER TABLE devices
    ADD COLUMN IF NOT EXISTS client_instance_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS platform VARCHAR(50) NOT NULL DEFAULT 'unknown',
    ADD COLUMN IF NOT EXISTS client_version VARCHAR(50) NOT NULL DEFAULT '1.0.0',
    ADD COLUMN IF NOT EXISTS status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE';

-- Backfill client_instance_id with id where null
UPDATE devices SET client_instance_id = id WHERE client_instance_id IS NULL;

-- Indexes for fast clientInstanceId lookup and device ownership checks
CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_client_instance_id ON devices(client_instance_id);
CREATE INDEX IF NOT EXISTS idx_devices_user_client ON devices(user_id, client_instance_id);
