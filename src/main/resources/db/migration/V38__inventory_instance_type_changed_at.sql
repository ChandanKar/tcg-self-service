-- E08-T06: when the instance type last changed (a resize in the app or outside it), so
-- rightsizing ignores CPU history and Compute Optimizer advice from the old size.
ALTER TABLE vm_inventory_snapshot ADD COLUMN instance_type_changed_at TIMESTAMP NULL;
