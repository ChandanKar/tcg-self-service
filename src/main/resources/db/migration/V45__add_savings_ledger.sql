-- V45 (E17-T02): the savings ledger. One row per VM, day and cause: hours the VM was stopped inside
-- its baseline, what that saved at On-Demand rates, and what it still cost (EBS, public IPv4) or
-- did not save because commitments already covered it.

CREATE TABLE savings_ledger_entry (
    entry_id                    VARCHAR(36)   NOT NULL PRIMARY KEY,
    ledger_date                 DATE          NOT NULL,
    vm_id                       VARCHAR(36)   NOT NULL,
    environment_id              VARCHAR(36)   NOT NULL,
    team                        VARCHAR(100)  NULL,
    cause                       VARCHAR(20)   NOT NULL,
    avoided_hours               DECIMAL(8,3)  NOT NULL,
    hourly_rate                 DECIMAL(10,5) NULL,
    gross_saving                DECIMAL(12,2) NOT NULL,
    residual_storage_cost       DECIMAL(12,2) NOT NULL DEFAULT 0,
    residual_ipv4_cost          DECIMAL(12,2) NOT NULL DEFAULT 0,
    commitment_coverage_percent DECIMAL(5,2)  NULL,
    commitment_adjustment       DECIMAL(12,2) NOT NULL DEFAULT 0,
    net_saving                  DECIMAL(12,2) NOT NULL,
    price_approximate           BOOLEAN       NOT NULL DEFAULT FALSE,
    note                        VARCHAR(255)  NULL,
    created_at                  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_savings_vm FOREIGN KEY (vm_id) REFERENCES vm (vm_id) ON DELETE CASCADE,
    CONSTRAINT fk_savings_environment FOREIGN KEY (environment_id) REFERENCES environment (environment_id) ON DELETE CASCADE,
    CONSTRAINT uq_savings_vm_day_cause UNIQUE (vm_id, ledger_date, cause)
);

CREATE INDEX ix_savings_ledger_date ON savings_ledger_entry (ledger_date);
CREATE INDEX ix_savings_environment_date ON savings_ledger_entry (environment_id, ledger_date);
