CREATE TABLE notification (
    notification_id VARCHAR(36)   NOT NULL,
    user_id         VARCHAR(36)   NOT NULL,
    type            VARCHAR(50)   NOT NULL,
    title           VARCHAR(255)  NOT NULL,
    message         TEXT          NOT NULL,
    entity_type     VARCHAR(50),
    entity_id       VARCHAR(36),
    is_read         BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (notification_id),
    INDEX idx_notification_user    (user_id),
    INDEX idx_notification_unread  (user_id, is_read),
    INDEX idx_notification_created (created_at)
);
