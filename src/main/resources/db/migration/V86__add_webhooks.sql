-- E34-T06: outbound webhooks. A subscription (global when environment_id is NULL) receives signed POSTs
-- for its event types; its signing secret is stored AES-GCM encrypted. Deliveries are queued and
-- retried with backoff by the webhook job.
CREATE TABLE webhook_subscription (
    webhook_id VARCHAR(36) NOT NULL PRIMARY KEY,
    label VARCHAR(100) NOT NULL,
    url TEXT NOT NULL,
    secret_enc TEXT NOT NULL,
    event_types TEXT NOT NULL,
    environment_id VARCHAR(36) NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    consecutive_failures INT NOT NULL DEFAULT 0,
    disabled_reason VARCHAR(255) NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_webhook_subscription_environment (environment_id),
    CONSTRAINT fk_webhook_subscription_environment FOREIGN KEY (environment_id) REFERENCES environment (environment_id) ON DELETE CASCADE
);

CREATE TABLE webhook_delivery (
    delivery_id VARCHAR(36) NOT NULL PRIMARY KEY,
    webhook_id VARCHAR(36) NOT NULL,
    event_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(60) NOT NULL,
    payload MEDIUMTEXT NOT NULL,
    attempt INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    next_attempt_at TIMESTAMP NULL,
    response_code INT NULL,
    error TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMP NULL,
    INDEX idx_webhook_delivery_due (status, next_attempt_at),
    INDEX idx_webhook_delivery_webhook (webhook_id, created_at),
    CONSTRAINT fk_webhook_delivery_subscription FOREIGN KEY (webhook_id) REFERENCES webhook_subscription (webhook_id) ON DELETE CASCADE
);
