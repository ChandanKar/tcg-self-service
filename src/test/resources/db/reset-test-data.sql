-- Reset all test data in correct order (respecting FK constraints)
SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM job_execution_log;
DELETE FROM scheduled_job;
DELETE FROM cloud_provider_credential;
DELETE FROM system_config;
DELETE FROM app_setting;
DELETE FROM login_session;
DELETE FROM vm_state_history;
DELETE FROM audit_log;
DELETE FROM user_notification_preference;
DELETE FROM notification;
DELETE FROM operation_detail;
DELETE FROM operation_execution;
DELETE FROM savings_ledger_entry;
DELETE FROM efficiency_snapshot;
DELETE FROM cost_daily_snapshot;
DELETE FROM vm_resize_event;
DELETE FROM reservation_coverage_snapshot;
DELETE FROM aws_cost_forecast;
DELETE FROM environment_carbon_monthly;
DELETE FROM carbon_monthly;
DELETE FROM carbon_ingest_file;
DELETE FROM lease_extension_request;
DELETE FROM environment_lease_participant;
DELETE FROM environment_lease;
DELETE FROM waste_action;
DELETE FROM waste_finding;
DELETE FROM cost_anomaly;
DELETE FROM cost_anomaly_monitor;
DELETE FROM budget_action_request;
DELETE FROM budget_alert;
DELETE FROM budget;
DELETE FROM automation_rule_override;
DELETE FROM automation_rule_window;
DELETE FROM automation_rule;
DELETE FROM holiday_date;
DELETE FROM holiday_calendar;
DELETE FROM lock_history;
DELETE FROM environment_lock;
DELETE FROM vm_provider_details;
-- Metric rows belong to VMs; with FK checks off they would otherwise outlive them (E12-T01).
DELETE FROM vm_metric_sample;
DELETE FROM vm_metric_sample_archive;
DELETE FROM vm_metric_daily;
DELETE FROM compute_optimizer_option;
DELETE FROM compute_optimizer_recommendation;
DELETE FROM spot_price_snapshot;
DELETE FROM provisioning_request;
DELETE FROM environment_template;
DELETE FROM vm;
DELETE FROM vm_group;
DELETE FROM environment_access_request;
DELETE FROM environment_access;
DELETE FROM environment;
DELETE FROM chargeback_statement;
DELETE FROM chat_action_token;
DELETE FROM api_token;
DELETE FROM webhook_delivery;
DELETE FROM webhook_subscription;
DELETE FROM external_ticket;
DELETE FROM approval_decision;
DELETE FROM approval_request_approver;
DELETE FROM approval_request;
DELETE FROM approval_policy_approver;
DELETE FROM approval_policy;
DELETE FROM chat_delivery_log;
DELETE FROM chat_channel;
DELETE FROM cost_split_share;
DELETE FROM cost_split_rule;
DELETE FROM team_quota_alert;
DELETE FROM team_quota;
DELETE FROM team_member;
DELETE FROM team;
DELETE FROM app_user;
SET FOREIGN_KEY_CHECKS = 1;

-- Insert test users for lock management tests
INSERT INTO app_user (user_id, email, display_name, azure_ad_object_id, admin, env_admin, is_active) VALUES
('user-001', 'user001@test.com', 'Test User 1', 'azure-001', FALSE, FALSE, TRUE),
('user-002', 'user002@test.com', 'Test User 2', 'azure-002', FALSE, FALSE, TRUE),
('admin-001', 'admin001@test.com', 'Test Admin', 'azure-admin-001', TRUE, TRUE, TRUE),
('dev-user-001', 'devuser@test.com', 'Dev User', 'azure-dev-001', FALSE, FALSE, TRUE);

-- Restore system config seed data (mirrors V1 migration inserts)
INSERT INTO system_config (config_id, config_key, config_value, description) VALUES
('cfg-001', 'state_sync_interval_seconds', '300', 'Interval for VM state synchronization with cloud providers'),
('cfg-002', 'lock_warning_threshold_hours', '4', 'Hours after which to warn about long-held locks'),
('cfg-003', 'max_concurrent_operations', '20', 'Maximum number of concurrent VM operations'),
('cfg-004', 'notification_retention_days', '90', 'Days to retain notifications before cleanup'),
('cfg-005', 'audit_log_retention_days', '365', 'Days to retain audit logs'),
('cfg-006', 'session_timeout_minutes', '480', 'User session timeout in minutes');

-- Restore scheduled job seed data (mirrors V1 migration inserts)
INSERT INTO scheduled_job (job_id, job_type, schedule_cron, is_enabled, configuration) VALUES
('job-001', 'state_sync', '0 */5 * * * *', TRUE, '{"batch_size": 100}'),
('job-002', 'notification_cleanup', '0 0 2 * * *', TRUE, '{"retention_days": 90}'),
('job-003', 'lock_warning', '0 0 * * * *', TRUE, '{"warning_threshold_hours": 4}'),
('job-004', 'access_expiration', '0 0 1 * * *', TRUE, '{"check_expired_access": true}');