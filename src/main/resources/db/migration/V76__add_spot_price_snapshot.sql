-- E31-T02: the newest Spot price per availability zone and instance type, captured daily, so
-- Spot VMs and node groups are priced at what they cost rather than the on-demand rate.
CREATE TABLE spot_price_snapshot (
    snapshot_id VARCHAR(36) NOT NULL PRIMARY KEY,
    aws_account_ref VARCHAR(36) NULL,
    region VARCHAR(32) NOT NULL,
    availability_zone VARCHAR(32) NOT NULL,
    instance_type VARCHAR(64) NOT NULL,
    price_per_hour DECIMAL(12,6) NOT NULL,
    captured_on DATE NOT NULL,
    CONSTRAINT uk_spot_price_snapshot UNIQUE (region, availability_zone, instance_type, captured_on)
);

CREATE INDEX idx_spot_price_lookup ON spot_price_snapshot (region, instance_type, captured_on);
