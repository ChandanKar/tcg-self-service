# Settings Page — Requirement (brainstorm, not yet built)

## Context

Every configurable value in this app today lives in `application.properties`/`.env` —
feature flags, thresholds, cron schedules, credentials, all mixed together. Changing
any of it requires editing `.env` and restarting the app. The ask: an Admin-only UI
page where everything **except credentials/secrets** can be viewed and edited live,
without a restart, wherever that's actually safe to do.

## The core design fork: not everything is equally safe to make live

It's not just "secrets vs. everything else" — there are three tiers:

### Tier 1 — Truly runtime-editable, no restart needed
Boolean feature flags and numeric thresholds. These are already read at *call time*
by the code (`if (!enabled) return;`, `if (avgCpu < threshold)`), so swapping their
source from a static property to a DB-backed value is mechanical and low-risk. This
is the bulk of what's actually useful to expose:

- **Notification System v2**: master email switch, 9 per-event-type email toggles
  (access requested/expiring/expired/granted/approved/denied/revoked, operation
  failed, lock broken), 3 weekly-report email toggles (cost/idle-waste/rightsizing)
- **Cost Management**: `cost.tagging.enabled`, `cost.actuals.enabled`,
  `cost.optimizer.enabled`, `cost.reservations.enabled`
- **Rightsizing thresholds**: scale-down/up CPU %, consecutive days/minutes
- **VM idle thresholds**: CPU %, network/disk bytes-per-period, minimum duration
- **Feature enables**: `eks.sync.auto-register.enabled`, `eks.sync.status-refresh.enabled`,
  `vm.discovery.enabled`,
  `automation.rules.enabled`, `vm.state.sync.enabled`, `cloudwatch.metric.enable`,
  `vm.metrics.archive.enabled`, `vm.inventory.sync.enabled`

### Tier 2 — Not secrets, but need a restart or don't make sense live
- `ENTRAID_ENABLED` — swaps the entire Spring Security filter chain; Spring picks
  this at startup via `@ConditionalOnProperty` on the whole config class. Cannot be
  hot-swapped without restructuring auth entirely.
- **Cron expressions** (`*.cron` properties) — `@Scheduled(cron=...)` is wired once
  at startup via Spring's `ScheduledTaskRegistrar`. Live-editing needs a dynamic
  rescheduler (`SchedulingConfigurer` + manual re-trigger), a real chunk of extra
  work beyond the Tier 1 pattern.
- `MAIL_HOST`/`MAIL_PORT` — the `JavaMailSender` bean is built once from these at
  startup by Spring Boot's mail autoconfiguration.
- `spring.datasource.*`, Flyway, `JPA_DDL_AUTO` — the connection pool is already
  open; these aren't meaningful to change without a restart regardless.
- `aws.region` — mostly moot already: every AWS-calling service resolves the
  target region per-VM and caches its own client per region on demand, so this
  property is really just a fallback default (e.g. for EKS cluster auto-discovery
  scanning), not a hard runtime dependency.

**Open decision**: show Tier 2 items read-only in the settings page (so admins see
the full picture in one place, with a "view only — edit `.env` and restart" note),
or leave them out of the UI entirely and keep them purely `.env`-documented.

### Tier 3 — Secrets (excluded per explicit request)
`DB_PASSWORD`, `AWS_ACCESS_KEY`/`AWS_SECRET_KEY`, `AZURE_CLIENT_SECRET`,
`MAIL_USERNAME`/`MAIL_PASSWORD`, etc. — never surfaced in the UI.

## Proposed architecture (not yet built)

- New table: `app_setting(key, value, updated_at, updated_by)` — simple key-value
  store, one new Flyway migration.
- New `SettingsService` with typed getters (`getBoolean(key, default)`,
  `getInt(key, default)`, `getString(key, default)`), seeded from whatever's
  currently in `application.properties` the *first time* each key is read — so
  nothing changes for anyone who never opens the settings page. Once a key is
  edited via the UI, the DB row becomes the source of truth for it going forward.
- Existing `@Value` fields across roughly 10 services get replaced with
  `SettingsService` lookups (mechanical, one field at a time).
- New Admin-only `/settings` page (`@PreAuthorize("hasRole('ADMIN')")`), grouped by
  feature area: Notifications, Cost Management, EKS/Discovery, Automation, VM
  Idle/Rightsizing Thresholds.
- Every change routed through the existing `AuditService` so there's a record of
  who changed what setting, when — same pattern already used for every other
  significant action in this app.

## Still to decide before building
1. Tier 2 items: read-only in the UI, or excluded entirely?
2. Is dynamic cron rescheduling worth building now, or acceptable to leave cron
   `.env`-only for a first version?
3. Caching strategy for `SettingsService` reads (in-memory map invalidated on
   write vs. a short TTL) — these get checked on nearly every notification/cost
   job invocation, so a DB round-trip per check is worth avoiding.
