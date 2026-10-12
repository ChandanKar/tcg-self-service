-- E30-T06: a safe resize (stop, change type, start, verify, roll back on failure) is one
-- vm_resize_event that moves through statuses. Rows written before are completed resizes.
ALTER TABLE vm_resize_event
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    ADD COLUMN stage VARCHAR(100) NULL,
    ADD COLUMN error_message TEXT NULL,
    ADD COLUMN restart_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN original_status VARCHAR(20) NULL,
    ADD COLUMN updated_at TIMESTAMP NULL;
