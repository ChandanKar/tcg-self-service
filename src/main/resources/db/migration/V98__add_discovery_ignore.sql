-- U19-T03: resources an administrator chose to ignore in "Found in AWS". An ignored EC2 instance
-- or EKS cluster stays out of the inbox until it is brought back.
CREATE TABLE discovery_ignore (
    discovery_ignore_id VARCHAR(36) NOT NULL PRIMARY KEY,
    resource_kind VARCHAR(16) NOT NULL,
    aws_account_ref VARCHAR(36) NOT NULL,
    region_code VARCHAR(32) NOT NULL,
    resource_id VARCHAR(255) NOT NULL,
    resource_name VARCHAR(255) NULL,
    ignored_by VARCHAR(36) NULL,
    ignored_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_discovery_ignore UNIQUE (resource_kind, aws_account_ref, region_code, resource_id)
);
