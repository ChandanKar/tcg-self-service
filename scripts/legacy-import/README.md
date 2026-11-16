# Legacy import

Converts a backup of the legacy self-service portal (MySQL 5.7, tables `external_user_master`,
`mcube_user_registration`, `vm_registration_master`, `vm_user_mapping`, `mcube_user_audit_trail`)
into five data files for a **new install** (V1 baseline + V2 defaults). The files hold rows only:
no table, column or index changes. Load them as in `docs/runbook-new-install.md`.

The backup and the generated files contain personal data: keep them outside the repository.

```bash
# 1. instance ids of the backup, then a read-only AWS lookup (DescribeRegions/DescribeInstances; needs `npm ci` in test/)
python scripts/legacy-import/convert.py --dump <legacy.sql> --print-instance-ids > <work>/instance-ids.txt
node   scripts/legacy-import/aws-lookup.cjs <work>/aws-instances.json <work>/instance-ids.txt

# 2. convert (legacy DATETIME values are India time by default: --tz-offset +05:30)
python scripts/legacy-import/convert.py --dump <legacy.sql> --aws <work>/aws-instances.json --out <work>/dataset

# 3. load in order into an install that holds only V1+V2, with the app stopped
for f in 01-users 02-environments 03-audit 04-access 05-audit-links; do mysql <db> < <work>/dataset/$f.sql; done
```

| File | Contents |
|---|---|
| `01-users.sql` | all legacy users (active, no password), the old portal registrations (inactive), the inactive "Legacy import" service account |
| `02-environments.sql` | an environment per legacy VM row (active = active in the portal and an instance still in AWS), one Default Group each, a VM per instance (each instance once) with its AWS details and tags |
| `03-audit.sql` | start/stop history (sign-in/sign-out and project codes are not imported), times converted to UTC |
| `04-access.sql` | each legacy grant (`auth = 1`) as Operator on the whole environment |
| `05-audit-links.sql` | links the history to users and environments |

`report.md` lists every count, rule, conflict and fallback; `manifest.json` has the sha256 of each file.
Ids are UUIDv5 of the legacy ids and nothing depends on the time of the run, so the same input gives
byte-identical files (a refresh at go-live produces the same records).

Tests (made-up data): `cd scripts/legacy-import && python -m unittest -v test_convert`
