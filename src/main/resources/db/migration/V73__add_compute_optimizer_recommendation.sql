-- E30-T04: AWS Compute Optimizer EC2 recommendations captured daily with every option, so the
-- rightsizing page reads them from here instead of calling AWS on page load.

CREATE TABLE compute_optimizer_recommendation (
    recommendation_id VARCHAR(36) NOT NULL PRIMARY KEY,
    vm_id VARCHAR(36) NULL,
    provider_vm_id VARCHAR(64) NOT NULL,
    aws_account_ref VARCHAR(36) NULL,
    region VARCHAR(32) NOT NULL,
    finding VARCHAR(40) NOT NULL,
    finding_reasons TEXT NULL,
    current_instance_type VARCHAR(64) NULL,
    lookback_days INT NULL,
    memory_metric_present BOOLEAN NOT NULL DEFAULT FALSE,
    captured_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_compute_optimizer_rec_instance UNIQUE (provider_vm_id),
    CONSTRAINT fk_compute_optimizer_rec_vm FOREIGN KEY (vm_id) REFERENCES vm (vm_id) ON DELETE SET NULL
);

CREATE INDEX idx_compute_optimizer_rec_target ON compute_optimizer_recommendation (region, aws_account_ref);

CREATE TABLE compute_optimizer_option (
    option_id VARCHAR(36) NOT NULL PRIMARY KEY,
    recommendation_id VARCHAR(36) NOT NULL,
    option_rank INT NOT NULL,
    instance_type VARCHAR(64) NOT NULL,
    performance_risk DECIMAL(4,2) NULL,
    migration_effort VARCHAR(20) NULL,
    platform_differences TEXT NULL,
    estimated_monthly_savings DECIMAL(12,2) NULL,
    CONSTRAINT fk_compute_optimizer_option_rec FOREIGN KEY (recommendation_id)
        REFERENCES compute_optimizer_recommendation (recommendation_id) ON DELETE CASCADE
);
