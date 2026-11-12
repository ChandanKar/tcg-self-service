# Plan: blank install and sanitized migrations (on hold)

Agreed with the owner on 2026-11-12; to be carried out later. Branch: `feature/blank-install` (from main 88f7406).

## Goal

A new installation starts with the full schema, the default settings and the default AWS account, and
nothing else: no users, environments, VMs or teams. The only admin is chandan.kar@tcgdigital.com, made
admin on the first Microsoft sign-in (`APP_INITIAL_ADMIN_EMAIL`). Users and environments are loaded after.

## Why

- `V3__migrate_external_users.sql` seeds 680 legacy users with their passwords in plain text (in every
  commit since ec6b70a, 2026-03-03). V24 clears them in the database, but they remain in the code and
  in git history. Treat every V3 password as leaked.
- `V4__migrate_legacy_vms.sql` seeds 161 environments, a default group each, and about 430 AWS instance
  IDs (internal infrastructure data). V57 then creates teams from those environments' `ownerTeam`.

## What the migrations hold (review of V1-V101 and V24)

Only 15 SQL migrations and the Java V24 write data; the other 86 create or alter tables and indexes.

| Migration | Data | Blank install |
|---|---|---|
| V1 | 6 `system_config` defaults, 4 old `scheduled_job` rows | keep |
| V3 | 680 legacy users, plain-text passwords | empty the file |
| V4 | 161 environments, default groups, VMs, provider details | empty the file |
| V5, V20, V34, V36, V37, V39, V40, V48, V90, V97 | one-off fixes and backfills | no effect on empty tables |
| V57 | teams from V4's environments | creates none once V4 is empty |
| V70 | the "Default account" AWS account row | keep (needed) |
| V24 (Java) | clears V3 passwords, hashes other plain text | no effect |

## Decisions

1. Existing databases: automatic checksum fix at startup (not a manual `flyway repair` per database).
2. Rewrite git history now (when the work is done).
3. Keep the 4 old V1 job rows.
4. First admin: chandan.kar@tcgdigital.com.

## Part A: sanitize the code (on `feature/blank-install`)

1. V3 and V4 keep their names and version numbers; their contents become a comment saying what they did
   and why they were emptied.
2. A startup step before Flyway: if `flyway_schema_history` holds V3 or V4 with exactly their old
   checksums, update just those two to the new ones. Any other mismatch still stops startup.
3. Tests: a fresh database has no users, environments, VMs or teams and keeps the defaults and the AWS
   account; the checksum fix updates the old values and refuses unknown ones; the initial admin is
   promoted once and the next person is not; the full Java suite.
4. Laptop trial: a fresh `vmcontrol_blank_test` schema comes up empty and Home, Environments and Users
   show empty pages; the existing laptop test schema starts with its two checksums fixed.
5. `docs/runbook-blank-install.md`: new database; settings (`APP_INITIAL_ADMIN_EMAIL`, the compose
   `hostname:` line from U21-T22, the Cost Explorer switches); first start; the admin's first sign-in;
   loading users and environments; rollback.
6. Commit Part A; show the owner before Part B.

## Part B: rewrite git history (each destructive step needs the owner's go)

1. Mirror backup of the GitHub repository in a folder outside any project (it still holds the
   passwords: delete it once the rewrite is confirmed).
2. `git filter-repo` on a fresh mirror clone: in every commit, V3 and V4 get the same sanitized content
   as Part A. Every commit ID changes (646 commits, 41 branches, no tags, no open pull requests on
   2026-11-12).
3. Check: no commit holds the passwords or the V4 instance list; the newest tree equals Part A; build and
   tests pass.
4. Force-push all branches (owner's go).
5. Ask GitHub Support to purge cached views and the old commit IDs.
6. Re-clone every local copy: this folder (keep the uncommitted `.gitignore` change, `docs/backlog.md`,
   `docs/ux-backlog.md`, `test/ux-review/crawl.mjs`), `d:\workspace\mcube\code\core\tcg-self-service`,
   and the test VM's synced copy. Keep filter-repo's old-to-new commit map to update the tracker, the
   Acceptance page and notes that quote commit IDs.

## Part C: staging (owner's go)

Deploy the new jar: proves the automatic checksum fix on a database with the old checksums.
Production follows with the release.

## Impact

- Fresh installs: blank; the owner is the only admin after the first sign-in.
- Existing databases: no data changes; V3/V4 checksums updated on the first start of the new build.
- Rollback: once the new build has run on a database, older jars refuse to start on it (checksums no
  longer match their files); rolling back needs a two-line SQL change, to be written in the runbook.
- Git: all commit IDs change; every copy must be re-cloned; references to old IDs need the map.
- Security: removes the passwords from code and history from now on; it does not undo the leak. Change
  the staging admin password (it equals the V3 value), check production's, and make sure no V3 password
  is used anywhere.
- Not covered: the old `aws-self-service` folder and `.war`, and copies outside this laptop and GitHub.

## Estimate

Part A about 2-3 hours (with the 25-minute suite); Part B 30-45 minutes; Part C about 15 minutes.
