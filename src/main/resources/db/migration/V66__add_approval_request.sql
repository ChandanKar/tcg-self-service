-- E25-T04: a start (or runtime extension) held for approval. The approvers eligible when it was
-- made are snapshotted; each approver's decision is recorded once. ANY completes on the first
-- approval, ALL when every approver agreed; any denial denies. On approval the operation runs
-- as the requester and execution_id records it.

CREATE TABLE approval_request (
    request_id VARCHAR(36) NOT NULL PRIMARY KEY,
    request_type VARCHAR(10) NOT NULL,
    environment_id VARCHAR(36) NOT NULL,
    requester_user_id VARCHAR(36) NOT NULL,
    payload MEDIUMTEXT NOT NULL,
    scope_summary VARCHAR(255) NULL,
    estimated_cost DECIMAL(12,2) NULL,
    requested_hours INT NULL,
    reasons TEXT NULL,
    approver_mode VARCHAR(5) NOT NULL,
    status VARCHAR(10) NOT NULL,
    status_reason VARCHAR(500) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    decided_at TIMESTAMP NULL,
    execution_id VARCHAR(36) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    INDEX idx_approval_request_status (status, expires_at),
    INDEX idx_approval_request_requester (requester_user_id),
    CONSTRAINT fk_approval_request_environment FOREIGN KEY (environment_id) REFERENCES environment (environment_id) ON DELETE CASCADE
);

CREATE TABLE approval_request_approver (
    request_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (request_id, user_id),
    INDEX idx_approval_request_approver_user (user_id),
    CONSTRAINT fk_approval_request_approver_request FOREIGN KEY (request_id) REFERENCES approval_request (request_id) ON DELETE CASCADE
);

CREATE TABLE approval_decision (
    request_id VARCHAR(36) NOT NULL,
    approver_user_id VARCHAR(36) NOT NULL,
    decision VARCHAR(10) NOT NULL,
    reason VARCHAR(500) NULL,
    decided_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (request_id, approver_user_id),
    CONSTRAINT fk_approval_decision_request FOREIGN KEY (request_id) REFERENCES approval_request (request_id) ON DELETE CASCADE
);
