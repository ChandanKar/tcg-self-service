-- E21-T02: anomalies ingested from AWS Cost Anomaly Detection, mapped to an environment through
-- their monitor (NULL for an unlinked monitor: admins only). notified_at and feedback are kept
-- when AWS reports the anomaly again.

CREATE TABLE cost_anomaly (
    anomaly_id VARCHAR(100) NOT NULL PRIMARY KEY,
    first_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    notified_at TIMESTAMP NULL,
    monitor_arn VARCHAR(255) NULL,
    environment_id VARCHAR(36) NULL,
    anomaly_start DATE NULL,
    anomaly_end DATE NULL,
    total_impact DECIMAL(12, 2) NULL,
    total_expected DECIMAL(12, 2) NULL,
    max_score DECIMAL(6, 2) NULL,
    root_causes TEXT NULL,
    feedback VARCHAR(20) NULL,
    INDEX idx_cost_anomaly_env_start (environment_id, anomaly_start),
    CONSTRAINT fk_cost_anomaly_env FOREIGN KEY (environment_id) REFERENCES environment (environment_id) ON DELETE SET NULL
);
