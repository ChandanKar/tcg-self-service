-- =====================================================
-- Flyway Migration: V2__add_dual_auth_columns.sql
-- Purpose: Add dual authentication support (Azure AD + username/password)
-- =====================================================

-- Add new columns to app_user for username/password authentication
ALTER TABLE app_user ADD COLUMN username VARCHAR(150) NULL;
ALTER TABLE app_user ADD COLUMN password VARCHAR(300) NULL;
ALTER TABLE app_user ADD COLUMN company_name VARCHAR(300) NULL;
ALTER TABLE app_user ADD COLUMN legacy_user_id INT NULL;
ALTER TABLE app_user ADD COLUMN password_updated_at TIMESTAMP NULL;

-- Make azure_ad_object_id nullable (standard SQL, works in H2 2.x and MySQL 8.0+)
ALTER TABLE app_user ALTER COLUMN azure_ad_object_id DROP NOT NULL;

-- Add unique constraints as separate indexes (allows NULL values for non-Entra users)
CREATE UNIQUE INDEX idx_user_username ON app_user(username);
CREATE UNIQUE INDEX idx_user_legacy_user_id ON app_user(legacy_user_id);

-- =====================================================
-- End of Migration
-- =====================================================

