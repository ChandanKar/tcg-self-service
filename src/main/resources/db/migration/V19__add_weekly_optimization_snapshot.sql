-- V19: Weekly idle-waste / rightsizing totals per environment, one row per environment per
-- report type per week — unlike cost (which already has daily CostDailySnapshot history to
-- diff against), idle-waste and rightsizing are computed live with no historical storage, so
-- their week-over-week delta needs this baseline captured each Monday before it's overwritten.

CREATE TABLE weekly_optimization_snapshot (
    snapshot_id VARCHAR(36) PRIMARY KEY,
    environment_id VARCHAR(36) NOT NULL,
    report_type VARCHAR(20) NOT NULL,
    snapshot_date DATE NOT NULL,
    metric_value DECIMAL(12,2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_weekly_opt_snapshot_env FOREIGN KEY (environment_id) REFERENCES environment(environment_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_weekly_opt_snapshot ON weekly_optimization_snapshot(environment_id, report_type, snapshot_date);
