-- V46 (E17-T04): every instance-type change, applied in the app or detected by inventory sync,
-- so the savings ledger can credit rightsizing (rate difference x hours run afterwards).

CREATE TABLE vm_resize_event (
    event_id           VARCHAR(36) NOT NULL PRIMARY KEY,
    vm_id              VARCHAR(36) NOT NULL,
    old_instance_type  VARCHAR(64) NOT NULL,
    new_instance_type  VARCHAR(64) NOT NULL,
    source             VARCHAR(20) NOT NULL,
    changed_at         TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_by_user_id VARCHAR(36) NULL,
    CONSTRAINT fk_resize_event_vm FOREIGN KEY (vm_id) REFERENCES vm (vm_id) ON DELETE CASCADE,
    CONSTRAINT fk_resize_event_user FOREIGN KEY (changed_by_user_id) REFERENCES app_user (user_id) ON DELETE SET NULL
);

CREATE INDEX ix_resize_event_vm_changed ON vm_resize_event (vm_id, changed_at);
