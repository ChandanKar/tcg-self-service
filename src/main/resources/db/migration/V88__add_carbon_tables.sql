-- E37-T01: AWS Customer Carbon Footprint data from a Data Export in S3, per month, account, region
-- and service, in kg CO2e (market-based and location-based when the export has them), and the export
-- files already ingested (key and ETag) so an unchanged file is not read twice.
CREATE TABLE carbon_monthly (
    carbon_id VARCHAR(36) NOT NULL PRIMARY KEY,
    month DATE NOT NULL,
    account_number VARCHAR(12) NOT NULL,
    region VARCHAR(30) NOT NULL,
    service_code VARCHAR(100) NOT NULL,
    emissions_mbm_kg DECIMAL(14,4) NULL,
    emissions_lbm_kg DECIMAL(14,4) NULL,
    source_file VARCHAR(1024) NULL,
    ingested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_carbon_monthly UNIQUE (month, account_number, region, service_code)
);

CREATE TABLE carbon_ingest_file (
    s3_key VARCHAR(768) NOT NULL PRIMARY KEY,
    etag VARCHAR(100) NOT NULL,
    ingested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_count INT NOT NULL DEFAULT 0
);
