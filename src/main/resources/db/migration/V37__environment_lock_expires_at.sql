-- V37: locks end on their own (H4, G3). expected_duration_minutes was stored but never read, and
-- nothing ever expired a lock. expires_at is when an active lock is released by the expiry sweep
-- (locks.expiry.enabled); NULL means "until manually released".
ALTER TABLE environment_lock ADD COLUMN expires_at TIMESTAMP NULL;

UPDATE environment_lock
   SET expires_at = TIMESTAMPADD(MINUTE, expected_duration_minutes, locked_at)
 WHERE is_active = TRUE AND expected_duration_minutes IS NOT NULL;

CREATE INDEX ix_environment_lock_active_expires ON environment_lock (is_active, expires_at);
