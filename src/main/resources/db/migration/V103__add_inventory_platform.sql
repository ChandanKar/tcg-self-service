-- U26-T02: a VM's operating system sets its on-demand price (RHEL and Windows cost more than Linux).
-- EC2 reports it per instance: platform_details ("Red Hat Enterprise Linux", "Windows", ...) and
-- usage_operation, the billing code the price list is keyed by ("RunInstances:0010" for RHEL).
-- NULL until the next inventory sync reads it, and NULL is priced as Linux/UNIX, as before.
ALTER TABLE vm_inventory_snapshot ADD COLUMN platform_details VARCHAR(100) NULL;
ALTER TABLE vm_inventory_snapshot ADD COLUMN usage_operation VARCHAR(32) NULL;
