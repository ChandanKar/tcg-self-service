# UX Remediation Task List

**Status: all 19 tasks below (TASK-034 through TASK-056) have been implemented and verified** —
`build-frontend.ps1` rebuilds cleanly, every modified JS file passes `node --check`, and every CSS
file in the project is brace-balanced. Two notes from implementation:
- TASK-036's actual bug was in `js/config.js`'s `STATUS.vm.STOPPED.color` (not `VM_STATUS_CONFIG`
  as originally written below — `VM_STATUS_CONFIG` uses a `cssClass`, not a raw color, so it was
  never affected). Fixed at the correct location; nothing currently reads that `.color` field
  directly today, so this closes a latent trap rather than a currently-visible bug.
- Verification also turned up a genuine pre-existing bug unrelated to any of the 19 findings: a
  missing closing brace in `css/layout/content.css` (`.group-vm-table td:nth-child(5)`) was
  silently swallowing the entire next rule (`.group-vm-table td, th` padding/font-size). Fixed
  as part of this pass since it's a straightforward typo-restoration, not a judgment call.
- **Regression found and fixed after initial completion:** TASK-043 (Login page → local `vendor/`
  assets) broke the login page. `/vendor/**` was never added to Spring Security's public-path
  whitelist (`DefaultSecurityConfig.java` / `EntraidSecurityConfig.java` only listed `/css/**`,
  `/js/**`, `/logo/**`, `/images/**`), so an unauthenticated request for
  `/vendor/bootstrap/css/bootstrap.min.css` was redirected (302) back to the login page instead of
  served — Bootstrap/Font Awesome never loaded, leaving the login page unstyled. Fixed by adding
  `/vendor/**` to the `permitAll` list in both security configs. Verified end-to-end by actually
  booting the app and curling the login page: all vendor assets now return `200`, `/css/**`/`/js/**`
  still public, the API still correctly returns `401` unauthenticated, and a path-traversal probe
  through `/vendor/..` still returns `400`. This is the one part of the whole pass that was
  confirmed against a *running* app rather than source/syntax checks alone.

Generated from [UX_REVIEW.md](UX_REVIEW.md). Task IDs continue the codebase's existing `TASK-NNN` convention (highest in use today is `TASK-033`, in `js/core/utils.js`/`dist/app.js`) — reference these IDs in commit messages and code comments the same way `TASK-021`, `TASK-031`, etc. are referenced today.

Each task lists the files to touch and a definition of done. Severity mirrors the review (HIGH = user-visible/trust-affecting, MEDIUM = noticeable inconsistency, LOW = maintenance/drift risk only).

---

## High Priority

### `- [x]` TASK-034 — Wire up `DestructiveConfirm` to real destructive actions (or remove it)
**Source:** UX_REVIEW.md § B9
**Problem:** `js/ui/modals.js` (871–1012) defines a complete type-to-confirm destructive modal (`DestructiveConfirm.show/confirmStopAll/confirmBreakLock/confirmDeleteEnvironment`) backed by real markup in `index.html:606-647`, but it has zero call sites anywhere. Six real delete/deactivate/revoke actions use a bare `window.confirm()`/`alert()` instead.
**Files to change:**
- `js/features/vm-registry.js:304` (Delete Environment → use `DestructiveConfirm.confirmDeleteEnvironment`), `:646` (Delete Group), `:1000` (Remove VM)
- `js/features/user-management.js:403` (Deactivate User)
- `js/features/access-management.js:948` (Revoke Access), `:1124` (replace `alert()` with `Notifications.error()` or a modal)
- `js/features/automation-rules.js:272` (Delete Automation Rule)
**Definition of done:** Every listed action opens the styled `#destructiveConfirmModal` (or, for the three without an existing `DestructiveConfirm` convenience method — Delete Group, Remove VM, Deactivate User, Revoke Access, Delete Automation Rule — a new thin wrapper following the same `show()` pattern) with an accurate impact list; no destructive action in the app falls through to a native browser dialog. If product decides typed-confirmation is overkill for some of these, downgrade them to `Modals.confirm()` instead — not raw `confirm()`.

### `- [x]` TASK-035 — Reconcile Dashboard's status colors with the app's design tokens
**Source:** UX_REVIEW.md § B1
**Problem:** `dashboard.css` (`.dashboard-dot.*`, `.dashboard-list-row em.*`, `.dashboard-scheduler-row em.*`, lines 244–266) and `dashboard.js` (lines 388–397, 429–430, 448, 523 — chart palettes) use a bespoke 5-color set that mismatches `main.css`'s `--success/warning/danger/info-color` on 3 of 4 non-primary colors (info is a different hue entirely: cyan vs. blue).
**Files to change:** `css/features/dashboard.css`, `js/features/dashboard.js`
**Definition of done:** All "success/warning/danger/info" swatches on Dashboard (KPI dots, list/scheduler row labels, ECharts donut/gauge palettes) resolve to `var(--success-color)` / `var(--warning-color)` / `var(--danger-color)` / `var(--info-color)` (or their exact hex) — a green badge on Environment Detail and a green dot on Dashboard are the same green.

### `- [x]` TASK-036 — Give "Stopped" its own color in `config.js` instead of reusing danger/error red
**Source:** UX_REVIEW.md § B2
**Problem:** `js/config.js:213-218`, `VM_STATUS_CONFIG.STOPPED.color` is `#ef4444` — identical to `ERROR.color`. A normal, frequently-reached state renders with the same alarm-red as a failure, wherever this config's `color` field is consumed directly (as opposed to the `.status-badge.stopped` CSS class, which correctly uses neutral gray).
**Files to change:** `js/config.js:214`
**Definition of done:** `STOPPED.color` matches the neutral gray already used by `--status-stopped-text`/`.status-badge.stopped` (`#4b5563`), not `--danger-color`. Audit any place reading `Config.VM_STATUS_CONFIG[status].color` directly (`environments.js:369,380` and similar) to confirm the visual fix lands there too.

### `- [x]` TASK-037 — Repalette the notification bell dropdown and fix its broken `var()` references
**Source:** UX_REVIEW.md § B3
**Problem:** `css/components/notification-bell.css` uses Tailwind's "gray" scale (`#111827`, `#6b7280`, `#9ca3af`, `#d1d5db`, `#e5e7eb`, `#f3f4f6`, `#f9fafb`, `#fafafa`, `#4b5563`) instead of the app-wide "slate" scale, and references `var(--text-muted, ...)` / `var(--primary, ...)` (lines 11, 20, 72) — neither custom property exists in `main.css`, so both always silently fall through to their hardcoded fallback.
**Files to change:** `css/components/notification-bell.css`
**Definition of done:** Every gray-family hex replaced with the matching slate-family value/variable (`--text-primary`, `--text-secondary`, `--border-color`, `--background-light`, etc.); `var(--text-muted, ...)` → `var(--text-secondary, ...)`; `var(--primary, ...)` → `var(--primary-color, ...)`.

---

## Medium Priority

### `- [x]` TASK-038 — Fix the `.content-header h1` responsive cascade bug (mobile renders larger than desktop)
**Source:** UX_REVIEW.md § B8
**Problem:** Three rules target the same selector at equal specificity: `main.css:190-192` (`1.5rem`, dead — never wins), `content.css:308-313` (`1.2rem`, wins on desktop), `mobile.css:115-117` (`1.25rem`, wins on mobile because it loads last). Net result: page titles are bigger on phones than on desktop.
**Files to change:** `css/main.css`, `css/layout/content.css`, `css/layout/mobile.css`
**Definition of done:** One canonical desktop size and one canonical (smaller, or intentionally equal) mobile size for `.content-header h1`; the other two rules deleted, not just overridden.

### `- [x]` TASK-039 — Delete or finish the dead `Modals.showRegisterVm` / `Modals.showCreateGroup` builders
**Source:** UX_REVIEW.md § B10
**Problem:** `js/ui/modals.js:611-692,697-804` define full modal builders with zero call sites anywhere in the app. The live "Register VM"/"Create Group" flows are the static modals in `index.html` driven by `VmRegistry.submitVm()`/`submitGroup()`, and the two designs don't even share the same fields (dead version has "VM Type", no AWS-import section; live version has AWS-import + Purpose/Remarks, no "VM Type").
**Files to change:** `js/ui/modals.js`
**Definition of done:** Either (a) delete `showRegisterVm`/`showCreateGroup` entirely, or (b) if "VM Type" is a field that should exist, port it into the live static modal in `index.html` and delete the dead builder anyway — don't leave two implementations.

### `- [x]` TASK-040 — Reconcile or remove the dead Audit Log Details template
**Source:** UX_REVIEW.md § B14
**Problem:** `templates/features/audit-logs.html:203-258` defines a nicely-labeled `<template id="log-detail-modal-template">` (modal id `logDetailModal`) that is never instantiated by any JS. The live modal is built ad hoc in `js/features/audit-logs.js:666-697` (`showLogDetailsModal`) as a plain flat table, id `logDetailsModal`, with no field labels.
**Files to change:** `templates/features/audit-logs.html`, `js/features/audit-logs.js`
**Definition of done:** Either delete the unused `<template>` block, or (preferred, for UX) rebuild `showLogDetailsModal()` to render the labeled two-column layout the template already defines, so the "Additional Information" JSON blob gets the padded/scrollable `<pre>` styling `components/audit.css:53-66` was written for.

### `- [x]` TASK-041 — Unify status/badge shape (pill vs. Bootstrap-default rectangle)
**Source:** UX_REVIEW.md § B11
**Problem:** Custom `.status-badge`/`.role-badge`/`.access-level-badge` (badges.css) are fully rounded pills; several pages use raw Bootstrap `<span class="badge bg-*">` (audit log action-type badges, `.badge.bg-PENDING/.bg-APPROVED/...` in `components/access.css:20-34`) which keep Bootstrap's default rounded-rectangle shape.
**Files to change:** `templates/features/audit-logs.html` (`{{actionBadgeClass}}` usage), `components/access.css`, any JS building `badge bg-*` strings directly
**Definition of done:** All status-style badges in the app render as the same shape — either migrate the raw Bootstrap badges onto `.status-badge`, or add `border-radius: 9999px` to the shared `.badge` base so both conventions look identical.

### `- [x]` TASK-042 — Wire up or remove the dead top-nav search box
**Source:** UX_REVIEW.md § A.1
**Problem:** `index.html:64-69` ships a fully-built search input (icon, placeholder "Search environments, VMs, or groups...") permanently hidden via inline `style="display:none"`, with zero JS references anywhere.
**Files to change:** `index.html`, (new or existing JS if implementing)
**Definition of done:** Either implement the search behavior and remove the inline `display:none`, or delete the dead markup entirely so it isn't mistaken for a half-broken feature.

### `- [x]` TASK-043 — Decide and fix Login page asset sourcing (CDN vs. local vendor)
**Source:** UX_REVIEW.md § B17
**Problem:** `login.html:9,12,84` load Bootstrap/Font Awesome from `cdn.jsdelivr.net`/`cdnjs.cloudflare.com`; the rest of the app (`index.html:9,11`) uses local `vendor/` copies. Login is the one page that will visibly break without outbound internet access.
**Files to change:** `login.html`
**Definition of done:** Login page references the same local `vendor/bootstrap`/`vendor/fontawesome` paths as `index.html`; confirm it renders correctly with no network access to those two CDN hosts.

### `- [x]` TASK-044 — Load the "Inter" font family used by charts, or drop it from the stack
**Source:** UX_REVIEW.md § B7
**Problem:** `js/features/dashboard.js:696` requests `fontFamily: 'Inter, Segoe UI, Arial, sans-serif'` for ECharts text; "Inter" is not loaded anywhere in the app (no `@font-face`, no font `<link>`) and doesn't appear in the app's actual global stack (`main.css:89`).
**Files to change:** `js/features/dashboard.js:696` (and any other chart config sharing this helper)
**Definition of done:** Either add an `Inter` web-font `<link>`/`@font-face` and confirm charts actually render in it, or simplify the fallback stack to match `main.css`'s real font stack so the declared intent matches what actually renders.

### `- [x]` TASK-045 — Consolidate "compact metric value" font sizes onto one token
**Source:** UX_REVIEW.md § B6
**Problem:** Base `.metric-card .metric-value` is `2rem` (`cards.css:29`), but page overrides pick four different "compact" sizes for the same visual role: `1.55rem` (Dashboard, All Logs, User Management stat-card), `1.45rem` (System Health), `1.25rem` (Environment Detail), `1.18rem` (Dashboard KPI tile).
**Files to change:** `css/main.css` (add e.g. `--font-size-metric-compact: 1.5rem`), `css/features/dashboard.css`, `css/features/all-logs.css`, `css/features/user-management.css`, `css/features/system-health.css`, `css/features/environments.css`
**Definition of done:** All "compact metric value" contexts reference one shared token; visual size differences that remain are deliberate (e.g., System Health's slightly tighter grid), not accidental drift.

---

## Low Priority (design-system hygiene)

### `- [x]` TASK-046 — Sweep hardcoded hex colors that duplicate existing CSS variables
**Source:** UX_REVIEW.md § B4
**Files:** `components/modals.css`, `layout/topnav.css`, `components/cards.css`, `components/operations.css` (top half only — bottom "TASK-019" section already does this correctly), `features/system-health.css`, and others
**Definition of done:** `#64748b`→`var(--text-secondary)`, `#e2e8f0`→`var(--border-color)`, `#f8fafc`→`var(--background-light)`, `#1e293b`→`var(--text-primary)` wherever they appear as literals. Consider promoting `#94a3b8` (used 15+ times, never named) to a new `--text-tertiary` or `--text-muted` variable first.

### `- [x]` TASK-047 — Unify the two muted-danger red hex values
**Source:** UX_REVIEW.md § B5
**Files:** `css/components/buttons.css:50` (`.btn-ghost.btn-outline-danger`, `#d99490`) and `:120` (`.btn-action.btn-danger`, `#e2a29e`)
**Definition of done:** One shared hex/variable for "muted at-rest danger" used by both `.btn-ghost` and `.btn-action`.

### `- [x]` TASK-048 — Remove the dead duplicate `.metric-card:hover` rule
**Source:** UX_REVIEW.md § B12
**Files:** `css/main.css:133-137`
**Definition of done:** `main.css`'s copy deleted; `cards.css:15-19` remains the single definition.

### `- [x]` TASK-049 — Decide the adoption path for `.table-baseline`
**Source:** UX_REVIEW.md § B13
**Problem:** Documented in `tables.css` as "the STANDARD table style for all views" but only used by `cost-management.js`; every other table re-implements the same values via page-scoped selectors (mostly via `var()`, but `vm-registry.css:102-113` hardcodes literals).
**Definition of done:** Either (a) apply the `.table-baseline` class to the other 8 tables and delete their duplicate per-page rules, or (b) if the per-page-scoped approach is now the intended pattern, delete `.table-baseline` and update the comment claiming it's the standard. Either way, fix `vm-registry.css` to reference `var(--table-body-font)`/`var(--table-header-font)`/`var(--table-cell-padding)` instead of hardcoded numbers.

### `- [x]` TASK-050 — Update or remove the stale `css/index.css` documentation file
**Source:** UX_REVIEW.md § B15
**Files:** `css/index.css`
**Definition of done:** Either delete it (it's never linked from any HTML) or update its "CSS ARCHITECTURE" comment to list all 11 current feature stylesheets, not the 5 it currently names.

### `- [x]` TASK-051 — Extract shared scrollbar-thumb styling into one rule
**Source:** UX_REVIEW.md § B16
**Files:** `main.css`, `sidebar.css`, `content.css` (×3), `vm-registry.css`, `activity-logs.css`, `all-logs.css`, `access-management.css`, `access-requests.css`, `system-health.css` (×3)
**Definition of done:** A single `.thin-scrollbar` (or similar) utility class replaces the ~10 duplicated `::-webkit-scrollbar-thumb { background:#cbd5e1; border-radius:3px; }` blocks.

### `- [x]` TASK-052 — Document a formal z-index scale
**Source:** UX_REVIEW.md § B.5
**Problem:** Magic numbers from 100 to 10000 scattered across 7+ files with inconsistent commenting (topnav/mobile stack is well-documented; notification-dropdown at 3000 and toasts at 9999 aren't explained).
**Definition of done:** A `Z-INDEX SCALE` comment block (in `main.css`, alongside the other token scales) listing every layer and its value, so the next overlay added doesn't have to guess.

### `- [x]` TASK-053 — Reconcile the Login page's alert styling and delete dead `.login-error` CSS
**Source:** UX_REVIEW.md § B20
**Problem:** `login.css` defines a custom `.alert-success` (lines 224-236, different padding/border/radius/font-size than the Bootstrap default used everywhere else) and a `.login-error` class (lines 209-221) that `login.html` never actually applies — its real error banner uses plain `class="alert alert-danger"` (`login.html:31`).
**Files to change:** `css/login.css`, possibly `login.html`
**Definition of done:** `.login-error` deleted (or wired up, if it was meant to replace the plain `alert-danger` usage); `.alert-success` either matches the rest of the app's alert styling or there's a documented reason it's login-specific.

### `- [x]` TASK-054 — Delete the dead, duplicate `pagination-template` blocks
**Source:** UX_REVIEW.md § B19
**Problem:** `templates/common.html:85` and `templates/features/activity-logs.html:128` both define `<template id="pagination-template">` — a duplicate id, and confirmed dead code (no JS anywhere loads a template by that id; both pages actually render pagination via the shared `Pagination.renderNumbered()` module).
**Files to change:** `templates/common.html`, `templates/features/activity-logs.html`
**Definition of done:** Both `<template id="pagination-template">` blocks removed.

### `- [x]` TASK-055 — Fix the `.pagination-info`/`.pg-btn` font-size mismatch
**Source:** UX_REVIEW.md § B19
**Problem:** `.pagination-info` is `0.75rem` (`components/pagination.css:23`) while the adjacent `.pg-btn` labels are `0.72rem` (`components/pagination.css:41`) — a one-off size difference inside a single otherwise well-unified component.
**Files to change:** `css/components/pagination.css`
**Definition of done:** Both use the same font-size value.

### `- [x]` TASK-056 — Route `#connection-status` colors through the shared CSS variables
**Source:** UX_REVIEW.md § B20
**Problem:** `components/loading.css`'s connected/disconnected/reconnecting indicator hardcodes `#10b981`/`#ef4444`/`#f59e0b` instead of `var(--success-color)`/`var(--danger-color)`/`var(--warning-color)`.
**Files to change:** `css/components/loading.css`
**Definition of done:** All three states reference the shared variables.

---

## Decisions Needed (not pure engineering tasks)

- **Left sidebar nav-item font size** (UX_REVIEW.md § B18). `.sidebar-menu-link` and `.submenu-item` have no explicit `font-size` and render at the inherited 16px — the single largest piece of text in an otherwise density-optimized app (section headers are 12px, table text is 11–13px). Decide whether this is intentional (larger click targets for primary nav is a defensible reason) or an oversight from before the rest of the app was compacted, then either add an explicit, documented font-size or leave it with a comment explaining why it's deliberately larger.

- **Audit Logs (All) vs. My Activity Logs visual language.** One is a dense flat table, the other a timeline/card layout, for conceptually similar event-history data (UX_REVIEW.md § Part C). Worth a deliberate product call on whether admins and end users should see history in the same visual format, before spending engineering time on either page's polish.
- **`environments.css` VM Insights panel font-size sprawl** (UX_REVIEW.md § B6) — ~25 one-off sizes in 0.01–0.02rem steps. Fixing this well needs a design pass to define 3–4 real tile-caption/tile-value/section-label sizes, not just a mechanical find-and-replace like TASK-045.
- **VM Registry EKS legend colors** (`vm-registry.js:870-872`, pastel `#b7f0cd`/`#f9e79f`/`#f5b7b1`) — decide whether these should map onto the app's existing status colors or remain a deliberately distinct "availability" palette, then fix accordingly.
