-- V17: Tracks when a VM's AWS cost-allocation tags (tcg:managed-by/environment/team) were last
-- successfully reconciled by TagReconciliationService. Admin visibility only — AWS remains the
-- source of truth for the actual tag state; this column never blocks or drives tagging logic.

ALTER TABLE vm ADD COLUMN tags_synced_at TIMESTAMP NULL;
