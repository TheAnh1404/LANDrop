-- ==============================================================================
-- LANDrop Server Control Center - Schema Migration V6
-- FOUNDATION-03A: System Admin, User Status & Control Center
-- ==============================================================================

-- 1. Add status and system_role columns to users table
ALTER TABLE users ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE users ADD COLUMN IF NOT EXISTS system_role VARCHAR(50) NOT NULL DEFAULT 'USER';

-- 2. Indexes for fast status checks and role lookups
CREATE INDEX IF NOT EXISTS idx_users_system_role ON users(system_role);
CREATE INDEX IF NOT EXISTS idx_users_status ON users(status);
