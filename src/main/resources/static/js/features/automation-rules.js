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
    let modalInstance = null;
    let editingRuleId = null;
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
        showLoading();
        fetchInitialData();
    }

    function showLoading() {
        $('#content-area').html('<div class="vr-empty" role="status">Loading…</div>');
    }

    function showError(message) {
        $('#content-area').html(`<div class="alert alert-danger m-3">${Utils.escapeHtml(message)}</div>`);
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
            if (error.status === 403) {
                showError('Access denied. You do not have permission to manage automation rules.');
            } else {
                showError('Failed to load automation rules. Please try again.');
            }
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

    function accessLine(rule) {
        const what = rule.action === 'STOP' ? 'stop' : 'start';
        return rule.accessGrantMode === 'LOCK_ACQUIRE'
            ? `When someone takes the lock: ${what} ${rule.scopeType === 'ENVIRONMENT' ? 'the environment' : rule.scopeName || 'it'}`
            : `When access is approved: ${what} ${rule.scopeType === 'ENVIRONMENT' ? 'the environment' : rule.scopeName || 'it'}`;
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
            <div id="ar-modal-container"></div>
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

    function headHtml(rule) {
        return Utils.html`<div class="ar-rule-head">${Utils.raw(switchHtml(rule))}<h2 class="ar-rule-name">${rule.name}</h2>
                ${Utils.raw(rule.enabled ? '' : '<span class="ar-chip-off">Off</span>')}
                ${Utils.raw(rule.overrideCount ? Utils.html`<span class="ar-override-badge" title="Active or upcoming overrides">${rule.overrideCount} override${rule.overrideCount === 1 ? '' : 's'}</span>` : '')}</div>
            <div class="ar-rule-env">${scopeLabel(rule)}</div>`;
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

    /** "When access is granted": the access-grant and lock rules (U12-T04 brings their editor). */
    function renderAccessRules() {
        const list = rules.filter(r => r.triggerType !== 'SCHEDULE');
        $('#ar-content').html(`<div class="ar-list-head"><button type="button" class="btn btn-secondary btn-sm" id="ar-new-access-btn">New access rule</button></div>
            <div class="ar-list" id="ar-table-wrapper">${list.length ? list.map(rule => Utils.html`
            <article class="ar-rule ar-rule-plain${Utils.raw(rule.enabled ? '' : ' ar-rule-off')}" data-rule-id="${rule.ruleId}">
                <div class="ar-rule-main">
                    ${Utils.raw(headHtml(rule))}
                    <div class="ar-rule-summary">${accessLine(rule)}</div>
                    ${Utils.raw(disabledHtml(rule))}
                    ${Utils.raw(rowActions(rule, false))}
                </div>
            </article>`).join('')
            : '<div class="ar-empty">No rules start or stop an environment when access is granted. Add one with New access rule.</div>'}</div>`);
    }

    function renderHolidays() {
        if (Auth.isAdmin() && typeof HolidayCalendars !== 'undefined') {
            $('#ar-content').html('<div id="ar-holidays-pane"></div>');
            HolidayCalendars.render($('#ar-holidays-pane'), list => { holidayCalendars = list; });
            return;
        }
        $('#ar-content').html(`<div class="ar-empty">Holiday calendars are kept by administrators. A schedule can stay off on
            ${holidayCalendars.length ? Utils.escapeHtml(holidayCalendars.map(c => c.name).join(', ')) : 'their'} holidays.</div>`);
    }

    /** "Recent runs": the last run of each rule (U12-T05 brings the full history). */
    function renderRecentRuns() {
        const recent = rules.filter(r => r.lastRunAt).slice()
            .sort((a, b) => new Date(b.lastRunAt) - new Date(a.lastRunAt)).slice(0, 20);
        $('#ar-content').html(recent.length ? `<div class="ar-runs">${recent.map(r => Utils.html`
            <div class="ar-run" data-rule-id="${r.ruleId}">
                <span class="ar-run-when">${clockText(r.lastRunAt)}</span>
                <span class="ar-run-what"><b>${r.name}</b> · ${r.environmentName || ''}${Utils.raw(r.lastRunDetail ? Utils.html`<span>${r.lastRunDetail}</span>` : '')}</span>
                <span class="ar-run-status ar-run-${String(r.lastRunStatus || '').toLowerCase()}">${
                    r.lastRunStatus === 'SUCCESS' ? 'Done' : r.lastRunStatus === 'SKIPPED' ? 'Skipped' : r.lastRunStatus === 'FAILED' ? 'Failed' : ''}</span>
            </div>`).join('')}</div>`
            : '<div class="ar-empty">No rule has run yet.</div>');
    }

    // ============= Events =============

    function bind() {
        const NS = '.automationRules';
        const $view = $('#automation-rules-view');
        $view.off(NS)
            .on('click' + NS, '#ar-new-access-btn', () => openRuleModal(null))
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
            .on('click' + NS, '[data-action="ar-page"]', function() {
                currentPage = Number(this.dataset.page) || 1;
                renderSchedules();
            })
            .on('click' + NS, '.ar-edit-btn', function() {
                const rule = rules.find(r => r.ruleId === $(this).data('rule-id'));
                if (!rule) return;
                if (rule.triggerType === 'SCHEDULE') window.location.hash = `#/automation-rules/${encodeURIComponent(rule.ruleId)}`;
                else openRuleModal(rule);
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

    // ============= Access-grant rule dialog (U12-T04 brings its page) =============
    // Schedules are edited on their own page (rule-editor.js, U12-T03).

    function openRuleModal(rule) {
        editingRuleId = rule ? rule.ruleId : null;
        $('#ar-modal-container').html(buildModalHtml(rule));
        modalInstance = bootstrap.Modal.getOrCreateInstance(document.getElementById('automationRuleModal'));
        bindModalEvents(rule);
        modalInstance.show();

        // Load scope options for the initially-selected environment
        const envId = rule ? rule.environmentId : (environments[0] && environments[0].environmentId);
        if (envId) {
            loadScopeOptions(envId).then(() => populateScopeSelect(rule));
        }
    }

    function buildModalHtml(rule) {
        const scopeType = rule ? rule.scopeType : 'ENVIRONMENT';
        const accessGrantMode = rule ? rule.accessGrantMode : 'LOCK_ACQUIRE';
        const envOptions = environments.map(e =>
            `<option value="${e.environmentId}" ${rule && rule.environmentId === e.environmentId ? 'selected' : ''}>${Utils.escapeHtml(e.name)}</option>`
        ).join('');

        return `
            <div class="modal fade" id="automationRuleModal" tabindex="-1" aria-labelledby="automationRuleModalLabel">
                <div class="modal-dialog modal-lg modal-dialog-scrollable">
                    <div class="modal-content">
                        <div class="modal-header">
                            <h5 class="modal-title" id="automationRuleModalLabel">${rule ? 'Edit access rule' : 'New access rule'}</h5>
                            <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
                        </div>
                        <div class="modal-body">
                            <form id="ar-form" novalidate>
                                <div class="mb-3">
                                    <label class="form-label" for="ar-name">Name <span class="text-danger">*</span></label>
                                    <input type="text" class="form-control" id="ar-name" required maxlength="255" value="${rule ? Utils.escapeHtml(rule.name) : ''}">
                                </div>
                                <div class="mb-3">
                                    <label class="form-label" for="ar-description">Description</label>
                                    <input type="text" class="form-control" id="ar-description" maxlength="1000" value="${rule ? Utils.escapeHtml(rule.description || '') : ''}">
                                </div>
                                <div class="row mb-3">
                                    <div class="col-md-6">
                                        <label class="form-label" for="ar-environment">Environment <span class="text-danger">*</span></label>
                                        <select class="form-select" id="ar-environment" ${rule ? 'disabled' : ''} required>
                                            ${envOptions}
                                        </select>
                                    </div>
                                    <div class="col-md-6">
                                        <label class="form-label" for="ar-scope-type">Scope</label>
                                        <select class="form-select" id="ar-scope-type">
                                            <option value="ENVIRONMENT" ${scopeType === 'ENVIRONMENT' ? 'selected' : ''}>Entire environment</option>
                                            <option value="GROUP" ${scopeType === 'GROUP' ? 'selected' : ''}>Specific group</option>
                                            <option value="VM" ${scopeType === 'VM' ? 'selected' : ''}>Specific VM</option>
                                        </select>
                                    </div>
                                </div>
                                <div class="mb-3" id="ar-scope-id-wrap" style="display:none">
                                    <label class="form-label" for="ar-scope-id">Select target</label>
                                    <select class="form-select" id="ar-scope-id"></select>
                                </div>
                                <fieldset class="mb-3" id="ar-access-grant-fields">
                                    <legend class="form-label fs-6">Fire when</legend>
                                    <div class="form-check">
                                        <input class="form-check-input" type="radio" name="ar-access-grant-mode" id="ar-mode-lock" value="LOCK_ACQUIRE" ${accessGrantMode === 'LOCK_ACQUIRE' ? 'checked' : ''}>
                                        <label class="form-check-label" for="ar-mode-lock">Someone takes the environment lock (starts the VMs as they begin work)</label>
                                    </div>
                                    <div class="form-check">
                                        <input class="form-check-input" type="radio" name="ar-access-grant-mode" id="ar-mode-approved" value="ACCESS_APPROVED" ${accessGrantMode === 'ACCESS_APPROVED' ? 'checked' : ''}>
                                        <label class="form-check-label" for="ar-mode-approved">An access request is approved or access is granted</label>
                                    </div>
                                </fieldset>
                                <div class="form-check mb-3">
                                    <input class="form-check-input" type="checkbox" id="ar-skip-already" ${!rule || rule.skipIfAlreadyInTargetState ? 'checked' : ''}>
                                    <label class="form-check-label" for="ar-skip-already">Skip VMs already in the target state</label>
                                </div>
                                <div class="form-check mb-0">
                                    <input class="form-check-input" type="checkbox" id="ar-enabled" ${!rule || rule.enabled ? 'checked' : ''}>
                                    <label class="form-check-label" for="ar-enabled">On</label>
                                </div>
                            </form>
                        </div>
                        <div class="modal-footer">
                            <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">Cancel</button>
                            <button type="button" class="btn btn-primary" id="ar-save-btn">${rule ? 'Save rule' : 'Add rule'}</button>
                        </div>
                    </div>
                </div>
            </div>
        `;
    }

    function bindModalEvents(rule) {
        $('#ar-scope-type').on('change', function() {
            $('#ar-scope-id-wrap').toggle($(this).val() !== 'ENVIRONMENT');
            populateScopeSelect(rule);
        });
        $('#ar-environment').on('change', function() {
            loadScopeOptions($(this).val()).then(() => populateScopeSelect(null));
        });
        $('#ar-save-btn').on('click', () => submitForm(rule));
    }

    async function loadScopeOptions(environmentId) {
        if (!environmentId || scopeOptionsCache[environmentId]) return;
        try {
            const [groups, vms] = await Promise.all([
                apiGet(Config.API.groups.list(environmentId)),
                apiGet(Config.API.vms.list(environmentId))
            ]);
            scopeOptionsCache[environmentId] = { groups: groups || [], vms: vms || [] };
        } catch (error) {
            scopeOptionsCache[environmentId] = { groups: [], vms: [] };
        }
    }

    function populateScopeSelect(rule) {
        const environmentId = $('#ar-environment').val();
        const scopeType = $('#ar-scope-type').val();
        const options = scopeOptionsCache[environmentId] || { groups: [], vms: [] };
        const $select = $('#ar-scope-id');

        $('#ar-scope-id-wrap').toggle(scopeType !== 'ENVIRONMENT');

        if (scopeType === 'GROUP') {
            $select.html(options.groups.map(g =>
                `<option value="${g.groupId}" ${rule && rule.scopeId === g.groupId ? 'selected' : ''}>${Utils.escapeHtml(g.displayName || g.name)}</option>`
            ).join(''));
        } else if (scopeType === 'VM') {
            $select.html(options.vms.map(v =>
                `<option value="${v.vmId}" ${rule && rule.scopeId === v.vmId ? 'selected' : ''}>${Utils.escapeHtml(v.displayName || v.name)}</option>`
            ).join(''));
        }
    }

    function buildDto() {
        const scopeType = $('#ar-scope-type').val();
        return {
            environmentId: $('#ar-environment').val(),
            name: $('#ar-name').val().trim(),
            description: $('#ar-description').val().trim(),
            scopeType: scopeType,
            scopeId: scopeType === 'ENVIRONMENT' ? null : $('#ar-scope-id').val(),
            triggerType: 'ACCESS_GRANT',
            accessGrantMode: $('input[name="ar-access-grant-mode"]:checked').val(),
            skipIfAlreadyInTargetState: $('#ar-skip-already').is(':checked'),
            enabled: $('#ar-enabled').is(':checked')
        };
    }

    async function submitForm(rule) {
        if (saving) return;
        const dto = buildDto();
        if (!dto.name) {
            Notifications.showError('Name is required.');
            return;
        }

        saving = true;
        const $save = $('#ar-save-btn').prop('disabled', true);
        try {
            if (rule) {
                await apiPut(Config.API.automationRules.update(rule.ruleId), dto);
            } else {
                await apiPost(Config.API.automationRules.create, dto);
            }
            Notifications.show('Rule saved', 'success');
            modalInstance.hide();
            await refreshRules();
        } catch (error) {
            Notifications.showError(serverMessage(error, 'Failed to save the rule.'));
        } finally {
            saving = false;
            $save.prop('disabled', false);
        }
    }

    return {
        load,
        model: { clockText, nextText, countsOf, skippedDay, scheduleLine, accessLine, hhmm, windowsOf }
    };
})();

window.AutomationRules = AutomationRules;
