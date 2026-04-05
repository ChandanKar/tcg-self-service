-- =====================================================
-- V5: Add is_active column to vm table
-- This column tracks whether a VM still exists in the cloud provider.
-- VMs that are NOT_FOUND or TERMINATED are marked as inactive.
-- =====================================================

-- Add is_active column with default value true
ALTER TABLE vm ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;

-- Create index for efficient querying of active VMs
CREATE INDEX IF NOT EXISTS idx_vm_is_active ON vm(is_active);

-- Mark any existing VMs with TERMINATED or NOT_FOUND status as inactive
UPDATE vm SET is_active = FALSE WHERE status IN ('TERMINATED', 'NOT_FOUND');

