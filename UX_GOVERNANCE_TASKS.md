# UI Consistency Governance — Task List

Generated from [`.ai/engineering/ui-consistency.md`](.ai/engineering/ui-consistency.md). This is a distinct list from [`UX_TASKS.md`](UX_TASKS.md): those tasks fix the specific inconsistencies `UX_REVIEW.md` found; these tasks build the mechanisms that stop new ones from accumulating the same way. Task IDs continue the same `TASK-NNN` sequence (last used: `TASK-056`).

Do these roughly in order — the first two are foundational and make several `UX_TASKS.md` fixes more durable if done first (see the "Relationship to UX_TASKS.md" note on each).

---

### `- [ ]` TASK-057 — Build the living component reference page
**Source:** ui-consistency.md § 5
**Goal:** A single static page, `static/style-guide.html`, loading the real app CSS/vendor bundle exactly like `index.html` does, that renders one live, labeled example of every shared component so "does something like this already exist?" is answerable by looking, not by grepping 31 CSS files.
**Must include, each labeled with its exact class/module name:**
- Every `.btn-*` variant in use: `.btn-primary`, `.btn-outline-primary`, `.btn-secondary`, `.btn-success/-danger/-warning`, `.btn-ghost` (all four semantic variants), `.btn-action` (all four semantic variants, enabled + `:disabled`), `.btn-icon` (all three sizes)
- Every badge/pill: `.status-badge` (all 8 states: running/stopped/partial/pending/starting/stopping/error/unknown), `.role-badge` (all 3), `.access-level-badge` (all 3), a plain Bootstrap `.badge.bg-*` side-by-side with `.status-badge` so the shape difference from B11 is visually obvious and impossible to reintroduce by accident
- Table density: one `<table>` using `.table-baseline` / the shared `var(--table-body-font)` pattern, at actual size, so a new page can literally copy the markup
- Modal triggers: buttons that open `Modals.confirm()`, `Modals.prompt()`, and `DestructiveConfirm.show()` live, so the type-to-confirm pattern is one click away to copy, not something to reconstruct from `modals.js` source
- Alert types: `.alert-success/-warning/-danger/-info`, plus a toast triggered via `Notifications.success/error/warning/info`
- Pagination: both `Pagination.renderNumbered()` and `Pagination.buildSimpleMarkup()` rendered with sample data
- The color/spacing/typography token swatches themselves (every `--*-color`, the `--font-size-*` scale, and the `--table-header-font`/`--table-body-font` pair), so a new hex value can be visually compared against what already exists before it's typed
**Definition of done:** page exists, loads without a backend (static data only), and is linked from the sidebar or a dev-only route so it doesn't get forgotten. New CSS/template PRs are expected to check it first (add this to whatever PR template/checklist exists, or to `.ai/workflows/feature-development.md` if none does).

### `- [ ]` TASK-058 — Add a `Utils.cssVar(name)` helper bridging CSS custom properties into JS
**Source:** ui-consistency.md § 1
**Goal:** Stop JS from hand-duplicating colors that `main.css` already owns — the root cause behind the Dashboard/`config.js` color-drift findings in `UX_REVIEW.md` (B1, B2).
**Files to change:** `js/core/utils.js`
**Implementation sketch:**
```javascript
function cssVar(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}
```
Expose it from `Utils`'s public API alongside the module's existing helpers.
**Definition of done:** `Utils.cssVar('--success-color')` (etc.) returns the live value from `main.css`'s `:root`, and is unit-testable/callable from the browser console for a quick sanity check.

### `- [ ]` TASK-059 — Migrate `config.js`'s `VM_STATUS_CONFIG` colors onto the bridge
**Source:** ui-consistency.md § 1; relates to `UX_TASKS.md` TASK-036
**Relationship to UX_TASKS.md:** TASK-036 fixes the immediate bug (STOPPED == ERROR's red). Do this task at the same time so the fix can't silently re-drift later — reading `Utils.cssVar('--status-stopped-text')` etc. instead of a second hardcoded hex means the CSS token is the only place this color is ever defined.
**Files to change:** `js/config.js:213-218`
**Definition of done:** `RUNNING`/`STOPPED`/`STARTING`/`STOPPING`/`UNKNOWN`/`ERROR` colors are all read via `Utils.cssVar(...)` from the corresponding `--status-*` or `--success/warning/danger-color` variable, not written as a literal hex.

### `- [ ]` TASK-060 — Migrate `dashboard.js`'s chart-color arrays onto the bridge
**Source:** ui-consistency.md § 1; relates to `UX_TASKS.md` TASK-035
**Relationship to UX_TASKS.md:** same reasoning as TASK-059 — fix the values (TASK-035) and remove the second source of truth (this task) together.
**Files to change:** `js/features/dashboard.js:388-397,429-430,448,523,607`
**Definition of done:** The five-color chart/dot palette (success/warning/danger/primary/info) is built from `Utils.cssVar(...)` calls at render time, not a hardcoded hex array — so retuning a brand color in `main.css` automatically retunes every Dashboard chart.

### `- [ ]` TASK-061 — Promote frequently-reused unnamed colors to real CSS variables
**Source:** ui-consistency.md § 1; UX_REVIEW.md § B4
**Problem:** `#94a3b8` and `#9ca3af` are each used 10+ times across the app as literals with no variable name, despite being de facto tokens.
**Files to change:** `css/main.css` (add the variable(s) to `:root`), then sweep existing usages
**Definition of done:** e.g. `--text-tertiary: #94a3b8;` added to `main.css`; existing literal usages of that hex updated to `var(--text-tertiary)` opportunistically as those files are touched (doesn't need to be one giant sweep — but any *new* usage must use the variable, not the literal).

### `- [ ]` TASK-062 — Add the consistency checklist to the PR/review workflow
**Source:** ui-consistency.md § 3, § 5
**Goal:** Make "did you check `main.css` tokens / the style guide page / the component-reuse table before adding this?" a standard review question, not something only this audit asks.
**Files to change:** `.ai/workflows/feature-development.md` (or wherever PR expectations already live) — add a short "Frontend changes" checklist item pointing at `.ai/engineering/ui-consistency.md` and `static/style-guide.html` (once TASK-057 exists)
**Definition of done:** the workflow doc explicitly calls this out; not dependent on any new tooling.

### `- [ ]` TASK-063 (optional) — Evaluate a lightweight lint check for hardcoded colors/font-sizes
**Source:** ui-consistency.md § 6
**Goal:** Automate what TASK-062's checklist otherwise relies on human review to catch.
**Note:** No Node/npm toolchain exists in this project today — this is explicitly optional, not a prerequisite for the other tasks here. Two options, cheapest first:
- A small standalone script (PowerShell or a single Node script run manually/in CI) that greps `css/**/*.css` for raw hex values matching an existing `--*-color`/`--text-*` variable's value, and flags them.
- A full `stylelint` config with `color-no-hex` if the team is open to adding Node as a dev dependency.
**Definition of done:** whichever option is chosen either runs in CI or is documented as a manual pre-merge step for CSS-touching PRs.

### `- [ ]` TASK-064 — Schedule a recurring UI consistency re-audit
**Source:** ui-consistency.md § 7
**Goal:** Prevent this list from being a one-time exercise — `UX_REVIEW.md` found ~20 findings accumulated gradually across `TASK-004` through `TASK-033`; the same drift will recur without a periodic check.
**Files to change:** wherever cadence/process is tracked for this project (e.g. `.ai/context/roadmap.md`, or a recurring calendar reminder — not a code change)
**Definition of done:** a documented cadence (e.g., "re-run a UX consistency pass every N feature milestones, or before any release") exists somewhere durable, not just in this conversation.

---

## Suggested order

1. TASK-057 (style guide page) and TASK-058 (CSS→JS bridge) first — both are pure additions, nothing depends on them being "correct" yet, and they make the next three tasks safer.
2. TASK-059, TASK-060, TASK-061 — apply the bridge/tokens to the specific drift points already found.
3. TASK-062 — lock the practice in via the review process.
4. TASK-063, TASK-064 — process/tooling upgrades, do whenever there's slack.
