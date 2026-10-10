-- E11-T02: snapshot the actor's name and email on each audit row, so history stays readable
-- after the user is hard-deleted (audit_log.user_id is ON DELETE SET NULL).
ALTER TABLE audit_log ADD COLUMN actor_name VARCHAR(255) NULL;
ALTER TABLE audit_log ADD COLUMN actor_email VARCHAR(255) NULL;

UPDATE audit_log a
JOIN app_user u ON u.user_id = a.user_id
SET a.actor_name = COALESCE(NULLIF(u.display_name, ''), u.email),
    a.actor_email = u.email
WHERE a.actor_name IS NULL;
