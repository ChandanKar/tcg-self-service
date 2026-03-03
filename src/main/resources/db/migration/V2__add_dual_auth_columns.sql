-- =====================================================
-- Flyway Migration: V2__add_dual_auth_columns.sql
-- Purpose: Add dual authentication support (Azure AD + username/password)
-- =====================================================

-- Add new columns to app_user for username/password authentication
ALTER TABLE app_user ADD COLUMN username VARCHAR(150) NULL UNIQUE;
ALTER TABLE app_user ADD COLUMN password VARCHAR(300) NULL;
ALTER TABLE app_user ADD COLUMN company_name VARCHAR(300) NULL;
ALTER TABLE app_user ADD COLUMN legacy_user_id INT NULL UNIQUE;
ALTER TABLE app_user ADD COLUMN password_updated_at TIMESTAMP NULL;

-- Modify azure_ad_object_id to be nullable (was NOT NULL in V1)
-- Note: This step depends on database support. For H2/MySQL, we need to recreate or use ALTER
-- For compatibility, we'll add a new column and handle migration manually if needed
-- H2/MySQL support ALTER COLUMN for nullability
ALTER TABLE app_user ALTER COLUMN azure_ad_object_id DROP NOT NULL;

-- Create indexes for performance on dual auth lookups
CREATE INDEX idx_user_username ON app_user(username);
CREATE INDEX idx_user_legacy_user_id ON app_user(legacy_user_id);

-- =====================================================
-- End of Migration
-- =====================================================

