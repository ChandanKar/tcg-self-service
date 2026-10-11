-- V47 (E17-T05): a daily Cost Efficiency % point for the fleet (environment_id NULL) and for each
-- environment: 1 - potential savings / optimisable spend, with both figures kept.

CREATE TABLE efficiency_snapshot (
    snapshot_id        VARCHAR(36)   NOT NULL PRIMARY KEY,
    snapshot_date      DATE          NOT NULL,
    environment_id     VARCHAR(36)   NULL,
    team               VARCHAR(100)  NULL,
    potential_savings  DECIMAL(12,2) NOT NULL,
    optimisable_spend  DECIMAL(12,2) NOT NULL,
    efficiency_percent DECIMAL(5,1)  NULL,
    created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 'FLEET' for the fleet row, so (date, scope) is unique even though environment_id is NULL.
    -- VIRTUAL: MySQL refuses STORED over a column with a cascading foreign key (as V36).
    scope_key          VARCHAR(36) GENERATED ALWAYS AS (COALESCE(environment_id, 'FLEET')) VIRTUAL,
    CONSTRAINT fk_efficiency_environment FOREIGN KEY (environment_id) REFERENCES environment (environment_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX ux_efficiency_date_scope ON efficiency_snapshot (snapshot_date, scope_key);
CREATE INDEX ix_efficiency_environment_date ON efficiency_snapshot (environment_id, snapshot_date);
