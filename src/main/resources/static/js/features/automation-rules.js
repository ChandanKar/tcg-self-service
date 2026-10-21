/**
 * Automation Rules Feature Module
 * Admin / Env Admin view for calendar-schedule and access-grant/lock-acquire rules that start or
 * stop an environment. U12-T02 (AutomationRules.dc.html): tabs Schedules / When access is granted /
 * Holiday calendars / Recent runs, counts, and each schedule with its week strip.
 */
const AutomationRules = (function() {
    'use strict';

    const DAY_ORDER = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];

    // State
    let rules = [];
    let environments = [];
    let holidayCalendars = []; // for the rule modal's calendar select (E19-T02)
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
            // Calendars only fill the rule modal's select; a failure leaves it at "None".
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
                    actionsHtml: `<button type="button" class="btn btn-primary" id="ar-new-rule-btn">
                        <svg width="14" height="14" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M10 4v12M4 10h12"></path></svg>
                        New schedule</button>`,
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
        $('#ar-content').html(`<div class="ar-list" id="ar-table-wrapper">${list.length ? list.map(rule => Utils.html`
            <article class="ar-rule ar-rule-plain${Utils.raw(rule.enabled ? '' : ' ar-rule-off')}" data-rule-id="${rule.ruleId}">
                <div class="ar-rule-main">
                    ${Utils.raw(headHtml(rule))}
                    <div class="ar-rule-summary">${accessLine(rule)}</div>
                    ${Utils.raw(disabledHtml(rule))}
                    ${Utils.raw(rowActions(rule, false))}
                </div>
            </article>`).join('')
            : '<div class="ar-empty">No rules start or stop an environment when access is granted.</div>'}</div>`);
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
            .on('click' + NS, '#ar-new-rule-btn', () => openRuleModal(null))
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
                if (rule) openRuleModal(rule);
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

    // ============= Create / Edit modal =============

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
        const isSchedule = !rule || rule.triggerType === 'SCHEDULE';
        const isAccessGrant = rule && rule.triggerType === 'ACCESS_GRANT';
        const scopeType = rule ? rule.scopeType : 'ENVIRONMENT';
        const windows = initialWindows(rule);
        const accessGrantMode = rule ? rule.accessGrantMode : 'LOCK_ACQUIRE';

        const envOptions = environments.map(e =>
            `<option value="${e.environmentId}" ${rule && rule.environmentId === e.environmentId ? 'selected' : ''}>${Utils.escapeHtml(e.name)}</option>`
        ).join('');

        // A new rule defaults to the browser's zone (it used to default to the first in the list).
        const timezone = rule ? rule.timezone : browserTimezone();
        const tzOptions = Config.IANA_TIMEZONES.map(tz =>
            `<option value="${tz}" ${timezone === tz ? 'selected' : ''}>${tz}</option>`
        ).join('');

        return `
            <div class="modal fade" id="automationRuleModal" tabindex="-1" aria-labelledby="automationRuleModalLabel">
                <div class="modal-dialog modal-lg modal-dialog-scrollable">
                    <div class="modal-content">
                        <div class="modal-header">
                            <h5 class="modal-title" id="automationRuleModalLabel">${rule ? 'Edit Automation Rule' : 'New Automation Rule'}</h5>
                            <button type="button" class="btn-close" data-bs-dismiss="modal"></button>
                        </div>
                        <div class="modal-body">
                            <form id="ar-form" novalidate>
                                <div class="mb-3">
                                    <label class="form-label">Name <span class="text-danger">*</span></label>
                                    <input type="text" class="form-control" id="ar-name" required maxlength="255" value="${rule ? Utils.escapeHtml(rule.name) : ''}">
                                </div>
                                <div class="mb-3">
                                    <label class="form-label">Description</label>
                                    <input type="text" class="form-control" id="ar-description" maxlength="1000" value="${rule ? Utils.escapeHtml(rule.description || '') : ''}">
                                </div>

                                <div class="mb-3">
                                    <label class="form-label" id="ar-trigger-label">Trigger type</label>
                                    <div class="row gap-cards" role="radiogroup" aria-labelledby="ar-trigger-label">
                                        <div class="col-md-6">
                                            <button type="button" role="radio" class="ar-choice-card ${isSchedule ? 'selected' : ''}" data-trigger-type="SCHEDULE"
                                                    aria-checked="${isSchedule ? 'true' : 'false'}" tabindex="${isSchedule ? '0' : '-1'}">
                                                <span class="ar-choice-top"><i class="fas fa-clock" aria-hidden="true"></i> Calendar Schedule</span>
                                                <span class="d-block text-muted" style="font-size:.78rem">Run on a recurring day-of-week and time window.</span>
                                            </button>
                                        </div>
                                        <div class="col-md-6">
                                            <button type="button" role="radio" class="ar-choice-card ${isAccessGrant ? 'selected' : ''}" data-trigger-type="ACCESS_GRANT"
                                                    aria-checked="${isAccessGrant ? 'true' : 'false'}" tabindex="${isAccessGrant ? '0' : '-1'}">
                                                <span class="ar-choice-top"><i class="fas fa-key" aria-hidden="true"></i> Access Grant / Lock Acquire</span>
                                                <span class="d-block text-muted" style="font-size:.78rem">Run the moment a user starts a session or is granted access.</span>
                                            </button>
                                        </div>
                                    </div>
                                    <input type="hidden" id="ar-trigger-type" value="${rule ? rule.triggerType : 'SCHEDULE'}">
                                </div>

                                <div class="row mb-3">
                                    <div class="col-md-6">
                                        <label class="form-label">Environment <span class="text-danger">*</span></label>
                                        <select class="form-select" id="ar-environment" ${rule ? 'disabled' : ''} required>
                                            ${envOptions}
                                        </select>
                                    </div>
                                    <div class="col-md-6">
                                        <label class="form-label">Scope</label>
                                        <select class="form-select" id="ar-scope-type">
                                            <option value="ENVIRONMENT" ${scopeType === 'ENVIRONMENT' ? 'selected' : ''}>Entire environment</option>
                                            <option value="GROUP" ${scopeType === 'GROUP' ? 'selected' : ''}>Specific group</option>
                                            <option value="VM" ${scopeType === 'VM' ? 'selected' : ''}>Specific VM</option>
                                        </select>
                                    </div>
                                </div>
                                <div class="mb-3" id="ar-scope-id-wrap" style="display:none">
                                    <label class="form-label">Select target</label>
                                    <select class="form-select" id="ar-scope-id"></select>
                                </div>

                                <div id="ar-schedule-fields" style="display:${isSchedule ? 'block' : 'none'}">
                                    <div class="d-flex justify-content-between align-items-center mb-2">
                                        <span class="form-label mb-0" id="ar-windows-label">Time windows</span>
                                        <div class="d-flex gap-1">
                                            <button type="button" class="btn btn-sm btn-ghost" id="ar-suggest-btn" aria-controls="ar-suggestion">
                                                <i class="fas fa-wand-magic-sparkles" aria-hidden="true"></i> Suggest from usage</button>
                                            <button type="button" class="btn btn-sm btn-ghost" id="ar-add-window">
                                                <i class="fas fa-plus" aria-hidden="true"></i> Add window</button>
                                        </div>
                                    </div>
                                    <div id="ar-suggestion" class="ar-suggestion" aria-live="polite" hidden></div>
                                    <div id="ar-windows" class="ar-windows" role="list" aria-labelledby="ar-windows-label">${windowsHtml(windows)}</div>
                                    <p class="form-text mt-1 mb-3">Each window starts and/or stops the target on its days. Leave a time empty for a stop-only or start-only window. Windows on the same day must not overlap.</p>
                                    <div class="row mb-3">
                                        <div class="col-md-6">
                                            <label class="form-label" for="ar-timezone">Timezone</label>
                                            <select class="form-select" id="ar-timezone">${tzOptions}</select>
                                        </div>
                                        <div class="col-md-6">
                                            <label class="form-label" for="ar-holiday-calendar">Holiday calendar</label>
                                            <select class="form-select" id="ar-holiday-calendar">${holidayOptions(rule)}</select>
                                        </div>
                                    </div>
                                    <div class="mb-3">
                                        <span class="form-label d-block" id="ar-preview-label">Next runs</span>
                                        <div id="ar-preview" class="ar-preview" aria-labelledby="ar-preview-label" aria-live="polite"></div>
                                    </div>
                                    ${rule && rule.triggerType === 'SCHEDULE' ? `
                                    <div class="mb-3" id="ar-overrides-section">
                                        <span class="form-label d-block" id="ar-overrides-label">Overrides</span>
                                        <div id="ar-overrides" aria-labelledby="ar-overrides-label" aria-live="polite">
                                            <span class="text-muted small">Loading…</span></div>
                                    </div>` : ''}
                                    <fieldset class="mb-3" id="ar-holiday-behavior" ${rule && rule.holidayCalendarId ? '' : 'hidden'}>
                                        <legend class="form-label fs-6">On holidays</legend>
                                        <div class="form-check">
                                            <input class="form-check-input" type="radio" name="ar-holiday-behavior" id="ar-holiday-skip-start" value="SKIP_START"
                                                   ${!rule || rule.holidayBehavior !== 'SKIP_ALL' ? 'checked' : ''}>
                                            <label class="form-check-label" for="ar-holiday-skip-start">Don't start on holidays (stops still run)</label>
                                        </div>
                                        <div class="form-check">
                                            <input class="form-check-input" type="radio" name="ar-holiday-behavior" id="ar-holiday-skip-all" value="SKIP_ALL"
                                                   ${rule && rule.holidayBehavior === 'SKIP_ALL' ? 'checked' : ''}>
                                            <label class="form-check-label" for="ar-holiday-skip-all">Skip all actions on holidays</label>
                                        </div>
                                    </fieldset>
                                </div>

                                <div id="ar-access-grant-fields" style="display:${isAccessGrant ? 'block' : 'none'}">
                                    <div class="mb-3">
                                        <label class="form-label">Fire when</label>
                                        <div class="form-check">
                                            <input class="form-check-input" type="radio" name="ar-access-grant-mode" id="ar-mode-lock" value="LOCK_ACQUIRE" ${accessGrantMode === 'LOCK_ACQUIRE' ? 'checked' : ''}>
                                            <label class="form-check-label" for="ar-mode-lock">A user acquires the environment lock (recommended — starts VMs right when someone begins working)</label>
                                        </div>
                                        <div class="form-check">
                                            <input class="form-check-input" type="radio" name="ar-access-grant-mode" id="ar-mode-approved" value="ACCESS_APPROVED" ${accessGrantMode === 'ACCESS_APPROVED' ? 'checked' : ''}>
                                            <label class="form-check-label" for="ar-mode-approved">An access request is approved / access is granted</label>
                                        </div>
                                    </div>
                                </div>

                                <div class="form-check mb-3">
                                    <input class="form-check-input" type="checkbox" id="ar-skip-already" ${!rule || rule.skipIfAlreadyInTargetState ? 'checked' : ''}>
                                    <label class="form-check-label" for="ar-skip-already">Skip VMs already in the target state</label>
                                </div>
                                <div class="form-check mb-3">
                                    <input class="form-check-input" type="checkbox" id="ar-enabled" ${!rule || rule.enabled ? 'checked' : ''}>
                                    <label class="form-check-label" for="ar-enabled">Enabled</label>
                                </div>

                                <div class="alert alert-light border d-flex gap-2 mb-0">
                                    <i class="fas fa-circle-info text-primary mt-1"></i>
                                    <div style="font-size:.8rem">
                                        <strong>Conflict handling.</strong> If the environment is locked by another user or an operation is running, the rule retries until its catch-up window ends, then is skipped. The rule's creator is notified. Rules never break an active lock.
                                    </div>
                                </div>
                            </form>
                        </div>
                        <div class="modal-footer">
                            <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">Cancel</button>
                            <button type="button" class="btn btn-primary" id="ar-save-btn">${rule ? 'Save Changes' : 'Create Rule'}</button>
                        </div>
                    </div>
                </div>
            </div>
        `;
    }

    /** "None" plus the calendars; a linked calendar that no longer lists still shows by name. */
    function holidayOptions(rule) {
        const selected = rule ? rule.holidayCalendarId : null;
        const options = holidayCalendars.map(c =>
            Utils.html`<option value="${c.calendarId}" ${c.calendarId === selected ? 'selected' : ''}>${c.name}</option>`);
        if (selected && !holidayCalendars.some(c => c.calendarId === selected)) {
            options.push(Utils.html`<option value="${selected}" selected>${rule.holidayCalendarName || selected}</option>`);
        }
        return `<option value="">None</option>${options.join('')}`;
    }

    // ============= Schedule windows (E19-T01) =============

    const MAX_WINDOWS = 7;

    /** A rule's windows; older payloads carry only the single-window fields. */
    function initialWindows(rule) {
        if (!rule) {
            return [{ daysOfWeek: ['MON', 'TUE', 'WED', 'THU', 'FRI'], startTime: '08:00', stopTime: '20:00' }];
        }
        if (Array.isArray(rule.windows) && rule.windows.length) {
            return rule.windows;
        }
        return [{ daysOfWeek: rule.daysOfWeek || [], startTime: rule.startTime || '', stopTime: rule.stopTime || '' }];
    }

    function windowsHtml(windows) {
        const removable = windows.length > 1;
        return windows.map((w, i) => {
            const days = w.daysOfWeek || [];
            const pills = DAY_ORDER.map(day => {
                const on = days.includes(day);
                return `<button type="button" class="ar-day-pill ${on ? 'on' : ''}" data-day="${day}"
                        aria-pressed="${on ? 'true' : 'false'}">${day.charAt(0) + day.slice(1).toLowerCase()}</button>`;
            }).join('');
            const n = i + 1;
            return `
                <div class="ar-window" role="listitem" data-index="${i}">
                    <div class="d-flex gap-2 flex-wrap" role="group" aria-label="Days for window ${n}">${pills}</div>
                    <div class="ar-window-times">
                        <label class="ar-window-time">Start at
                            <input type="time" class="form-control form-control-sm ar-start-time" aria-label="Start time for window ${n}"
                                   value="${Utils.escapeHtml(w.startTime || '')}">
                        </label>
                        <label class="ar-window-time">Stop at
                            <input type="time" class="form-control form-control-sm ar-stop-time" aria-label="Stop time for window ${n}"
                                   value="${Utils.escapeHtml(w.stopTime || '')}">
                        </label>
                        ${removable ? `<button type="button" class="btn btn-sm btn-ghost ar-remove-window" aria-label="Remove window ${n}">
                            <i class="fas fa-trash" aria-hidden="true"></i></button>` : ''}
                    </div>
                </div>`;
        }).join('');
    }

    function collectWindows() {
        return $('#ar-windows .ar-window').map(function() {
            return {
                daysOfWeek: $(this).find('.ar-day-pill.on').map(function() { return $(this).data('day'); }).get(),
                startTime: $(this).find('.ar-start-time').val() || null,
                stopTime: $(this).find('.ar-stop-time').val() || null
            };
        }).get();
    }

    function renderWindows(windows, focusIndex) {
        $('#ar-windows').html(windowsHtml(windows));
        $('#ar-add-window').prop('disabled', windows.length >= MAX_WINDOWS);
        if (focusIndex !== undefined) {
            $('#ar-windows .ar-window').eq(focusIndex).find('.ar-day-pill').first().trigger('focus');
        }
    }

    function browserTimezone() {
        try {
            const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
            return Config.IANA_TIMEZONES.includes(zone) ? zone : null;
        } catch (e) {
            return null;
        }
    }

    function bindModalEvents(rule) {
        // Trigger type is a radio group (E13-T09): click selects; arrow keys move and select.
        const selectTrigger = ($card) => {
            $('.ar-choice-card').removeClass('selected').attr({ 'aria-checked': 'false', tabindex: '-1' });
            $card.addClass('selected').attr({ 'aria-checked': 'true', tabindex: '0' });
            const triggerType = $card.data('trigger-type');
            $('#ar-trigger-type').val(triggerType);
            $('#ar-schedule-fields').toggle(triggerType === 'SCHEDULE');
            $('#ar-access-grant-fields').toggle(triggerType === 'ACCESS_GRANT');
        };
        $('.ar-choice-card').on('click', function() {
            selectTrigger($(this));
        });
        $('.ar-choice-card').on('keydown', function(e) {
            const step = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 }[e.key];
            if (!step) return;
            e.preventDefault();
            const $cards = $('.ar-choice-card');
            const next = $cards.eq(($cards.index(this) + step + $cards.length) % $cards.length);
            selectTrigger(next);
            next.trigger('focus');
        });

        // Window rows are re-rendered on add/remove, so their events are delegated.
        $('#ar-windows').on('click', '.ar-day-pill', function() {
            $(this).toggleClass('on').attr('aria-pressed', $(this).hasClass('on') ? 'true' : 'false');
        });
        $('#ar-windows').on('click', '.ar-remove-window', function() {
            const windows = collectWindows();
            const index = $(this).closest('.ar-window').data('index');
            windows.splice(index, 1);
            renderWindows(windows, Math.max(0, index - 1));
        });
        $('#ar-add-window').prop('disabled', $('#ar-windows .ar-window').length >= MAX_WINDOWS).on('click', () => {
            const windows = collectWindows();
            if (windows.length >= MAX_WINDOWS) return;
            windows.push({ daysOfWeek: [], startTime: null, stopTime: null });
            renderWindows(windows, windows.length - 1);
        });

        $('#ar-holiday-calendar').on('change', function() {
            $('#ar-holiday-behavior').prop('hidden', !$(this).val());
        });

        $('#ar-scope-type').on('change', function() {
            const scopeType = $(this).val();
            $('#ar-scope-id-wrap').toggle(scopeType !== 'ENVIRONMENT');
            populateScopeSelect(rule);
        });

        $('#ar-environment').on('change', function() {
            loadScopeOptions($(this).val()).then(() => populateScopeSelect(null));
        });

        $('#ar-save-btn').on('click', () => submitForm(rule));
        if (rule && rule.triggerType === 'SCHEDULE') {
            loadOverrides(rule);
        }

        $('#ar-suggest-btn').on('click', () => loadSuggestion(rule));

        // Next runs refresh 400 ms after any change to the schedule (E19-T04).
        $('#ar-form').on('input change', () => schedulePreview(rule));
        $('#ar-windows').on('click', '.ar-day-pill, .ar-remove-window', () => schedulePreview(rule));
        $('#ar-add-window').on('click', () => schedulePreview(rule));
        $('.ar-choice-card').on('click', () => schedulePreview(rule));
        schedulePreview(rule, 0);
    }

    // ============= Suggest from usage (E19-T05) =============

    const DAY_LABELS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

    function windowText(w) {
        const days = (w.daysOfWeek || []).map(d => d.charAt(0) + d.slice(1).toLowerCase()).join(', ');
        if (w.startTime && w.stopTime) return `${days} ${w.startTime}–${w.stopTime}`;
        return w.startTime ? `${days} start ${w.startTime}` : `${days} stop ${w.stopTime}`;
    }

    /** 7 x 24 strip of the share of weeks each hour was used (darker is busier). */
    function heatStripHtml(activity) {
        const rows = DAY_LABELS.map((day, d) => {
            const cells = Array.from({ length: 24 }, (_, h) => {
                const a = activity[d * 24 + h] || 0;
                return `<span class="ar-heat-cell" style="opacity:${(0.08 + 0.92 * a).toFixed(2)}"
                    title="${day} ${String(h).padStart(2, '0')}:00 · used in ${Math.round(a * 100)}% of weeks"></span>`;
            }).join('');
            return `<div class="ar-heat-row"><span class="ar-heat-day">${day}</span>${cells}</div>`;
        }).join('');
        const hours = [0, 6, 12, 18].map(h => `<span style="grid-column:${h + 2}">${String(h).padStart(2, '0')}</span>`).join('');
        return `<div class="ar-heat" role="img" aria-label="Usage by hour of the week; darker hours were used in more weeks">
                    ${rows}<div class="ar-heat-hours" aria-hidden="true">${hours}</div></div>`;
    }

    async function loadSuggestion(rule) {
        const $panel = $('#ar-suggestion').prop('hidden', false)
            .html('<div class="text-muted small"><i class="fas fa-spinner fa-spin me-1" aria-hidden="true"></i>Reading the last 4 weeks of usage…</div>');
        const environmentId = $('#ar-environment').val();
        const timezone = $('#ar-timezone').val() || 'UTC';
        let s;
        try {
            s = await apiGetQuiet(Config.API.automationRules.suggestion(environmentId, timezone, 4));
        } catch (error) {
            $panel.html(Utils.html`<div class="text-muted small"><i class="fas fa-circle-info me-1" aria-hidden="true"></i>${serverMessage(error, 'Could not suggest a schedule.')}</div>`);
            return;
        }
        const proposal = s.windows.length
            ? Utils.html`<ul class="mb-1">${s.windows.map(w => Utils.raw(Utils.html`<li>${windowText(w)}</li>`))}</ul>`
            : '<p class="mb-1">No windows: nothing to stop on a schedule.</p>';
        const alwaysOn = s.alwaysOnDays.length
            ? Utils.html`<p class="small text-muted mb-1">Used around the clock on ${s.alwaysOnDays.join(', ')}; those days get no window.</p>` : '';
        const unpriced = s.unpricedVmCount ? ` (${s.unpricedVmCount} VM(s) without a known price not counted)` : '';
        $panel.html(`
            ${heatStripHtml(s.activityByHourOfWeek)}
            <div class="ar-suggestion-body">
                <strong>Suggested (${Utils.escapeHtml(s.timezone)}, last ${s.weeks} weeks)</strong>
                ${proposal}${alwaysOn}
                <p class="small mb-1">${Utils.escapeHtml(`Now ${s.currentRunningHoursPerWeek} VM-hours a week; ${s.suggestedHoursPerWeek} with these windows; about ${Utils.formatCurrency(Number(s.estimatedWeeklySaving))} a week saved${unpriced}.`)}</p>
                <p class="small text-muted mb-2">${Utils.escapeHtml(`Confidence ${s.confidence.toLowerCase()}: ${s.metricsCoveragePercent}% of the used hours had metrics; the rest come from start/stop history.`)}</p>
                <div class="d-flex gap-2">
                    <button type="button" class="btn btn-sm btn-primary" id="ar-use-suggestion" ${s.windows.length ? '' : 'disabled'}>Use these windows</button>
                    <button type="button" class="btn btn-sm btn-ghost" id="ar-close-suggestion">Close</button>
                </div>
            </div>`);
        $('#ar-use-suggestion').on('click', () => {
            renderWindows(s.windows);
            $panel.prop('hidden', true).empty();
            Notifications.show('Windows filled from usage. Review them, then Save.', 'info');
            schedulePreview(rule, 0);
        });
        $('#ar-close-suggestion').on('click', () => $panel.prop('hidden', true).empty());
    }

    let previewTimer = null;
    let previewSeq = 0;

    function schedulePreview(rule, delay = 400) {
        clearTimeout(previewTimer);
        previewTimer = setTimeout(() => refreshPreview(rule), delay);
    }

    async function refreshPreview(rule) {
        const $panel = $('#ar-preview');
        if (!$panel.length || $('#ar-trigger-type').val() !== 'SCHEDULE') return;
        const seq = ++previewSeq;
        const dto = buildDto();
        dto.name = dto.name || 'Preview'; // the name does not change the runs
        let runs;
        try {
            runs = await apiPost(Config.API.automationRules.preview(rule ? rule.ruleId : null), dto);
        } catch (error) {
            if (seq !== previewSeq) return;
            $panel.html(Utils.html`<div class="text-muted small"><i class="fas fa-circle-exclamation me-1" aria-hidden="true"></i>${serverMessage(error, 'Could not preview the schedule.')}</div>`);
            return;
        }
        if (seq !== previewSeq) return; // a newer edit is on its way
        if (!runs || !runs.length) {
            $panel.html('<div class="text-muted small">No runs in the next 60 days.</div>');
            return;
        }
        $panel.html(`<ol class="ar-preview-list">${runs.map(r => {
            const text = `${r.label} — ${r.action === 'STOP' ? 'Stop' : 'Start'}`;
            return r.skipped
                ? Utils.html`<li class="ar-run-skipped"><s>${text}</s> <span class="text-muted">(${r.skipReason})</span></li>`
                : Utils.html`<li>${text}</li>`;
        }).join('')}</ol>`);
    }

    /** The rule's active and upcoming overrides, each removable (E19-T03). */
    async function loadOverrides(rule) {
        const $list = $('#ar-overrides');
        let overrides;
        try {
            overrides = await apiGetQuiet(Config.API.automationRules.overrides(rule.ruleId)) || [];
        } catch (error) {
            $list.html('<span class="text-danger small">Could not load overrides.</span>');
            return;
        }
        if (!overrides.length) {
            $list.html('<span class="text-muted small">None. Use "Skip next stop" in the rules table to skip one stop.</span>');
            return;
        }
        $list.html(`<ul class="list-unstyled mb-0 ar-override-list">${overrides.map(o => Utils.html`
            <li class="d-flex justify-content-between align-items-center gap-2">
                <span><strong>Skip ${o.skipAction === 'ALL' ? 'all actions' : o.skipAction.toLowerCase()}</strong>
                    ${Utils.formatDate(o.startsAt)} – ${Utils.formatDate(o.endsAt)}${o.active ? ' (active)' : ''}
                    <span class="text-muted">· ${o.reason} (${o.createdByDisplayName})</span></span>
                <button type="button" class="btn btn-sm btn-ghost ar-remove-override" data-id="${o.overrideId}"
                        aria-label="Remove override: ${o.reason}"><i class="fas fa-times" aria-hidden="true"></i></button>
            </li>`).join('')}</ul>`);
        $list.find('.ar-remove-override').on('click', async function() {
            try {
                await apiDelete(Config.API.automationRules.deleteOverride(rule.ruleId, $(this).data('id')));
                loadOverrides(rule);
                refreshRules();
            } catch (error) {
                Notifications.showError(serverMessage(error, 'Failed to remove the override.'));
            }
        });
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

    /** The rule as the form describes it (also the preview's draft). */
    function buildDto() {
        const triggerType = $('#ar-trigger-type').val();
        const scopeType = $('#ar-scope-type').val();
        const name = $('#ar-name').val().trim();
        const dto = {
            environmentId: $('#ar-environment').val(),
            name: name,
            description: $('#ar-description').val().trim(),
            scopeType: scopeType,
            scopeId: scopeType === 'ENVIRONMENT' ? null : $('#ar-scope-id').val(),
            triggerType: triggerType,
            skipIfAlreadyInTargetState: $('#ar-skip-already').is(':checked'),
            enabled: $('#ar-enabled').is(':checked')
        };

        if (triggerType === 'SCHEDULE') {
            dto.windows = collectWindows();
            dto.timezone = $('#ar-timezone').val();
            dto.holidayCalendarId = $('#ar-holiday-calendar').val() || null;
            dto.holidayBehavior = $('input[name="ar-holiday-behavior"]:checked').val() || 'SKIP_START';
        } else {
            dto.accessGrantMode = $('input[name="ar-access-grant-mode"]:checked').val();
        }
        return dto;
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
                Notifications.show('Automation rule updated.', 'success');
            } else {
                await apiPost(Config.API.automationRules.create, dto);
                Notifications.show('Automation rule created.', 'success');
            }
            modalInstance.hide();
            await refreshRules();
        } catch (error) {
            Notifications.showError(serverMessage(error, 'Failed to save automation rule.'));
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
