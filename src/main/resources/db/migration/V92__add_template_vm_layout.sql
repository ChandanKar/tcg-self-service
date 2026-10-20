-- U09-T09: the VMs a template creates, per group in start order ("1|2|1" = 1, then 2, then 1),
-- for the catalogue's power strip and VM count. Null when not described.
ALTER TABLE environment_template ADD COLUMN vm_layout VARCHAR(64) NULL;
