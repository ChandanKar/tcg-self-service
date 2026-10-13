-- E34-T03: service accounts are users that never sign in (no directory id, no password) and act only
-- through tokens an admin issues; each names the person responsible for it.
ALTER TABLE app_user
    ADD COLUMN is_service_account BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN service_owner_user_id VARCHAR(36) NULL;

ALTER TABLE api_token
    ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'PERSONAL';
