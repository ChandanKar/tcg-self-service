-- =====================================================================================
-- V2: default rows a new install needs
--
-- Only reference rows: system settings and the scheduled-job rows (both from the old V1)
-- and the default AWS account (old V70). No users, environments, VMs or teams: those are
-- loaded after the install (docs/runbook-new-install.md). The first admin is whoever
-- signs in with APP_INITIAL_ADMIN_EMAIL while no active admin exists.
-- =====================================================================================

INSERT INTO system_config (config_id, config_key, config_value, description) VALUES
('cfg-001', 'state_sync_interval_seconds', '300', 'Interval for VM state synchronization with cloud providers'),
('cfg-002', 'lock_warning_threshold_hours', '4', 'Hours after which to warn about long-held locks'),
('cfg-003', 'max_concurrent_operations', '20', 'Maximum number of concurrent VM operations'),
('cfg-004', 'notification_retention_days', '90', 'Days to retain notifications before cleanup'),
('cfg-005', 'audit_log_retention_days', '365', 'Days to retain audit logs'),
('cfg-006', 'session_timeout_minutes', '480', 'User session timeout in minutes');

INSERT INTO scheduled_job (job_id, job_type, schedule_cron, is_enabled, configuration) VALUES
('job-001', 'state_sync', '0 */5 * * * *', TRUE, '{"batch_size": 100}'),
('job-002', 'notification_cleanup', '0 0 2 * * *', TRUE, '{"retention_days": 90}'),
('job-003', 'lock_warning', '0 0 * * * *', TRUE, '{"warning_threshold_hours": 4}'),
('job-004', 'access_expiration', '0 0 1 * * *', TRUE, '{"check_expired_access": true}');

INSERT INTO aws_account (aws_account_ref, display_name, is_default, is_billing_account, source)
VALUES ('00000000-0000-0000-0000-000000000001', 'Default account', TRUE, TRUE, 'DEFAULT');
