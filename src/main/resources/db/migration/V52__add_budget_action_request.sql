-- E20-T03: an exhausted budget's action (freeze starts / stop the environment) waits for an
-- owner's approval; a freeze blocks starts until environment.start_frozen_until.
-- execution_id holds the STOP operation an approved stop action started (E20-T04).

CREATE TABLE budget_action_request (
    request_id VARCHAR(36) NOT NULL PRIMARY KEY,
    requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    budget_id VARCHAR(36) NOT NULL,
    budget_month CHAR(7) NOT NULL,
    action_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    decided_by_user_id VARCHAR(36) NULL,
    decided_at TIMESTAMP NULL,
    decision_reason VARCHAR(500) NULL,
    execution_id VARCHAR(36) NULL,
    CONSTRAINT uk_budget_action_request UNIQUE (budget_id, budget_month, action_type),
    CONSTRAINT fk_budget_action_request_budget FOREIGN KEY (budget_id) REFERENCES budget (budget_id) ON DELETE CASCADE
);

CREATE INDEX idx_budget_action_request_status ON budget_action_request (status);

ALTER TABLE environment ADD COLUMN start_frozen_until TIMESTAMP NULL;
