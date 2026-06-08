-- V6: Add service_type column to environment table

SET @addCol = 'ALTER TABLE environment ADD COLUMN service_type VARCHAR(20) NOT NULL DEFAULT ''EC2''';
SET @s = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'environment' AND COLUMN_NAME = 'service_type') = 0,
    @addCol,
    'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'environment' AND INDEX_NAME = 'idx_environment_service_type') = 0,
    'CREATE INDEX idx_environment_service_type ON environment(service_type)',
    'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
