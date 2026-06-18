-- V10: Query-path indexes for the notification table (created in V9).
-- Kept separate from V9 to avoid H2/MySQL DDL differences with inline INDEX.
CREATE INDEX idx_notification_user    ON notification (user_id);
CREATE INDEX idx_notification_unread  ON notification (user_id, is_read);
CREATE INDEX idx_notification_created ON notification (created_at);
