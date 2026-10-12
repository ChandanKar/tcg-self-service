-- E34-T01: personal API tokens. Only a keyed hash (HMAC-SHA256 with api-tokens.pepper) of the full
-- token is stored; the 8-character prefix finds the row. Every token expires.
CREATE TABLE api_token (
    token_id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    name VARCHAR(100) NOT NULL,
    token_prefix CHAR(8) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    scopes TEXT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP NULL,
    last_used_ip VARCHAR(64) NULL,
    revoked_at TIMESTAMP NULL,
    revoked_by VARCHAR(36) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_api_token_prefix UNIQUE (token_prefix),
    CONSTRAINT fk_api_token_user FOREIGN KEY (user_id) REFERENCES app_user (user_id) ON DELETE CASCADE,
    INDEX idx_api_token_user (user_id, revoked_at)
);
