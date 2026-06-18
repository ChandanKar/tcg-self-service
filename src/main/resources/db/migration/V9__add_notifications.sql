-- V9: Replace the V1 notification table (wrong column names) with the schema
-- that matches the Notification entity (type, entity_type, entity_id).
-- V1 had: notification_type, related_entity_type, related_entity_id — none of
-- which map to the Java entity, so the V1 table is replaced here.
DROP TABLE IF EXISTS notification;

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
    PRIMARY KEY (notification_id)
);
