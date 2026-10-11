-- E24-T03: one row per chat message attempt (success or failure). dedup_key lets a per-user
-- event (a lease warning sent to every participant) reach a channel only once.

CREATE TABLE chat_delivery_log (
    delivery_id VARCHAR(36) NOT NULL PRIMARY KEY,
    channel_id VARCHAR(36) NOT NULL,
    notification_type VARCHAR(50) NOT NULL,
    environment_id VARCHAR(36) NULL,
    dedup_key VARCHAR(200) NULL,
    success BOOLEAN NOT NULL,
    error VARCHAR(500) NULL,
    sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_chat_delivery_sent_at (sent_at),
    INDEX idx_chat_delivery_dedup (channel_id, dedup_key)
);
