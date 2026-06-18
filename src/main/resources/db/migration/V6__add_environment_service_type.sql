-- V6: Add service_type column to environment table
-- Flyway guarantees this script runs exactly once, so no conditional guard is needed.
ALTER TABLE environment ADD COLUMN service_type VARCHAR(20) NOT NULL DEFAULT 'EC2';
CREATE INDEX idx_environment_service_type ON environment(service_type);
