-- V36: at most one active lock per environment, enforced by the database (M1). acquireLock read
-- then inserted with no guard, so two simultaneous acquires could both succeed and every later
-- "the active lock" lookup failed with a non-unique result.

-- 1. Repair: keep the newest active lock of each environment, close the others.
UPDATE environment_lock l
JOIN (SELECT environment_id, MAX(locked_at) AS keep_at
        FROM environment_lock WHERE is_active = TRUE
       GROUP BY environment_id HAVING COUNT(*) > 1) d ON d.environment_id = l.environment_id
SET l.is_active = FALSE, l.released_at = NOW(), l.break_reason = 'Duplicate active lock removed by migration'
WHERE l.is_active = TRUE AND l.locked_at < d.keep_at;

-- Same locked_at: keep the highest lock_id.
UPDATE environment_lock l
JOIN (SELECT environment_id, MAX(lock_id) AS keep_id
        FROM environment_lock WHERE is_active = TRUE
       GROUP BY environment_id HAVING COUNT(*) > 1) d ON d.environment_id = l.environment_id
SET l.is_active = FALSE, l.released_at = NOW(), l.break_reason = 'Duplicate active lock removed by migration'
WHERE l.is_active = TRUE AND l.lock_id < d.keep_id;

-- 2. The guard: the environment id while active, NULL otherwise; unique (NULLs may repeat).
-- VIRTUAL: MySQL refuses a STORED generated column on a base column with a cascading foreign key.
ALTER TABLE environment_lock
    ADD COLUMN active_environment_id VARCHAR(36)
        GENERATED ALWAYS AS (CASE WHEN is_active THEN environment_id ELSE NULL END) VIRTUAL;
CREATE UNIQUE INDEX ux_environment_lock_one_active ON environment_lock (active_environment_id);
