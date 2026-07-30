-- V18: Account-wide Reserved Instance / Savings Plan coverage & utilization, one row per day.
-- Not per-VM/per-tag (Cost Explorer's reservation/savings-plan APIs are account-wide), so this
-- is a small standalone snapshot table, cached daily rather than queried live on every page load
-- since the underlying Cost Explorer calls are both rate-limited and billed per call.

CREATE TABLE reservation_coverage_snapshot (
    snapshot_id VARCHAR(36) PRIMARY KEY,
    snapshot_date DATE NOT NULL,
    on_demand_cost DECIMAL(12,2),
    covered_cost DECIMAL(12,2),
    coverage_percent DECIMAL(5,2),
    ri_utilization_percent DECIMAL(5,2),
    sp_coverage_percent DECIMAL(5,2),
    sp_utilization_percent DECIMAL(5,2),
    net_savings DECIMAL(12,2),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_reservation_coverage_snapshot_date ON reservation_coverage_snapshot(snapshot_date);
