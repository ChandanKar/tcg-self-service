-- V12: VM inventory, utilization metrics, idle summaries, rollups, and scheduler locks.

CREATE TABLE scheduled_job_lock (
    lock_name VARCHAR(128) PRIMARY KEY,
    locked_by VARCHAR(128),
    locked_until TIMESTAMP NOT NULL,
    acquired_at TIMESTAMP NULL
);

CREATE TABLE vm_inventory_snapshot (
    inventory_id VARCHAR(36) PRIMARY KEY,
    vm_id VARCHAR(36) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    provider_vm_id VARCHAR(255) NOT NULL,
    instance_type VARCHAR(64),
    vcpu_count INT,
    memory_mib INT,
    architecture VARCHAR(64),
    private_ip VARCHAR(64),
    public_ip VARCHAR(64),
    availability_zone VARCHAR(64),
    launch_time TIMESTAMP NULL,
    total_storage_gib INT,
    last_refreshed_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_vm_inventory_vm FOREIGN KEY (vm_id) REFERENCES vm(vm_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_vm_inventory_vm ON vm_inventory_snapshot(vm_id);
CREATE INDEX idx_vm_inventory_provider_id ON vm_inventory_snapshot(provider, provider_vm_id);

CREATE TABLE vm_volume_snapshot (
    volume_snapshot_id VARCHAR(36) PRIMARY KEY,
    vm_id VARCHAR(36) NOT NULL,
    volume_id VARCHAR(128) NOT NULL,
    device_name VARCHAR(128),
    volume_type VARCHAR(32),
    size_gib INT,
    iops INT,
    throughput_mbps INT,
    encrypted BOOLEAN,
    delete_on_termination BOOLEAN,
    last_refreshed_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_vm_volume_vm FOREIGN KEY (vm_id) REFERENCES vm(vm_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_vm_volume ON vm_volume_snapshot(vm_id, volume_id);
CREATE INDEX idx_vm_volume_vm ON vm_volume_snapshot(vm_id);

CREATE TABLE vm_metric_sample (
    metric_sample_id VARCHAR(36) PRIMARY KEY,
    vm_id VARCHAR(36) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    provider_vm_id VARCHAR(255) NOT NULL,
    sample_time TIMESTAMP NOT NULL,
    period_seconds INT NOT NULL,
    cpu_utilization DECIMAL(7,3),
    network_in_bytes BIGINT,
    network_out_bytes BIGINT,
    disk_read_bytes BIGINT,
    disk_write_bytes BIGINT,
    status_at_sample VARCHAR(32),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_vm_metric_vm FOREIGN KEY (vm_id) REFERENCES vm(vm_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_vm_metric_sample ON vm_metric_sample(vm_id, sample_time, period_seconds);
CREATE INDEX idx_vm_metric_vm_time ON vm_metric_sample(vm_id, sample_time);
CREATE INDEX idx_vm_metric_time ON vm_metric_sample(sample_time);

CREATE TABLE vm_idle_summary (
    idle_summary_id VARCHAR(36) PRIMARY KEY,
    vm_id VARCHAR(36) NOT NULL,
    is_idle BOOLEAN NOT NULL DEFAULT FALSE,
    idle_since TIMESTAMP NULL,
    idle_duration_minutes INT NOT NULL DEFAULT 0,
    latest_cpu_utilization DECIMAL(7,3),
    latest_network_in_bytes BIGINT,
    latest_network_out_bytes BIGINT,
    latest_disk_read_bytes BIGINT,
    latest_disk_write_bytes BIGINT,
    latest_sample_time TIMESTAMP NULL,
    reason VARCHAR(512),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_vm_idle_summary_vm FOREIGN KEY (vm_id) REFERENCES vm(vm_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_vm_idle_summary_vm ON vm_idle_summary(vm_id);

CREATE TABLE vm_metric_hourly (
    metric_hourly_id VARCHAR(36) PRIMARY KEY,
    vm_id VARCHAR(36) NOT NULL,
    bucket_start TIMESTAMP NOT NULL,
    avg_cpu_utilization DECIMAL(7,3),
    max_cpu_utilization DECIMAL(7,3),
    sum_network_in_bytes BIGINT,
    sum_network_out_bytes BIGINT,
    sum_disk_read_bytes BIGINT,
    sum_disk_write_bytes BIGINT,
    sample_count INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_vm_metric_hourly_vm FOREIGN KEY (vm_id) REFERENCES vm(vm_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_vm_metric_hourly ON vm_metric_hourly(vm_id, bucket_start);
CREATE INDEX idx_vm_metric_hourly_time ON vm_metric_hourly(bucket_start);

CREATE TABLE vm_metric_daily (
    metric_daily_id VARCHAR(36) PRIMARY KEY,
    vm_id VARCHAR(36) NOT NULL,
    bucket_date DATE NOT NULL,
    avg_cpu_utilization DECIMAL(7,3),
    max_cpu_utilization DECIMAL(7,3),
    sum_network_in_bytes BIGINT,
    sum_network_out_bytes BIGINT,
    sum_disk_read_bytes BIGINT,
    sum_disk_write_bytes BIGINT,
    sample_count INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_vm_metric_daily_vm FOREIGN KEY (vm_id) REFERENCES vm(vm_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_vm_metric_daily ON vm_metric_daily(vm_id, bucket_date);
CREATE INDEX idx_vm_metric_daily_date ON vm_metric_daily(bucket_date);

CREATE TABLE vm_metric_sample_archive (
    metric_sample_archive_id VARCHAR(36) PRIMARY KEY,
    original_metric_sample_id VARCHAR(36),
    vm_id VARCHAR(36) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    provider_vm_id VARCHAR(255) NOT NULL,
    sample_time TIMESTAMP NOT NULL,
    period_seconds INT NOT NULL,
    cpu_utilization DECIMAL(7,3),
    network_in_bytes BIGINT,
    network_out_bytes BIGINT,
    disk_read_bytes BIGINT,
    disk_write_bytes BIGINT,
    status_at_sample VARCHAR(32),
    created_at TIMESTAMP NOT NULL,
    archived_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_vm_metric_archive_vm FOREIGN KEY (vm_id) REFERENCES vm(vm_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_vm_metric_sample_archive ON vm_metric_sample_archive(vm_id, sample_time, period_seconds);
CREATE INDEX idx_vm_metric_archive_vm_time ON vm_metric_sample_archive(vm_id, sample_time);
CREATE INDEX idx_vm_metric_archive_time ON vm_metric_sample_archive(sample_time);
