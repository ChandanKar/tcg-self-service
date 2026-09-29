# E2E tests (Playwright) — TCG VM Self-Service Platform

Standalone Playwright/TypeScript project under `test/`, deliberately kept
separate from the Gradle build (`build.gradle` is untouched; this has its own
`package.json`). It drives the running app as a real browser and over HTTP —
it does not import any Java code.

## 1. Prerequisites

- Node.js 18+ (this machine has Node 22, fine).
- A running instance of the app to test against — start it separately:
  ```bash
  cd ..
  ./gradlew bootRun
  ```
  (requires a root `.env` — see `.env.example` — and a reachable MySQL instance).
- At least one seeded user account to exercise anything behind login (see §3).
  This repo does not ship known test credentials — plaintext/legacy passwords
  exist in the DB per `docs/production-readiness-audit-2026-09-14.md` finding
  1, but their current validity is explicitly untested and they should not be
  used for anything (rotate/hash them, don't rely on them). Create a
  dedicated test account instead.

## 2. Install

```bash
cd test
npm install
npx playwright install chromium   # downloads the browser binary Playwright drives
```

## 3. Configure

```bash
cp .env.example .env
```

Fill in:

| Variable | Purpose |
|---|---|
| `BASE_URL` | Where the app is running (default `http://localhost:8080`) |
| `TEST_USERNAME` / `TEST_PASSWORD` | A regular (non-admin) account — powers dashboard/navigation/session tests |
| `TEST_ADMIN_USERNAME` / `TEST_ADMIN_PASSWORD` | An account with `admin=true` — powers admin-only navigation tests |
| `TEST_INVALID_PASSWORD` | Any string — used only for the "wrong password" test, never needs to be real |

**Every test that needs a session calls `test.skip()` when its required
credentials aren't set**, rather than failing. This means `npm test` is safe
to run with zero configuration (login/dashboard/nav tests just skip) — useful
for a first sanity check that the app is reachable at all (`tests/smoke/`).

Login goes through the legacy username/password endpoint
(`POST /api/auth/login`) rather than a real Microsoft Entra ID flow, because
that endpoint stays active even when `ENTRAID_ENABLED=true` (see
`docs/production-readiness-audit-2026-09-14.md`, finding 1) and Playwright has
no way to complete a real interactive Microsoft login without a dedicated
test tenant and stored credentials. If you need to test the actual Entra ID
SSO flow, that's a separate, manual exercise — not covered here.

## 4. Run

```bash
npm test              # full suite, headless
npm run test:headed   # see the browser
npm run test:ui       # Playwright's interactive UI mode — best for authoring/debugging
npm run test:smoke    # just tests/smoke (no login required)
npm run report        # open the last HTML report
```

## 5. Layout

```
test/
├── playwright.config.ts     # baseURL, projects, reporters
├── fixtures/
│   └── auth.ts              # creds from env, loginAs()/logout() helpers
└── tests/
    ├── smoke/                # no login needed — is the app even up?
    │   ├── health.spec.ts
    │   └── login.spec.ts
    ├── auth/
    │   └── session.spec.ts   # unauthenticated access is blocked; logout clears session
    ├── dashboard/
    │   └── dashboard.spec.ts
    ├── navigation/
    │   ├── navigation.spec.ts        # sidebar items every authenticated user sees
    │   └── navigation.admin.spec.ts  # admin-only sidebar items
    └── security/
        └── known-findings.spec.ts    # `test.fixme` regression guards tied to
                                       # specific cited findings in the repo's
                                       # 2026-09-14 production-readiness audit —
                                       # NOT currently passing, intentionally
                                       # skipped until the underlying issues
                                       # are fixed. See that file's header.
```

## 6. What this does and doesn't cover

**Covers:** login page rendering and validation, authenticated navigation
across the main sidebar views (user and admin), basic session/route
protection, app reachability (`/actuator/health`, Swagger).

**Doesn't cover (left for the next iteration):**
- VM start/stop/operation flows — these mutate real or mocked cloud state and
  need a deliberate test-environment strategy (a disposable AWS sandbox
  account or a stubbed `CloudProviderService`) before they're safe to
  automate; not attempted here.
- Environment locking, access-grant workflows, automation rules, cost
  management detail views — straightforward to add following the same
  `loginAs()` + `data-content`/`data-view` pattern once specific fixture data
  (known environment/group/VM IDs) is available in a test database.
- The real Microsoft Entra ID OAuth2 flow (see §3).
- Anything requiring the audit's flagged fixes to actually be testable
  end-to-end (e.g. cross-environment authorization) — see `tests/security/`.

## 7. Extending

Add new specs following the existing pattern: import `loginAs`/`creds` from
`../../fixtures/auth`, `test.skip()` when required creds/fixture IDs are
missing, and prefer the existing `id`/`data-view`/`data-content`/`data-table`
attributes already used throughout `home.html` over brittle CSS selectors —
see `.ai/engineering/ui-consistency.md` in the repo root for the frontend's
own component conventions if you're asserting on visual state (badges,
buttons, modals).
