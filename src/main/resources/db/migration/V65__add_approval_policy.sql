-- E25-T03: approval policies. A start (or runtime extension) that matches an active policy for its
-- environment (global, the environment's own, or its team's) needs approval: by instance type glob,
-- vCPU count, requested runtime or estimated cost. Approvers are users, a team's members, or the
-- environment's admins; ANY = one approval is enough, ALL = every approver must agree.

CREATE TABLE approval_policy (
    policy_id VARCHAR(36) NOT NULL PRIMARY KEY,
    environment_id VARCHAR(36) NULL,
    team_id VARCHAR(36) NULL,
    name VARCHAR(100) NOT NULL,
    instance_type_patterns TEXT NULL,
    min_vcpu INT NULL,
    max_runtime_hours INT NULL,
    max_estimated_cost DECIMAL(12,2) NULL,
    approver_mode VARCHAR(5) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(36) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_approval_policy_environment (environment_id),
    INDEX idx_approval_policy_team (team_id),
    CONSTRAINT fk_approval_policy_environment FOREIGN KEY (environment_id) REFERENCES environment (environment_id) ON DELETE CASCADE,
    CONSTRAINT fk_approval_policy_team FOREIGN KEY (team_id) REFERENCES team (team_id) ON DELETE CASCADE
);

CREATE TABLE approval_policy_approver (
    policy_id VARCHAR(36) NOT NULL,
    position INT NOT NULL,
    approver_type VARCHAR(20) NOT NULL,
    approver_id VARCHAR(36) NULL,
    PRIMARY KEY (policy_id, position),
    CONSTRAINT fk_approval_policy_approver_policy FOREIGN KEY (policy_id) REFERENCES approval_policy (policy_id) ON DELETE CASCADE
);
