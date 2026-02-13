-- =====================================================
-- VM Self-Service Platform - Initial Database Schema
-- Flyway Migration: V1__initial_schema.sql
-- Compatible with: H2, MySQL 5.7+
-- =====================================================

-- =====================================================
-- 1. USER MANAGEMENT
-- =====================================================

-- User table - automatically registered via Azure AD
-- Note: Using 'app_user' instead of 'user' as 'user' is a reserved keyword in H2
CREATE TABLE app_user (
    user_id VARCHAR(36) PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    display_name VARCHAR(255) NOT NULL,
    azure_ad_object_id VARCHAR(255) NOT NULL UNIQUE,
    admin BOOLEAN NOT NULL DEFAULT FALSE,
    env_admin BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP NULL
);

CREATE INDEX idx_user_email ON app_user(email);
CREATE INDEX idx_user_azure_ad_object_id ON app_user(azure_ad_object_id);
CREATE INDEX idx_user_admin ON app_user(admin);
CREATE INDEX idx_user_env_admin ON app_user(env_admin);

-- =====================================================
-- 2. ENVIRONMENT HIERARCHY
-- =====================================================

-- Environment - top level organizational unit
CREATE TABLE environment (
    environment_id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    display_name VARCHAR(255) NOT NULL,
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    metadata TEXT  -- JSON stored as TEXT for H2 compatibility
);

CREATE INDEX idx_environment_name ON environment(name);
CREATE INDEX idx_environment_is_active ON environment(is_active);

-- Environment Access - user to environment mapping
CREATE TABLE environment_access (
    access_id VARCHAR(36) PRIMARY KEY,
    environment_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    access_level VARCHAR(20) NOT NULL,  -- 'viewer', 'user', 'admin'
    granted_by_user_id VARCHAR(36) NOT NULL,
    granted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NULL,
    revoked_at TIMESTAMP NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active',  -- 'pending', 'active', 'revoked', 'expired'
    notes TEXT,
    FOREIGN KEY (environment_id) REFERENCES environment(environment_id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES app_user(user_id) ON DELETE CASCADE,
    FOREIGN KEY (granted_by_user_id) REFERENCES app_user(user_id)
);

-- Only one active access per user per environment
-- Note: For H2 compatibility, we use composite unique instead of filtered index
-- Application layer should enforce: only one status='active' per user+environment
CREATE UNIQUE INDEX idx_environment_access_env_user ON environment_access(environment_id, user_id, status);

CREATE INDEX idx_environment_access_user ON environment_access(user_id, status);
CREATE INDEX idx_environment_access_env ON environment_access(environment_id);
CREATE INDEX idx_environment_access_expires ON environment_access(expires_at);

-- Environment Access Request - workflow for requesting access
CREATE TABLE environment_access_request (
    request_id VARCHAR(36) PRIMARY KEY,
    environment_id VARCHAR(36) NOT NULL,
    requester_user_id VARCHAR(36) NOT NULL,
    requested_access_level VARCHAR(20) NOT NULL,  -- 'viewer', 'user', 'admin'
    business_justification TEXT NOT NULL,
    duration_days INT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',  -- 'pending', 'approved', 'denied', 'cancelled'
    reviewed_by_user_id VARCHAR(36) NULL,
    reviewed_at TIMESTAMP NULL,
    review_decision_notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    auto_expire_at TIMESTAMP NULL,
    FOREIGN KEY (environment_id) REFERENCES environment(environment_id) ON DELETE CASCADE,
    FOREIGN KEY (requester_user_id) REFERENCES app_user(user_id) ON DELETE CASCADE,
    FOREIGN KEY (reviewed_by_user_id) REFERENCES app_user(user_id)
);

CREATE INDEX idx_env_access_request_status ON environment_access_request(status, created_at);
CREATE INDEX idx_env_access_request_env ON environment_access_request(environment_id, status);
CREATE INDEX idx_env_access_request_requester ON environment_access_request(requester_user_id);

-- Group - logical collection of VMs within environment
CREATE TABLE vm_group (
    group_id VARCHAR(36) PRIMARY KEY,
    environment_id VARCHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    description TEXT,
    sequence_position INT NOT NULL,
    depends_on_group_ids TEXT,  -- JSON array stored as TEXT
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    metadata TEXT,  -- JSON
    FOREIGN KEY (environment_id) REFERENCES environment(environment_id) ON DELETE CASCADE
);

-- Unique name and sequence per environment
CREATE UNIQUE INDEX idx_group_env_name ON vm_group(environment_id, name);
CREATE UNIQUE INDEX idx_group_env_sequence ON vm_group(environment_id, sequence_position);
CREATE INDEX idx_group_env ON vm_group(environment_id);

-- =====================================================
-- 3. VM MANAGEMENT
-- =====================================================

-- VM - individual virtual machine
CREATE TABLE vm (
    vm_id VARCHAR(36) PRIMARY KEY,
    group_id VARCHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    description TEXT,
    provider VARCHAR(20) NOT NULL,  -- 'AWS', 'GCP', 'AZURE', 'OCI'
    region VARCHAR(100) NOT NULL,
    provider_vm_id VARCHAR(255) NOT NULL,
    vm_type VARCHAR(20) NOT NULL DEFAULT 'dev',  -- 'dev', 'test', 'staging'
    sequence_position INT NOT NULL,
    depends_on_vm_ids TEXT,  -- JSON array, VMs in same group only
    status VARCHAR(20) NOT NULL DEFAULT 'unknown',  -- 'running', 'stopped', 'starting', 'stopping', 'error', 'unknown'
    last_known_state VARCHAR(20),
    last_state_sync_at TIMESTAMP NULL,
    state_drift_detected BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    metadata TEXT,  -- JSON
    FOREIGN KEY (group_id) REFERENCES vm_group(group_id) ON DELETE CASCADE
);

-- Unique constraints
CREATE UNIQUE INDEX idx_vm_provider_id ON vm(provider, provider_vm_id);
CREATE UNIQUE INDEX idx_vm_group_name ON vm(group_id, name);
CREATE UNIQUE INDEX idx_vm_group_sequence ON vm(group_id, sequence_position);

-- Performance indexes
CREATE INDEX idx_vm_status ON vm(status);
CREATE INDEX idx_vm_provider ON vm(provider);
CREATE INDEX idx_vm_drift ON vm(state_drift_detected);
CREATE INDEX idx_vm_last_sync ON vm(last_state_sync_at);

-- VM Provider Details - cloud provider specific information
CREATE TABLE vm_provider_details (
    vm_provider_detail_id VARCHAR(36) PRIMARY KEY,
    vm_id VARCHAR(36) NOT NULL UNIQUE,
    provider VARCHAR(20) NOT NULL,
    region_code VARCHAR(100) NOT NULL,
    region_name VARCHAR(255),
    availability_zone VARCHAR(100),
    instance_type VARCHAR(100),
    cpu_cores INT,
    memory_gb DECIMAL(10,2),
    network_interfaces TEXT,  -- JSON
    storage_volumes TEXT,  -- JSON
    tags TEXT,  -- JSON - AWS tags
    labels TEXT,  -- JSON - GCP labels, general purpose
    provider_console_url VARCHAR(500),
    private_ip VARCHAR(50),
    public_ip VARCHAR(50),
    vpc_id VARCHAR(255),
    subnet_id VARCHAR(255),
    security_groups TEXT,  -- JSON
    iam_profile VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (vm_id) REFERENCES vm(vm_id) ON DELETE CASCADE
);

CREATE INDEX idx_vm_provider_details_provider ON vm_provider_details(provider);
CREATE INDEX idx_vm_provider_details_region ON vm_provider_details(region_code);

-- =====================================================
-- 4. LOCK MANAGEMENT
-- =====================================================

-- Environment Lock - environment-wide exclusive lock
CREATE TABLE environment_lock (
    lock_id VARCHAR(36) PRIMARY KEY,
    environment_id VARCHAR(36) NOT NULL,
    locked_by_user_id VARCHAR(36) NOT NULL,
    locked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lock_reason TEXT,
    expected_duration_minutes INT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    released_at TIMESTAMP NULL,
    released_by_user_id VARCHAR(36) NULL,
    broken_by_admin_user_id VARCHAR(36) NULL,
    break_reason TEXT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (environment_id) REFERENCES environment(environment_id) ON DELETE CASCADE,
    FOREIGN KEY (locked_by_user_id) REFERENCES app_user(user_id),
    FOREIGN KEY (released_by_user_id) REFERENCES app_user(user_id),
    FOREIGN KEY (broken_by_admin_user_id) REFERENCES app_user(user_id)
);

-- Only one active lock per environment
-- Note: For H2 compatibility, enforce programmatically or use composite unique
-- This index allows lookup but application must ensure single active lock
CREATE INDEX idx_environment_lock_env_active ON environment_lock(environment_id, is_active);

CREATE INDEX idx_environment_lock_user ON environment_lock(locked_by_user_id);
CREATE INDEX idx_environment_lock_active_idx ON environment_lock(is_active, locked_at);

-- Lock History - audit trail for lock operations
CREATE TABLE lock_history (
    history_id VARCHAR(36) PRIMARY KEY,
    lock_id VARCHAR(36) NOT NULL,
    environment_id VARCHAR(36) NOT NULL,
    action VARCHAR(20) NOT NULL,  -- 'acquired', 'released', 'broken', 'expired'
    performed_by_user_id VARCHAR(36) NOT NULL,
    performed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    notes TEXT,
    metadata TEXT,  -- JSON
    FOREIGN KEY (lock_id) REFERENCES environment_lock(lock_id) ON DELETE CASCADE,
    FOREIGN KEY (environment_id) REFERENCES environment(environment_id) ON DELETE CASCADE,
    FOREIGN KEY (performed_by_user_id) REFERENCES app_user(user_id)
);

CREATE INDEX idx_lock_history_lock ON lock_history(lock_id, performed_at DESC);
CREATE INDEX idx_lock_history_env ON lock_history(environment_id, performed_at DESC);
CREATE INDEX idx_lock_history_performed_at ON lock_history(performed_at DESC);

-- =====================================================
-- 5. OPERATION TRACKING
-- =====================================================

-- Operation Execution - tracks multi-step operations
CREATE TABLE operation_execution (
    execution_id VARCHAR(36) PRIMARY KEY,
    environment_id VARCHAR(36) NOT NULL,
    operation_type VARCHAR(50) NOT NULL,  -- 'start_environment', 'stop_environment', 'start_group', 'stop_group', 'start_vm', 'stop_vm'
    initiated_by_user_id VARCHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',  -- 'pending', 'in_progress', 'completed', 'failed', 'cancelled'
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL,
    error_message TEXT,
    total_targets INT NOT NULL DEFAULT 0,
    completed_targets INT NOT NULL DEFAULT 0,
    failed_targets INT NOT NULL DEFAULT 0,
    execution_plan TEXT,  -- JSON - stores the sequence plan
    FOREIGN KEY (environment_id) REFERENCES environment(environment_id) ON DELETE CASCADE,
    FOREIGN KEY (initiated_by_user_id) REFERENCES app_user(user_id)
);

CREATE INDEX idx_operation_execution_env ON operation_execution(environment_id, started_at DESC);
CREATE INDEX idx_operation_execution_user ON operation_execution(initiated_by_user_id, started_at DESC);
CREATE INDEX idx_operation_execution_status ON operation_execution(status, started_at DESC);

-- Operation Detail - individual VM/group operations within execution
CREATE TABLE operation_detail (
    detail_id VARCHAR(36) PRIMARY KEY,
    execution_id VARCHAR(36) NOT NULL,
    target_type VARCHAR(20) NOT NULL,  -- 'vm', 'group'
    target_id VARCHAR(36) NOT NULL,
    target_name VARCHAR(255) NOT NULL,
    action VARCHAR(20) NOT NULL,  -- 'start', 'stop'
    sequence_position INT NOT NULL,
    depends_on_detail_ids TEXT,  -- JSON array
    status VARCHAR(20) NOT NULL DEFAULT 'pending',  -- 'pending', 'waiting', 'in_progress', 'completed', 'failed', 'skipped'
    started_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    cloud_operation_id VARCHAR(255),
    error_code VARCHAR(50),
    error_message TEXT,
    metadata TEXT,  -- JSON
    FOREIGN KEY (execution_id) REFERENCES operation_execution(execution_id) ON DELETE CASCADE
);

CREATE INDEX idx_operation_detail_execution ON operation_detail(execution_id, sequence_position);
CREATE INDEX idx_operation_detail_status ON operation_detail(status);
CREATE INDEX idx_operation_detail_target ON operation_detail(target_type, target_id);

-- =====================================================
-- 6. NOTIFICATIONS
-- =====================================================

-- Notification - in-app notifications (bell icon)
CREATE TABLE notification (
    notification_id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    notification_type VARCHAR(50) NOT NULL,
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    severity VARCHAR(20) NOT NULL DEFAULT 'info',  -- 'info', 'warning', 'error', 'success'
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    is_dismissed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMP NULL,
    related_entity_type VARCHAR(50),  -- 'environment', 'vm', 'group', 'lock', 'access_request'
    related_entity_id VARCHAR(36),
    action_url VARCHAR(500),
    metadata TEXT,  -- JSON
    FOREIGN KEY (user_id) REFERENCES app_user(user_id) ON DELETE CASCADE
);

CREATE INDEX idx_notification_user_read ON notification(user_id, is_read, created_at DESC);
CREATE INDEX idx_notification_user_dismissed ON notification(user_id, is_dismissed, created_at DESC);
CREATE INDEX idx_notification_type ON notification(notification_type, created_at DESC);
CREATE INDEX idx_notification_created ON notification(created_at DESC);

-- User Notification Preference - notification settings
CREATE TABLE user_notification_preference (
    preference_id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    notification_type VARCHAR(50) NOT NULL,
    in_app_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    email_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES app_user(user_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX idx_user_notif_pref_unique ON user_notification_preference(user_id, notification_type);

-- =====================================================
-- 7. AUDIT & HISTORY
-- =====================================================

-- Audit Log - comprehensive audit trail
CREATE TABLE audit_log (
    audit_id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36),  -- NULL for system actions
    environment_id VARCHAR(36),  -- NULL for global actions
    action_type VARCHAR(50) NOT NULL,
    target_type VARCHAR(50),  -- 'environment', 'group', 'vm', 'lock', 'access_request', 'user'
    target_id VARCHAR(36),
    target_name VARCHAR(255),
    action_status VARCHAR(20) NOT NULL,  -- 'initiated', 'succeeded', 'failed'
    details TEXT,  -- JSON - action-specific data
    ip_address VARCHAR(50),
    user_agent VARCHAR(500),
    session_id VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    error_message TEXT,
    FOREIGN KEY (user_id) REFERENCES app_user(user_id) ON DELETE SET NULL,
    FOREIGN KEY (environment_id) REFERENCES environment(environment_id) ON DELETE SET NULL
);

CREATE INDEX idx_audit_log_user ON audit_log(user_id, created_at DESC);
CREATE INDEX idx_audit_log_env ON audit_log(environment_id, created_at DESC);
CREATE INDEX idx_audit_log_action_type ON audit_log(action_type, created_at DESC);
CREATE INDEX idx_audit_log_created_at ON audit_log(created_at DESC);
CREATE INDEX idx_audit_log_target ON audit_log(target_type, target_id);

-- VM State History - track VM state changes
CREATE TABLE vm_state_history (
    history_id VARCHAR(36) PRIMARY KEY,
    vm_id VARCHAR(36) NOT NULL,
    previous_status VARCHAR(20),
    new_status VARCHAR(20) NOT NULL,
    change_source VARCHAR(20) NOT NULL,  -- 'user_action', 'state_sync', 'cloud_event', 'system'
    changed_by_user_id VARCHAR(36),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    metadata TEXT,  -- JSON
    FOREIGN KEY (vm_id) REFERENCES vm(vm_id) ON DELETE CASCADE,
    FOREIGN KEY (changed_by_user_id) REFERENCES app_user(user_id) ON DELETE SET NULL
);

CREATE INDEX idx_vm_state_history_vm ON vm_state_history(vm_id, created_at DESC);
CREATE INDEX idx_vm_state_history_created_at ON vm_state_history(created_at DESC);

-- Login Session - track SSH/RDP sessions to VMs
CREATE TABLE login_session (
    session_id VARCHAR(36) PRIMARY KEY,
    vm_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    session_type VARCHAR(20) NOT NULL,  -- 'ssh', 'rdp', 'console'
    source_ip VARCHAR(50),
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMP NULL,
    duration_minutes INT,
    metadata TEXT,  -- JSON - encrypted if contains PII
    FOREIGN KEY (vm_id) REFERENCES vm(vm_id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES app_user(user_id) ON DELETE CASCADE
);

CREATE INDEX idx_login_session_vm ON login_session(vm_id, started_at DESC);
CREATE INDEX idx_login_session_user ON login_session(user_id, started_at DESC);
-- Track active sessions (ended_at IS NULL) - enforce uniqueness in application
CREATE INDEX idx_login_session_ended ON login_session(vm_id, ended_at);

-- =====================================================
-- 8. SYSTEM CONFIGURATION
-- =====================================================

-- System Config - platform-wide configuration
CREATE TABLE system_config (
    config_id VARCHAR(36) PRIMARY KEY,
    config_key VARCHAR(255) NOT NULL UNIQUE,
    config_value TEXT NOT NULL,  -- JSON
    description TEXT,
    updated_by_user_id VARCHAR(36),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (updated_by_user_id) REFERENCES app_user(user_id)
);

CREATE INDEX idx_system_config_key ON system_config(config_key);

-- Cloud Provider Credential - encrypted credentials
CREATE TABLE cloud_provider_credential (
    credential_id VARCHAR(36) PRIMARY KEY,
    provider VARCHAR(20) NOT NULL,
    credential_name VARCHAR(255) NOT NULL,
    credential_type VARCHAR(50) NOT NULL,
    encrypted_credentials TEXT NOT NULL,  -- Encrypted JSON
    encryption_key_version INT NOT NULL DEFAULT 1,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by_user_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    rotated_at TIMESTAMP NULL,
    expires_at TIMESTAMP NULL,
    FOREIGN KEY (created_by_user_id) REFERENCES app_user(user_id)
);

CREATE INDEX idx_cloud_credential_provider ON cloud_provider_credential(provider, is_active);

-- =====================================================
-- 9. JOB SCHEDULING
-- =====================================================

-- Scheduled Job - background job definitions
CREATE TABLE scheduled_job (
    job_id VARCHAR(36) PRIMARY KEY,
    job_type VARCHAR(50) NOT NULL,
    schedule_cron VARCHAR(100) NOT NULL,
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_run_at TIMESTAMP NULL,
    last_run_status VARCHAR(20),  -- 'success', 'failed', 'running'
    next_run_at TIMESTAMP,
    configuration TEXT,  -- JSON
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_scheduled_job_enabled ON scheduled_job(is_enabled, next_run_at);
CREATE INDEX idx_scheduled_job_type ON scheduled_job(job_type);

-- Job Execution Log - track scheduled job runs
CREATE TABLE job_execution_log (
    execution_id VARCHAR(36) PRIMARY KEY,
    job_id VARCHAR(36) NOT NULL,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL,
    status VARCHAR(20) NOT NULL,  -- 'running', 'completed', 'failed'
    records_processed INT NOT NULL DEFAULT 0,
    error_message TEXT,
    execution_details TEXT,  -- JSON
    FOREIGN KEY (job_id) REFERENCES scheduled_job(job_id) ON DELETE CASCADE
);

CREATE INDEX idx_job_execution_log_job ON job_execution_log(job_id, started_at DESC);
CREATE INDEX idx_job_execution_log_status ON job_execution_log(status, started_at DESC);

-- =====================================================
-- INITIAL DATA / SEED DATA
-- =====================================================

-- Insert default system configurations
INSERT INTO system_config (config_id, config_key, config_value, description) VALUES
('cfg-001', 'state_sync_interval_seconds', '300', 'Interval for VM state synchronization with cloud providers'),
('cfg-002', 'lock_warning_threshold_hours', '4', 'Hours after which to warn about long-held locks'),
('cfg-003', 'max_concurrent_operations', '20', 'Maximum number of concurrent VM operations'),
('cfg-004', 'notification_retention_days', '90', 'Days to retain notifications before cleanup'),
('cfg-005', 'audit_log_retention_days', '365', 'Days to retain audit logs'),
('cfg-006', 'session_timeout_minutes', '480', 'User session timeout in minutes');

-- Insert default scheduled jobs
INSERT INTO scheduled_job (job_id, job_type, schedule_cron, is_enabled, configuration) VALUES
('job-001', 'state_sync', '0 */5 * * * *', TRUE, '{"batch_size": 100}'),
('job-002', 'notification_cleanup', '0 0 2 * * *', TRUE, '{"retention_days": 90}'),
('job-003', 'lock_warning', '0 0 * * * *', TRUE, '{"warning_threshold_hours": 4}'),
('job-004', 'access_expiration', '0 0 1 * * *', TRUE, '{"check_expired_access": true}');

-- =====================================================
-- END OF SCHEMA
-- =====================================================
