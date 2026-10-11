-- E24-T02: chat (Microsoft Teams) channels per environment, or a global default when
-- environment_id is NULL. The webhook URL is a bearer secret: it is stored AES-GCM encrypted,
-- and only its host is kept in clear for display.

CREATE TABLE chat_channel (
    channel_id VARCHAR(36) NOT NULL PRIMARY KEY,
    environment_id VARCHAR(36) NULL,
    provider VARCHAR(20) NOT NULL,
    label VARCHAR(100) NOT NULL,
    webhook_url_enc TEXT NOT NULL,
    webhook_host VARCHAR(255) NOT NULL,
    event_types TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(36) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_chat_channel_environment (environment_id),
    CONSTRAINT fk_chat_channel_environment FOREIGN KEY (environment_id) REFERENCES environment (environment_id) ON DELETE CASCADE
);
