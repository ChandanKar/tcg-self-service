-- V30: Recover executions orphaned by a restart (H12).
-- last_heartbeat_at: refreshed while a worker runs the execution; a run with no heartbeat for
--                    vm.operations.stale-after-minutes is failed instead of blocking the
--                    environment with "already in progress" forever.
-- executor_id:       the node (hostname-pid) running it, so a restarted node fails its own
--                    interrupted runs at startup.
ALTER TABLE operation_execution
    ADD COLUMN last_heartbeat_at TIMESTAMP NULL,
    ADD COLUMN executor_id VARCHAR(128) NULL;
