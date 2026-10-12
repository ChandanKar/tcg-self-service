-- E32-T01: a catalogue of approved environment templates (AWS Service Catalog products or
-- Terraform workspaces) and users' requests to provision an environment from one.

CREATE TABLE environment_template (
    template_id VARCHAR(36) NOT NULL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description TEXT NULL,
    engine VARCHAR(20) NOT NULL,
    engine_ref TEXT NOT NULL,
    parameter_schema TEXT NULL,
    aws_account_ref VARCHAR(36) NULL,
    region VARCHAR(32) NULL,
    default_ttl_hours INT NOT NULL DEFAULT 72,
    max_ttl_hours INT NOT NULL DEFAULT 336,
    requires_approval BOOLEAN NOT NULL DEFAULT TRUE,
    approver_role VARCHAR(20) NOT NULL DEFAULT 'ADMIN',
    allowed_roles VARCHAR(100) NOT NULL DEFAULT 'ENV_ADMIN,USER',
    estimated_monthly_cost DECIMAL(12,2) NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(36) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL,
    CONSTRAINT uk_environment_template_name UNIQUE (name)
);

CREATE TABLE provisioning_request (
    request_id VARCHAR(36) NOT NULL PRIMARY KEY,
    template_id VARCHAR(36) NOT NULL,
    requested_by_user_id VARCHAR(36) NOT NULL,
    environment_name VARCHAR(120) NOT NULL,
    parameters TEXT NULL,
    justification TEXT NULL,
    ttl_hours INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    reviewed_by_user_id VARCHAR(36) NULL,
    reviewed_at TIMESTAMP NULL,
    review_notes TEXT NULL,
    engine_record_id VARCHAR(200) NULL,
    engine_resource_id VARCHAR(200) NULL,
    environment_id VARCHAR(36) NULL,
    destroy_at TIMESTAMP NULL,
    error_message TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL,
    CONSTRAINT fk_provisioning_request_template FOREIGN KEY (template_id) REFERENCES environment_template (template_id),
    CONSTRAINT fk_provisioning_request_environment FOREIGN KEY (environment_id) REFERENCES environment (environment_id) ON DELETE SET NULL
);

CREATE INDEX idx_provisioning_request_status ON provisioning_request (status);
CREATE INDEX idx_provisioning_request_requester ON provisioning_request (requested_by_user_id);
