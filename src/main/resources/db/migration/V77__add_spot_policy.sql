-- E31-T03: an environment's administrators opt it in to Spot; single VMs can be kept off it.
ALTER TABLE environment ADD COLUMN spot_policy VARCHAR(20) NOT NULL DEFAULT 'NONE';
ALTER TABLE vm ADD COLUMN spot_excluded BOOLEAN NOT NULL DEFAULT FALSE;
