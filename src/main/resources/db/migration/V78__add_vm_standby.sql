-- E31-T05: a node group converted to Spot keeps its on-demand original as a standby (scaled to 0,
-- skipped when the environment starts); the two point at each other.
ALTER TABLE vm ADD COLUMN is_standby BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE vm ADD COLUMN spot_partner_vm_id VARCHAR(36) NULL;
