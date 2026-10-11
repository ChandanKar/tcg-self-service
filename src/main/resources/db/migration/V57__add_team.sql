-- E23-T01: teams as real records instead of the free-text metadata.ownerTeam, so spelling variants
-- no longer split costs. Backfilled from ownerTeam (trimmed, slugged: 'Platform Team ' and
-- 'Platform Team' become one team 'platform-team'); metadata is left untouched. Rows whose
-- metadata is not JSON are skipped (JSON_EXTRACT is only reached through a JSON_VALID guard,
-- since MySQL may evaluate the select list before the WHERE filter).

CREATE TABLE team (
    team_id VARCHAR(36) NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    name VARCHAR(100) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    cost_center VARCHAR(100) NULL,
    entra_group_id VARCHAR(64) NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uk_team_name UNIQUE (name),
    CONSTRAINT uk_team_entra_group UNIQUE (entra_group_id)
);

ALTER TABLE environment
    ADD COLUMN team_id VARCHAR(36) NULL,
    ADD CONSTRAINT fk_environment_team FOREIGN KEY (team_id) REFERENCES team (team_id);

CREATE INDEX ix_environment_team ON environment (team_id);

INSERT INTO team (team_id, name, display_name)
SELECT UUID(), x.slug, MIN(x.t)
FROM (SELECT y.t, TRIM(BOTH '-' FROM LOWER(REGEXP_REPLACE(y.t, '[^A-Za-z0-9]+', '-'))) AS slug
      FROM (SELECT TRIM(CASE WHEN JSON_VALID(metadata)
                             THEN JSON_UNQUOTE(JSON_EXTRACT(metadata, '$.ownerTeam')) END) AS t
            FROM environment) y) x
WHERE x.t IS NOT NULL AND x.t <> '' AND x.t <> 'null' AND x.slug <> ''
  AND NOT EXISTS (SELECT 1 FROM team existing WHERE existing.name = x.slug)
GROUP BY x.slug;

UPDATE environment e
JOIN team tm ON tm.name = TRIM(BOTH '-' FROM LOWER(REGEXP_REPLACE(
        TRIM(CASE WHEN JSON_VALID(e.metadata) THEN JSON_UNQUOTE(JSON_EXTRACT(e.metadata, '$.ownerTeam')) END),
        '[^A-Za-z0-9]+', '-')))
SET e.team_id = tm.team_id
WHERE e.team_id IS NULL;
