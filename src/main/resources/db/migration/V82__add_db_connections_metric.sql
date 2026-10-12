-- E33-T03: RDS databases as VMs. Database connections per metric sample (Maximum over the period),
-- and the engine and Multi-AZ flag in the inventory snapshot. NULL for EC2 and EKS.
ALTER TABLE vm_metric_sample ADD COLUMN db_connections DECIMAL(12,3) NULL;
ALTER TABLE vm_metric_sample_archive ADD COLUMN db_connections DECIMAL(12,3) NULL;
ALTER TABLE vm_inventory_snapshot
    ADD COLUMN engine VARCHAR(40) NULL,
    ADD COLUMN multi_az BOOLEAN NULL;
