-- E20-T01: monthly budgets per environment or team, with alert thresholds and an optional
-- action (freeze starts / stop the environment), and the alerts already fired per month.

CREATE TABLE budget (
    budget_id VARCHAR(36) NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    scope_type VARCHAR(20) NOT NULL,
    scope_id VARCHAR(100) NOT NULL,
    monthly_amount DECIMAL(12, 2) NOT NULL,
    thresholds VARCHAR(50) NOT NULL DEFAULT '50,75,90,100',
    forecast_alert BOOLEAN NOT NULL DEFAULT TRUE,
    action_type VARCHAR(20) NOT NULL DEFAULT 'NONE',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    aws_budget_name VARCHAR(100) NULL,
    created_by_user_id VARCHAR(36) NOT NULL,
    CONSTRAINT uk_budget_scope UNIQUE (scope_type, scope_id)
);

CREATE TABLE budget_alert (
    alert_id VARCHAR(36) NOT NULL PRIMARY KEY,
    fired_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    budget_id VARCHAR(36) NOT NULL,
    budget_month CHAR(7) NOT NULL,
    threshold INT NOT NULL,
    kind VARCHAR(10) NOT NULL,
    spend DECIMAL(12, 2) NULL,
    forecast DECIMAL(12, 2) NULL,
    CONSTRAINT uk_budget_alert UNIQUE (budget_id, budget_month, threshold, kind),
    CONSTRAINT fk_budget_alert_budget FOREIGN KEY (budget_id) REFERENCES budget (budget_id) ON DELETE CASCADE
);
