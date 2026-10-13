-- E35-T01: AWS Cost Explorer's forecast for platform-managed resources, captured weekly: DAILY points
-- for the next 90 days and MONTHLY points for the next 18 months, each with its mean and prediction
-- interval; is_total marks the whole-horizon total AWS returns alongside the points.
CREATE TABLE aws_cost_forecast (
    forecast_id VARCHAR(36) NOT NULL PRIMARY KEY,
    generated_on DATE NOT NULL,
    granularity VARCHAR(10) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    mean DECIMAL(14,2) NULL,
    lower_bound DECIMAL(14,2) NULL,
    upper_bound DECIMAL(14,2) NULL,
    scope VARCHAR(20) NOT NULL DEFAULT 'MANAGED',
    is_total BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_aws_cost_forecast UNIQUE (generated_on, granularity, period_start, scope, is_total)
);
