-- V14: Daily estimated (and, in future, actual) cost snapshot per environment,
-- feeding the Cost Management trend chart.

CREATE TABLE cost_daily_snapshot (
    snapshot_id VARCHAR(36) PRIMARY KEY,
    environment_id VARCHAR(36) NOT NULL,
    snapshot_date DATE NOT NULL,
    estimated_cost DECIMAL(12,2) NOT NULL,
    actual_cost DECIMAL(12,2) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_cost_snapshot_env FOREIGN KEY (environment_id) REFERENCES environment(environment_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_cost_snapshot ON cost_daily_snapshot(environment_id, snapshot_date);
CREATE INDEX idx_cost_snapshot_date ON cost_daily_snapshot(snapshot_date);
