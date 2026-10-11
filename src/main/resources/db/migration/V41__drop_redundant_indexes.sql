-- E14-T05: drop indexes that duplicate a UNIQUE key or are a leftmost prefix of another index.
-- Each drop runs only if the index is still present (MySQL has no DROP INDEX IF EXISTS), so the
-- migration is safe on databases where an index was already removed by hand. Every foreign key
-- keeps a supporting index whose leftmost column is the FK column.
-- The unused-table drops of E14-T05 wait for a production-copy check (Parking lot PL-10).

-- app_user.idx_user_email: duplicates the UNIQUE key on email
SET @drop_sql = (SELECT IF(COUNT(*) > 0, 'DROP INDEX idx_user_email ON app_user', 'DO 0') FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'app_user' AND index_name = 'idx_user_email');
PREPARE drop_stmt FROM @drop_sql;
EXECUTE drop_stmt;
DEALLOCATE PREPARE drop_stmt;

-- app_user.idx_user_azure_ad_object_id: duplicates the UNIQUE key on azure_ad_object_id
SET @drop_sql = (SELECT IF(COUNT(*) > 0, 'DROP INDEX idx_user_azure_ad_object_id ON app_user', 'DO 0') FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'app_user' AND index_name = 'idx_user_azure_ad_object_id');
PREPARE drop_stmt FROM @drop_sql;
EXECUTE drop_stmt;
DEALLOCATE PREPARE drop_stmt;

-- environment.idx_environment_name: duplicates the UNIQUE key on name
SET @drop_sql = (SELECT IF(COUNT(*) > 0, 'DROP INDEX idx_environment_name ON environment', 'DO 0') FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'environment' AND index_name = 'idx_environment_name');
PREPARE drop_stmt FROM @drop_sql;
EXECUTE drop_stmt;
DEALLOCATE PREPARE drop_stmt;

-- vm_group.idx_group_env: leftmost prefix of idx_group_env_name (environment_id, name)
SET @drop_sql = (SELECT IF(COUNT(*) > 0, 'DROP INDEX idx_group_env ON vm_group', 'DO 0') FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'vm_group' AND index_name = 'idx_group_env');
PREPARE drop_stmt FROM @drop_sql;
EXECUTE drop_stmt;
DEALLOCATE PREPARE drop_stmt;

-- notification.idx_notification_user: leftmost prefix of idx_notification_unread (user_id, is_read)
SET @drop_sql = (SELECT IF(COUNT(*) > 0, 'DROP INDEX idx_notification_user ON notification', 'DO 0') FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'notification' AND index_name = 'idx_notification_user');
PREPARE drop_stmt FROM @drop_sql;
EXECUTE drop_stmt;
DEALLOCATE PREPARE drop_stmt;

-- vm_volume_snapshot.idx_vm_volume_vm: leftmost prefix of uq_vm_volume (vm_id, volume_id)
SET @drop_sql = (SELECT IF(COUNT(*) > 0, 'DROP INDEX idx_vm_volume_vm ON vm_volume_snapshot', 'DO 0') FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'vm_volume_snapshot' AND index_name = 'idx_vm_volume_vm');
PREPARE drop_stmt FROM @drop_sql;
EXECUTE drop_stmt;
DEALLOCATE PREPARE drop_stmt;

-- vm_metric_sample.idx_vm_metric_vm_time: leftmost prefix of uq_vm_metric_sample (vm_id, sample_time, period_seconds)
SET @drop_sql = (SELECT IF(COUNT(*) > 0, 'DROP INDEX idx_vm_metric_vm_time ON vm_metric_sample', 'DO 0') FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'vm_metric_sample' AND index_name = 'idx_vm_metric_vm_time');
PREPARE drop_stmt FROM @drop_sql;
EXECUTE drop_stmt;
DEALLOCATE PREPARE drop_stmt;

-- vm_metric_sample_archive.idx_vm_metric_archive_vm_time: leftmost prefix of uq_vm_metric_sample_archive
SET @drop_sql = (SELECT IF(COUNT(*) > 0, 'DROP INDEX idx_vm_metric_archive_vm_time ON vm_metric_sample_archive', 'DO 0') FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'vm_metric_sample_archive' AND index_name = 'idx_vm_metric_archive_vm_time');
PREPARE drop_stmt FROM @drop_sql;
EXECUTE drop_stmt;
DEALLOCATE PREPARE drop_stmt;

-- automation_rule.idx_automation_rule_env: leftmost prefix of idx_automation_rule_env_trigger_mode
SET @drop_sql = (SELECT IF(COUNT(*) > 0, 'DROP INDEX idx_automation_rule_env ON automation_rule', 'DO 0') FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'automation_rule' AND index_name = 'idx_automation_rule_env');
PREPARE drop_stmt FROM @drop_sql;
EXECUTE drop_stmt;
DEALLOCATE PREPARE drop_stmt;
