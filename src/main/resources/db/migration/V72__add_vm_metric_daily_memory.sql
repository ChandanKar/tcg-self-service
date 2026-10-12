-- E30-T01: daily memory utilisation (CloudWatch agent mem_used_percent) next to CPU, so
-- rightsizing can use memory. VMs without the agent keep null averages and a zero count.
ALTER TABLE vm_metric_daily
    ADD COLUMN avg_memory_utilization DECIMAL(7,3) NULL,
    ADD COLUMN max_memory_utilization DECIMAL(7,3) NULL,
    ADD COLUMN memory_sample_count INT NOT NULL DEFAULT 0;
