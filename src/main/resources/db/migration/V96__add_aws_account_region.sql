-- U19-T01: the regions each AWS account has enabled (ec2:DescribeRegions, opt-in regions included),
-- read once a day, so scans cover every region without a region list in the settings. A region whose
-- calls were refused (an SCP, a missing permission) is recorded and skipped for a day, not retried
-- on every run.
CREATE TABLE aws_account_region (
    aws_account_region_id VARCHAR(36) NOT NULL PRIMARY KEY,
    aws_account_ref VARCHAR(36) NOT NULL,
    region_code VARCHAR(32) NOT NULL,
    opt_in_status VARCHAR(32) NULL,
    read_at TIMESTAMP NOT NULL,
    refused_at TIMESTAMP NULL,
    refused_reason VARCHAR(500) NULL,
    CONSTRAINT uk_aws_account_region UNIQUE (aws_account_ref, region_code),
    CONSTRAINT fk_aws_account_region_account FOREIGN KEY (aws_account_ref)
        REFERENCES aws_account (aws_account_ref) ON DELETE CASCADE
);
