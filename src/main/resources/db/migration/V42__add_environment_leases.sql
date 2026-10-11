-- V42 (E15-T01): environment leases. A lease gives a running, opted-in environment an end time;
-- the people keeping it alive are its participants. The whole feature ships off (lease.enabled)
-- and each environment opts in separately (lease_enabled defaults to FALSE).

CREATE TABLE environment_lease (
    lease_id               VARCHAR(36)  NOT NULL PRIMARY KEY,
    environment_id         VARCHAR(36)  NOT NULL,
    status                 VARCHAR(20)  NOT NULL,
    source                 VARCHAR(20)  NOT NULL,
    lock_id                VARCHAR(36)  NULL,
    started_by_user_id     VARCHAR(36)  NOT NULL,
    started_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ends_at                TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at               TIMESTAMP    NULL,
    end_reason             VARCHAR(255) NULL,
    extended_minutes_total INT          NOT NULL DEFAULT 0,
    stop_execution_id      VARCHAR(36)  NULL,
    stop_attempts          INT          NOT NULL DEFAULT 0,
    version                BIGINT       NOT NULL DEFAULT 0,
    -- The environment id while ACTIVE, NULL otherwise: the unique index allows one active lease
    -- per environment. VIRTUAL because MySQL refuses a STORED generated column over a base column
    -- with a cascading foreign key (same as V36).
    active_environment_id  VARCHAR(36)
        GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN environment_id ELSE NULL END) VIRTUAL,
    CONSTRAINT fk_lease_environment FOREIGN KEY (environment_id) REFERENCES environment (environment_id) ON DELETE CASCADE,
    CONSTRAINT fk_lease_lock FOREIGN KEY (lock_id) REFERENCES environment_lock (lock_id) ON DELETE SET NULL,
    CONSTRAINT fk_lease_started_by FOREIGN KEY (started_by_user_id) REFERENCES app_user (user_id)
);

CREATE UNIQUE INDEX ux_environment_lease_one_active ON environment_lease (active_environment_id);
CREATE INDEX ix_environment_lease_status_ends ON environment_lease (status, ends_at);
CREATE INDEX ix_environment_lease_environment ON environment_lease (environment_id);

CREATE TABLE environment_lease_participant (
    lease_id    VARCHAR(36)  NOT NULL,
    user_id     VARCHAR(36)  NOT NULL,
    joined_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    left_at     TIMESTAMP    NULL,
    left_reason VARCHAR(100) NULL,
    PRIMARY KEY (lease_id, user_id),
    CONSTRAINT fk_lease_participant_lease FOREIGN KEY (lease_id) REFERENCES environment_lease (lease_id) ON DELETE CASCADE,
    CONSTRAINT fk_lease_participant_user FOREIGN KEY (user_id) REFERENCES app_user (user_id)
);

CREATE INDEX ix_lease_participant_user ON environment_lease_participant (user_id);

ALTER TABLE environment
    ADD COLUMN lease_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN lease_default_minutes INT NULL,
    ADD COLUMN lease_max_minutes INT NULL;
