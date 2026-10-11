-- E26-T04: pod-level EKS cost from Split Cost Allocation Data (CUR 2.0 via Athena), one row per
-- environment, usage day, Kubernetes namespace and workload. split_cost is what the pods'
-- requests were charged; unused_cost is node capacity paid for but not requested.

CREATE TABLE eks_workload_cost_daily (
    environment_id VARCHAR(36) NOT NULL,
    usage_date DATE NOT NULL,
    namespace VARCHAR(253) NOT NULL,
    workload VARCHAR(253) NOT NULL,
    split_cost DECIMAL(14,4) NOT NULL DEFAULT 0,
    unused_cost DECIMAL(14,4) NOT NULL DEFAULT 0,
    ingested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (environment_id, usage_date, namespace, workload),
    CONSTRAINT fk_eks_workload_cost_env FOREIGN KEY (environment_id) REFERENCES environment(environment_id) ON DELETE CASCADE
);

CREATE INDEX idx_eks_workload_cost_date ON eks_workload_cost_daily (usage_date);
