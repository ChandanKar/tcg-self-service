-- E28-T03: the AWS account registry. Every environment and AWS/AWS_EKS VM belongs to an account;
-- existing rows move to a default account (the identity behind the app's own credentials).
-- Member accounts are reached by assuming role_arn (E28-T04).

CREATE TABLE aws_account (
    aws_account_ref VARCHAR(36) NOT NULL PRIMARY KEY,
    account_number VARCHAR(12) NULL,
    display_name VARCHAR(255) NOT NULL,
    role_arn VARCHAR(2048) NULL,
    external_id VARCHAR(1224) NULL,
    regions TEXT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    is_billing_account BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    source VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    last_verified_at TIMESTAMP NULL,
    last_error TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_aws_account_number UNIQUE (account_number)
);

INSERT INTO aws_account (aws_account_ref, display_name, is_default, is_billing_account, source)
VALUES ('00000000-0000-0000-0000-000000000001', 'Default account', TRUE, TRUE, 'DEFAULT');

ALTER TABLE environment
    ADD COLUMN aws_account_ref VARCHAR(36) NULL,
    ADD CONSTRAINT fk_environment_aws_account FOREIGN KEY (aws_account_ref) REFERENCES aws_account (aws_account_ref);
CREATE INDEX idx_environment_aws_account ON environment (aws_account_ref);

ALTER TABLE vm
    ADD COLUMN aws_account_ref VARCHAR(36) NULL,
    ADD CONSTRAINT fk_vm_aws_account FOREIGN KEY (aws_account_ref) REFERENCES aws_account (aws_account_ref);
CREATE INDEX idx_vm_aws_account_region ON vm (aws_account_ref, region);

UPDATE environment SET aws_account_ref = '00000000-0000-0000-0000-000000000001';
UPDATE vm SET aws_account_ref = '00000000-0000-0000-0000-000000000001' WHERE provider IN ('AWS', 'AWS_EKS');
