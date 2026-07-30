-- V15: Automation Rules — calendar-schedule and access-grant/lock-acquire triggers
-- for auto start/stop of an environment (optionally narrowed to a group or VM).

CREATE TABLE automation_rule (
    rule_id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000) NULL,
    environment_id VARCHAR(36) NOT NULL,
    scope_type VARCHAR(20) NOT NULL,           -- ENVIRONMENT | GROUP | VM
    scope_id VARCHAR(36) NULL,                 -- group_id or vm_id; NULL for ENVIRONMENT
    trigger_type VARCHAR(20) NOT NULL,         -- SCHEDULE | ACCESS_GRANT
    days_of_week VARCHAR(30) NULL,             -- CSV e.g. MON,TUE,WED,THU,FRI (SCHEDULE only)
    stop_time VARCHAR(5) NULL,                 -- "HH:mm" (SCHEDULE only)
    start_time VARCHAR(5) NULL,                -- "HH:mm" (SCHEDULE only)
    timezone VARCHAR(64) NULL,                 -- IANA zone id (SCHEDULE only)
    access_grant_mode VARCHAR(20) NULL,        -- LOCK_ACQUIRE | ACCESS_APPROVED (ACCESS_GRANT only)
    skip_if_already_in_target_state BOOLEAN NOT NULL DEFAULT TRUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by_user_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_run_at TIMESTAMP NULL,
    last_run_status VARCHAR(20) NULL,          -- SUCCESS | SKIPPED | FAILED
    last_run_detail VARCHAR(500) NULL,
    last_stop_fired_on DATE NULL,
    last_start_fired_on DATE NULL,
    CONSTRAINT fk_automation_rule_env FOREIGN KEY (environment_id) REFERENCES environment(environment_id) ON DELETE CASCADE,
    CONSTRAINT fk_automation_rule_created_by FOREIGN KEY (created_by_user_id) REFERENCES app_user(user_id)
);

CREATE INDEX idx_automation_rule_env ON automation_rule(environment_id);
CREATE INDEX idx_automation_rule_enabled_trigger ON automation_rule(enabled, trigger_type);
CREATE INDEX idx_automation_rule_env_trigger_mode ON automation_rule(environment_id, trigger_type, access_grant_mode);
