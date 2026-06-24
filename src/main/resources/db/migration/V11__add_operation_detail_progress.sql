-- Add per-target operation progress fields for live start/stop status.

ALTER TABLE operation_detail
    ADD COLUMN stage_label VARCHAR(120);

ALTER TABLE operation_detail
    ADD COLUMN progress_percentage INT NOT NULL DEFAULT 0;

ALTER TABLE operation_detail
    ADD COLUMN status_checks_passed INT;

ALTER TABLE operation_detail
    ADD COLUMN status_checks_total INT;
