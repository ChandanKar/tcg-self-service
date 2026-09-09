-- V20: Scope an access grant (and an access request) to a specific VM group / EKS node
-- group inside an environment, not just the whole environment. Also record whether a grant
-- was made directly by an admin or produced by approving a request.
--
-- Additive + backfill, no downtime. Every existing row is an ENVIRONMENT-scoped grant, so
-- scope_id is backfilled to environment_id and the strict environment-authorization queries
-- (which now filter scope_type = 'ENVIRONMENT') behave exactly as before.

ALTER TABLE environment_access ADD COLUMN scope_type VARCHAR(20) NOT NULL DEFAULT 'ENVIRONMENT';
ALTER TABLE environment_access ADD COLUMN scope_id   VARCHAR(36) NULL;
ALTER TABLE environment_access ADD COLUMN initiation VARCHAR(20) NOT NULL DEFAULT 'DIRECT';
ALTER TABLE environment_access ADD COLUMN source_request_id VARCHAR(36) NULL;

UPDATE environment_access SET scope_id = environment_id WHERE scope_id IS NULL;
ALTER TABLE environment_access MODIFY COLUMN scope_id VARCHAR(36) NOT NULL;

ALTER TABLE environment_access_request ADD COLUMN scope_type VARCHAR(20) NOT NULL DEFAULT 'ENVIRONMENT';
ALTER TABLE environment_access_request ADD COLUMN scope_id   VARCHAR(36) NULL;

UPDATE environment_access_request SET scope_id = environment_id WHERE scope_id IS NULL;
ALTER TABLE environment_access_request MODIFY COLUMN scope_id VARCHAR(36) NOT NULL;

-- Lookup indexes only. "One active grant per (user, scope, scope_id)" is enforced in
-- application code by the applyGrant upsert (MySQL has no partial unique index, and a plain
-- unique index would reject legitimate historical REVOKED / EXPIRED duplicates).
CREATE INDEX ix_environment_access_scope      ON environment_access (scope_type, scope_id, status);
CREATE INDEX ix_environment_access_user_scope ON environment_access (user_id, scope_type, scope_id);
