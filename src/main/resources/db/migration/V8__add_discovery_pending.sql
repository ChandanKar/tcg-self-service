-- Add discovery_pending flag to vm table.
-- true  = auto-discovered by VmDiscoveryService, awaiting admin review
-- false = manually registered OR already acknowledged by admin (default)
ALTER TABLE vm ADD COLUMN discovery_pending BOOLEAN NOT NULL DEFAULT FALSE;
