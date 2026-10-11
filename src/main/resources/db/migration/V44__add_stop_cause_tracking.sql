-- V44 (E17-T01): why each VM stopped. State-history rows written by an operation carry its id
-- (the existing idx_vm_state_history_vm (vm_id, created_at) serves per-VM lookups), and every
-- operation records what started it. Existing rows read operation_id NULL and MANUAL.

ALTER TABLE vm_state_history ADD COLUMN operation_id VARCHAR(36) NULL;
CREATE INDEX idx_vm_state_history_operation ON vm_state_history (operation_id);

ALTER TABLE operation_execution ADD COLUMN trigger_source VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
