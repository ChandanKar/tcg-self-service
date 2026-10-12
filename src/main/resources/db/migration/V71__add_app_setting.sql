-- E29-T01: admin overrides of safe runtime settings (feature flags and thresholds). A key with no
-- row takes its value from application.properties; secrets are never stored here.

CREATE TABLE app_setting (
    setting_key VARCHAR(150) NOT NULL PRIMARY KEY,
    setting_value TEXT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by_user_id VARCHAR(36) NULL
);
