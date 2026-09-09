# Requirement — Onboard a directory user from the admin panel (Microsoft Graph)

Status: **DRAFT — awaiting "lock it"**
Owner: platform
Related: [access-grants-requirement.md](access-grants-requirement.md)

---

## 1. Problem

An `app_user` row is created **only** on a user's first successful Entra ID sign-in
(`CustomOAuth2UserService.loadUser` → `UserService.findOrCreateUser`). There is no
create-user endpoint. Every admin workflow that must reference a person before that
first login is therefore blocked:

- Grant environment / group access (`EnvironmentAccessService.grantScoped` does
  `userService.getUserByEmail(...).orElseThrow()`).
- Pre-assign `admin` / `env_admin`.
- Find a not-yet-onboarded colleague in the "Grant Access" typeahead
  (`/api/v1/users/search` only returns existing rows).

Result: a new hire cannot be prepared in the platform until they have logged in at
least once. Bulk onboarding is impossible.

## 2. Chosen approach

**Microsoft Graph directory lookup**, entry point in the **User Management** admin
panel. An admin searches the Entra ID directory, picks the real person, and the
platform creates their `app_user` record immediately — optionally with a first
environment/group grant in the same action. On that person's first Entra sign-in the
record is adopted by `oid`; no duplicate.

### 2.1 Locked decisions

| # | Decision | Choice |
|---|---|---|
| D1 | Graph application permission | **`User.ReadBasic.All`** (application, tenant-admin consent). Enough for search + onboard. |
| D2 | Behaviour when Graph is unavailable (dev mode, or consent not yet granted) | **Manual-entry fallback** — an ADMIN may type email + display name; the row is created without `azure_ad_object_id` and flagged *unverified*. |
| D3 | Who may onboard | **ADMIN only** (v1). ENV_ADMIN revisited later. |
| D4 | Flow shape | **Onboard & grant combined** — the onboard dialog optionally carries a first env/group grant, applied atomically with the user create. |

### 2.2 Non-goals (v1)

- No group / manager / org-chart sync from Graph.
- No SCIM or automated (event-driven) provisioning.
- No bulk CSV import (separate feature).
- No de-provisioning sync — a user disabled in Entra is **not** auto-deactivated here.
- ENV_ADMIN cannot onboard.
- Manual-entry rows are not validated against the directory (D2); the UPN-vs-email
  first-login mismatch risk applies to those rows only.

## 3. Architecture

### 3.1 Graph authentication — no new dependency

The app is already a confidential client (`AZURE_CLIENT_ID` / `AZURE_CLIENT_SECRET` /
`AZURE_TENANT_ID`) and already pulls in `spring-boot-starter-oauth2-client`.

- New client registration **`graph`**: `authorization-grant-type=client_credentials`,
  `scope=https://graph.microsoft.com/.default`, provider `token-uri =
  https://login.microsoftonline.com/${AZURE_TENANT_ID}/oauth2/v2.0/token`.
- Token lifecycle handled by
  `AuthorizedClientServiceOAuth2AuthorizedClientManager` (service-level, no servlet
  request needed) — caches and refreshes automatically.
- Graph is called with a plain `RestClient` (Spring Framework 6.2, already on the
  classpath via `starter-web`). Only two Graph calls are needed, so **no Graph SDK**.

### 3.2 Ops prerequisite (not code)

On the existing Azure app registration:
1. API permissions → Microsoft Graph → **Application permissions** → `User.ReadBasic.All`.
2. **Grant admin consent** for the tenant.
3. Set `GRAPH_DIRECTORY_ENABLED=true`.

Until all three are done, `graph.directory.enabled` stays `false` and the feature
degrades to the D2 manual path. The rest of the app is unaffected.

### 3.3 Feature flag

`graph.directory.enabled` (default `false`). Effective only when `entraid.enabled=true`
as well (client-credentials needs the Azure registration). When off:
- `GET /api/v1/directory/search` → `409 Conflict` `{ "error": "directory_lookup_disabled" }`.
- The onboard dialog renders the manual form only.

## 4. Data model

First-login adoption already works: `findOrCreateUser` matches `findByAzureAdObjectId(oid)`
first, then falls back to `findByEmail`. A Graph-onboarded row carries the authoritative
`oid`, so it is adopted by the primary path — **no schema change needed for adoption**.

Add two nullable columns for provenance + reporting (migration **V21**):

| Column | Type | Meaning |
|---|---|---|
| `app_user.onboarded_by` | `VARCHAR(36) NULL` | `user_id` of the admin who onboarded this row; `NULL` for self-registered or legacy rows. |
| `app_user.onboarded_at` | `TIMESTAMP NULL` | when onboarded. |

"Not signed in yet" = `last_login_at IS NULL` (no new column). "Unverified" (D2 manual
row) = `azure_ad_object_id IS NULL AND onboarded_by IS NOT NULL`.

Existing unique constraints on `email` and `azure_ad_object_id` are the dedupe backstop.

## 5. API

### 5.1 `GET /api/v1/directory/search`

- Auth: `@PreAuthorize("hasRole('ADMIN')")`.
- Query: `q` (required, trimmed, min 2 chars, `"` and control chars stripped),
  `top` (optional, default 15, max 25).
- Backend: `GET https://graph.microsoft.com/v1.0/users`
  `?$search="displayName:{q}" OR "mail:{q}" OR "userPrincipalName:{q}"`
  `&$select=id,displayName,mail,userPrincipalName&$top={top}`
  header `ConsistencyLevel: eventual`. 5 s connect/read timeout.
- Enrich each hit with `alreadyInApp` / `appUserId` via `userRepository.findByAzureAdObjectId`
  (fall back to `findByEmail`).
- Response `200`: `DirectoryUserDTO[]`
  `{ directoryObjectId, displayName, email, userPrincipalName, alreadyInApp, appUserId }`.
- `409` when `graph.directory.enabled=false`; `502 { "error": "directory_unavailable" }`
  on Graph timeout / error (client then offers the manual form).

### 5.2 `POST /api/v1/users`

- Auth: `@PreAuthorize("hasRole('ADMIN')")`.
- Body (`OnboardUserDTO`):
  ```
  directoryObjectId : string | null   // present → Graph-verified path
  email             : string          // required; ignored server-side when directoryObjectId set
  displayName       : string | null   // ignored server-side when directoryObjectId set
  admin             : bool = false
  envAdmin          : bool = false
  initialGrant      : {               // optional (D4)
      environmentId : string
      accessLevel   : VIEWER|USER|ADMIN
      scopeType     : ENVIRONMENT|GROUP
      groupIds      : string[]        // required + non-empty when scopeType=GROUP
      durationDays  : int | null
  } | null
  ```
- Service (`UserService.onboardUser`), one `@Transactional`:
  1. If `directoryObjectId` set and `graph.directory.enabled`: re-fetch the user from
     Graph by id (`$select=id,displayName,mail,userPrincipalName`); trust **that**
     `id` / `mail ?? userPrincipalName` / `displayName`, not the client body. If Graph
     fails → `502`.
  2. If `directoryObjectId` null: D2 manual path — require non-blank `email` +
     `displayName`; `azureAdObjectId` stays null.
  3. Dedupe: `findByAzureAdObjectId` then `findByEmail`.
     - active match → `409` + the existing `UserDTO` (UI selects them instead).
     - inactive match → `409 { error: "user_inactive", userId }` (UI offers reactivate).
  4. Create: `userId=UUID`, `email`, `displayName`, `azureAdObjectId`, `isActive=true`,
     `admin`, `envAdmin`, `onboardedBy=actor`, `onboardedAt=now`, `lastLoginAt=null`.
     `saveAndFlush`.
  5. Audit `USER_ONBOARDED` (actor, new userId, email, `"directory"` | `"manual"`).
  6. If `initialGrant` present: build an `AccessGrantRequestDTO` with the new user's
     email and delegate to `EnvironmentAccessService.grantScoped(actorUserId, dto)` —
     same `applyGrant` path, same audit + notification. Any validation failure there
     rolls the whole transaction back (no orphan user).
- Response `201`: `{ user: UserDTO, grants: EnvironmentAccessDTO[] }`.

### 5.3 First-login adoption (`UserService.findOrCreateUser`)

- `oid` match on an onboarded row: also refresh `email` when the real token's
  `mail`/`preferred_username` differs from what was stored (Graph `mail` can be null at
  onboard time → UPN stored → real login may bring a better address). Set
  `lastLoginAt`. No other behaviour change.
- Optional: audit `USER_FIRST_LOGIN` when `onboardedBy != null` and `lastLoginAt` was null.

## 6. Frontend — User Management admin panel

- **"Onboard User"** button beside `#btn-refresh-users` / the search bar in
  `user-management.js` → `buildViewHtml`.
- **Modal** (`Modals.show`):
  - When `graph.directory.enabled`: a directory typeahead (debounced 300 ms, min 2
    chars) → `GET /api/v1/directory/search`. Result rows show name, email, and a
    disabled "Already in app" state for `alreadyInApp`. Selecting a row locks in
    `directoryObjectId` + name/email (read-only).
  - When disabled / on `502`: a plain form — email + display name, with an
    *"Unverified — not checked against the directory"* note.
  - Role checkboxes: **Admin**, **Env Admin** (default off).
  - **Optional grant section (D4):** "Also grant access now" → environment typeahead +
    access level + (when the env has groups and `access.group-scope.enabled`) a group
    checklist. Reuses the pickers from `access-management.js`.
  - Submit → `POST /api/v1/users`. On `409` active-match, show "already a user" and,
    if a grant was filled, offer to apply just the grant.
- After success: toast, refresh the table. `buildUserRow` shows a **"Not signed in
  yet"** badge when `lastLoginAt == null`, and an **"Unverified"** badge when the row
  is a manual onboard.
- `config.js`: `directory: { search }`, `users.create`.
- `build-frontend.ps1` after JS/CSS changes.

## 7. Config keys

`application.properties`:
```
spring.security.oauth2.client.registration.graph.provider=graph
spring.security.oauth2.client.registration.graph.client-id=${AZURE_CLIENT_ID}
spring.security.oauth2.client.registration.graph.client-secret=${AZURE_CLIENT_SECRET}
spring.security.oauth2.client.registration.graph.authorization-grant-type=client_credentials
spring.security.oauth2.client.registration.graph.scope=https://graph.microsoft.com/.default
spring.security.oauth2.client.provider.graph.token-uri=https://login.microsoftonline.com/${AZURE_TENANT_ID}/oauth2/v2.0/token

graph.api.base-url=${GRAPH_API_BASE_URL:https://graph.microsoft.com/v1.0}
graph.directory.enabled=${GRAPH_DIRECTORY_ENABLED:false}
graph.api.timeout-ms=${GRAPH_API_TIMEOUT_MS:5000}
```
`.env.example`: `GRAPH_DIRECTORY_ENABLED=false`.

## 8. Security / resilience notes

- Never log the Graph access token. Log actor + query term + hit count at DEBUG only.
- `$search` requires `ConsistencyLevel: eventual`; strip `"` from `q` to prevent
  breaking out of the `$search` string.
- `RestClient` timeout 5 s; Graph error → `502`, never a 500 stack to the client.
- `POST /users` is ADMIN-only, so the delegated grant's
  `canManageEnvironmentAccess` / `canManageGroupAccess` checks always pass — but they
  are still executed for a single audit/notification code path.
- No caching of search results (directory changes); a 60 s micro-cache of identical
  `(q, top)` is allowed.

## 9. Build plan — one commit per step

1. **Graph client plumbing** — `graph` registration (properties + `.env.example`),
   `GraphConfig` (`RestClient` bean + `AuthorizedClientServiceOAuth2AuthorizedClientManager`),
   `graph.directory.enabled` / `graph.api.*` props. No endpoint yet.
2. **`GraphDirectoryService` + `DirectoryUserDTO` + `GET /api/v1/directory/search`** —
   `$search` query, input escaping, timeout, `alreadyInApp` enrichment, ADMIN-gated.
   Unit tests with `MockRestServiceServer` / a stub `RestClient`.
3. **Onboard endpoint** — `OnboardUserDTO`, `UserService.onboardUser` (Graph re-fetch,
   dedupe, create, audit), `AuditAction.USER_ONBOARDED`, migration **V21**
   (`onboarded_by`, `onboarded_at`), `POST /api/v1/users`. `initialGrant` delegation
   to `EnvironmentAccessService.grantScoped`. Tests.
4. **First-login adoption polish** — `findOrCreateUser` email refresh on `oid` match;
   test that an onboarded row is adopted, not duplicated.
5. **Admin-panel UI** — "Onboard User" button + modal (directory typeahead + manual
   fallback + role checkboxes + optional grant section), `config.js`, "Not signed in
   yet" / "Unverified" badges, `build-frontend.ps1`.
6. **Docs** — finalize this file; README + `.env.example` notes on the `User.ReadBasic.All`
   consent and `GRAPH_DIRECTORY_ENABLED`.

## 10. v2 backlog

- ENV_ADMIN may onboard (scoped to their environments; still ADMIN-only for global roles).
- "Onboard" fall-through inside the existing Grant Access modal's user search.
- Bulk CSV onboarding.
- Optional Entra `accountEnabled` / `userType` display (needs `User.Read.All`).
- De-provisioning: nightly reconcile against Entra, flag/disable removed accounts.
