-- V21: Provenance for users created by an admin from the panel (Microsoft Graph directory
-- lookup, or manual entry) before that person has ever signed in. See
-- docs/graph-user-onboarding.md.
--
-- Additive, nullable, no backfill. Existing rows keep onboarded_by = NULL (self-registered
-- via Entra login, or legacy-migrated).
--
--   onboarded_by set  + last_login_at IS NULL        -> pending onboard (badge "Not signed in yet")
--   onboarded_by set  + azure_ad_object_id IS NULL   -> manual/unverified onboard (badge "Unverified")

ALTER TABLE app_user ADD COLUMN onboarded_by VARCHAR(36) NULL;
ALTER TABLE app_user ADD COLUMN onboarded_at TIMESTAMP NULL;
