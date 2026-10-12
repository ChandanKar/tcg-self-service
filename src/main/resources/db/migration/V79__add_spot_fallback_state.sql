-- E31-T06: a Spot twin's fallback state, in columns (EKS sync rewrites node-group metadata each run):
-- since when it has been short of capacity, whether its on-demand standby was started, and how
-- many healthy checks in a row it has had since.
ALTER TABLE vm ADD COLUMN spot_degraded_since TIMESTAMP NULL;
ALTER TABLE vm ADD COLUMN spot_fallback_active BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE vm ADD COLUMN spot_healthy_checks INT NOT NULL DEFAULT 0;
