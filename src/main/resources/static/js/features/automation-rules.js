/**
 * Automation Rules Feature Module
 * Admin / Env Admin view for calendar-schedule and access-grant/lock-acquire rules that start or
 * stop an environment. U12-T02 (AutomationRules.dc.html): tabs Schedules / When access is granted /
 * Holiday calendars / Recent runs, counts, and each schedule with its week strip.
 */
const AutomationRules = (function() {
    'use strict';

    // State
    let rules = [];
    let environments = [];
    let holidayCalendars = []; // named on the Holiday calendars tab for environment admins (E19-T02)
    let scopeOptionsCache = {}; // environmentId -> { groups: [...], vms: [...] }
    let accessForm = null; // { rule } while the access-rule panel is open (U12-T04)
    // Recent runs (U12-T05): the filters, the page and the page's rows from the audit log.
    let runs = { page: 1, environmentId: '', result: '', data: null, seq: 0 };
    const RUN_ACTIONS = ['AUTOMATION_RULE_TRIGGERED', 'AUTOMATION_RULE_SKIPPED', 'AUTOMATION_RULE_FAILED'];
    const RUN_DAYS = 30;
    let saving = false; // one Save in flight at a time (a double click created two rules)

    /** Labels for lastRunReason (V31). */
    const RUN_REASON_LABELS = {
        OK: 'Started',
        NOTHING_TO_DO: 'Nothing to do',
        LOCKED: 'Environment locked',
        OPERATION_IN_PROGRESS: 'Operation in progress',
        ENVIRONMENT_INACTIVE: 'Environment deactivated',
        CREATOR_INACTIVE: 'Creator inactive',
        SCOPE_MISSING: 'Target missing',
        ERROR: 'Error',
        MISSED_WINDOW: 'Missed window',
        HOLIDAY: 'Holiday',
        OVERRIDE: 'Overridden'
    };
    const PAGE_SIZE = 10;
    const DAY_MS = 24 * 60 * 60 * 1000;

    /** The page's tabs (U12-T02, AutomationRules.dc.html), the selected one in ?tab=. */
    const TABS = [
        { key: 'schedules', label: 'Schedules' },
        { key: 'access', label: 'When access is granted' },
        { key: 'holidays', label: 'Holiday calendars' },
        { key: 'runs', label: 'Recent runs' }
    ];
    let tab = 'schedules';
    let currentPage = 1;

    // ============= Load =============

    // Navigation token of the current visit (see the page contract in core/router.js).
    let pageToken = null;

    function isActive() {
        return pageToken !== null && ContentRouter.isCurrent(pageToken);
    }

    /** Router loader. */
    function load() {
        pageToken = ContentRouter.token();
        if (!Auth.isEnvAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3">Access denied. Admin or Env Admin only.</div>');
            return;
        }
        const q = ContentRouter.query ? ContentRouter.query() : {};
        tab = TABS.some(t => t.key === q.tab) ? q.tab : 'schedules';
        currentPage = 1;
        accessForm = null;
        runs = { page: 1, environmentId: '', result: '', data: null, seq: 0 };
        showLoading();
        fetchInitialData();
    }

    function showLoading() {
        $('#content-area').html('<div class="vr-empty" role="status">Loading…</div>');
    }

    function showError(xhr) {
        $('#content-area').html(UiStates.failedPage({ title: 'Automation rules', what: 'The automation rules', xhr }));
    }

    async function fetchInitialData() {
        try {
            const [envList, ruleList] = await Promise.all([
                apiGet(Config.API.environments.list),
                apiGet(Config.API.automationRules.list())
            ]);
            if (!isActive()) return;
            environments = envList || [];
            rules = ruleList || [];
            // Calendars are only named on the Holiday calendars tab; a failure names none.
            holidayCalendars = await apiGetQuiet(Config.API.holidayCalendars.list).catch(() => []) || [];
            if (!isActive()) return;
            render();
        } catch (error) {
            if (!isActive()) return;
            console.error('Failed to load automation rules:', error);
            showError(error);
        }
    }

    async function refreshRules() {
        const fresh = await apiGet(Config.API.automationRules.list()) || [];
        if (!isActive()) return;
        rules = fresh;
        renderCounts();
        renderContent();
    }

    function apiGet(url) {
        return new Promise((resolve, reject) => {
            ApiClient.get(url).done(resolve).fail(reject);
        });
    }

    function apiGetQuiet(url) {
        return new Promise((resolve, reject) => {
            ApiClient.get(url, { suppressGlobalError: true }).done(resolve).fail(reject);
        });
    }

    // ============= Model (pure; node tests use AutomationRules.model) =============

    function viewerTimezone() {
        try {
            return Intl.DateTimeFormat().resolvedOptions().timeZone || null;
        } catch (e) {
            return null;
        }
    }

    function hhmm(h) {
        const total = Math.round((((h % 24) + 24) % 24) * 60);
        return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
    }

    /** "today 20:00", "tomorrow 06:30", "Mon 09:00" within a week, else "3 Nov 09:00" (viewer time). */
    function clockText(at, now) {
        const d = new Date(at);
        const n = now || new Date();
        const time = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
        const startOf = x => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
        const days = Math.round((startOf(d) - startOf(n)) / DAY_MS);
        if (days === 0) return `today ${time}`;
        if (days === 1) return `tomorrow ${time}`;
        if (days > 1 && days < 7) return `${d.toLocaleDateString('en-GB', { weekday: 'short' })} ${time}`;
        return `${d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })} ${time}`;
    }

    /** "Stop today 20:00" / "Start Mon 09:00", or '' when nothing is coming. */
    function nextText(rule, now) {
        if (!rule.nextRunAt) return '';
        return `${rule.nextRunAction === 'STOP' ? 'Stop' : 'Start'} ${clockText(rule.nextRunAt, now)}`;
    }

    /** The counts above the tabs: rules on, environments covered, runs skipped last week because of a lock. */
    function countsOf(list, now) {
        const n = (now || new Date()).getTime();
        const on = (list || []).filter(r => r.enabled);
        return {
            on: on.length,
            environments: new Set(on.map(r => r.environmentId)).size,
            skippedLocked: (list || []).filter(r => r.lastRunReason === 'LOCKED' && r.lastRunAt
                && n - new Date(r.lastRunAt).getTime() <= 7 * DAY_MS).length
        };
    }

    /** The weekday (Mon = 0) of the last run when it was skipped in the last 7 days, else null. */
    function skippedDay(rule, now, viewerTz) {
        if (rule.lastRunStatus !== 'SKIPPED' || !rule.lastRunAt) return null;
        const at = new Date(rule.lastRunAt);
        if ((now || new Date()).getTime() - at.getTime() > 7 * DAY_MS) return null;
        return typeof WeekStrip !== 'undefined' ? WeekStrip.weekdayIndex(at, viewerTz) : null;
    }

    function windowsOf(rule) {
        if (rule.windows && rule.windows.length) return rule.windows;
        return [{ daysOfWeek: rule.daysOfWeek || [], startTime: rule.startTime, stopTime: rule.stopTime }];
    }

    /**
     * The schedule's line: "Mon–Fri 08:00–20:00 · Asia/Kolkata · off on India public holidays", with
     * "(06:30–10:30 here)" when the rule's time zone is not the viewer's and "skipped Wed: environment
     * locked" after a recent skip.
     */
    function scheduleLine(rule, now, viewerTz) {
        const parts = [];
        if (rule.scheduleSummary) {
            parts.push(rule.scheduleSummary);
        } else {
            const days = (rule.daysOfWeek || []).join(', ');
            const times = [rule.startTime ? `Start ${rule.startTime}` : null, rule.stopTime ? `Stop ${rule.stopTime}` : null]
                .filter(Boolean).join(', ');
            parts.push([days, times].filter(Boolean).join(' '));
        }
        let tzText = rule.timezone || '';
        if (rule.timezone && viewerTz && rule.timezone !== viewerTz && typeof WeekStrip !== 'undefined') {
            const w = windowsOf(rule)[0] || {};
            const shift = WeekStrip.shiftHours(rule.timezone, viewerTz, now || new Date());
            const h = t => { const [a, b] = String(t).split(':').map(Number); return a + (b || 0) / 60; };
            if (shift && w.startTime && w.stopTime) {
                tzText += ` (${hhmm(h(w.startTime) + shift)}–${hhmm(h(w.stopTime) + shift)} here)`;
            } else if (shift && (w.startTime || w.stopTime)) {
                tzText += ` (${hhmm(h(w.startTime || w.stopTime) + shift)} here)`;
            }
        }
        if (tzText) parts.push(tzText);
        if (rule.holidayCalendarName) {
            parts.push(`${rule.holidayBehavior === 'SKIP_ALL' ? 'off' : 'no starts'} on ${rule.holidayCalendarName} holidays`);
        }
        const day = skippedDay(rule, now, viewerTz);
        if (day !== null && day >= 0) {
            const why = (RUN_REASON_LABELS[rule.lastRunReason] || 'skipped').toLowerCase();
            parts.push(`skipped ${WeekStrip.LABELS[day]}: ${why}`);
        }
        return parts.join(' · ');
    }

    function scopeLabel(rule) {
        if (rule.scopeType === 'ENVIRONMENT') return rule.environmentName || '';
        return `${rule.environmentName || ''} · ${rule.scopeName || ''} (${String(rule.scopeType || '').toLowerCase()})`;
    }

    /**
     * An access rule in a sentence (U12-T04): "Starts CRM UAT when someone takes the lock, in their
     * name" / "Starts Web in CRM UAT when an access request is approved, in Asha's name". Access rules
     * only start: the lease or a schedule stops it.
     */
    function accessLine(rule) {
        const env = rule.environmentName || 'the environment';
        const target = rule.scopeType === 'ENVIRONMENT' ? env : `${rule.scopeName || 'a group'} in ${env}`;
        return rule.accessGrantMode === 'LOCK_ACQUIRE'
            ? `Starts ${target} when someone takes the lock, in their name`
            : `Starts ${target} when an access request is approved, in ${rule.createdByDisplayName ? `${rule.createdByDisplayName}'s` : "the rule owner's"} name`;
    }

    /** A run's result from its audit row: Started / Stopped / Skipped / Failed. */
    function runResult(row) {
        if (row.action === 'AUTOMATION_RULE_FAILED') return 'Failed';
        if (row.action === 'AUTOMATION_RULE_SKIPPED') return 'Skipped';
        return /\bSTOP\b/.test(row.details || '') ? 'Stopped' : 'Started';
    }

    /**
     * What happened, in words: "on schedule", "when Asha Rao took the lock", "environment was locked
     * by Asha Rao", "holiday (Diwali)", "nothing to do", or the error.
     */
    function runDetail(row) {
        const text = String(row.details || '');
        if (row.action === 'AUTOMATION_RULE_TRIGGERED') {
            const how = (text.match(/\(([^)]*)\)\s*$/) || [])[1] || '';
            if (how === 'lock acquired') return `when ${row.userDisplayName || 'someone'} took the lock`;
            if (how === 'access approved') return 'when access was approved';
            return 'on schedule';
        }
        if (row.action === 'AUTOMATION_RULE_FAILED') return row.errorMessage || text || 'failed';
        const why = text.replace(/^Skipped:\s*/, '');
        const locked = why.match(/^Environment locked by (.+)$/);
        if (locked) return `environment was locked by ${locked[1]}`;
        if (/^Nothing to do\b/.test(why)) return 'nothing to do';
        if (/^by override: /.test(why)) return `skipped by override: ${why.slice('by override: '.length)}`;
        return why ? why.charAt(0).toLowerCase() + why.slice(1) : 'skipped';
    }

    /** GET /audit/logs for the tab: the run actions of the last 30 days, 10 a page. */
    function runsUrl(state, now) {
        const actions = state.result === 'SKIPPED' ? ['AUTOMATION_RULE_SKIPPED']
            : state.result === 'FAILED' ? ['AUTOMATION_RULE_FAILED'] : RUN_ACTIONS;
        const from = new Date((now || new Date()).getTime() - RUN_DAYS * DAY_MS).toISOString();
        const params = ['category=AUTOMATION', ...actions.map(a => `action=${a}`), `from=${encodeURIComponent(from)}`,
            `page=${state.page - 1}`, `size=${PAGE_SIZE}`];
        if (state.environmentId) params.push(`environmentId=${encodeURIComponent(state.environmentId)}`);
        return `${Config.API.audit.logs}?${params.join('&')}`;
    }

    // ============= Render =============

    function render() {
        const help = typeof HelpLink !== 'undefined';
        $('#content-area').html(`
            <div class="content-view ar-page" id="automation-rules-view">
                ${PageHeader.markup({ title: 'Automation rules',
                    summaryHtml: 'Schedules that start and stop environments. A rule never breaks a lock: it retries, then skips and tells you.',
                    actionsHtml: `<a class="btn btn-primary" id="ar-new-rule-btn" href="#/automation-rules/new">
                        <svg width="14" height="14" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M10 4v12M4 10h12"></path></svg>
                        New schedule</a>`,
                    helpPage: help ? 'automation-rules' : null, helpLabel: 'Automation rules' })}
                <div class="ar-counts" id="ar-counts"></div>
                <div class="ar-tabs" role="tablist" aria-label="Automation">${TABS.map(t => `<button type="button" role="tab"
                    class="ar-tab" id="ar-tab-${t.key}" data-ar-tab="${t.key}" aria-controls="ar-panel"
                    aria-selected="${t.key === tab}">${t.label}</button>`).join('')}</div>
                <section class="ar-panel" id="ar-panel" role="tabpanel" aria-labelledby="ar-tab-${tab}">
                    <div id="ar-content"></div>
                </section>
            </div>
        `);
        bind();
        renderCounts();
        renderContent();
    }

    function renderCounts() {
        const c = countsOf(rules);
        $('#ar-counts').html(`<span><b>${c.on}</b> rule${c.on === 1 ? '' : 's'} on</span>
            <span>cover <b>${c.environments}</b> environment${c.environments === 1 ? '' : 's'}</span>
            ${c.skippedLocked ? `<span class="ar-count-warn"><b>${c.skippedLocked}</b> run${c.skippedLocked === 1 ? '' : 's'} skipped last week: environment was locked</span>` : ''}`);
    }

    function selectTab(key) {
        if (!TABS.some(t => t.key === key) || key === tab) return;
        tab = key;
        currentPage = 1;
        accessForm = null;
        $('#automation-rules-view .ar-tab').each(function() { this.setAttribute('aria-selected', String(this.dataset.arTab === key)); });
        $('#ar-panel').attr('aria-labelledby', `ar-tab-${key}`);
        if (window.history && window.history.replaceState) {
            window.history.replaceState(null, '', `#/automation-rules${key === 'schedules' ? '' : `?tab=${key}`}`);
        }
        renderContent();
    }

    function renderContent() {
        if (tab === 'schedules') {
            $('#ar-content').html('<div class="ar-list" id="ar-table-wrapper"></div><div id="ar-pagination"></div>');
            renderSchedules();
        } else if (tab === 'access') {
            renderAccessRules();
        } else if (tab === 'holidays') {
            renderHolidays();
        } else {
            renderRecentRuns();
        }
    }

    function renderSchedules() {
        const list = rules.filter(r => r.triggerType === 'SCHEDULE')
            // Rules that are on first, then the newest: a schedule just added is at the top.
            .slice().sort((a, b) => Number(b.enabled) - Number(a.enabled)
                || new Date(b.createdAt || 0) - new Date(a.createdAt || 0));
        const pages = Math.max(1, Math.ceil(list.length / PAGE_SIZE));
        currentPage = Math.min(Math.max(currentPage, 1), pages);
        const items = Pagination.slice(list, currentPage, PAGE_SIZE);
        $('#ar-table-wrapper').html(items.length ? items.map(scheduleRowHtml).join('')
            : '<div class="ar-empty">No schedules yet. Add one with New schedule to start and stop an environment at set times.</div>');
        $('#ar-pagination').html(list.length > PAGE_SIZE ? Pagination.buildPager({ page: currentPage, pageSize: PAGE_SIZE,
            totalItems: list.length, action: 'ar-page', key: 'schedules' }) : '');
    }

    function switchHtml(rule) {
        return Utils.html`<label class="ar-switch"><input type="checkbox" role="switch" class="ar-toggle-enabled" data-rule-id="${rule.ruleId}"
            aria-label="Turn ${rule.name} on"${Utils.raw(rule.enabled ? ' checked' : '')}><span aria-hidden="true"></span></label>`;
    }

    function rowActions(rule, withSkip) {
        return Utils.html`<div class="ar-rule-actions">
                <button type="button" class="ar-act ar-edit-btn" data-rule-id="${rule.ruleId}" aria-label="Edit ${rule.name}">Edit</button>
                ${Utils.raw(withSkip && rule.enabled && rule.nextStopAt ? Utils.html`<button type="button" class="ar-act ar-skip-stop-btn" data-rule-id="${rule.ruleId}"
                    aria-label="Skip next stop of ${rule.name}" title="Skip the stop at ${clockText(rule.nextStopAt)}">Skip next stop</button>` : '')}
                <button type="button" class="ar-act ar-danger ar-delete-btn" data-rule-id="${rule.ruleId}" aria-label="Delete ${rule.name}">Delete</button>
            </div>`;
    }

    function headHtml(rule, withEnv = true) {
        return Utils.html`<div class="ar-rule-head">${Utils.raw(switchHtml(rule))}<h2 class="ar-rule-name">${rule.name}</h2>
                ${Utils.raw(rule.enabled ? '' : '<span class="ar-chip-off">Off</span>')}
                ${Utils.raw(rule.overrideCount ? Utils.html`<span class="ar-override-badge" title="Active or upcoming overrides">${rule.overrideCount} override${rule.overrideCount === 1 ? '' : 's'}</span>` : '')}</div>
            ${Utils.raw(withEnv ? Utils.html`<div class="ar-rule-env">${scopeLabel(rule)}</div>` : '')}`;
    }

    function disabledHtml(rule) {
        return !rule.enabled && rule.disabledReason
            ? Utils.html`<div class="ar-disabled-reason">Turned off: ${rule.disabledReason}</div>` : '';
    }

    function scheduleRowHtml(rule) {
        const now = new Date();
        const tz = viewerTimezone();
        const line = scheduleLine(rule, now, tz);
        const next = rule.enabled ? nextText(rule, now) : '';
        const strip = typeof WeekStrip !== 'undefined'
            ? WeekStrip.html(WeekStrip.bars(windowsOf(rule), rule.timezone, tz, now),
                { label: line, skippedDay: skippedDay(rule, now, tz), off: !rule.enabled }) : '';
        return Utils.html`<article class="ar-rule${Utils.raw(rule.enabled ? '' : ' ar-rule-off')}" data-rule-id="${rule.ruleId}">
                <div class="ar-rule-main">
                    ${Utils.raw(headHtml(rule))}
                    <div class="ar-rule-summary">${line}${Utils.raw(next ? Utils.html` · next <span class="ar-next-run">${next}</span>` : '')}</div>
                    ${Utils.raw(disabledHtml(rule))}
                    ${Utils.raw(rowActions(rule, true))}
                </div>
                ${Utils.raw(strip)}
            </article>`;
    }

    /** "When access is granted": the access-grant and lock rules, and their form beside them (U12-T04). */
    function renderAccessRules() {
        const list = rules.filter(r => r.triggerType !== 'SCHEDULE');
        const editing = accessForm && accessForm.rule ? accessForm.rule.ruleId : null;
        const cards = list.length ? list.map(rule => Utils.html`
            <article class="ar-rule ar-rule-plain${Utils.raw(rule.enabled ? '' : ' ar-rule-off')}${Utils.raw(rule.ruleId === editing ? ' ar-rule-editing' : '')}" data-rule-id="${rule.ruleId}">
                <div class="ar-rule-main">
                    ${Utils.raw(headHtml(rule, false))}
                    <div class="ar-rule-summary">${accessLine(rule)}</div>
                    ${Utils.raw(disabledHtml(rule))}
                    ${Utils.raw(rowActions(rule, false))}
                </div>
            </article>`).join('')
            : '<div class="ar-empty">No rule starts an environment when someone begins work. Add one with New rule.</div>';
        $('#ar-content').html(`<div class="ar-access${accessForm ? ' ar-access-open' : ''}">
                <div class="ar-access-main">
                    <div class="ar-list-head">
                        <p class="ar-access-intro">Start an environment the moment someone takes its lock or is given access.</p>
                        <button type="button" class="btn btn-secondary btn-sm" id="ar-new-access-btn">New rule</button>
                    </div>
                    <div class="ar-list" id="ar-table-wrapper">${cards}</div>
                </div>
                ${accessForm ? accessFormHtml(accessForm.rule) : ''}
            </div>`);
        if (accessForm) startAccessForm(accessForm.rule);
    }

    function renderHolidays() {
        if (typeof HolidayCalendars === 'undefined') return;
        // Environment admins read the calendars; only administrators change them (U12-T04).
        $('#ar-content').html('<div id="ar-holidays-pane"></div>');
        HolidayCalendars.render($('#ar-holidays-pane'), list => { holidayCalendars = list; }, { canEdit: Auth.isAdmin() });
    }

    /** "Recent runs" (U12-T05): every run of the last 30 days from the audit log, 10 a page. */
    function renderRecentRuns() {
        const envOptions = administered().map(e => Utils.html`<option value="${e.environmentId}"${Utils.raw(runs.environmentId === e.environmentId ? ' selected' : '')}>${e.displayName || e.name}</option>`).join('');
        const result = [['', 'Any result'], ['SKIPPED', 'Skipped'], ['FAILED', 'Failed']]
            .map(([v, l]) => `<option value="${v}"${runs.result === v ? ' selected' : ''}>${l}</option>`).join('');
        $('#ar-content').html(`<div class="ar-runs-bar">
                <label class="ar-runs-filter">Environment
                    <select class="form-select form-select-sm" id="ar-runs-env"><option value="">All environments</option>${envOptions}</select></label>
                <label class="ar-runs-filter">Result
                    <select class="form-select form-select-sm" id="ar-runs-result">${result}</select></label>
                <span class="ar-runs-span">Last ${RUN_DAYS} days</span>
            </div>
            <div id="ar-runs-list"><div class="ar-empty" role="status">Loading runs…</div></div>
            <div id="ar-runs-pager"></div>`);
        loadRuns();
    }

    async function loadRuns() {
        const seq = ++runs.seq;
        let page;
        try {
            page = await apiGetQuiet(runsUrl(runs));
        } catch (error) {
            if (seq !== runs.seq || !isActive()) return;
            $('#ar-runs-list').html(Utils.html`<div class="alert alert-danger" role="alert">${serverMessage(error, 'Could not load the runs.')}</div>`);
            return;
        }
        if (seq !== runs.seq || !isActive() || tab !== 'runs') return;
        runs.data = page || { content: [], totalElements: 0 };
        drawRuns();
    }

    function runRuleHtml(row) {
        const rule = rules.find(r => r.ruleId === row.targetId);
        if (rule && rule.triggerType === 'SCHEDULE') {
            return Utils.html`<a href="#/automation-rules/${encodeURIComponent(rule.ruleId)}">${row.targetName || rule.name}</a>`;
        }
        if (rule) {
            return Utils.html`<button type="button" class="ar-act ar-run-rule" data-rule-id="${rule.ruleId}">${row.targetName || rule.name}</button>`;
        }
        return Utils.html`<span>${row.targetName || 'Deleted rule'}</span>`;
    }

    function drawRuns() {
        const list = runs.data.content || [];
        const total = runs.data.totalElements || 0;
        if (!list.length) {
            $('#ar-runs-list').html(`<div class="ar-empty">No runs in the last ${RUN_DAYS} days${runs.environmentId || runs.result ? ' that match' : ''}.</div>`);
            $('#ar-runs-pager').empty();
            return;
        }
        $('#ar-runs-list').html(`<div class="ar-runs-wrap"><table class="ar-runs-table" id="ar-runs-table">
                <thead><tr><th scope="col">When</th><th scope="col">Rule</th><th scope="col">Environment</th><th scope="col">Result</th><th scope="col">Detail</th></tr></thead>
                <tbody>${list.map(row => {
                    const result = runResult(row);
                    return Utils.html`<tr>
                        <td class="ar-runs-when">${clockText(row.createdAt)}</td>
                        <td>${Utils.raw(runRuleHtml(row))}</td>
                        <td>${row.environmentName || ''}</td>
                        <td><span class="ar-result ar-result-${result.toLowerCase()}">${result}</span></td>
                        <td class="ar-runs-detail">${runDetail(row)}</td>
                    </tr>`;
                }).join('')}</tbody></table></div>`);
        $('#ar-runs-pager').html(total > PAGE_SIZE ? Pagination.buildPager({ page: runs.page, pageSize: PAGE_SIZE,
            totalItems: total, action: 'ar-runs-page', key: 'runs' }) : '');
    }

    // ============= Events =============

    function bind() {
        const NS = '.automationRules';
        const $view = $('#automation-rules-view');
        $view.off(NS)
            .on('click' + NS, '#ar-new-access-btn', () => { accessForm = { rule: null }; renderAccessRules(); })
            .on('click' + NS, '[data-ar-tab]', function() { selectTab(this.dataset.arTab); })
            .on('keydown' + NS, '[data-ar-tab]', function(e) {
                const i = TABS.findIndex(t => t.key === this.dataset.arTab);
                const next = e.key === 'ArrowRight' ? i + 1 : e.key === 'ArrowLeft' ? i - 1 : null;
                if (next === null) return;
                e.preventDefault();
                const key = TABS[(next + TABS.length) % TABS.length].key;
                selectTab(key);
                document.getElementById(`ar-tab-${key}`).focus();
            })
            .on('change' + NS, '#ar-runs-env, #ar-runs-result', () => {
                runs.environmentId = $('#ar-runs-env').val();
                runs.result = $('#ar-runs-result').val();
                runs.page = 1;
                loadRuns();
            })
            .on('click' + NS, '[data-action="ar-runs-page"]', function() {
                runs.page = Number(this.dataset.page) || 1;
                loadRuns();
            })
            .on('click' + NS, '.ar-run-rule', function() {
                const rule = rules.find(r => r.ruleId === this.dataset.ruleId);
                if (!rule) return;
                selectTab('access');
                accessForm = { rule };
                renderAccessRules();
            })
            .on('click' + NS, '[data-action="ar-page"]', function() {
                currentPage = Number(this.dataset.page) || 1;
                renderSchedules();
            })
            .on('click' + NS, '.ar-edit-btn', function() {
                const rule = rules.find(r => r.ruleId === $(this).data('rule-id'));
                if (!rule) return;
                if (rule.triggerType === 'SCHEDULE') {
                    window.location.hash = `#/automation-rules/${encodeURIComponent(rule.ruleId)}`;
                } else {
                    accessForm = { rule };
                    renderAccessRules();
                }
            })
            // 'Skip next stop' (E19-T03): a STOP override around the next stop time, with a reason.
            .on('click' + NS, '.ar-skip-stop-btn', function() {
                const rule = rules.find(r => r.ruleId === $(this).data('rule-id'));
                if (!rule || !rule.nextStopAt) return;
                const at = new Date(rule.nextStopAt).getTime();
                Modals.prompt('Skip next stop', `Why skip the stop at ${clockText(rule.nextStopAt)}?`, async (reason) => {
                    try {
                        await apiPost(Config.API.automationRules.overrides(rule.ruleId), {
                            startsAt: new Date(at - 60000).toISOString(),
                            endsAt: new Date(at + 60000).toISOString(),
                            skipAction: 'STOP',
                            reason: reason.trim()
                        });
                        Notifications.show('The next stop will be skipped.', 'success');
                        await refreshRules();
                    } catch (error) {
                        Notifications.showError(serverMessage(error, 'Failed to skip the next stop.'));
                    }
                }, { required: true, placeholder: 'e.g. release tonight', submitText: 'Skip stop' });
            })
            .on('click' + NS, '.ar-delete-btn', function() {
                const ruleId = $(this).data('rule-id');
                const rule = rules.find(r => r.ruleId === ruleId);
                if (!rule) return;
                DestructiveConfirm.confirmDeleteAutomationRule(rule.name, async function() {
                    try {
                        await apiDelete(Config.API.automationRules.delete(ruleId));
                        Notifications.show('Automation rule deleted.', 'success');
                        await refreshRules();
                    } catch (error) {
                        Notifications.showError('Failed to delete automation rule.');
                    }
                });
            })
            .on('change' + NS, '.ar-toggle-enabled', async function() {
                const ruleId = $(this).data('rule-id');
                const enabled = $(this).is(':checked');
                try {
                    await apiPatch(Config.API.automationRules.setEnabled(ruleId), { enabled });
                    await refreshRules();
                } catch (error) {
                    // e.g. "This rule cannot be enabled: Environment deactivated"
                    Notifications.showError(serverMessage(error, 'Failed to update rule status.'));
                    $(this).prop('checked', !enabled);
                }
            });
    }

    // Writes show their own message (the server's reason), so no second, global toast.
    const QUIET = { suppressGlobalError: true };

    function serverMessage(error, fallback) {
        return (error && error.responseJSON && error.responseJSON.message) || fallback;
    }

    function apiDelete(url) {
        return new Promise((resolve, reject) => {
            ApiClient.delete(url, QUIET).done(resolve).fail(reject);
        });
    }

    function apiPatch(url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.patch(url, data, QUIET).done(resolve).fail(reject);
        });
    }

    function apiPost(url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.post(url, data, QUIET).done(resolve).fail(reject);
        });
    }

    function apiPut(url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.put(url, data, QUIET).done(resolve).fail(reject);
        });
    }

    // ============= Access rule form (U12-T04, AutomationTabs.dc.html) =============
    // Schedules are edited on their own page (rule-editor.js, U12-T03).

    /** The environments the person administers (the server checks each write). */
    function administered() {
        return environments.filter(e => e.viewerCanAdminister !== false && e.isActive !== false);
    }

    function accessFormHtml(rule) {
        const mode = rule ? rule.accessGrantMode : 'LOCK_ACQUIRE';
        const applies = rule ? rule.scopeType : 'ENVIRONMENT';
        const choice = (value, title, sub) => Utils.html`<label class="ag-choice">
                <input type="radio" name="ag-mode" value="${value}"${Utils.raw(mode === value ? ' checked' : '')}>
                <span><b>${title}</b><span>${sub}</span></span></label>`;
        return Utils.html`<aside class="ar-side" id="ag-panel" aria-labelledby="ag-title">
                <div class="ar-side-head">
                    <div class="ar-side-eyebrow">When access is granted</div>
                    <h2 id="ag-title">${rule ? `Edit ${rule.name}` : 'New rule'}</h2>
                </div>
                <form id="ag-form" novalidate>
                    <div class="ar-side-body">
                        <label class="re-field">Name
                            <input type="text" class="form-control" id="ag-name" maxlength="255" autocomplete="off" value="${rule ? rule.name : ''}"></label>
                        <div class="ag-grid">
                            <label class="re-field">Applies to
                                <select class="form-select" id="ag-applies">
                                    <option value="ENVIRONMENT"${Utils.raw(applies === 'ENVIRONMENT' ? ' selected' : '')}>Environment</option>
                                    <option value="GROUP"${Utils.raw(applies === 'GROUP' ? ' selected' : '')}>Group</option>
                                    ${Utils.raw(applies === 'VM' ? '<option value="VM" selected>VM</option>' : '')}
                                </select></label>
                            <label class="re-field">Which
                                <select class="form-select" id="ag-which"></select></label>
                        </div>
                        <fieldset class="ag-modes">
                            <legend class="re-legend">Start it when</legend>
                            ${Utils.raw(choice('LOCK_ACQUIRE', 'Someone takes the lock', 'Started in their name'))}
                            ${Utils.raw(choice('ACCESS_APPROVED', 'An access request is approved', "Started in your name, as the rule's owner"))}
                        </fieldset>
                        <label class="re-check ag-skip"><input type="checkbox" id="ag-skip"${Utils.raw(!rule || rule.skipIfAlreadyInTargetState ? ' checked' : '')}> Do nothing if it's already running</label>
                        <p class="re-note">These rules only start. The lease or a schedule stops it.</p>
                        <div class="re-error" id="ag-error" role="alert" hidden></div>
                    </div>
                    <div class="ar-side-foot">
                        <button type="button" class="btn btn-secondary" id="ag-cancel">Cancel</button>
                        <button type="submit" class="btn btn-primary" id="ag-save">${rule ? 'Save rule' : 'Create rule'}</button>
                    </div>
                </form>
            </aside>`;
    }

    async function loadScopeOptions(environmentId) {
        if (!environmentId || scopeOptionsCache[environmentId]) return;
        try {
            const groups = await apiGetQuiet(Config.API.groups.list(environmentId));
            scopeOptionsCache[environmentId] = { groups: (groups || []).filter(g => g.name !== 'discovered') };
        } catch (error) {
            scopeOptionsCache[environmentId] = { groups: [] };
        }
    }

    /** Which: the environments, or each environment's groups under its name; a rule's environment stays fixed. */
    async function fillWhich(rule) {
        const applies = $('#ag-applies').val();
        const envs = rule ? [{ environmentId: rule.environmentId, name: rule.environmentName }] : administered();
        const $which = $('#ag-which');
        if (applies === 'VM') {
            $which.html(Utils.html`<option value="${rule.environmentId}|${rule.scopeId}">${rule.scopeName || rule.scopeId}</option>`);
            return;
        }
        if (applies === 'ENVIRONMENT') {
            $which.html(envs.map(e => Utils.html`<option value="${e.environmentId}"${Utils.raw(rule && rule.environmentId === e.environmentId ? ' selected' : '')}>${e.displayName || e.name}</option>`).join(''));
            return;
        }
        $which.html('<option value="">Loading groups…</option>');
        await Promise.all(envs.map(e => loadScopeOptions(e.environmentId)));
        if ($('#ag-applies').val() !== 'GROUP') return;
        const html = envs.map(e => {
            const groups = (scopeOptionsCache[e.environmentId] || { groups: [] }).groups;
            return groups.length ? Utils.html`<optgroup label="${e.displayName || e.name}">${Utils.raw(groups.map(g => Utils.html`<option value="${e.environmentId}|${g.groupId}"${Utils.raw(rule && rule.scopeId === g.groupId ? ' selected' : '')}>${g.displayName || g.name}</option>`).join(''))}</optgroup>` : '';
        }).join('');
        $which.html(html || '<option value="">No groups</option>');
    }

    function startAccessForm(rule) {
        fillWhich(rule);
        const $panel = $('#ag-panel');
        $panel.on('change', '#ag-applies', () => fillWhich(rule))
            .on('click', '#ag-cancel', closeAccessForm)
            .on('keydown', e => { if (e.key === 'Escape') closeAccessForm(); })
            .on('submit', '#ag-form', e => { e.preventDefault(); saveAccessRule(rule); });
        $('#ag-name').trigger('focus');
    }

    function closeAccessForm() {
        accessForm = null;
        renderAccessRules();
        $('#ar-new-access-btn').trigger('focus');
    }

    /** The request body (AutomationRuleRequestDTO) of the access rule form. */
    function accessDto(rule) {
        const applies = $('#ag-applies').val();
        const which = String($('#ag-which').val() || '');
        const [environmentId, scopeId] = which.split('|');
        return {
            environmentId: environmentId || null,
            name: $('#ag-name').val().trim(),
            description: rule ? rule.description : null,
            scopeType: applies,
            scopeId: applies === 'ENVIRONMENT' ? null : (scopeId || null),
            triggerType: 'ACCESS_GRANT',
            accessGrantMode: $('input[name="ag-mode"]:checked').val(),
            skipIfAlreadyInTargetState: $('#ag-skip').is(':checked'),
            enabled: rule ? rule.enabled : true
        };
    }

    async function saveAccessRule(rule) {
        if (saving) return;
        const dto = accessDto(rule);
        const $error = $('#ag-error');
        const problem = !dto.name ? 'Give the rule a name.' : !dto.environmentId ? 'Choose which environment or group it starts.' : '';
        if (problem) {
            $error.text(problem).prop('hidden', false);
            $(!dto.name ? '#ag-name' : '#ag-which').trigger('focus');
            return;
        }
        saving = true;
        const $save = $('#ag-save').prop('disabled', true);
        try {
            if (rule) await apiPut(Config.API.automationRules.update(rule.ruleId), dto);
            else await apiPost(Config.API.automationRules.create, dto);
            if (!isActive()) return;
            Notifications.show('Rule saved', 'success');
            accessForm = null;
            await refreshRules();
        } catch (error) {
            $error.text(serverMessage(error, 'The rule was not saved. Try again.')).prop('hidden', false);
            $save.prop('disabled', false);
        } finally {
            saving = false;
        }
    }

    return {
        load,
        model: { clockText, nextText, countsOf, skippedDay, scheduleLine, accessLine, hhmm, windowsOf, runResult, runDetail, runsUrl }
    };
})();

window.AutomationRules = AutomationRules;
