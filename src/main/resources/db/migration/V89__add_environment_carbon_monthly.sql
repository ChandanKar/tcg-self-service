-- E37-T02: each month's carbon emissions attributed to environments by their share of cost in the same
-- account, region and service; environment_id NULL is the unallocated remainder. cost_basis is the
-- cost the share was computed from, coverage_pct the attributed share of the known accounts' emissions.
CREATE TABLE environment_carbon_monthly (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    environment_id VARCHAR(36) NULL,
    month DATE NOT NULL,
    emissions_mbm_kg DECIMAL(14,4) NULL,
    emissions_lbm_kg DECIMAL(14,4) NULL,
    cost_basis DECIMAL(14,2) NULL,
    coverage_pct DECIMAL(5,2) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_env_carbon_environment FOREIGN KEY (environment_id) REFERENCES environment (environment_id) ON DELETE CASCADE,
    CONSTRAINT uk_env_carbon_month UNIQUE (month, environment_id)
);

CREATE INDEX idx_env_carbon_env_month ON environment_carbon_monthly (environment_id, month);
