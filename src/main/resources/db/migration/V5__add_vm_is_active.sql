-- V5: Add is_active column to vm table
-- Flyway guarantees this script runs exactly once, so no conditional guard is needed.
ALTER TABLE vm ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;
CREATE INDEX idx_vm_is_active ON vm(is_active);
UPDATE vm SET is_active = FALSE WHERE status IN ('TERMINATED', 'NOT_FOUND');
