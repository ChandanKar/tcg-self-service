-- V35: idle auto-stop (E16, G5). A rule per environment (or group) stops it after its running VMs
-- have been idle for idle_minutes, first in DRY_RUN (records what would have been saved), later
-- ENFORCE. Snoozes pause it; every decision is recorded as an event with its evidence.
-- environment.is_production excludes an environment from idle stop altogether.

ALTER TABLE environment ADD COLUMN is_production BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE idle_stop_rule (
    rule_id             VARCHAR(36)   NOT NULL PRIMARY KEY,
    environment_id      VARCHAR(36)   NOT NULL,
    scope_type          VARCHAR(20)   NOT NULL DEFAULT 'ENVIRONMENT',
    group_id            VARCHAR(36)   NULL,
    idle_minutes        INT           NOT NULL DEFAULT 60,
    cpu_max_percent     DECIMAL(5,2)  NOT NULL DEFAULT 5.00,
    network_mb_per_day  DECIMAL(10,2) NOT NULL DEFAULT 5.00,
    mode                VARCHAR(20)   NOT NULL DEFAULT 'DRY_RUN',
    enabled             BOOLEAN       NOT NULL DEFAULT TRUE,
    dry_run_started_at  TIMESTAMP     NULL,
    created_by_user_id  VARCHAR(36)   NOT NULL,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT fk_idle_rule_environment FOREIGN KEY (environment_id) REFERENCES environment (environment_id) ON DELETE CASCADE,
    CONSTRAINT fk_idle_rule_group FOREIGN KEY (group_id) REFERENCES vm_group (group_id) ON DELETE CASCADE,
    CONSTRAINT uq_idle_rule_scope UNIQUE (environment_id, group_id)
);

CREATE TABLE idle_stop_snooze (
    snooze_id           VARCHAR(36)   NOT NULL PRIMARY KEY,
    environment_id      VARCHAR(36)   NOT NULL,
    snoozed_until       TIMESTAMP     NOT NULL,
    snoozed_by_user_id  VARCHAR(36)   NULL,
    reason              VARCHAR(255)  NULL,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_idle_snooze_environment FOREIGN KEY (environment_id) REFERENCES environment (environment_id) ON DELETE CASCADE,
    INDEX idx_idle_snooze_env_until (environment_id, snoozed_until)
);

CREATE TABLE idle_stop_event (
    event_id                   VARCHAR(36)    NOT NULL PRIMARY KEY,
    rule_id                    VARCHAR(36)    NOT NULL,
    environment_id             VARCHAR(36)    NOT NULL,
    evaluated_at               TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    outcome                    VARCHAR(20)    NOT NULL,
    reason                     VARCHAR(255)   NULL,
    idle_since                 TIMESTAMP      NULL,
    evidence_json              TEXT           NULL,
    projected_saving_per_hour  DECIMAL(12,4)  NULL,
    execution_id               VARCHAR(36)    NULL,
    CONSTRAINT fk_idle_event_rule FOREIGN KEY (rule_id) REFERENCES idle_stop_rule (rule_id) ON DELETE CASCADE,
    INDEX idx_idle_event_env_time (environment_id, evaluated_at)
);
