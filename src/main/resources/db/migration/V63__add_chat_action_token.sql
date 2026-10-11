-- E24-T04: one row per one-click chat action link. The link itself is a signed token; this row
-- makes it single-use (used_at is set atomically) and shows who used it.

CREATE TABLE chat_action_token (
    jti VARCHAR(36) NOT NULL PRIMARY KEY,
    action_type VARCHAR(30) NOT NULL,
    environment_id VARCHAR(36) NULL,
    target_id VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP NULL,
    used_by_user_id VARCHAR(36) NULL,
    INDEX idx_chat_action_token_expires (expires_at)
);
