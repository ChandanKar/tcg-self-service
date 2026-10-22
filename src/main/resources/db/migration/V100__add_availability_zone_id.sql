-- U19-T07: zone names map to different physical zones in each AWS account (eu-west-1a here is not
-- eu-west-1a there); the zone ID (euw1-az3) is the same everywhere, so it is kept next to the name.
ALTER TABLE vm_inventory_snapshot ADD COLUMN availability_zone_id VARCHAR(32) NULL;
