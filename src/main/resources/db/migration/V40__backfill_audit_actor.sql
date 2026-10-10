-- E11-T06: My Activity now matches on audit_log.user_id only (no LIKE over details). Older rows
-- stored the actor only in details as "by user: <user id>"; give those rows their user_id.
-- Only that explicit actor pattern is used, never a mere mention of the user (the subject).
UPDATE audit_log a
JOIN app_user u ON a.details LIKE CONCAT('%by user: ', u.user_id, '%')
SET a.user_id = u.user_id
WHERE a.user_id IS NULL;
