-- E27-T01: AWS Savings Plans and Reserved Instance purchase recommendations, captured weekly from
-- Cost Explorer for display next to schedule data. Nothing is ever purchased by the app.

CREATE TABLE commitment_recommendation (
    rec_id VARCHAR(36) NOT NULL PRIMARY KEY,
    generated_on DATE NOT NULL,
    rec_type VARCHAR(30) NOT NULL,
    term VARCHAR(10) NOT NULL,
    payment_option VARCHAR(20) NOT NULL,
    lookback_days INT NULL,
    hourly_commitment DECIMAL(12,4) NULL,
    upfront_cost DECIMAL(12,2) NULL,
    estimated_monthly_savings DECIMAL(12,2) NULL,
    estimated_savings_percent DECIMAL(6,2) NULL,
    estimated_on_demand_cost DECIMAL(12,2) NULL,
    details TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_commitment_recommendation UNIQUE (generated_on, rec_type, term, payment_option)
);
