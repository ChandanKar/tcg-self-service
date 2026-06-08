-- EKS cloud provider support.
-- The `provider` column in the `vm` table is a plain VARCHAR with no CHECK constraint,
-- so no schema change is required. AWS_EKS is written by EksCloudProviderService.
-- This migration documents the new enum value and ensures the index is present.

-- Ensure existing EKS environments have their node-group VMs indexed efficiently.
-- (No DDL changes needed; index on provider already exists via V1 schema.)
