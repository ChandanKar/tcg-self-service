# Runbook: environment admins limited to their own environments

Since U21-T11 the default is `security.env-admin.scope=assigned`: an environment admin (role
`ENV_ADMIN`) manages only the environments where they hold an **active, environment-wide Admin
grant**. A group-level grant does not count. Global administrators (`ADMIN`) are not affected.

Before the first deploy with this default, on each database (staging, then production), check who
would lose admin rights, and add the missing grants first. To keep the old behaviour for a while,
set `SECURITY_ENV_ADMIN_SCOPE=global` and restart.

## 1. Environment admins and the environments they will still manage

```sql
SELECT u.email, u.display_name,
       COUNT(e.environment_id)                                         AS environments_kept,
       GROUP_CONCAT(e.display_name ORDER BY e.display_name SEPARATOR ', ') AS kept
FROM app_user u
LEFT JOIN environment_access ea
       ON ea.user_id = u.user_id
      AND ea.scope_type = 'ENVIRONMENT'
      AND ea.access_level = 'ADMIN'
      AND ea.status = 'ACTIVE'
      AND (ea.expires_at IS NULL OR ea.expires_at > NOW())
LEFT JOIN environment e ON e.environment_id = ea.environment_id
WHERE u.env_admin = 1 AND u.admin = 0 AND u.is_active = 1
GROUP BY u.user_id, u.email, u.display_name
ORDER BY environments_kept, u.email;
```

A row with `environments_kept = 0` is an environment admin who will manage **no** environment
after the deploy. A short list is a person who will keep only those environments.

Read-only: run it in the database container, e.g. on staging
`docker exec vmcontrol-db-staging sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql db_self_service -e "…"'`.

## 2. Fix before the deploy

For each person and environment they should keep managing, grant **Admin** on the **whole
environment** from Access management (Grant access), or keep them global for now with
`SECURITY_ENV_ADMIN_SCOPE=global`. An environment admin who creates an environment gets an Admin
grant on it automatically, so new environments need nothing.

## 3. After the deploy

Sign in as one of the environment admins (or ask one to) and check that Manage shows exactly their
environments, and that an environment outside that list answers "You don't have access".
