-- E21-T01: AWS Cost Anomaly Detection monitors and the environment each one watches, so an
-- anomaly can be routed to that environment's owners. "managed" monitors were created by the app.

CREATE TABLE cost_anomaly_monitor (
    monitor_arn VARCHAR(255) NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NULL,
    environment_id VARCHAR(36) NULL,
    monitor_name VARCHAR(255) NOT NULL,
    monitor_type VARCHAR(20) NULL,
    managed BOOLEAN NOT NULL DEFAULT FALSE,
    linked_manually BOOLEAN NOT NULL DEFAULT FALSE,
    INDEX idx_cost_anomaly_monitor_env (environment_id),
    CONSTRAINT fk_cost_anomaly_monitor_env FOREIGN KEY (environment_id)
        REFERENCES environment (environment_id) ON DELETE SET NULL
);
