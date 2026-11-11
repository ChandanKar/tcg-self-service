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

**The app under test.** Run it on a `_test` schema with `ENTRAID_ENABLED=false`
and `TEST_DEV_USER_ID=user-001` / `TEST_DEV_ADMIN_ID=admin-001` here (dev
`X-User-Id` sign-in; without them nearly every spec skips). Start it with
`SECURITY_ENV_ADMIN_SCOPE=global`: since U21-T11 the app limits environment
admins to environments they hold an Admin grant on, and the seeded environment
admins hold none, so specs such as `operations/approvals.spec.ts` fail under the
default `assigned` (the Java tests pin `global` the same way). Some specs also
need features that are off by default (leases for the environment alerts and the
start dialog's lengths, Teams channels); on an app without them those specs fail
the same way on `main`.

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
├── playwright.config.ts
├── fixtures/
│   ├── auth.ts          # signIn() with the dev X-User-Id header or the password form; devIds
│   ├── seed.ts          # seedEnvironment(): a fresh environment per spec, never shared data
│   ├── mock-api.ts      # faked API answers for pages that need specific data
│   ├── capabilities.ts  # withFeatures(): force feature switches on or off for a spec
│   └── axe.ts           # seriousViolations(): axe with the WCAG 2.2 AA rules, exceptions with reasons
├── ux-review/           # compare-mockup.mjs (mockup next to the app), seed-and-shoot.mjs, seed.mjs
└── tests/               # 100 specs
    ├── a11y/            # axe on every screen and panel (contrast.spec), names and targets, focus, tokens
    ├── access/          # request access, approvals of access, My account
    ├── admin/           # settings, AWS accounts
    ├── auth/            # unauthenticated access is blocked; logout clears the session
    ├── automation/      # schedules, windows, overrides, holiday calendars, suggestions
    ├── cost/            # cost management tabs, savings, waste, budgets, chargeback, setup
    ├── dashboard/       # Home for each role
    ├── environments/    # the environment page, VM table, leases, locks, teams, chat actions
    ├── inventory/       # VM registry, adding VMs, discovery review
    ├── mobile/          # phone layout: shell, Home, environment, every route per persona
    ├── navigation/      # shell, menu, router, help, states, load failures, fit to 1440 x 900
    ├── operations/      # start and stop progress, history, approvals of starts
    ├── security/        # activity and audit logs, system health, known findings
    └── smoke/           # no login: is the app up, the login page
```

Specs added since 2026-10 live under `tests/`, which is gitignored for new files: add a test to an
existing spec in the right folder (and stage it with `git add -u test/tests`).

## 6. Checks that hold the redesign in place

- **Fits the screen**: `navigation/shell-layout.spec.ts` checks every route for each persona
  (admin-001, dev-user-001, user-001, user-002) at 1440 x 900: neither the page nor the main area
  scrolls; long lists page instead. `mobile/phone-layout.spec.ts` checks every route at 390 x 844:
  nothing wider than the screen, wide tables as labelled cards, 44 px targets.
- **Accessibility**: `a11y/contrast.spec.ts` runs axe (WCAG 2.2 AA) on every route for each persona,
  the side panels, the notifications, the shortcuts dialog and the start confirm; serious and critical
  findings fail it.
- **States**: `navigation/status-widgets.spec.ts` fails every read with a 500 and expects each page
  to show "X didn't load" with Try again, never a blank area; `navigation/background-polls.spec.ts`
  covers "Reconnecting…" and the signed-out dialog.
- **The mockup**: with the smoke app running,
  `node ux-review/compare-mockup.mjs --runtime <dc-runtime.js>` writes a side-by-side report of every
  board (`docs/design/mockup`) and its app screen to `test-results/mockup-compare/report.html`
  (see `docs/design/mockup/README.md` for the runtime).

Not covered on purpose: real starts and stops against AWS (only the VMs on the allowlist may be used,
by hand), EKS (no cluster to test against), and the Microsoft Entra ID sign-in itself (see §3).

## 7. Extending

Add new specs following the existing pattern: import `loginAs`/`creds` from
`../../fixtures/auth`, `test.skip()` when required creds/fixture IDs are
missing, and prefer ids, roles and accessible names (`getByRole('button', { name: … })`) over
CSS classes. Wait for the first page (`#content-area h1`) before changing the hash: an early hash
change can load a page twice.
