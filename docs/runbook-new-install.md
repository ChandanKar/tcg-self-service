# Runbook: new install from the clean baseline, then load data

Since November 2026 the migrations start from a clean baseline:

| File | Contents |
|---|---|
| `V1__baseline_schema.sql` | The whole schema (88 tables) as the old V1-V104 left it. |
| `V2__default_data.sql` | Default rows only: 6 `system_config` settings, 4 `scheduled_job` rows, the "Default account" AWS account. |
| `V3` onwards | New changes, added as usual. |

The old incremental migrations are in git at tag `pre-baseline-v104`. They seeded 680 legacy users
(plain-text passwords), 161 environments and their VMs; the baseline seeds none of that.

**A database that ran the old migrations cannot be started with this build** (its history says
V1-V104 with other checksums, so validation fails). Such a database moves over by **loading its data
into a new install**, as below. Keep the old build and a backup together as the rollback pair.

`scripts/compare-schemas.sh` proves two schemas are the same (tables, columns, indexes, foreign keys).
On 16 Nov 2026 the baseline was identical to a database built by V1-V104 and to staging's live schema
(apart from the two legacy helper tables `temp_vm_master` and `temp_instance_parsed`, dropped on purpose).

## Who is the first admin

Nobody, until someone signs in. `APP_INITIAL_ADMIN_EMAIL` (in `.env`) is made admin on their first
Microsoft sign-in **while the app has no active admin**. If the loaded data already has an active admin,
the setting does nothing. Do not let anyone sign in between "start the new build" and "load the data":
the sign-in creates a user row that can clash with the loaded one (same email).

## 1. Prepare the data (source must be at V104)

The data must have the V104 shape. Staging is (checked 16 Nov 2026). A database on an older level
(for example production) is first brought to V104 on a copy: restore its dump into a scratch schema and
start the `pre-baseline-v104` build on it once (never the live database).

Check the source has an active admin (or rely on `APP_INITIAL_ADMIN_EMAIL`):

```sql
SELECT email FROM app_user WHERE admin = 1 AND is_active = 1;
SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;   -- must be 104
```

Export the data only (no table definitions, no Flyway history, no stale job locks, not the two dropped
helper tables). On the staging host the source runs in a container:

```bash
SRC=db_self_service
docker exec vmcontrol-db-staging sh -c "MYSQL_PWD=\"\$MYSQL_ROOT_PASSWORD\" mysqldump -uroot \
  --no-create-info --complete-insert --single-transaction --skip-triggers --hex-blob --set-gtid-purged=OFF \
  --ignore-table=$SRC.flyway_schema_history --ignore-table=$SRC.scheduled_job_lock \
  --ignore-table=$SRC.temp_vm_master --ignore-table=$SRC.temp_instance_parsed $SRC" \
  | gzip > ~/data-$(date -u +%F-%H%M).sql.gz
```

Optional, to keep the load small: add `--ignore-table=$SRC.vm_metric_sample --ignore-table=$SRC.vm_metric_sample_archive`
(raw 5-minute samples, about 200 MB on staging; the daily rollups stay).

Record the source row counts for the check in step 3:

```sql
SELECT table_name, table_rows FROM information_schema.tables
WHERE table_schema = DATABASE() ORDER BY table_name;      -- estimates; use COUNT(*) for key tables
SELECT 'app_user', COUNT(*) FROM app_user UNION ALL SELECT 'environment', COUNT(*) FROM environment
UNION ALL SELECT 'vm', COUNT(*) FROM vm UNION ALL SELECT 'environment_access', COUNT(*) FROM environment_access;
```

Rehearse once on the laptop: create an empty `*_test` schema, start the new build on it (it applies
V1 and V2), stop it, load the file as in step 2c, start it again and compare the counts.

## 2. Install (staging)

Each step on the owner's go. Never touch the production containers.

a. **Backup and rollback pair.** Full dump of the current database
   (`~/stage-db-before-baseline-<date>.sql.gz`) and keep the current jar as `app/vmcontrol.jar.working.N`.

b. **Empty database, new build.**
   - In `/opt/tcg-self-service-stage/.env` set `FLYWAY_BASELINE_ON_MIGRATE=false` (with `true`, a
     schema that has tables but no history would be marked as V1 and skip the baseline).
   - Stop the app container, then recreate the schema:
     `DROP DATABASE db_self_service; CREATE DATABASE db_self_service CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;`
   - Put the new jar in place, start the app. The log must say
     `Successfully applied 2 migrations ... now at version v2`. Nobody signs in.

c. **Load the data.** Stop the app (no scheduled job may run on half-loaded data), then replace the
   default rows with the source's and load:

   ```bash
   docker exec -i vmcontrol-db-staging sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot db_self_service' <<'SQL'
   SET FOREIGN_KEY_CHECKS = 0;
   DELETE FROM system_config; DELETE FROM scheduled_job; DELETE FROM aws_account;
   SQL
   gunzip -c ~/data-<stamp>.sql.gz | docker exec -i vmcontrol-db-staging sh -c \
     'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot db_self_service'
   ```

   (mysqldump's output turns foreign-key checks off for the load and back on at the end.)

d. **Start and check.**
   - Start the app: `Successfully validated 2 migrations`, schema at v2, no ERROR lines.
   - `SELECT version FROM flyway_schema_history` shows only 1 and 2.
   - Counts of `app_user`, `environment`, `vm`, `environment_access` equal the source's.
   - An admin signs in; Home, Environments and Users show the loaded data.
   - The read-only staging checks pass (`test/uat`, `UAT_DRY=1`).

**Rollback:** stop the app, recreate the schema from the backup dump, put the old jar back, start.
The old jar does not start on the new database and the new jar does not start on the old one.

## 3. Production (later)

Same as staging, with production's data brought to V104 on a copy first (step 1). Rehearse the whole
sequence on staging with that copy before the production window.

## Laptop databases

The test suite's schema (`TEST_DB_URL`, e.g. `vmcontrol_test`) must be recreated empty once after this
change (`DROP DATABASE ...; CREATE DATABASE ...`); from then on the tests migrate and reseed it as before.
A local dev database that ran the old migrations is recreated the same way.
