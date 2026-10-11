# Runbook: Flyway validation failures and manual repair

The application no longer runs `flyway.repair()` on startup. Spring Boot runs a plain
`migrate()` with `spring.flyway.validate-on-migrate=true`, so the app **refuses to start** when the
database's migration history does not match the migration files in the build.

That is deliberate: an edited, already-applied migration used to be accepted silently (repair
rewrote the stored checksum) while the schema itself stayed unchanged, so environments drifted.

## When startup fails

The log shows a `FlywayValidateException`, typically one of:

| Message | Meaning |
|---|---|
| `Migration checksum mismatch for migration version N` | The file `V<N>__*.sql` (or the Java migration) differs from what was applied. |
| `Detected applied migration not resolved locally: N` | The database has a version this build does not contain (wrong build, or a deleted file). |
| `Detected failed migration to version N` | A previous migration run failed half-way (MySQL DDL is not transactional). |

## What to do

1. **Do not edit applied migrations.** If a change is needed, add a new `V<next>__*.sql`.
2. Find out why the history differs. Compare the database with the build:

   ```sql
   SELECT installed_rank, version, description, type, checksum, success
   FROM flyway_schema_history ORDER BY installed_rank;
   ```

   and, from the same commit, `flyway info` (Flyway CLI with the same `locations`) or the
   `FlywayMigrationTest` output against a fresh test schema.
3. Decide, with the change owner, which side is right:
   - **The file was edited by mistake** → restore the file from git so its checksum matches again.
   - **The edit is intended and the schema already matches it** (e.g. a comment-only or formatting
     change that was approved) → run a one-off, recorded repair (below).
   - **A failed migration** → fix the schema by hand to the state before that migration (check
     which statements ran), delete the failed row, then let the app migrate again.

## One-off manual repair (approved changes only)

Run it once, against the one database concerned, from a machine with the release build:

```bash
flyway -url="jdbc:mysql://<host>:3306/<schema>" -user="<user>" -password="<prompt or env>" \
       -locations="filesystem:src/main/resources/db/migration,classpath:db/migration" repair
```

or remove a failed row explicitly:

```sql
DELETE FROM flyway_schema_history WHERE version = '<N>' AND success = 0;
```

Record in the PR or change ticket: the database, the versions affected, the old and new
checksums, and who approved it. Then start the application normally; it must migrate and validate
cleanly.

## Before rolling out this change

On every deployed environment, compare `flyway_schema_history` with this build once (step 2 above).
Any existing drift that the old automatic repair was hiding will now block startup and must be
resolved deliberately first.
