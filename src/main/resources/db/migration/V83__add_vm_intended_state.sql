-- E33-T04: AWS starts a stopped RDS database again after seven days. intended_stopped_since is set
-- when the platform stops an AWS_RDS VM and cleared when the platform starts it; auto_start_detected_at
-- marks an automatic start already recorded, so it is audited once.
ALTER TABLE vm
    ADD COLUMN intended_stopped_since TIMESTAMP NULL,
    ADD COLUMN auto_start_detected_at TIMESTAMP NULL;
