-- ==============================================================================
-- LANDrop Phase 18A: File Management & LAN Transfer Backend Migration (V5)
--
-- 1. Enhances 'folders' table with created_by_id and deleted_at (soft delete)
-- 2. Enhances 'files' table with storage_type, original_name, storage_path, deleted_at
-- 3. Creates 'devices' table for User -> Device -> Peer mapping
-- 4. Creates 'transfers' table for User-authorized File Transfer requests
-- ==============================================================================

-- 1. Update folders table
ALTER TABLE folders
    ADD COLUMN IF NOT EXISTS created_by_id UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ DEFAULT NULL;

CREATE INDEX IF NOT EXISTS idx_folders_deleted_at ON folders(deleted_at);

-- 2. Update files table
ALTER TABLE files
    ADD COLUMN IF NOT EXISTS original_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS storage_type VARCHAR(50) NOT NULL DEFAULT 'LAN_ONLY',
    ADD COLUMN IF NOT EXISTS storage_path TEXT,
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ DEFAULT NULL;

CREATE INDEX IF NOT EXISTS idx_files_deleted_at ON files(deleted_at);
CREATE INDEX IF NOT EXISTS idx_files_sha256 ON files(sha256);

-- 3. Table: devices
CREATE TABLE IF NOT EXISTS devices (
    id VARCHAR(100) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_name VARCHAR(255) NOT NULL,
    peer_id VARCHAR(100),
    ip_address VARCHAR(100),
    port INT,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_devices_user_id ON devices(user_id);
CREATE INDEX IF NOT EXISTS idx_devices_peer_id ON devices(peer_id);

-- 4. Table: transfers
CREATE TABLE IF NOT EXISTS transfers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_id UUID NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    sender_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    receiver_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sender_device_id VARCHAR(100),
    receiver_device_id VARCHAR(100),
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    error_message TEXT
);

CREATE INDEX IF NOT EXISTS idx_transfers_file_id ON transfers(file_id);
CREATE INDEX IF NOT EXISTS idx_transfers_sender_user ON transfers(sender_user_id);
CREATE INDEX IF NOT EXISTS idx_transfers_receiver_user ON transfers(receiver_user_id);
CREATE INDEX IF NOT EXISTS idx_transfers_status ON transfers(status);
