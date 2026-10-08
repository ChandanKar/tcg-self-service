-- V28: Race-free operation executions (M8).
-- version: optimistic-lock column; status transitions are conditional UPDATEs that bump it, so a
--          stale entity save elsewhere fails fast instead of overwriting a newer status.
-- skipped_targets: steps skipped because a dependency failed were counted as failures.
ALTER TABLE operation_execution
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN skipped_targets INT NOT NULL DEFAULT 0;
