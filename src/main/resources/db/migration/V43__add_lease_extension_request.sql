-- V43 (E15-T04): extension requests above an environment's maximum lease, decided by its owners.

CREATE TABLE lease_extension_request (
    request_id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    lease_id            VARCHAR(36)  NOT NULL,
    requester_user_id   VARCHAR(36)  NOT NULL,
    minutes             INT          NOT NULL,
    reason              VARCHAR(500) NULL,
    status              VARCHAR(20)  NOT NULL,
    reviewed_by_user_id VARCHAR(36)  NULL,
    reviewed_at         TIMESTAMP    NULL,
    review_notes        VARCHAR(500) NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- The lease id while PENDING, NULL otherwise: one pending request per lease (unique index).
    -- VIRTUAL for the same reason as V36/V42 (cascading foreign key on lease_id).
    pending_lease_id    VARCHAR(36)
        GENERATED ALWAYS AS (CASE WHEN status = 'PENDING' THEN lease_id ELSE NULL END) VIRTUAL,
    CONSTRAINT fk_lease_ext_lease FOREIGN KEY (lease_id) REFERENCES environment_lease (lease_id) ON DELETE CASCADE,
    CONSTRAINT fk_lease_ext_requester FOREIGN KEY (requester_user_id) REFERENCES app_user (user_id),
    CONSTRAINT fk_lease_ext_reviewer FOREIGN KEY (reviewed_by_user_id) REFERENCES app_user (user_id)
);

CREATE INDEX ix_lease_ext_lease_status ON lease_extension_request (lease_id, status);
CREATE UNIQUE INDEX ux_lease_ext_one_pending ON lease_extension_request (pending_lease_id);
