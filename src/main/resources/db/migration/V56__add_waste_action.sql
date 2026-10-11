-- E22-T04: fix actions on waste findings. Proposed by one admin, approved by another, executed
-- in the background, and rolled back from rollback_data where AWS allows it. version guards a
-- double approval.

CREATE TABLE waste_action (
    action_id VARCHAR(36) NOT NULL PRIMARY KEY,
    proposed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    approved_at TIMESTAMP NULL,
    executed_at TIMESTAMP NULL,
    rolled_back_at TIMESTAMP NULL,
    finding_id VARCHAR(36) NOT NULL,
    action_type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    proposed_by VARCHAR(36) NOT NULL,
    approved_by VARCHAR(36) NULL,
    rollback_data TEXT NULL,
    error_message TEXT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    INDEX idx_waste_action_status (status),
    CONSTRAINT fk_waste_action_finding FOREIGN KEY (finding_id) REFERENCES waste_finding (finding_id) ON DELETE CASCADE
);
