-- E22-T01: account-level waste found by the daily sweep (unattached volumes, unassociated IPs,
-- gp2 volumes, long-stopped VMs, old snapshots), one row per type, region and resource.

CREATE TABLE waste_finding (
    finding_id VARCHAR(36) NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    first_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    eligible_since TIMESTAMP NULL,
    finding_type VARCHAR(30) NOT NULL,
    region VARCHAR(30) NOT NULL,
    resource_id VARCHAR(255) NOT NULL,
    resource_name VARCHAR(255) NULL,
    environment_id VARCHAR(36) NULL,
    vm_id VARCHAR(36) NULL,
    details TEXT NULL,
    est_monthly_cost DECIMAL(12, 2) NULL,
    status VARCHAR(20) NOT NULL,
    dismissed_by VARCHAR(36) NULL,
    dismissed_reason VARCHAR(500) NULL,
    CONSTRAINT uk_waste_finding UNIQUE (finding_type, region, resource_id),
    INDEX idx_waste_finding_status_type (status, finding_type)
);
