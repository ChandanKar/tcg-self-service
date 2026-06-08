-- V5: Add is_active column to vm table
-- Using information_schema conditionals for MySQL compatibility and idempotency

SET @s = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vm' AND COLUMN_NAME = 'is_active') = 0,
    'ALTER TABLE vm ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE',
    'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vm' AND INDEX_NAME = 'idx_vm_is_active') = 0,
    'CREATE INDEX idx_vm_is_active ON vm(is_active)',
    'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

UPDATE vm SET is_active = FALSE WHERE status IN ('TERMINATED', 'NOT_FOUND');
