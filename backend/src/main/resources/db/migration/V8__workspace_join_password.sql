-- ==============================================================================
-- LANDrop Workspace Join Policy & Passcode Protection - Schema Migration V8
-- ==============================================================================

ALTER TABLE workspaces
    ADD COLUMN IF NOT EXISTS join_policy VARCHAR(30) NOT NULL DEFAULT 'INVITE_ONLY',
    ADD COLUMN IF NOT EXISTS join_password_hash VARCHAR(255) DEFAULT NULL;

CREATE INDEX IF NOT EXISTS idx_workspaces_join_policy ON workspaces(join_policy);
