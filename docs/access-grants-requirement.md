# Access Grants — Requirement (admin-initiated + group-level access)

Status: **design, not yet built.** Companion to `docs/settings-page-requirement.md`.

## 1. Context

Two gaps in the current access model, addressed here as one feature because they
share a data model, a service path, and a UI:

1. **Access is environment-wide only.** A grant is `EnvironmentAccess(user,
   environment, level, expiresAt)`. There is no way to give someone access to
   just one VM group / EKS node group inside an environment.

2. **Admin-initiated grant exists but is thin and hidden.** `POST
   /api/v1/environments/{id}/access` (`EnvironmentAccessService.grantAccess`,
   `@PreAuthorize hasAnyRole('ADMIN','ENV_ADMIN')`) already grants directly
   without a user request, and `access-management.js` has a "Grant Access"
   modal for it. But:
   - it only lives on the standalone **Access Management** admin page — not on
     the environment detail view where an admin is actually looking;
   - the **update-existing-grant** branch of `grantAccess` returns early and
     skips audit, user notification, and automation (a VIEWER→ADMIN upgrade is
     invisible), and can never clear an expiry back to permanent;
   - it has no concept of group scope.

Key fact that makes this one feature, not two: **an EKS node group is a
`VmGroup`.** `EksSyncService` maps each node group to one `VmGroup` (plus one
`Vm` inside it). "Node-group access" and "VM-group access" are the same thing —
**group-level access** — and for EKS it also happens to be VM-level granularity.

## 2. Goals

- An admin (or scoped env-admin) can **search for a user** and grant them access
  to an environment **and/or a chosen subset of its groups**, in one flow, with
  no request from the user.
- While granting, the admin sees the environment's groups as a **checklist
  (group name + VM count + running count + checkbox)** and ticks the ones to
  include.
- An existing grant can be **modified later** — change level, change the group
  set, change or remove expiry — through the same UI.
- Users keep the existing **request → approve** channel (unchanged in spirit),
  extended so a request can also target a group.
- One code path applies a grant whether it came from a direct admin action or an
  approved request (F3).
- Env-admins can grant at group level within the environments they administer
  (F4).

## 3. Non-goals (v1)

- **Deny / restrict grants.** Grants only ever *add* capability. "env-USER but
  read-only on group X" is not supported.
- **Group-scoped locks.** The `EnvironmentLock` stays environment-wide; a
  group-scoped user still takes the whole-environment lock to run an operation.
  See §12. Concurrent independent per-group operations are v2.
- **Group "bundles" / tag-based grants.** Grants point at individual groups.
- **VM-level grants for EC2** (a grant on a group covers all its VMs). For EKS
  this is moot — one VM per group.
- **Per-group admin *role*.** "Who can manage access" is derived from the
  environment (§5), not a new role.

## 4. The unified model

Every grant is `(scope, level, initiation, expiry)`:

| Field | Values |
|---|---|
| **scope** | `ENVIRONMENT` &nbsp;\|&nbsp; `GROUP` |
| **scopeId** | environment_id, or group_id |
| **level** | `VIEWER` &nbsp;\|&nbsp; `USER` &nbsp;\|&nbsp; `ADMIN` (existing `AccessLevel`) |
| **initiation** | `DIRECT` (admin) &nbsp;\|&nbsp; `REQUEST` (approved) |
| **expiry** | permanent, or `expiresAt` (from `durationDays`) |

A user can hold at most one **active** grant per `(scope, scopeId)`. Granting
again on the same target **updates** the existing row (level / expiry / notes).

Level meaning is unchanged: `VIEWER` = see only, `USER` = operate + acquire lock,
`ADMIN` = operate + manage access on that scope.

## 5. Authorization matrix — who can grant what, and where

| Actor | May grant scope | May grant levels | Target restriction |
|---|---|---|---|
| Global **ADMIN** | ENVIRONMENT, GROUP | VIEWER, USER, ADMIN | any environment, any group |
| Global **ENV_ADMIN** role | ENVIRONMENT, GROUP | VIEWER, USER, ADMIN | any environment, any group (scope unchanged from today) |
| Per-environment **ADMIN** grantee | ENVIRONMENT, GROUP | VIEWER, USER, ADMIN | only that environment and the groups inside it |
| USER / VIEWER | — | — | — (must use the request channel) |

Notes:
- A per-environment ADMIN grantee **can** promote a peer to environment-level
  ADMIN — this already follows from `SecurityService.canManageEnvironmentAccess`
  and is kept.
- Granting the **global** `ENV_ADMIN` / `ADMIN` role is user-management, out of
  scope here.
- The controller must enforce the "target restriction" column — today it only
  checks the role, not whether the actor is scoped to *this* environment/group
  (F4). New checks: `SecurityService.canManageEnvironmentAccess(environmentId)`
  (exists, currently unused by the grant endpoint) and a new
  `canManageGroupAccess(groupId)` that resolves the group's environment and
  defers to the same rule.

## 6. Data model

### 6.1 Add scope columns to `environment_access` (V20)

**Decided:** keep the table name `environment_access` and the entity name
`EnvironmentAccess`; just add columns. No rename (avoids churning every
repository and query for zero functional gain).

```sql
-- V20__add_access_scope.sql
ALTER TABLE environment_access ADD COLUMN scope_type VARCHAR(20) NOT NULL DEFAULT 'ENVIRONMENT';
ALTER TABLE environment_access ADD COLUMN scope_id   VARCHAR(36) NULL;
ALTER TABLE environment_access ADD COLUMN initiation VARCHAR(20) NOT NULL DEFAULT 'DIRECT';
ALTER TABLE environment_access ADD COLUMN source_request_id VARCHAR(36) NULL;

UPDATE environment_access SET scope_id = environment_id WHERE scope_id IS NULL;
ALTER TABLE environment_access MODIFY scope_id VARCHAR(36) NOT NULL;

-- environment_access_request carries the requested scope too
ALTER TABLE environment_access_request ADD COLUMN scope_type VARCHAR(20) NOT NULL DEFAULT 'ENVIRONMENT';
ALTER TABLE environment_access_request ADD COLUMN scope_id   VARCHAR(36) NULL;
UPDATE environment_access_request SET scope_id = environment_id WHERE scope_id IS NULL;
ALTER TABLE environment_access_request MODIFY scope_id VARCHAR(36) NOT NULL;

CREATE INDEX ix_environment_access_scope       ON environment_access (scope_type, scope_id, status);
CREATE INDEX ix_environment_access_user_scope  ON environment_access (user_id, scope_type, scope_id);
```

`environment_id` stays populated on every row — for a GROUP-scoped grant it is
the group's enclosing environment (§6.3), so `WHERE ea.environment.environmentId
= :id` queries keep returning that environment's grants at every scope.

**Uniqueness** ("one active grant per `(user, scope, scopeId)`") is enforced in
application code by the upsert in `applyGrant` (§14), the way `grantAccess`
already does via `findActiveAccess` today. No DB unique index — MySQL can't do a
partial `WHERE status = 'ACTIVE'` index, and a plain unique index would reject
legitimate historical `REVOKED` / `EXPIRED` duplicates. The two indexes above
are lookup-only.

New fields on the entity:
- `AccessScopeType scopeType` — enum `{ ENVIRONMENT, GROUP }` (mirror of
  `AutomationScopeType` minus `VM`).
- `String scopeId`.
- `AccessInitiation initiation` — enum `{ DIRECT, REQUEST }`.
- `String sourceRequestId` — nullable link to the request that produced it.
- keep: `environment` (always the enclosing environment), `user`, `accessLevel`,
  `grantedBy`, `grantedAt`, `expiresAt`, `revokedAt`, `status`, `notes`.

### 6.2 Extend the request entity

`environment_access_request` gains `scope_type` + `scope_id` (nullable, default
`ENVIRONMENT` / the environment id) so a user can request a single group. Rename
concept → `resource_access_request`. Reviewer set is unchanged (§5 actors minus
"USER/VIEWER").

### 6.3 Why keep `environment_id` on every row

A GROUP-scoped grant still records its environment. This keeps:
- the existing "list access for environment" query working (it can return both
  environment-scoped and group-scoped grants for that environment);
- `getAdministeredEnvironmentIds`, notification recipient resolution, and the
  weekly-report scoping able to reason at environment granularity without a join
  through `vm_group`.

## 7. Effective access resolution

`SecurityService` gains group-aware checks. The core function:

```
effectiveLevel(user, group) =
    max(
        globalRoleLevel(user),                 // ADMIN → ADMIN; ENV_ADMIN → ADMIN; else null
        activeGrantLevel(user, ENVIRONMENT, group.environmentId),
        activeGrantLevel(user, GROUP, group.id)
    )                                          // null if the user has none

effectiveEnvLevel(user, environment) =
    max(globalRoleLevel(user), activeGrantLevel(user, ENVIRONMENT, environment.id))
```

`hasGroupAccess(groupId)` → `effectiveLevel != null`.
`hasGroupAccessLevel(groupId, required)` → `effectiveLevel >= required`.
`getVisibleGroupIds(environmentId)` → every group in the environment where
`effectiveLevel(user, group) != null` (env-level access ⇒ all groups).

Composition rules:
- Grants only **raise** the level. A group grant can exceed the user's
  environment level (env-VIEWER + group-USER on one group ⇒ USER there, VIEWER
  elsewhere). It cannot lower it.
- No environment access **and** no grant on a given group ⇒ that group and its
  VMs are invisible to the user (§10).

## 8. API changes

### 8.1 New / changed grant endpoints

| Method | Path | Body | Auth |
|---|---|---|---|
| `GET` | `/api/v1/environments/{envId}/groups?withCounts=true` | — | any access to env — feeds the group checklist (already returns `vmCount` / `runningVmCount` via `VmGroupDTO.fromEntityWithCounts`) |
| `POST` | `/api/v1/access-grants` | `GrantAccessDTO` (below) | §5 |
| `PATCH` | `/api/v1/access-grants/{accessId}` | `{ accessLevel?, durationDays?, clearExpiry?, notes? }` | §5, must be able to manage that grant's scope |
| `DELETE` | `/api/v1/access-grants/{accessId}` | — | §5 (replaces `DELETE /environments/{id}/access/{userId}` which cannot express scope) |
| `GET` | `/api/v1/environments/{envId}/access` | — | list grants for an environment — now returns env- **and** group-scoped rows, each tagged with `scopeType` / `scopeId` / `scopeName` |
| `GET` | `/api/v1/users/me/access` | — | "My Access" — now returns env- and group-scoped rows |
| `GET` | `/api/v1/users?q=...` | — | user search for the grant modal (typeahead by name/email); may already exist for the autocomplete — reuse |

`GrantAccessDTO` (new shape; old one is a subset):

```
{
  "userId":        "…",          // or userEmail — accept either, resolve to user
  "environmentId": "…",          // required
  "accessLevel":   "USER",       // VIEWER | USER | ADMIN
  "scope": {
     "type": "GROUP",            // ENVIRONMENT | GROUP
     "groupIds": ["…","…"]       // required & non-empty when type = GROUP; ignored otherwise
  },
  "durationDays":  30,           // optional; null = permanent
  "notes":         "…"           // optional
}
```

A single call with `scope.type = GROUP` and N `groupIds` upserts N grant rows
(one per group) in one transaction, plus, if requested, one `ENVIRONMENT` row.
Response: the list of resulting `AccessGrantDTO`.

### 8.2 Operation authorization (the enforcement that gives group access teeth)

`VmOperationsController.startOperation` today: `hasEnvironmentAccessLevel(envId,
USER)`. New behaviour:

1. Resolve the operation's target groups:
   - `scope = vmIds` → the groups those VMs belong to;
   - `scope = groupIds` → those groups;
   - `scope = whole environment` → **the caller's visible groups**, not literally
     all (`getVisibleGroupIds`).
2. Require `effectiveLevel(user, group) >= USER` for **every** resolved group.
3. If any target group fails, return **403** with a message naming the
   disallowed groups (fail fast — no silent partial run, consistent with the
   existing "No VMs to operate on" 400).
4. `VmOperationsService.resolveTargetVms`: when the request is "whole
   environment" and the caller is group-scoped, restrict to visible groups
   before dependency ordering.

### 8.3 Read endpoints that must filter to visible groups

- `GET /environments/{id}` (detail hierarchy) — groups & their VMs filtered to
  `getVisibleGroupIds`; environment name/description still shown.
- `GET /environments/{id}/insights`
- `GET /environments/{id}/groups`, `GET .../vms`
- `VmMgmtController` list endpoints
- Dashboard / cost / weekly-report aggregations that currently think in
  "environment members" — see §9 for the long tail.

## 9. Enforcement surface (code to touch)

| Area | Change |
|---|---|
| `SecurityService` | `hasGroupAccess`, `hasGroupAccessLevel`, `getVisibleGroupIds`, `canManageGroupAccess`, `effectiveLevel/effectiveEnvLevel`; call `canManageEnvironmentAccess` from the grant endpoint (F4) |
| `EnvironmentAccessService` → `AccessGrantService` | one internal `applyGrant(scopeType, scopeId, user, level, expiry, initiation, sourceRequestId, actor)` used by **both** direct grant and request approval (F3); fix the update branch to audit + notify + fire automation + honour `clearExpiry` |
| `EnvironmentAccessController` → `AccessGrantController` | new endpoints (§8.1); keep old routes as thin delegates for one release if anything external calls them |
| `resource_access` entity + repo | new fields, new finder methods (`findActiveByUserAndScope`, `findActiveForEnvironmentAllScopes`, `findActiveGroupGrantsForUser`) |
| `AccessExpirationScheduler` | already iterates the table; now also expires GROUP rows and notifies with the group name |
| `VmOperationsController` / `VmOperationsService` | §8.2 |
| Read endpoints | §8.3 filtering |
| `DashboardSummaryService`, `CostEstimationService`, `WeeklyReportService`, `NotificationService` recipient resolution (`resolveEnvironmentRecipients`, `resolveAdministeringEnvAdmins`) | make "who belongs to / can see this" group-aware so per-group figures and notification targeting are correct for group-scoped users — **the long tail; can be phased** |
| Frontend | §11 |

## 10. Visibility rules

- A **group-scoped user** (no environment grant) still sees the environment in
  their **My Environments** list and can open it. The detail view shows only the
  groups they can see; other groups are omitted (not greyed — omitted).
- **Environment-level** VIEWER/USER/ADMIN and global ADMIN/ENV_ADMIN see all
  groups, as today.
- Notifications, dashboards, cost tables scoped to an environment should only
  surface data for groups the recipient can see. Until §9's long tail is done,
  the safe interim is: group-scoped users get **operation** notifications for
  their groups only, and are **excluded** from environment-wide digests.

## 11. UI / UX

### 11.1 Grant / Manage Access modal (redesign of the existing one)

1. **User** — typeahead search by name / email (existing autocomplete). Shows
   the user's current grants on this environment inline ("already has USER on
   *web-tier*").
2. **Environment** — typeahead (existing) or pre-filled when opened from an
   environment's detail page.
3. **Scope** — radio:
   - **Whole environment** — current behaviour.
   - **Specific groups** — reveals a **checklist**: one row per group with
     `☐  <display name>   <n> VMs   <m> running`. Search box above it when a
     cluster has many node groups. "Select all" / "Select none".
4. **Access level** — `VIEWER | USER | ADMIN` (choices limited per §5).
5. **Duration** — none (permanent) / 7 / 30 / 90 days / custom.
6. **Notes** — free text (reason).
7. Submit → `POST /access-grants`. Success toast: "Granted USER on 3 groups in
   *prod-eks* to jane.doe (expires in 30 days)".

### 11.2 Entry points

- **Access Management** page — the existing standalone admin view (unchanged
  location).
- **Environment detail** view — new **"Manage access"** button for anyone who
  passes §5 for that environment; opens the modal pre-scoped to the environment.
- **Group card** on the environment detail — a per-group "Manage access" action
  that opens the modal pre-scoped to that group.

### 11.3 Existing-grant management

The environment's access table (`access-management.js`) gains a **Scope** column
(`Environment` badge, or `Group: web-tier`). Row actions:
- **Edit** — reopens the modal populated with the current level / group set /
  expiry; saving diffs the group set (adds/removes rows) and `PATCH`es the level
  / expiry.
- **Revoke** — `DELETE /access-grants/{accessId}`; for a multi-group grant made
  in one action, offer "revoke this group" vs "revoke all of this user's groups
  here".

### 11.4 "My Access"

`/users/me/access` view lists env- and group-scoped grants with the same Scope
column, level, granted-by, expiry.

## 12. The lock (explicit limitation for v1)

`EnvironmentLock` is one exclusive lock per environment. A group-scoped USER who
wants to start/stop their group must still acquire the **whole-environment**
lock, and while they hold it nobody else can operate any other group in that
environment. This is acceptable for v1 (groups in an environment are rarely
operated independently by different people at the same time) and is called out
in the UI ("Acquiring this lock blocks all groups in *prod-eks*").

**v2**: `EnvironmentLock` gains optional `scopeType` / `scopeId`; two different
group locks coexist; any group lock blocks the environment-wide lock and vice
versa. Touches `LockService`, `verifyLockPermission`, the lock banner, the
lock-acquire automation trigger, and break-lock.

## 13. Notifications & audit

New/extended notification types (`NotificationType`): reuse `ACCESS_GRANTED`,
`ACCESS_REVOKED`, `ACCESS_EXPIRING`, `ACCESS_EXPIRED`,
`ACCESS_REQUEST_APPROVED/DENIED` — message text includes the scope ("Access
granted: USER on group *web-tier* in *prod*"). Add `ACCESS_LEVEL_CHANGED` for
the modify case (today an upgrade is silent).

Audit (`AuditAction`): `ACCESS_GRANTED` / `ACCESS_REVOKED` / `ACCESS_DENIED`
already exist — details string carries `scopeType` + scope name + `initiation`
(`DIRECT` vs `REQUEST`). Add `ACCESS_LEVEL_CHANGED`. Every path routes through
`AuditService` (per project convention), including the previously-silent update
branch.

Automation: `automationRuleService.handleAccessGranted(environmentId)` still
fires on any grant within an environment (env or group scope) — no change to the
trigger contract.

## 14. Convergence of grant + approve (F3)

`grantAccess` (direct) and `approveRequest` currently both end in "upsert an
access row + audit + notify + automation", with drift between them (the update
branch of `grantAccess` skips all three). Refactor to:

```
applyGrant(GrantSpec spec, Actor actor):
    for each (scopeType, scopeId) in spec.targets:
        row = findActiveByUserAndScope(user, scopeType, scopeId)
        if row exists: update level / expiry / notes  (clearExpiry honoured)
        else:          create
        audit(ACCESS_GRANTED or ACCESS_LEVEL_CHANGED, scope, initiation)
        notify(user, scope, level)
    automationRuleService.handleAccessGranted(environmentId)   // once
```

`grantAccess` builds a `GrantSpec` with `initiation = DIRECT`; `approveRequest`
builds one from the request with `initiation = REQUEST`, `sourceRequestId` set.

## 15. Migration & rollout

- Migration is additive + backfill (§6.1); no downtime.
- Old endpoints (`POST/DELETE /environments/{id}/access`) kept as delegates for
  one release, then removed.
- Optional feature flag `access.group-scope.enabled` (default **on** once
  merged; off gives exactly today's behaviour — grant modal hides the Scope
  selector, operation authz stays env-only). Cheap insurance for the first
  deploy.
- `.env` / `application.properties`: `access.group-scope.enabled`,
  and (v2) nothing yet for scoped locks.

## 16. Test plan (outline)

- `SecurityServiceTest`: `effectiveLevel` composition (env only, group only,
  both, none, expired grant, global roles); `getVisibleGroupIds`.
- `AccessGrantServiceTest`: direct grant env; direct grant N groups in one call;
  upsert changes level + audits + notifies (regression for the silent-update
  bug); `clearExpiry`; approve-request path produces an identical row with
  `initiation=REQUEST`.
- `AccessGrantControllerTest`: §5 matrix — global admin, global env-admin,
  per-env ADMIN grantee (allowed only in their env), USER (403).
- `VmOperationsControllerTest`: group-scoped USER can start their group; gets
  403 naming the disallowed group when the request also targets another;
  "whole environment" start runs only visible groups.
- Read filtering: `GET /environments/{id}` hides non-visible groups for a
  group-scoped user; shows all for env-level.
- `AccessExpirationScheduler`: a GROUP grant expires and notifies with the group
  name.

## 17. Out of scope for v1 (v2 backlog)

- Group-scoped locks (§12).
- Deny / read-only overrides.
- Group bundles / tag-based grants.
- True per-VM grants for EC2.
- A dedicated per-group admin role.
- Full group-awareness of dashboards / cost / weekly digests (phase after core).

## 18. Decisions (locked 2026-09-09)

1. **Table & entity keep their names** (`environment_access` / `EnvironmentAccess`);
   scope columns are added, not a rename. (§6.1)
2. **A per-environment ADMIN grantee may grant environment-level ADMIN** — keeps
   today's `canManageEnvironmentAccess` behaviour; peer escalation is allowed.
3. **Feature flag on first release: yes.** `access.group-scope.enabled`, default
   `true`. `false` = exactly today's behaviour (no Scope selector, env-only
   operation authz, group endpoints return 404).
4. **Interim notification behaviour** (until §9): group-scoped users get
   operation notifications for their groups only and are excluded from
   environment-wide digests.
5. **Uniqueness of active grants** is application-enforced by the `applyGrant`
   upsert; no DB unique index. (§6.1)

## 19. Implementation steps

Each step is one commit, compiles on its own, and (1–7) changes no user-visible
behaviour until the step that turns it on. Tests are written per step but the
suite only runs where Docker is available (Testcontainers MySQL).

| # | Step | Touches | Turns anything on? |
|---|------|---------|--------------------|
| **1** | **Scope columns, enums, repo finders.** `AccessScopeType {ENVIRONMENT, GROUP}`, `AccessInitiation {DIRECT, REQUEST}`; V20 migration (+ backfill); new fields on `EnvironmentAccess` and `EnvironmentAccessRequest`; add `scope_type = 'ENVIRONMENT'` to the four strict env-auth queries (`findActiveAccess`, `hasAccess`, `hasAccessLevel`, `findByUserWithMinAccessLevel` — no-op today); new unused finders `findActiveByUserAndScope`, `findActiveByScopeTypeAndScopeId`, `findActiveByScopeTypeAndScopeIdIn`. | model, repo, migration | No — inert |
| **2** | **Converge grant + approve on `applyGrant()` (F3).** Extract `applyGrant(GrantSpec, actor)`; route `grantAccess` (DIRECT) and `approveRequest` (REQUEST, `sourceRequestId` set) through it; fix the update branch to audit + notify + fire automation + honour `clearExpiry`. New `NotificationType.ACCESS_LEVEL_CHANGED` + `AuditAction.ACCESS_LEVEL_CHANGED`. Still `ENVIRONMENT` scope only. | `EnvironmentAccessService`, `NotificationService`, `AuditService`, enums | Only the bug fixes |
| **3** | **Group-aware `SecurityService`.** `effectiveEnvLevel`, `effectiveLevel(user, group)`, `hasGroupAccess`, `hasGroupAccessLevel`, `getVisibleGroupIds(environmentId)`, `canManageGroupAccess(groupId)`. Pure additions, nothing calls them yet. Heavy unit tests for composition. | `SecurityService` | No |
| **4** | **Grant API accepts GROUP scope.** `AccessGrantController` (`POST /api/v1/access-grants`, `PATCH /{id}`, `DELETE /{id}`), `GrantAccessDTO` v2, `AccessGrantDTO` (carries `scopeType`/`scopeId`/`scopeName`). `applyGrant` handles N group targets per call. Enforce §5 matrix via `canManageEnvironmentAccess` / `canManageGroupAccess` (F4). Old routes become thin delegates. `GET /environments/{id}/access` and `/users/me/access` return group rows. `AccessExpirationScheduler` expires GROUP rows. Gated by `access.group-scope.enabled`. | controllers, DTOs, service, scheduler, config | **Yes** — admins can grant group scope via API |
| **5** | **Operation authorization honours group scope.** `VmOperationsController.startOperation` + `VmOperationsService.resolveTargetVms`: resolve target groups → require ≥USER on each → 403 fail-fast naming the disallowed groups; "whole environment" for a group-scoped caller = visible groups only. | `VmOperationsController`, `VmOperationsService` | **Yes** |
| **6** | **Read-path visibility filtering.** `GET /environments/{id}` detail, insights, group/VM lists filter to `getVisibleGroupIds`; the "My Environments" list de-dupes environments reachable only through a group grant. | `EnvironmentController`, `VmMgmtController`, services | **Yes** |
| **7** | **Request channel accepts GROUP scope.** Request create/approve carry `scopeType`/`scopeId`; reviewer set unchanged; approval flows through `applyGrant` (already wired in step 2). | `EnvironmentAccessController`, request DTO/service | **Yes** |
| **8** | **Frontend.** Redesigned Grant/Manage Access modal (scope toggle + group checklist w/ VM counts, live summary); "Manage access" entry points on env detail + group cards; Scope column + edit flow in the access table; grouped "My Access". `build-frontend.ps1`. | `static/js`, `static/css` | **Yes** — the feature is usable |
| **9** | **Long tail (may ship after v1).** Group-awareness in `DashboardSummaryService`, `CostEstimationService`, `WeeklyReportService`, and `NotificationService` recipient resolution. | those services | polish |

Mermaid dependency: `1 → 2 → 3 → 4 → 5,6,7 → 8 → 9` (5, 6, 7 are parallel after 4).
