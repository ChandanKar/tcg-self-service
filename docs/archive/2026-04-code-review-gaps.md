# TCG Self-Service VM Control Platform — Full Code Review

## 1. MISSING / INCOMPLETE FEATURES

### Backend — Critical Gaps

| # | Feature | Status | Detail |
|---|---------|--------|--------|
| 1 | **Password Hashing** | 🔴 Missing | `AuthenticationService` stores/compares passwords in **plain text**. 680 legacy users affected. Must use bcrypt/Argon2. |
| 2 | **RBAC Enforcement Gaps** | 🔴 Partial | Several controllers rely on `SecurityService` checks but `@PreAuthorize` is not consistently applied. `DefaultSecurityConfig` permits all when `entraid.enabled=false`. |
| 3 | **NotificationService** | 🔴 Not built | `LockService` has a TODO to notify users when an admin breaks their lock. No notification controller, service, or transport exists. |
| 4 | **Azure/GCP Cloud Providers** | 🟡 Stub only | `CloudProviderFactory` + `CloudProviderService` interface exist, but only `AwsCloudProviderService` is implemented. Azure & GCP are referenced in enums but have **no implementation**. |
| 5 | **OAuth2 / Entra ID Not Active** | 🟡 Disabled | Config exists (`EntraidSecurityConfig`, `CustomOAuth2UserService`) but toggled off (`entraid.enabled=false`). No production auth path is active. |
| 6 | **Access Expiration Processing** | 🟡 Partial | `AccessExpirationScheduler` runs daily at 1 AM, but the `processExpiredAccess()` method logic should be verified for completeness. |
| 7 | **H2 Console Exposed** | 🟡 Security | Both security configs permit `/h2-console/**`. Must be disabled for production. |
| 8 | **CSRF Disabled for APIs** | 🟡 Risk | CSRF ignored for `/api/**` — acceptable for stateless JWT/token auth, but current session-based auth means **CSRF protection is missing**. |
| 9 | **Input Sanitization** | 🟡 Weak | DTOs use `@Valid` with bean validation, but no custom sanitization for freeform TEXT fields (description, metadata, notes) — potential stored XSS / SQL injection vectors. |
| 10 | **Automation/Scheduling Engine** | 🔴 Not built | No backend support for scheduled VM start/stop rules (frontend has mock UI for this). |
| 11 | **System Health / Metrics API** | 🔴 Not built | No Spring Actuator or custom health endpoint for the "System Health" feature shown in frontend mocks. |
| 12 | **Cost Tracking** | 🔴 Not built | No entity, service, or API for cloud cost data. |
| 13 | **Email Integration** | 🔴 Not built | No email transport configured for notifications or access request alerts. |
| 14 | **E2E / Security Tests** | 🔴 Missing | 197 unit/integration tests exist, but no E2E, load, or security test suites. |

### Frontend — Critical Gaps

| # | Feature | Status | Detail |
|---|---------|--------|--------|
| 1 | **Access Management UI** | 🔴 Mock only | `js/features/features.js` renders hardcoded HTML; no API integration for managing environment access grants. |
| 2 | **Automation Rules UI** | 🔴 Mock only | Shows 3 hardcoded rules. Backend for scheduled operations doesn't exist either. |
| 3 | **System Health UI** | 🔴 Mock only | Static badges. No backend API to consume. |
| 4 | **Cost Management** | 🔴 Placeholder | Sidebar link exists but feature not implemented. |
| 5 | **Settings Page** | 🔴 Placeholder | No settings/preferences UI. |
| 6 | **Help & Documentation** | 🔴 Placeholder | No in-app help or docs viewer. |
| 7 | **User Management** | 🟡 Scaffolded | Template (`templates/features/user-management.html`) and CSS exist; JS integration may be incomplete. No dedicated `user-management.js` feature module found. |
| 8 | **WebSocket Real-time** | 🟡 Gap | All real-time updates use HTTP polling (2–60s intervals). No WebSocket/SSE for instant updates during VM operations. |
| 9 | **Responsive/Mobile UI** | 🟡 Unknown | Bootstrap 5 used, but no evidence of mobile-specific testing or PWA support. |

---

## 2. REDUNDANT & UNUSED CODE

### Files to Delete

| File/Directory | Reason |
|----------------|--------|
| `home.html` | **Dead code** — Duplicate of `index.html` with inline CSS. Not referenced by any route. |
| `home-original.html` | **Dead code** — Older mockup version of home.html. |
| `old_backup/` directory (7 files) | **Dead code** — Old prototypes with mock data (`backup/`, `backup2/`, `mock-v2.html`, `mok-v3.html`). Completely superseded. |
| `dist/` directory (`app.js`, `app.css`) | **Likely dead** — Build artifacts not loaded by index.html. Individual modules are loaded directly. |

### Backend Redundancy

| Item | Detail |
|------|--------|
| **`SecurityService`** | Methods like `canAccessVm()`, `canAccessGroup()` exist but appear underutilized. Either integrate fully with `@PreAuthorize` or remove. |
| **`HomeController`** routes for `home.html` | Routes to dead HTML pages (`/home` → `index.html` is fine, but the `home.html` file itself is unused). |
| **Duplicate build output** | `build/resources/main/static/` mirrors `src/main/resources/static/` — standard Gradle behavior, but the dead files above are copied into build output too. |

### Frontend Redundancy

| Item | Detail |
|------|--------|
| **`features.js`** | Contains mock implementations for 3 features. The `loadVmRegistry()` function is just a shim delegating to `VmRegistry.load()` — redundant. |
| **3 duplicate audit log modules** | `activity-logs.js`, `all-logs.js`, and `audit-logs.js` — three separate modules querying the same `/api/v1/audit/logs` endpoint with varying filters. Could be consolidated into one module with configurable scope. |
| **`css/features/user-management.css`** | CSS exists but no `user-management.js` module — either implement the feature or remove the CSS. |

---

## 3. SECURITY ISSUES — Priority Order

| Priority | Issue | Location | Fix |
|----------|-------|----------|-----|
| 🔴 P0 | Plain-text passwords | `AuthenticationService`, `User` entity | Implement bcrypt hashing immediately |
| 🔴 P0 | Default security permits all | `DefaultSecurityConfig` | Remove or restrict; never deploy with `entraid.enabled=false` |
| 🔴 P1 | AWS credentials in config | `AwsConfig` → `application.properties` | Use IAM roles, env vars, or AWS Secrets Manager |
| 🟡 P2 | H2 console exposed | Both security configs | Disable for non-dev profiles |
| 🟡 P2 | CSRF disabled with session auth | `EntraidSecurityConfig` | Enable CSRF for session-based auth, or switch to stateless tokens |
| 🟡 P3 | No rate limiting | Login endpoint | Add rate limiting to prevent brute force |

---

## 4. QUICK WINS (< 1 day each)

1. **Delete dead files**: `home.html`, `home-original.html`, `old_backup/`, `dist/`
2. **Add `@PreAuthorize`** to any unprotected admin endpoints
3. **Implement password hashing** in `AuthenticationService` (bcrypt)
4. **Disable H2 console** in production profile
5. **Consolidate** the 3 audit log JS modules into one configurable module
6. **Implement `user-management.js`** — backend API already exists, template & CSS ready

---

## 5. SUMMARY METRICS

| Area | Total | Complete | Partial | Missing |
|------|-------|----------|---------|---------|
| Backend Controllers | 13 | 13 | — | — |
| Backend Services | 16+ | 14 | 2 | NotificationService |
| Backend Entities | 21 | 21 | — | — |
| REST Endpoints | 60+ | 60+ | — | — |
| Frontend Features | 16 | 10 | 1 | 5 |
| Tests | 197 | 196 pass | 1 expected fail | E2E, Security |
| Dead Files | 11 | — | — | Should delete |

**Bottom line**: The backend is ~90% production-ready but has critical security gaps (password hashing, RBAC enforcement). The frontend is ~65% complete with core workflows functional but admin/advanced features still mocked or missing. There are ~11 dead files that should be cleaned up.
