# VM Self-Service Platform — UX & Visual Design Review

**Scope:** Fonts, type scale, CSS architecture, color usage, buttons, badges, modals, and cross-page consistency, across every page, menu, and dialog in the application.
**Method:** Source-level audit, not a rendered/visual walkthrough — this environment has no browser/screenshot tool available, so every finding below is grounded in the actual CSS/HTML/JS source (file + line) and, where a defect was suspected, verified by grepping for real call sites rather than assumed. Treat this as "what the code will render," cross-checked for internal consistency, not a substitute for clicking through the live app.
**Passes performed:**
1. Structural inventory — every page, sidebar/topnav menu item, modal, and template enumerated from `index.html`, `templates/**/*.html`.
2. Full read of all 31 CSS files (global tokens → layout → components → 11 feature stylesheets) extracting every color, font-size, radius, and shadow value.
3. Cross-file consistency pass — diffing those extracted values against each other and against the CSS custom properties declared in `main.css`.
4. Runtime verification pass — grepping actual JS call sites to confirm which competing/duplicate code paths are live vs. dead, rather than reporting suspected issues as fact.

**Excluded:** `home.html` and `home-original.html` are explicitly marked in-file as a deprecated mockup ("DO NOT modify this file... Production file: index.html") and were excluded from findings.

---

## How to read this

Each finding has a severity:

- **HIGH** — a typical user will notice it; it undermines trust, safety, or the app's own visual identity.
- **MEDIUM** — a careful/attentive user will notice it; inconsistent but not alarming.
- **LOW** — invisible to end users today, but a maintenance/drift risk (duplicated values, dead code, hardcoding).

Every finding includes exact file paths and line numbers so it can be located and fixed directly.

---

## Part A — Application Map

### A.1 Top Navigation (`index.html` lines 51–110)

| Element | Notes |
|---|---|
| Hamburger button | Toggles sidebar |
| Brand link (logo + "Self-Service Platform") | Links to `#/dashboard` |
| Search box (`nav-center`) | **Fully built** (icon, input, placeholder "Search environments, VMs, or groups...") but shipped with inline `style="display:none"` (index.html:64) and **has zero JS references anywhere** in `js/` — dead UI, permanently hidden, not wired to anything. |
| Sync indicator | "Last synced with cloud" pill |
| Notification bell | Dropdown, see Part C |
| User profile | Populated by `user-menu.js` |

### A.2 Sidebar Menus (`index.html` lines 112–284)

| Section | Items | Visibility |
|---|---|---|
| **MY WORKSPACE** (collapsed by default) | Running (badge), Locked by Me (badge), Favorites (submenu), Recents (submenu) | `#workspace-section` hidden until JS populates it |
| **OPERATIONS** | Dashboard, My Environments, Request Access, Pending Requests (badge), Activity Logs | Pending Requests is `env-admin-only` |
| **ADMIN** (collapsed by default) | User Management, Access Management, VM Registry, Automation Rules, Audit Logs (All), Cost Management, System Health | Whole section `admin-only`; User Management + Cost Management further restricted to `admin-only`; Access Management, VM Registry, Automation Rules, Audit Logs (All) restricted to `env-admin-only`; **System Health carries no role class of its own** — visible to anyone who can see the Admin section at all (Admin or Env Admin) |

### A.3 Mobile Bottom Nav (`index.html` lines 653–670)
Dashboard, Environments, Activity, More (only 4 of the ~12 sidebar destinations are reachable from mobile's primary nav; everything else lives behind "More").

### A.4 Pages (routed into `#content-area`)

| Route / Page | Template / Source | Layout style |
|---|---|---|
| Dashboard | `templates/features/dashboard.html` + `dashboard.js` | KPI grid + table, "zero scroll" density |
| My Environments (list) | `templates/features/environments-list.html` | Card list |
| Environment Detail | `templates/features/environment-detail.html` | Tabs: VMs / Activity / Access / Settings |
| Request Access | `templates/features/request-access.html` | Cards + tables |
| Pending Requests (admin) | `templates/features/pending-requests.html` | Request cards |
| My Activity Logs | `templates/features/activity-logs.html` | Timeline layout |
| Audit Logs (All, admin) | `templates/features/audit-logs.html` | Flat table layout |
| VM Registry (admin) | No template — 100% JS-generated (`vm-registry.js`) | Table, "Dashboard baseline" density (hand-copied) |
| Access Management (admin) | Template exists but is **entirely HTML-commented-out**; real markup generated in `access-management.js` | Two-panel workspace layout |
| User Management (admin) | Template exists but is **entirely HTML-commented-out**; real markup generated in `user-management.js` | Table |
| Cost Management (admin) | No template — 100% JS-generated (`cost-management.js`) | Reuses dashboard KPI/chart classes |
| System Health | No template — 100% JS-generated (`system-health.js`) | Compact cards + tables |
| Automation Rules (admin) | No template — 100% JS-generated (`automation-rules.js`) | Table + rule-builder modal |
| Login | `login.html` (standalone, not part of the SPA shell) | Centered card |

Five of the thirteen post-login pages (VM Registry, Cost Management, System Health, Automation Rules, and effectively Access/User Management) have **no static HTML to review by eye** — they only exist as template strings inside JS. This raises the risk of undetected drift because nobody can `diff` a template file to see what changed.

### A.5 Modals & Dialogs (catalogued by where they live)

| Modal | Defined in | Status |
|---|---|---|
| Manage Groups & VMs | `index.html` (static) | Live |
| Create/Edit Group | `index.html` (static, `#createGroupModal`) | **Live path** for Create; also a second, unused builder — see Finding H1/M-code below |
| Register VM | `index.html` (static, `#registerVmModal`) | **Live path**; a second, unused builder also exists — see below |
| Destructive Confirm | `index.html` (static, `#destructiveConfirmModal`) | **Built, fully wired internally, never invoked** — see Finding H1 |
| Lock Environment / Unlock / Force Unlock | `templates/features/locks.html` | Live |
| VM Operation Confirm / Bulk Operation / Operation Progress | `templates/features/vm-operations.html` | Live |
| VM Detail slideout | `templates/features/vm-operations.html` | Live |
| Request Access modal | `templates/features/request-access.html` | Live |
| Reject Request modal | `templates/features/pending-requests.html` | Live |
| Log Detail modal | **Built entirely in `audit-logs.js` at runtime** | Live — see Finding M-dead-template |
| `<template id="log-detail-modal-template">` (`#logDetailModal`) | `templates/features/audit-logs.html` | **Dead** — see below |
| Grant Access / Deny Request modals | JS-built in `access-management.js` (per code comments in `access-management.css`) | Live |
| Create Environment / Edit Environment | Dynamically built in `js/ui/modals.js` (`Modals.showCreateEnvironment/showEditEnvironment`) | Live (called from `vm-registry.js`, `environments.js`) |
| Generic Confirm / Prompt | `Modals.confirm()` / `Modals.prompt()` in `js/ui/modals.js` | Live, used by `locks.js`, `environments.js`, `access-requests.js` |

---

## Part B — Cross-Cutting Design System Findings

### B.1 Color

**`main.css` (lines 6–79) declares a real token set** — `--primary-color`, `--text-primary/secondary`, `--border-color`, `--background-light`, `--success/warning/danger/info-color`, plus a `--status-*-bg/text` pairing for badges. This is a legitimate design system. The problems are all about **adoption**, not absence.

**[HIGH] B1 — Dashboard's own status-color palette contradicts the app's status-color variables, on the highest-traffic page.**
`css/features/dashboard.css` defines its own five-color set for KPI dots and scheduler/list row labels (`.dashboard-dot.*`, `.dashboard-list-row em.*`, `.dashboard-scheduler-row em.*`, lines 244–266) and it's echoed again in `js/features/dashboard.js` (lines 388–397, and fed into the ECharts palettes at lines 429–430, 448, 523):

| Semantic | Dashboard's color | Rest-of-app `--*-color` variable (`main.css:27-30`) | Match? |
|---|---|---|---|
| Primary | `#2563eb` | `#2563eb` | ✅ |
| Success | `#059669` | `#10b981` | ❌ visibly darker/more saturated green |
| Warning | `#d97706` | `#f59e0b` | ❌ darker amber |
| Danger | `#dc2626` | `#ef4444` | ❌ darker red |
| Info | `#0891b2` (cyan) | `#3b82f6` (blue) | ❌ different hue family entirely |

A user who sees a green KPI dot on the Dashboard, then a green "Running" badge two clicks later on Environment Detail, is looking at two different greens. This isn't a 1-pixel nitpick — cyan vs. blue for "info" is a different color, not a different shade.

**[HIGH] B2 — `config.js` codes "Stopped" with the same red as "Error".**
`js/config.js` lines 213–218, the app's central VM-status config:
```
RUNNING:  color: '#10b981'   // matches --success-color ✓
STOPPED:  color: '#ef4444'   // = --danger-color
STARTING: color: '#f59e0b'   // = --warning-color
STOPPING: color: '#f59e0b'
UNKNOWN:  color: '#6b7280'
ERROR:    color: '#ef4444'   // = --danger-color, IDENTICAL to STOPPED
```
"Stopped" is a normal, frequently-reached, often user-intended state (most VMs sit stopped most of the time to save cost); "Error" is a failure. Coding them with the identical red is a semantic mismatch. It's inconsistent internally too: `badges.css`'s `.status-badge.stopped` correctly treats stopped as neutral gray (`--status-stopped-bg #f3f4f6` / `--status-stopped-text #4b5563`), so **whether a "stopped" VM reads as alarming red or calm gray depends entirely on which code path renders it** — the badge class vs. anything that reads `Config.VM_STATUS_CONFIG[status].color` directly (charts, dots, icon coloring in `environments.js:369,380`).

**[HIGH] B3 — Notification Bell dropdown is built on a different gray ramp than the rest of the app, and references two CSS variables that don't exist.**
`css/components/notification-bell.css` uses Tailwind's "gray" scale throughout: `#111827, #6b7280, #9ca3af, #d1d5db, #e5e7eb, #f3f4f6, #f9fafb, #fafafa, #4b5563`. Every other file in the app (main.css tokens, topnav, sidebar, content, cards, tables) uses Tailwind's "slate" scale: `#1e293b, #334155, #475569, #64748b, #94a3b8, #cbd5e1, #e2e8f0, #f1f5f9, #f8fafc`. Slate carries a faint cool-blue undertone; gray is neutral — side-by-side (the bell dropdown opens directly over the slate-toned top nav) the seam is visible.
Additionally, lines 11, 20, and 72 reference `var(--text-muted, #6b7280)` and `var(--primary, #3b82f6)` — **neither `--text-muted` nor `--primary` exists anywhere in `main.css`'s `:root`** (the real names are `--text-secondary` and `--primary-color`). These always silently resolve to their hardcoded fallback. It looks fine today only because the fallback happens to be close to the real brand blue — if `--primary-color` is ever retuned, the bell won't follow.

**[LOW] B4 — Pervasive hardcoding of colors that already have variables.**
`#64748b` (`--text-secondary`), `#94a3b8` (used 15+ times but never named as a variable at all — a de facto token that was never promoted), `#e2e8f0` (`--border-color`), `#f8fafc` (`--background-light`), and `#1e293b` (`--text-primary`) are hand-typed as raw hex in `modals.css`, `topnav.css`, `cards.css`, `operations.css`, `system-health.css`, and elsewhere, instead of `var(--text-secondary)` etc. — while `badges.css`, `tables.css`, `buttons.css`, and `access-management.css` consistently use the `var(--x, #fallback)` pattern. Notably `operations.css` shows both eras in the same file: its older `.operation-status-*` rules (top of file) hardcode hex; its newer "TASK-019" block (bottom of file) correctly uses `var(--success-color)` etc. The discipline exists, it's just unevenly applied.

**[LOW] B5 — Two different muted-danger hex values for what should be one "quiet red" token.** `buttons.css`'s `.btn-ghost.btn-outline-danger` uses `#d99490` (line 50) while `.btn-action.btn-danger` uses `#e2a29e` (line 120) — same intent (a desaturated red for at-rest danger actions), two different hand-picked hex values four lines apart in the same file's design philosophy.

### B.2 Typography

**[MEDIUM] B6 — No enforced micro type-scale; sizes proliferate in ~0.01–0.02rem steps.**
`main.css:33-39` declares a clean 7-step scale (`--font-size-xs` 12px through `--font-size-3xl` 32px). It's barely used. Instead, a second, informal scale — `--table-header-font` (0.7rem) / `--table-body-font` (0.81rem) — became the *real* de facto standard for table text and **is** reused correctly and consistently across `content.css`, `tables.css`, `badges.css`, `buttons.css`'s `.btn-action`, `access-management.css`, `access-requests.css`, `activity-logs.css`, `all-logs.css`, and `user-management.css`. That's a genuine strength.
Below that, though, there is no shared scale at all. `css/features/environments.css` alone (the VM Insights slideout section) introduces roughly two dozen one-off values: `0.62, 0.63, 0.64, 0.66, 0.67, 0.68, 0.70, 0.72, 0.73, 0.74, 0.76, 0.78, 0.8, 0.81, 0.82, 0.86, 0.9, 0.92, 0.95, 1.05, 1.18`rem — all serving the same 2–3 conceptual roles (tile caption, tile value, section label). `dashboard.css` independently contributes its own overlapping set (`.66/.68/.72/.76/.78/.86rem`). No single pair is visually distinguishable from its neighbor; the aggregate is a maintenance hazard, not a perceptible bug.
The "compact metric value" size is a good example: the base `.metric-card .metric-value` in `cards.css:29` is `2rem` (matches `--font-size-3xl`), but nearly every real page overrides it smaller for density, and each picked a different value: **1.55rem** (Dashboard, All Logs, User Management's stat-card), **1.45rem** (System Health), **1.25rem** (Environment Detail), **1.18rem** (Dashboard KPI tiles). Three of those four are close enough that a single shared `--font-size-metric-compact` token would visibly unify them.

**[MEDIUM] B7 — Chart text requests a font family that is never loaded anywhere in the app.**
`js/features/dashboard.js:696`: `fontFamily: 'Inter, Segoe UI, Arial, sans-serif'`. "Inter" is a specific web font; there is no `@font-face`, no Google Fonts `<link>`, nothing that loads it anywhere in `index.html` or `login.html`. The app's actual global font stack (`main.css:89`) is `-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif` — no "Inter" there either. In practice charts silently fall through to "Segoe UI" (fine on Windows, different on Mac/Linux), but the declared intent never renders for anyone. It's a small thing, but it is the one place in the codebase where the type stack diverges from the rest of the app on paper.

**[MEDIUM] B8 — Mobile page titles render *larger* than desktop — the opposite of typical responsive intent — because of a three-way rule conflict, one of which is fully dead.**
Three separate rules target `.content-header h1` with identical CSS specificity:
- `main.css:190-192` (inside `@media max-width:768px`): `font-size: 1.5rem` — **this rule never fires**, see below.
- `content.css:308-313` (unconditional, all viewport widths): `font-size: 1.2rem; font-weight:700; color:#1e293b`.
- `mobile.css:115-117` (inside `@media max-width:768px`): `font-size: 1.25rem`.

Load order in `index.html` (lines 14-43) is `main.css → topnav → sidebar → content.css → components/* → features/* → mobile.css` (last). With equal specificity, later-loaded wins. So: on desktop, only `content.css`'s rule applies → **1.2rem**. On mobile, both `main.css`'s media rule and `mobile.css`'s media rule apply — `mobile.css` loads later and wins → **1.25rem**. Net effect: page titles are bigger on phones than on desktop, and `main.css`'s `1.5rem` rule is unreachable dead code. This repeats the same pattern (a page header override with a stray one-off value, `1.25rem`, not `1.2rem`) that shows up again in `js/features/environments.js:538` (see B10 below).

**[LOW-MEDIUM] B18 — The left sidebar's own nav-item labels are the one major piece of text in the app with no explicit font-size — they render at the full inherited 16px while everything else was compacted down to 0.6–0.95rem.**
`css/layout/sidebar.css`: `.sidebar-menu-link` (the top-level items — Dashboard, My Environments, Request Access, etc.) and `.submenu-item` (the Favorites/Recents environment names nested under "MY WORKSPACE") declare **no `font-size` at all** — they inherit the browser/body default (16px, since `main.css` never sets a root font-size below that). Everything immediately around them in the same file *is* explicitly sized down: `.sidebar-section-title` (the "MY WORKSPACE"/"OPERATIONS"/"ADMIN" group headers) is `0.75rem`, `.sidebar-menu-link .badge` (the Running/Locked count pills) is `0.75rem`, `.submenu-item .btn-sm` (the little "View" button) is `0.7rem`, `.coming-soon-badge` is `0.6rem`. So the sidebar's internal hierarchy is inverted relative to everything else in the shell: its primary link labels are its single *largest* piece of text, sitting directly beside 12px section headers, while the main content area next to it runs almost entirely on 0.68–0.81rem table text. This might be a deliberate choice (larger click targets for primary navigation is defensible on its own merits), but nothing marks it as intentional the way the Dashboard/VM Registry density choices are explicitly commented elsewhere — it reads more like the sidebar was simply never brought into the same compacting pass everything else went through.

### B.3 Buttons, Badges & Modals

**[HIGH] B9 — The app's own polished "type-to-confirm" destructive-action modal is built, fully wired, and never called from anywhere.**
`js/ui/modals.js` (lines 871–1012) defines a complete `DestructiveConfirm` module: red modal header, an impact-list, and a **type the environment's name to enable the confirm button** pattern — genuinely good, deliberate safety UX, backed by real markup in `index.html:606-647` (`#destructiveConfirmModal`). It exports three ready-made convenience wrappers: `confirmStopAll`, `confirmBreakLock`, `confirmDeleteEnvironment`.
A search of the entire `static/` tree (HTML + JS) for `DestructiveConfirm` turns up exactly **one** match: its own definition. It is never invoked.
Meanwhile, the actual destructive actions in the app use a plain browser `window.confirm()` — no impact list, no typed confirmation, dismissible by an accidental Enter key, unstyled, unbrandable:
- `js/features/vm-registry.js:304` — **Delete Environment** ("Are you sure you want to delete... This action cannot be undone.") — this is *exactly* what `DestructiveConfirm.confirmDeleteEnvironment()` was written for, and doesn't use it.
- `js/features/vm-registry.js:646` — Delete Group
- `js/features/vm-registry.js:1000` — Remove VM
- `js/features/user-management.js:403` — Deactivate User
- `js/features/access-management.js:948` — Revoke Access (plus a raw `alert()` for errors at line 1124)
- `js/features/automation-rules.js:272` — Delete Automation Rule

Some destructive flows (Stop All / Break Lock, via `Modals.confirm()` in `locks.js:74`, `environments.js:767,1038`) at least get the app's *styled* Cancel/Confirm modal, just not the deluxe typed-confirmation one they were arguably designed for. The net picture: **the single most safety-critical piece of UI in the app — confirming an irreversible delete — is the least consistent and least polished interaction in the whole product**, ranging from a nice on-brand modal down to a bare OS dialog, seemingly by accident of which developer wrote which delete button.

**[MEDIUM] B10 — Two competing implementations exist for "Register VM" and "Create Group"; only one of each is actually live.**
`js/ui/modals.js` defines `Modals.showRegisterVm()` (lines 697–804) and `Modals.showCreateGroup()` (lines 611–692) — full modal builders with their own field sets. Neither has **any** call site anywhere in the codebase. The live versions are the static modals in `index.html` (`#registerVmModal` lines 476-603, `#createGroupModal` lines 417-473), driven by `Features.submitVm()`/`Features.submitGroup()` → `VmRegistry.submitVm()`/`VmRegistry.submitGroup()`. The two designs aren't even the same shape: the dead `Modals.showRegisterVm()` has a "VM Type" field and no AWS-import section; the live static modal has the AWS EC2-import picker plus Purpose/Remarks fields and no "VM Type" field. Nothing is broken today (the dead code isn't reachable), but it's a real trap for the next person who greps for "register VM modal," finds `showRegisterVm`, edits it, and ships a change nobody sees.

**[MEDIUM] B11 — Two visually different "status pill" shapes coexist for the same concept.**
The custom `.status-badge`, `.role-badge`, and `.access-level-badge` classes (`badges.css`) are all fully rounded pills (`border-radius: 9999px`). But several pages use Bootstrap's stock `<span class="badge bg-warning">`/`bg-success` directly (e.g., audit log action-type badges via `{{actionBadgeClass}}` in `templates/features/audit-logs.html:183`, and the enum-matched `.badge.bg-PENDING/.bg-APPROVED/.bg-DENIED/.bg-CANCELLED !important` overrides in `components/access.css:20-34`, which only override the *color*, not the *shape*). Bootstrap 5's default badge corner radius (~0.375rem) is a rounded rectangle, not a pill. So the exact same concept — "this is a status label" — is a pill on some pages/tables and a rounded rectangle on others. (`badges.css`'s own code comments confirm this exact class of bug — pill vs. rectangle — was already found and partially fixed once before for role/access-level badges; it just wasn't generalized to the rest of the badge usage in the app.)

**[LOW] B12 — `.metric-card:hover` is defined twice, with different shadow colors; one definition is dead.**
`main.css:133-137` sets the hover shadow to `rgba(0,0,0,0.1)` (neutral black); `cards.css:15-19` (loaded later, identical specificity) sets it to `rgba(37,99,235,0.15)` (blue-tinted) and adds a `border-color`. `cards.css` wins the cascade, so `main.css`'s copy is silently dead — a "two sources of truth" trap for whoever edits the wrong one.

**[LOW] B13 — Three parallel "standard" table systems; only one table in the whole app actually uses the one that's documented as the standard.**
`tables.css` defines `.table-baseline`, explicitly commented **"the STANDARD table style for all views"**. A grep for its actual usage across the codebase finds it applied in exactly one place: `cost-management.js`. Every other data table in the app (Dashboard, My Environments, VM Registry, Access Management, Access Requests, Activity Logs, All Logs, User Management, System Health) instead re-implements the identical padding/font-size values a second (or third) time via page-scoped ID selectors (`#dashboard-view .table`, `.vm-registry-table-wrapper .table`, etc.). Most of those do at least reference the shared `var(--table-body-font)`/`var(--table-cell-padding)` custom properties (good), but `vm-registry.css:102-113` hardcodes the literal numbers (`0.81rem`, `0.7rem`, `0.28rem 0.55rem`) instead — identical today, but the one table that won't follow if the density tokens are ever retuned centrally.

### B.4 Structural / Dead Code

**[MEDIUM] B14 — `templates/features/audit-logs.html`'s `<template id="log-detail-modal-template">` no longer reflects what users actually see.**
The template (lines 203–258) defines a nicely-labeled two-column modal (`id="logDetailModal"`, distinct `Timestamp`/`Status`/`User`/`IP Address`/`Action`/`Environment`/`Target`/`Additional Information` fields with `.form-label.small.text-muted` styling). But the actual runtime code (`js/features/audit-logs.js:666-697`, function `showLogDetailsModal`) builds a completely different, simpler modal from scratch via the shared `Modals.show()` helper — a flat `<table class="table table-sm">` of raw `<tr><th>/<td>` pairs, id `logDetailsModal` (note the extra "s"). A grep for `logDetailModal` (the template's id, no "s") across all of `js/` returns zero matches — the template is not instantiated by anything. The `#logDetailsModal`-scoped CSS in `components/audit.css:53-66` (`.table th` background, `pre` padding/background/scroll) is correctly targeting the **live** JS-built modal, not the dead template — so nothing is visually broken, but the shipped modal is plainer than the one a developer would assume exists from reading the template file, and the template itself is stale cruft worth deleting or reconciling.

**[LOW] B15 — `css/index.css` is a documentation-only file that is itself out of date.**
It's never linked from `index.html` (only `main.css` + individual component/feature files are). Its own "CSS ARCHITECTURE" comment (lines 9-55) lists only 5 feature stylesheets (dashboard, environments, access-requests, activity-logs, user-management) — 6 more now exist (`vm-registry.css`, `access-management.css`, `all-logs.css`, `system-health.css`, `cost-management.css`, `automation-rules.css`) and aren't mentioned. Harmless, but it's documentation actively describing a codebase that no longer exists.

**[LOW] B16 — Scrollbar styling is copy-pasted verbatim in at least 8 places.**
`background:#cbd5e1; border-radius:3px` for `::-webkit-scrollbar-thumb` appears identically in `main.css`, `sidebar.css`, `content.css` (×3 different wrappers), `vm-registry.css`, `activity-logs.css`, `all-logs.css`, `access-management.css`, `request-access` (`access-requests.css`), and `system-health.css` (×3). A single shared class (or one rule targeting a common wrapper attribute) would replace ~10 duplicated blocks.

**[LOW] B17 — Login page loads Bootstrap and Font Awesome from public CDNs while the rest of the app uses local `vendor/` copies.**
`login.html:9,12,84` point at `cdn.jsdelivr.net` and `cdnjs.cloudflare.com`; `index.html:9,11` (and `home.html`, deprecated) point at local `vendor/bootstrap/...` / `vendor/fontawesome/...`. Beyond the inconsistency, this means the login page — the one page every user hits first, including in air-gapped/offline demo environments — is the one page that will visibly break (unstyled Bootstrap-less form) if outbound internet to those two CDNs isn't available, while the rest of the app works fine offline.

### B.5 Responsive & Accessibility (brief — not the primary ask, noted for completeness)

- **Positive:** `mobile.css`'s "Touch Target Sizes (TASK-012)" block (lines 178–249) is a genuinely thorough, correctly-reasoned pass — 44px minimum targets on buttons/pagination/close buttons, and 16px form-control font size specifically to prevent iOS Safari's auto-zoom-on-focus. This is exactly the kind of detail most teams skip.
- **Positive:** `*:focus-visible` outlines (`main.css:223-247`) are implemented modernly (keyboard-only, not mouse-click) and applied to buttons, cards, sidebar links, and form controls.
- **[LOW]** No shared z-index scale — values are scattered magic numbers from 100 (`loading.css` overlay) up to 10000 (`.skip-link`), through 999/1000/1001/1030/1031/1032/1070/1080/3000/9999 across seven different files. Most of the topnav/mobile-sidebar/overlay layering is thoughtfully commented and internally consistent; the notification dropdown (3000) and toast container (9999) sit far above everything else with no documented reason why. No actual stacking conflict was found, but there's also no single source of truth, so the next added overlay is a coin flip.

### B.6 Pagination, Alerts & Toasts

**[POSITIVE, with two small leftovers] B19 — Pagination is the single strongest consistency story in the app.**
`js/ui/pagination.js` is a genuine success: one shared `Pagination.renderNumbered()` / `Pagination.buildSimpleMarkup()` module (its own header comment documents that it replaced eight independently-drifted "Showing X–Y of Z" bars) is used consistently by VM Registry, Cost Management, Audit Logs, All Logs, Activity Logs, Access Requests, User Management, Access Management, and Environments — nine call sites confirmed by grep, all rendering the same `.pagination-bar-wrap`/`.pg-btn` markup. The two render modes (a full numbered-page bar vs. a compact Prev/Next-only bar) are a deliberate, documented choice — Cost Management needs up to three bars on one screen, where a full numbered widget would be cramped. This is exactly the kind of unification the rest of the app's components (tables, badges, modals) are still working toward.
Two small leftovers from before this consolidation are worth cleaning up:
- `templates/common.html:85` and `templates/features/activity-logs.html:128` **both** still define a `<template id="pagination-template">` — a duplicate id across two files, and dead code: a grep of every JS file for `pagination-template` returns zero matches, confirming neither is ever loaded. Both pages actually render their pagination bar through `Pagination.renderNumbered()`, not either template.
- Within `.pagination-bar-wrap` itself, `.pagination-info` text is `0.75rem` (`components/pagination.css:23`) while the `.pg-btn` button labels sitting directly next to it are `0.72rem` (`components/pagination.css:41`) — a barely-there one-off size mismatch inside an otherwise exemplary, well-unified component.

**[LOW-MEDIUM] B20 — Alerts are consistent inside the app shell; the Login page runs a parallel, partly-dead alert styling of its own.**
Inside the SPA, every `.alert-warning/-info/-danger` usage (Dashboard's drift-detected banner, the three info/warning/danger alerts in `templates/features/locks.html`, the operation-warning alert in `templates/features/vm-operations.html`) relies on unmodified Bootstrap defaults — genuinely consistent, nothing to fix there.
`css/login.css`, however, defines two custom alert treatments that only exist on the login page: a custom `.alert-success` override (lines 224–236: different padding/border/radius/font-size than Bootstrap's default, which is what every other success alert in the app uses) and a hand-built `.login-error` class (lines 209–221). `login.html` itself never actually applies `.login-error` — its real error banner is plain `class="alert alert-danger"` (`login.html:31`) — so `.login-error` is dead CSS. Net effect: the login page's error alert renders as plain default-Bootstrap red, its success alert renders custom-styled, and neither matches how alerts look anywhere else in the app, for no reason tied to anything special about the login layout.
Two related, smaller notes:
- The toast notification system (`components/toasts.css`) is one of the most disciplined files in the app — consistent `var(--x, fallback)` usage throughout, plus a `prefers-reduced-motion` guard on its slide animations. Worth holding up as the template for how the rest of the app's status-color usage should look.
- `components/loading.css`'s bottom-corner `#connection-status` indicator (connected/disconnected/reconnecting) hardcodes `#10b981`/`#ef4444`/`#f59e0b` directly instead of `var(--success-color)` etc. — a fourth independent spot (after Dashboard's KPI colors, `config.js`, and assorted badge CSS) where the same three status colors get retyped by hand instead of referencing the token.

---

## Part C — Per-Page Notes

### Dashboard (default landing page)
- Home of Finding B1 (off-palette KPI/status colors) and B7 (unloaded chart font).
- `#dashboard-view .content-header h1` is declared identically in *both* `content.css:308` (generic) and `dashboard.css:17` (page-scoped) — redundant, not conflicting, but two places to keep in sync for one rule.
- Deliberately hyper-compact ("zero page scroll, maximum density" per `content.css:29` comment) — `.dashboard-kpi span` captions run at **0.68rem (≈10.9px)**, and `.metric-label-hint` at **0.63rem (≈10px)**. That's below the ~12px floor most accessibility guidance treats as a legibility minimum for supporting/caption text, on the page every user sees first.

### My Environments / Environment Detail
- `.metric-card` hover-lift is explicitly disabled here (`transform: none` overrides, `content.css:69,371`) while the same class animates elsewhere — same component, different interaction affordance depending on which page it's on.
- Environment Detail's "VM Insights" slideout (`environments.css` lines 289–995) is the single largest source of one-off font sizes in the app (see B6).

### VM Registry (admin)
- No template file — cannot be reviewed by eye, only by reading `vm-registry.js` template strings.
- Table density hardcoded rather than variable-driven (B13).
- Three separate native `confirm()` dialogs for its three delete actions (Environment/Group/VM) — see B9.
- EKS availability legend (`vm-registry.js:870-872`) uses three pastel swatches (`#b7f0cd`, `#f9e79f`, `#f5b7b1`) that match none of the app's existing status colors — a one-off palette invented just for this legend.

### Access Management / User Management (admin)
- Both templates in `templates/features/` are fully HTML-commented-out placeholders; actual markup lives only in JS. `templates/features/user-management.html:81-85` even leaves a comment admitting the real page still uses "simple `confirm()` dialogs" for lack of a built modal — confirmed still true (B9).
- To their credit, both feature CSS files (`access-management.css`, `access-requests.css`) are the most disciplined in the app — consistent `var(--x, fallback)` usage throughout and explicit comments stating they sync to "Dashboard baseline" density, and they actually succeed at it.

### Audit Logs (All) vs. My Activity Logs
- Two structurally different UIs for conceptually similar data: Audit Logs (All) is a dense flat table with a details modal (whose modal is the B14 dead-template case); My Activity Logs is a timeline/card layout (`log-entry`, `log-icon`, `log-content` in `activity-logs.css`/template). Not a "bug," but worth a deliberate decision on whether admins and end users should see the same event history in two different visual languages.

### Login
- Isolated from the rest of the design system in more ways than fonts (B17): it has its own copies of `.btn-login`/`.btn-entraid` button treatment in `login.css` (padding 10px, radius 6px) rather than reusing `components/buttons.css`. Reasonable given it's outside the SPA shell, but worth knowing it will silently diverge if the main app's buttons are restyled later.

### Cost Management / Automation Rules
- Best-architected of the JS-only pages: both explicitly comment that they intentionally reuse `.dashboard-kpi-grid`/`.metric-card`/`.status-badge`/`.btn-action` rather than inventing new classes, and only add what's genuinely unique to the page (`cost-management.css:1-6`, `automation-rules.css:1-5`). `cost-management.css:14-19`'s comment explaining *why* it overrides the KPI grid to `auto-fit` instead of the Dashboard's fixed 6 columns is a good example of a well-reasoned, well-documented responsive decision.

---

## Part D — What's Working Well

It's worth being explicit about this so the findings above read as "tighten the screws," not "start over" — the foundations are genuinely good:

- A real design-token layer exists (`main.css` `:root`) with a sensible typography scale, spacing scale, shadow scale, and status-color pairing, and large parts of the app (Access Management, Access Requests, User Management, Cost Management, Automation Rules) build correctly on top of it.
- The table-density token pair (`--table-header-font`/`--table-body-font`/`--table-cell-padding`) is consistently and correctly reused across 8+ files — real success at unifying what used to visibly differ (per `pagination.css`'s own comment describing six previously-independent pagination widgets now unified into one `.pg-btn` system).
- `buttons.css`'s `.btn-ghost`/`.btn-action` redesign is a deliberate, well-documented UX decision (its own comment explains moving away from "a wall of colored buttons" in table rows) and is consistently applied.
- Accessibility touches are real, not cosmetic: skip-link, `:focus-visible` treatment, 44px touch targets, 16px iOS-zoom-safe inputs, ARIA labeling on icon-only buttons throughout the templates, and focus-trapping inside modals (`js/ui/modals.js:14-56`).
- Several code comments show the team has already found and fixed this exact class of bug before (duplicate badge shapes, duplicate pagination widgets) — the review below is continuing work that's clearly already underway, not identifying a new category of problem.

---

## Part E — Prioritized Recommendations

1. **Wire up `DestructiveConfirm`** to the six destructive actions currently on bare `confirm()`/`alert()` (vm-registry.js ×3, user-management.js, access-management.js, automation-rules.js), or delete it if the product decision is that typed-confirmation is overkill — right now it's the worst of both worlds (built, but not protecting anyone).
2. **Reconcile Dashboard's color palette** (`dashboard.css` dots/em colors, `dashboard.js` chart colors) with `main.css`'s `--success/warning/danger/info-color` variables — this is a find-and-replace, not a redesign, and it's the highest-visibility fix available.
3. **Fix `config.js`'s STOPPED color** — give it its own neutral gray (matching `--status-stopped-*`) instead of reusing `--danger-color`/ERROR's red.
4. **Repalette `notification-bell.css`** from the Tailwind "gray" scale to the app's "slate" scale, and fix the two broken `var(--text-muted)`/`var(--primary)` references to the real variable names.
5. **Delete the dead code** identified in B10 (`Modals.showRegisterVm`, `Modals.showCreateGroup`) and B14 (the `log-detail-modal-template` in `audit-logs.html`) — or, if any were mid-migration, finish the migration. Either is better than two silently-diverging implementations of the same feature.
6. **Fix the mobile/desktop `.content-header h1` cascade** (B8) — pick one value, delete the other two rules.
7. Longer-term: formalize `#94a3b8` and the two muted-danger reds (B4, B5) as named tokens, and consider whether the ~25 one-off font sizes in `environments.css` can collapse onto 4–5 shared "compact" sizes.

---

## Appendix — Raw Value Inventories

### Colors observed in `--*-color`/status usage across the app, by "family"

**Slate family (dominant — the real neutral palette):** `#1e293b` `#334155` `#475569` `#64748b` `#94a3b8` `#cbd5e1` `#e2e8f0` `#f1f5f9` `#f8fafc`
**Gray family (intruding, no variable, isolated to notification-bell.css + a handful of status/cancelled states):** `#111827` `#6b7280` `#9ca3af` `#d1d5db` `#e5e7eb` `#f3f4f6` `#f9fafb` `#fafafa` `#4b5563`
**Brand/primary:** `#2563eb` (primary) `#3b82f6` (primary-light / info) `#1e40af` (primary-dark)
**Status — "canonical" (`main.css`):** success `#10b981` · warning `#f59e0b` · danger `#ef4444` · info `#3b82f6`
**Status — "Dashboard's version" (`dashboard.css`/`dashboard.js`):** success `#059669` · warning `#d97706` · danger `#dc2626` · info `#0891b2`
**Status — "config.js's version":** running `#10b981` · stopped `#ef4444` · starting/stopping `#f59e0b` · unknown `#6b7280` · error `#ef4444`

### Font-size fallback values referencing `--table-body-font` (should all be `0.81rem`)
Consistent (`0.81rem`): `content.css`, `tables.css`, `access-management.css`, `access-requests.css`, `activity-logs.css`, `all-logs.css`, `user-management.css`.
Inconsistent fallback text (functionally harmless since the variable is always defined, but a sign the value was retyped from memory rather than copied): `system-health.css:124` uses fallback `0.8rem`.
Hardcoded, no variable at all: `vm-registry.css:105`.

### `.metric-card .metric-value` size by page (base class = `2rem`, `cards.css:29`)
Dashboard `1.55rem` · All Logs `1.55rem` · User Management stat-card `1.55rem` · System Health `1.45rem` · Environment Detail `1.25rem` · Dashboard KPI tile `1.18rem`
