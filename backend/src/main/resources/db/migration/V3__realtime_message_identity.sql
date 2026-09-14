-- Nullable for messages written before Phase 15.5.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS client_message_id UUID;
CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_sender_client_id ON messages(user_id, client_message_id);
CREATE INDEX IF NOT EXISTS idx_messages_channel_history ON messages(channel_id, created_at DESC, id DESC);
