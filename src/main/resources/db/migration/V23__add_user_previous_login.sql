-- V23: Keep the sign-in before the current one, so the My Account panel can show
-- "Previous sign-in" (last_login_at is overwritten on every login and therefore always
-- shows the current session).
--
-- Additive, nullable, no backfill: existing users show no previous sign-in until they
-- log in once more.

ALTER TABLE app_user ADD COLUMN previous_login_at TIMESTAMP NULL;
