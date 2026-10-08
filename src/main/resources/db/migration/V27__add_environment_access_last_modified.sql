-- V27: Record who last edited a grant directly, without overwriting who granted it
-- (LOW-ACC-3). Until now applyGrant replaced granted_by_user_id on every change, so My Account
-- named the last editor as the granter. Extensions approved from a request are not edits:
-- they show as their own "Extended" activity. Existing rows keep their stored granter.
ALTER TABLE environment_access
    ADD COLUMN last_modified_by_user_id VARCHAR(36) NULL,
    ADD COLUMN last_modified_at TIMESTAMP NULL,
    ADD CONSTRAINT fk_env_access_last_modified_by
        FOREIGN KEY (last_modified_by_user_id) REFERENCES app_user(user_id);
