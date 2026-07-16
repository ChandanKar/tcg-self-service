-- V16: Memory utilization column for vm_metric_sample (and its archive counterpart), sourced
-- from the CloudWatch Agent's custom "mem_used_percent" metric. NULL when the agent isn't
-- installed/configured on a VM (standard EC2 monitoring doesn't publish memory), same as CPU
-- fields being NULL for VMs with no monitoring data elsewhere in this schema.

ALTER TABLE vm_metric_sample ADD COLUMN memory_utilization DECIMAL(7,3);
ALTER TABLE vm_metric_sample_archive ADD COLUMN memory_utilization DECIMAL(7,3);
