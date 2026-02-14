-- Reset all test data in correct order (respecting FK constraints)
SET REFERENTIAL_INTEGRITY FALSE;
DELETE FROM job_execution_log;
DELETE FROM scheduled_job;
DELETE FROM cloud_provider_credential;
DELETE FROM system_config;
DELETE FROM login_session;
DELETE FROM vm_state_history;
DELETE FROM audit_log;
DELETE FROM user_notification_preference;
DELETE FROM notification;
DELETE FROM operation_detail;
DELETE FROM operation_execution;
DELETE FROM lock_history;
DELETE FROM environment_lock;
DELETE FROM vm_provider_details;
DELETE FROM vm;
DELETE FROM vm_group;
DELETE FROM environment_access_request;
DELETE FROM environment_access;
DELETE FROM environment;
DELETE FROM app_user;
SET REFERENTIAL_INTEGRITY TRUE;

-- Insert test users for lock management tests
INSERT INTO app_user (user_id, email, display_name, azure_ad_object_id, admin, env_admin, is_active) VALUES
('user-001', 'user001@test.com', 'Test User 1', 'azure-001', FALSE, FALSE, TRUE),
('user-002', 'user002@test.com', 'Test User 2', 'azure-002', FALSE, FALSE, TRUE),
('admin-001', 'admin001@test.com', 'Test Admin', 'azure-admin-001', TRUE, TRUE, TRUE),
('dev-user-001', 'devuser@test.com', 'Dev User', 'azure-dev-001', FALSE, FALSE, TRUE);

