/**
 * VM Self-Service Platform - Schedule editor (U12-T03, RuleEditor.dc.html)
 * #/automation-rules/new and #/automation-rules/<ruleId>: a schedule as Name, Environment (only the
 * ones the person administers, optionally one group), Days as seven pills with one Start at / Stop
 * at for every chosen day, Time zone and On holidays. Rules with different times on different days
 * open in "Advanced: different times per day", the windows editor, and keep their windows. "This
 * week" draws the draft on a week strip with the hours it runs and the first start from
 * POST /automation-rules/preview; "Suggest from last month's usage" fills the days and times from
 * GET /automation-rules/suggestion. Save schedule POSTs or PUTs; the server's reason shows next to
 * the field it is about.
 */
const RuleEditor = (function() {
    'use strict';

    const DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];
    const LABELS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
    const MAX_WINDOWS = 7;
    const WEEKDAYS = { daysOfWeek: ['MON', 'TUE', 'WED', 'THU', 'FRI'], startTime: '08:00', stopTime: '20:00' };
    const LIST_HASH = '#/automation-rules?tab=schedules';
    const QUIET = { suppressGlobalError: true };

    let s = null; // page state

    // ------------------------------------------------------------------ model

    function sortDays(days) {
        return DAYS.filter(d => (days || []).map(x => String(x).toUpperCase()).includes(d));
    }

    /** "Mon–Fri", "Mon, Wed, Sat", "Every day": three or more days in a row read as a range. */
    function daysText(days) {
        const idx = sortDays(days).map(d => DAYS.indexOf(d));
        if (idx.length === 7) return 'Every day';
        const parts = [];
        for (let i = 0; i < idx.length;) {
            let j = i;
            while (j + 1 < idx.length && idx[j + 1] === idx[j] + 1) j++;
            if (j - i >= 2) parts.push(`${LABELS[idx[i]]}–${LABELS[idx[j]]}`);
            else for (let k = i; k <= j; k++) parts.push(LABELS[idx[k]]);
            i = j + 1;
        }
        return parts.join(', ');
    }

    function windowText(w) {
        const days = daysText(w.daysOfWeek);
        if (w.startTime && w.stopTime) return `${days} ${w.startTime}–${w.stopTime}`;
        if (w.startTime) return `${days} start ${w.startTime}`;
        return w.stopTime ? `${days} stop ${w.stopTime}` : days;
    }

    /** The days and times when every window has the same times (one window), else null. */
    function simpleOf(windows) {
        const list = windows || [];
        if (list.length > 1) return null;
        const w = list[0] || { daysOfWeek: [], startTime: '', stopTime: '' };
        return { days: sortDays(w.daysOfWeek), start: w.startTime || '', stop: w.stopTime || '' };
    }

    function windowsFromSimple(simple) {
        return [{ daysOfWeek: sortDays(simple.days), startTime: simple.start || null, stopTime: simple.stop || null }];
    }

    /** Hours a week the draft keeps the environment running (overlaps counted once). */
    function runHours(windows) {
        if (typeof WeekStrip === 'undefined') return 0;
        return WeekStrip.bars(windows, null, null).reduce((sum, day) => {
            let end = -1;
            let total = 0;
            day.filter(b => b.kind === 'run').forEach(b => {
                const from = Math.max(b.from, end);
                if (b.to > from) total += b.to - from;
                end = Math.max(end, b.to);
            });
            return sum + total;
        }, 0);
    }

    function hoursNumber(h) {
        const r = Math.round(h * 10) / 10;
        return Number.isInteger(r) ? String(r) : r.toFixed(1);
    }

    /** "Runs 60 hours a week instead of 168", or what a schedule without starts does. */
    function hoursText(windows) {
        const h = runHours(windows);
        const hasStop = (windows || []).some(w => w.stopTime && (w.daysOfWeek || []).length);
        if (h > 0) return `Runs ${hoursNumber(h)} hour${h === 1 ? '' : 's'} a week instead of 168`;
        return hasStop ? 'Only stops: nothing is started on a schedule' : 'Pick days and a time to see the week';
    }

    /** The first run the preview lists that is not skipped: "first start Mon 20 Oct, 08:00 IST". */
    function firstText(runs) {
        const list = (runs || []).filter(r => !r.skipped);
        const start = list.find(r => r.action === 'START');
        if (start) return `first start ${start.label}`;
        return list.length ? `first stop ${list[0].label}` : '';
    }

    /** The On holidays choices: for each calendar, don't start / don't start or stop; and run as usual. */
    function holidayChoices(calendars, rule) {
        const list = (calendars || []).map(c => ({ id: c.calendarId, name: c.name }));
        if (rule && rule.holidayCalendarId && !list.some(c => c.id === rule.holidayCalendarId)) {
            list.push({ id: rule.holidayCalendarId, name: rule.holidayCalendarName || 'the linked calendar' });
        }
        const out = [];
        list.forEach(c => out.push({ value: `SKIP_START:${c.id}`, label: `Don't start on ${c.name} holidays` }));
        out.push({ value: '', label: 'Run as usual' });
        list.forEach(c => out.push({ value: `SKIP_ALL:${c.id}`, label: `Don't start or stop on ${c.name} holidays` }));
        return out;
    }

    function holidayValue(rule) {
        if (!rule || !rule.holidayCalendarId) return '';
        return `${rule.holidayBehavior === 'SKIP_ALL' ? 'SKIP_ALL' : 'SKIP_START'}:${rule.holidayCalendarId}`;
    }

    function parseHoliday(value) {
        const i = String(value || '').indexOf(':');
        if (i < 0) return { holidayCalendarId: null, holidayBehavior: 'SKIP_START' };
        return { holidayCalendarId: value.slice(i + 1), holidayBehavior: value.slice(0, i) === 'SKIP_ALL' ? 'SKIP_ALL' : 'SKIP_START' };
    }

    /** Which field a server message is about. */
    function fieldOf(message) {
        const m = String(message || '');
        if (/^name\b/i.test(m)) return 'name';
        if (/timezone|time zone/i.test(m)) return 'tz';
        if (/overlap|time|window|\bday\b/i.test(m)) return 'times';
        if (/holiday/i.test(m)) return 'holidays';
        if (/environment|group|\bVM\b|scope/i.test(m)) return 'environment';
        return 'form';
    }

    /** The request body (AutomationRuleRequestDTO) for the form's values. */
    function dtoOf(f) {
        const scope = String(f.scope || '');
        const i = scope.indexOf(':');
        const dto = {
            environmentId: f.environmentId,
            name: (f.name || '').trim(),
            description: f.description || null,
            scopeType: i < 0 ? 'ENVIRONMENT' : scope.slice(0, i),
            scopeId: i < 0 ? null : scope.slice(i + 1),
            triggerType: 'SCHEDULE',
            skipIfAlreadyInTargetState: f.skipIfAlreadyInTargetState !== false,
            enabled: f.enabled !== false,
            windows: f.windows,
            timezone: f.timezone
        };
        return Object.assign(dto, parseHoliday(f.holiday));
    }

    /** What a form misses before it is sent, by field. */
    function problems(f) {
        const out = {};
        if (!(f.name || '').trim()) out.name = 'Give the schedule a name.';
        if (!f.environmentId) out.environment = 'Pick an environment.';
        const windows = f.windows || [];
        if (windows.some(w => !(w.daysOfWeek || []).length)) out.times = 'Pick at least one day.';
        else if (windows.some(w => !w.startTime && !w.stopTime)) out.times = 'Set a start time, a stop time or both.';
        return out;
    }

    // ------------------------------------------------------------------ page

    function call(method, url, body) {
        return new Promise((resolve, reject) => {
            const req = method === 'GET' ? ApiClient.get(url, QUIET)
                : method === 'POST' ? ApiClient.post(url, body, QUIET) : ApiClient.put(url, body, QUIET);
            req.done(resolve).fail(reject);
        });
    }

    function serverMessage(error, fallback) {
        return (error && error.responseJSON && error.responseJSON.message) || fallback;
    }

    function current() {
        return s && ContentRouter.isCurrent(s.token);
    }

    // Old names some browsers still report for zones the list has under their current name.
    const ZONE_ALIASES = { 'Asia/Calcutta': 'Asia/Kolkata', 'Asia/Saigon': 'Asia/Ho_Chi_Minh', 'Asia/Katmandu': 'Asia/Kathmandu',
        'Asia/Rangoon': 'Asia/Yangon', 'Europe/Kiev': 'Europe/Kyiv', 'America/Buenos_Aires': 'America/Argentina/Buenos_Aires' };

    function browserTimezone() {
        try {
            const reported = Intl.DateTimeFormat().resolvedOptions().timeZone;
            const zone = Config.IANA_TIMEZONES.includes(reported) ? reported : ZONE_ALIASES[reported];
            return Config.IANA_TIMEZONES.includes(zone) ? zone : 'UTC';
        } catch (e) {
            return 'UTC';
        }
    }

    /** Router loader: params.ruleId to edit a schedule, none for a new one. */
    async function load(params) {
        const token = ContentRouter.token();
        const ruleId = params && params.ruleId;
        s = { token, ruleId, rule: null, environments: [], calendars: [], groups: {}, mode: 'simple',
            simple: simpleOf([WEEKDAYS]), windows: [WEEKDAYS], saving: false, previewSeq: 0, previewTimer: null };
        ContentRouter.onLeave(() => { if (s) clearTimeout(s.previewTimer); });
        $('#content-area').html('<div class="content-view re-page"><div class="vr-empty" role="status">Loading…</div></div>');
        let envs;
        let rule = null;
        try {
            [envs, rule] = await Promise.all([
                call('GET', Config.API.environments.list),
                ruleId ? call('GET', Config.API.automationRules.get(ruleId)) : Promise.resolve(null)
            ]);
        } catch (error) {
            if (!current()) return;
            $('#content-area .re-page').html(UiStates.failed({ what: ruleId ? 'This schedule' : 'Your environments', xhr: error,
                onRetry: () => ContentRouter.reload() }) + Utils.html`<p><a href="${LIST_HASH}">Back to automation rules</a></p>`);
            return;
        }
        const calendars = await call('GET', Config.API.holidayCalendars.list).catch(() => []);
        if (!current()) return;
        if (rule && rule.triggerType !== 'SCHEDULE') {
            // Access-grant rules keep their dialog on the Automation rules page (U12-T04 brings their editor).
            window.location.replace('#/automation-rules?tab=access');
            return;
        }
        s.rule = rule;
        s.calendars = Array.isArray(calendars) ? calendars : [];
        s.environments = (Array.isArray(envs) ? envs : []).filter(e => e.viewerCanAdminister !== false && e.isActive !== false);
        if (rule) {
            const windows = rule.windows && rule.windows.length ? rule.windows
                : [{ daysOfWeek: rule.daysOfWeek || [], startTime: rule.startTime, stopTime: rule.stopTime }];
            s.windows = windows.map(w => ({ daysOfWeek: sortDays(w.daysOfWeek), startTime: w.startTime || null, stopTime: w.stopTime || null }));
            const simple = simpleOf(s.windows);
            s.mode = simple ? 'simple' : 'advanced';
            if (simple) s.simple = simple;
        }
        render();
    }

    function envOptions() {
        const rule = s.rule;
        const list = s.environments.slice();
        if (rule && !list.some(e => e.environmentId === rule.environmentId)) {
            list.push({ environmentId: rule.environmentId, name: rule.environmentName, displayName: rule.environmentName });
        }
        return list.map(e => Utils.html`<option value="${e.environmentId}"${Utils.raw(rule && rule.environmentId === e.environmentId ? ' selected' : '')}>${
            e.displayName || e.name}</option>`).join('');
    }

    function render() {
        const rule = s.rule;
        const title = rule ? `Edit ${rule.name}` : 'New schedule';
        const tz = rule ? rule.timezone : browserTimezone();
        const holiday = holidayValue(rule);
        const noEnvs = !rule && !s.environments.length;
        $('#content-area').html(`
            <div class="content-view re-page" id="rule-editor-view">
                ${PageHeader.markup({ title, breadcrumb: [{ label: 'Automation rules', href: LIST_HASH }, { label: rule ? rule.name : 'New schedule' }],
                    summaryHtml: 'Start and stop an environment at the same times each week.',
                    helpPage: typeof HelpLink !== 'undefined' ? 'automation-rules' : null, helpLabel: 'Automation rules' })}
                ${noEnvs ? `<div class="re-card"><div class="re-body"><p class="re-note">You don't administer an environment, so there is
                    nothing to schedule. Ask an administrator for environment admin access.</p></div></div>` : `
                <form class="re-card" id="ar-form" novalidate>
                    <div class="re-body">
                        <div class="re-grid re-grid-2">
                            <label class="re-field">Name
                                <input type="text" class="form-control" id="ar-name" maxlength="255" autocomplete="off"
                                       aria-describedby="re-err-name" value="${Utils.escapeHtml(rule ? rule.name : '')}">
                                <span class="re-error" id="re-err-name" hidden></span>
                            </label>
                            <div class="re-field">
                                <label for="ar-environment">Environment</label>
                                <select class="form-select" id="ar-environment" aria-describedby="re-err-environment"${rule ? ' disabled' : ''}>${envOptions()}</select>
                                <label class="re-check"><input type="checkbox" id="re-only-group"${rule && rule.scopeType !== 'ENVIRONMENT' ? ' checked' : ''}> Only one group</label>
                                <select class="form-select" id="ar-scope-id" aria-label="Group" hidden></select>
                                <span class="re-error" id="re-err-environment" hidden></span>
                            </div>
                        </div>
                        <div id="re-times"></div>
                        <label class="re-field" id="re-tz-field">Time zone
                            <select class="form-select" id="ar-timezone" aria-describedby="re-err-tz">${Config.IANA_TIMEZONES.map(z =>
                                `<option value="${z}"${z === tz ? ' selected' : ''}>${z}</option>`).join('')}</select>
                            <span class="re-error" id="re-err-tz" hidden></span>
                        </label>
                        <label class="re-field">On holidays
                                <select class="form-select" id="re-holidays" aria-describedby="re-err-holidays">${holidayChoices(s.calendars, rule).map(o =>
                                    Utils.html`<option value="${o.value}"${Utils.raw(o.value === holiday ? ' selected' : '')}>${o.label}</option>`).join('')}</select>
                                <span class="re-error" id="re-err-holidays" hidden></span>
                            </label>
                        <div id="ar-suggestion" class="ar-suggestion" aria-live="polite" hidden></div>
                        <section class="re-week" id="ar-preview" aria-labelledby="re-week-title" aria-live="polite">
                            <h2 class="re-week-title" id="re-week-title">This week</h2>
                            <div id="re-week-strip"></div>
                            <div class="re-hours" id="re-hours"></div>
                        </section>
                        ${rule ? `<section class="re-overrides" aria-labelledby="re-overrides-title">
                            <h2 class="re-week-title" id="re-overrides-title">Overrides</h2>
                            <div id="ar-overrides"><span class="re-note">Loading…</span></div>
                        </section>` : ''}
                        <div class="re-error re-error-form" id="re-err-form" role="alert" hidden></div>
                    </div>
                    <div class="re-foot">
                        <button type="button" class="re-link" id="ar-suggest-btn" aria-controls="ar-suggestion">Suggest from last month's usage</button>
                        <div class="re-foot-actions">
                            <a class="btn btn-secondary" href="${LIST_HASH}" id="re-cancel">Cancel</a>
                            <button type="submit" class="btn btn-primary" id="ar-save-btn">Save schedule</button>
                        </div>
                    </div>
                </form>`}
            </div>`);
        if (noEnvs) return;
        renderTimes();
        bind();
        loadGroups();
        if (rule) loadOverrides();
        drawWeek();
        schedulePreview(0);
    }

    // ---- days and times

    function pillsHtml(days, label, extraClass) {
        return DAYS.map((d, i) => {
            const on = days.includes(d);
            return `<button type="button" class="ar-day-pill${on ? ' on' : ''}${extraClass || ''}" data-day="${d}" aria-pressed="${on}"
                aria-label="${LABELS[i]}${label ? ` ${label}` : ''}">${LABELS[i]}</button>`;
        }).join('');
    }

    function windowsHtml(windows) {
        const removable = windows.length > 1;
        return windows.map((w, i) => {
            const n = i + 1;
            return `<div class="ar-window" role="listitem" data-index="${i}">
                    <div class="re-pills" role="group" aria-label="Days for times ${n}">${pillsHtml(w.daysOfWeek || [], `(times ${n})`)}</div>
                    <div class="ar-window-times">
                        <label class="ar-window-time">Start at
                            <input type="time" class="form-control form-control-sm ar-start-time" aria-label="Start time for times ${n}"
                                   value="${Utils.escapeHtml(w.startTime || '')}"></label>
                        <label class="ar-window-time">Stop at
                            <input type="time" class="form-control form-control-sm ar-stop-time" aria-label="Stop time for times ${n}"
                                   value="${Utils.escapeHtml(w.stopTime || '')}"></label>
                        ${removable ? `<button type="button" class="btn btn-sm btn-ghost ar-remove-window" aria-label="Remove times ${n}">
                            <i class="fas fa-trash" aria-hidden="true"></i></button>` : ''}
                    </div>
                </div>`;
        }).join('');
    }

    function renderTimes(focusIndex) {
        const err = '<span class="re-error" id="re-err-times" hidden></span>';
        // Time zone sits beside Start at / Stop at (RuleEditor.dc.html), and under the advanced list.
        const $tz = $('#re-tz-field').detach();
        if (s.mode === 'simple') {
            const x = s.simple;
            $('#re-times').html(`
                <fieldset class="re-days">
                    <legend class="re-legend">Days</legend>
                    <div class="re-pills" id="re-days">${pillsHtml(x.days, '')}</div>
                </fieldset>
                <div class="re-grid re-grid-3 re-times-row">
                    <label class="re-field">Start at
                        <input type="time" class="form-control re-time" id="re-start" value="${Utils.escapeHtml(x.start)}" aria-describedby="re-err-times"></label>
                    <label class="re-field">Stop at
                        <input type="time" class="form-control re-time" id="re-stop" value="${Utils.escapeHtml(x.stop)}" aria-describedby="re-err-times"></label>
                    <div id="re-tz-slot"></div>
                </div>
                ${err}
                <div class="re-hint-row"><p class="re-hint">Leave Start at empty to only stop it; people start it when they need it.</p>
                    <button type="button" class="re-link" id="re-advanced" aria-expanded="false">Advanced: different times per day</button></div>`);
            $('#re-tz-slot').replaceWith($tz);
            return;
        }
        $('#re-times').html(`
            <fieldset class="re-days">
                <legend class="re-legend">Different times per day</legend>
                <div id="ar-windows" class="ar-windows" role="list">${windowsHtml(s.windows)}</div>
                <div class="re-advanced-actions">
                    <button type="button" class="btn btn-sm btn-ghost" id="ar-add-window"${s.windows.length >= MAX_WINDOWS ? ' disabled' : ''}>
                        <i class="fas fa-plus" aria-hidden="true"></i> Add times</button>
                    <button type="button" class="re-link" id="re-advanced" aria-expanded="true">Same times every day</button>
                </div>
            </fieldset>
            ${err}
            <div class="re-grid re-grid-2"><div id="re-tz-slot"></div></div>`);
        $('#re-tz-slot').replaceWith($tz);
        if (focusIndex !== undefined) {
            $('#ar-windows .ar-window').eq(focusIndex).find('.ar-day-pill').first().trigger('focus');
        }
    }

    /** The windows the form holds now. */
    function readWindows() {
        if (s.mode === 'simple') {
            s.simple = {
                days: $('#re-days .ar-day-pill.on').map(function() { return this.dataset.day; }).get(),
                start: $('#re-start').val() || '',
                stop: $('#re-stop').val() || ''
            };
            return windowsFromSimple(s.simple);
        }
        s.windows = $('#ar-windows .ar-window').map(function() {
            return {
                daysOfWeek: $(this).find('.ar-day-pill.on').map(function() { return this.dataset.day; }).get(),
                startTime: $(this).find('.ar-start-time').val() || null,
                stopTime: $(this).find('.ar-stop-time').val() || null
            };
        }).get();
        return s.windows;
    }

    function setMode(mode) {
        const windows = readWindows();
        if (mode === 'advanced') {
            s.windows = windows;
        } else {
            // Back to one set of times: every chosen day, the first times.
            const days = sortDays([].concat(...windows.map(w => w.daysOfWeek || [])));
            const first = windows[0] || {};
            s.simple = { days, start: first.startTime || '', stop: first.stopTime || '' };
        }
        s.mode = mode;
        renderTimes();
        $('#re-advanced').trigger('focus');
        changed(0);
    }

    function useWindows(windows) {
        const simple = simpleOf(windows);
        if (simple) {
            s.mode = 'simple';
            s.simple = simple;
        } else {
            s.mode = 'advanced';
            s.windows = windows.map(w => ({ daysOfWeek: sortDays(w.daysOfWeek), startTime: w.startTime || null, stopTime: w.stopTime || null }));
        }
        renderTimes();
        changed(0);
    }

    // ---- scope

    async function loadGroups() {
        const environmentId = $('#ar-environment').val();
        if (!environmentId) return;
        if (!s.groups[environmentId]) {
            s.groups[environmentId] = await call('GET', Config.API.groups.list(environmentId)).catch(() => []) || [];
            if (!current()) return;
        }
        fillScope();
    }

    function fillScope() {
        const rule = s.rule;
        const groups = (s.groups[$('#ar-environment').val()] || []).filter(g => g.name !== 'discovered');
        const selected = rule && rule.scopeType !== 'ENVIRONMENT' ? `${rule.scopeType}:${rule.scopeId}` : '';
        const options = groups.map(g => Utils.html`<option value="GROUP:${g.groupId}"${Utils.raw(selected === `GROUP:${g.groupId}` ? ' selected' : '')}>${g.displayName || g.name}</option>`);
        if (rule && rule.scopeType === 'VM') {
            options.unshift(Utils.html`<option value="VM:${rule.scopeId}" selected>VM: ${rule.scopeName || rule.scopeId}</option>`);
        }
        $('#ar-scope-id').html(options.join(''));
        const only = $('#re-only-group');
        only.prop('disabled', !options.length && !only.is(':checked'));
        $('#ar-scope-id').prop('hidden', !only.is(':checked'));
    }

    // ---- preview

    function formValues() {
        return {
            environmentId: $('#ar-environment').val(),
            name: $('#ar-name').val(),
            description: s.rule ? s.rule.description : null,
            scope: $('#re-only-group').is(':checked') ? $('#ar-scope-id').val() : '',
            skipIfAlreadyInTargetState: s.rule ? s.rule.skipIfAlreadyInTargetState : true,
            enabled: s.rule ? s.rule.enabled : true,
            windows: readWindows(),
            timezone: $('#ar-timezone').val(),
            holiday: $('#re-holidays').val()
        };
    }

    function drawWeek(firstLine) {
        const windows = readWindows();
        const tz = $('#ar-timezone').val();
        const label = `This week: ${windows.map(windowText).join('; ')} · ${tz}`;
        $('#re-week-strip').html(typeof WeekStrip !== 'undefined' ? WeekStrip.html(WeekStrip.bars(windows, tz, tz), { label }) : '');
        const hours = hoursText(windows);
        $('#re-hours').html(Utils.html`<span class="re-hours-main">${hours}</span>${Utils.raw(firstLine
            ? Utils.html`<span class="re-first"> · ${firstLine}</span>` : '')}`);
    }

    function changed(delay) {
        clearError();
        drawWeek(s.firstLine);
        schedulePreview(delay);
    }

    function schedulePreview(delay = 400) {
        clearTimeout(s.previewTimer);
        s.previewTimer = setTimeout(refreshPreview, delay);
    }

    async function refreshPreview() {
        if (!current()) return;
        const seq = ++s.previewSeq;
        const dto = dtoOf(formValues());
        dto.name = dto.name || 'Preview'; // the name does not change the runs
        let runs;
        try {
            runs = await call('POST', Config.API.automationRules.preview(s.rule ? s.rule.ruleId : null), dto);
        } catch (error) {
            if (seq !== s.previewSeq || !current()) return;
            s.firstLine = '';
            drawWeek();
            $('#re-hours').append(Utils.html`<span class="re-preview-error"> · ${serverMessage(error, 'Could not preview the schedule.')}</span>`);
            return;
        }
        if (seq !== s.previewSeq || !current()) return;
        s.firstLine = firstText(runs) || (runs && runs.length ? '' : 'no runs in the next 60 days');
        drawWeek(s.firstLine);
    }

    // ---- suggestion

    function heatStripHtml(activity) {
        const rows = LABELS.map((day, d) => {
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

    async function loadSuggestion() {
        const $panel = $('#ar-suggestion').prop('hidden', false)
            .html('<div class="re-note" role="status">Reading the last 4 weeks of usage…</div>');
        let x;
        try {
            x = await call('GET', Config.API.automationRules.suggestion($('#ar-environment').val(), $('#ar-timezone').val() || 'UTC', 4));
        } catch (error) {
            if (!current()) return;
            $panel.html(Utils.html`<div class="re-note">${serverMessage(error, 'Could not suggest a schedule.')}</div>
                <button type="button" class="re-link" id="ar-close-suggestion">Close</button>`);
            return;
        }
        if (!current()) return;
        const proposal = x.windows.length
            ? Utils.html`<ul class="re-suggest-list">${x.windows.map(w => Utils.raw(Utils.html`<li>${windowText(w)}</li>`))}</ul>`
            : '<p class="re-note">Nothing to stop on a schedule: it is used whenever it runs.</p>';
        const alwaysOn = x.alwaysOnDays.length
            ? Utils.html`<p class="re-note">Used around the clock on ${x.alwaysOnDays.join(', ')}; those days get no times.</p>` : '';
        const unpriced = x.unpricedVmCount ? ` (${x.unpricedVmCount} VM${x.unpricedVmCount === 1 ? '' : 's'} without a known price not counted)` : '';
        $panel.html(`
            ${heatStripHtml(x.activityByHourOfWeek)}
            <div class="ar-suggestion-body">
                <strong>${Utils.escapeHtml(`Based on the last ${x.weeks} weeks of use, in ${x.timezone}`)}</strong>
                ${proposal}${alwaysOn}
                <p class="re-small">${Utils.escapeHtml(`Now ${x.currentRunningHoursPerWeek} VM-hours a week; ${x.suggestedHoursPerWeek} with these times; about ${Utils.formatCurrency(Number(x.estimatedWeeklySaving))} a week saved${unpriced}.`)}</p>
                <p class="re-note">${Utils.escapeHtml(`Confidence ${String(x.confidence).toLowerCase()}: ${x.metricsCoveragePercent}% of the used hours had metrics; the rest come from start and stop history.`)}</p>
                <div class="re-suggest-actions">
                    <button type="button" class="btn btn-sm btn-primary" id="ar-use-suggestion"${x.windows.length ? '' : ' disabled'}>Use these times</button>
                    <button type="button" class="btn btn-sm btn-ghost" id="ar-close-suggestion">Close</button>
                </div>
            </div>`);
        s.suggestion = x;
    }

    // ---- overrides (edit)

    async function loadOverrides() {
        const rule = s.rule;
        const $list = $('#ar-overrides');
        let overrides;
        try {
            overrides = await call('GET', Config.API.automationRules.overrides(rule.ruleId)) || [];
        } catch (error) {
            if (current()) $list.html('<span class="re-error">Could not load the overrides.</span>');
            return;
        }
        if (!current()) return;
        if (!overrides.length) {
            $list.html('<span class="re-note">None. Use "Skip next stop" on the schedule to skip one stop.</span>');
            return;
        }
        $list.html(`<ul class="re-override-list">${overrides.map(o => Utils.html`
            <li>
                <span><strong>Skip ${o.skipAction === 'ALL' ? 'all actions' : o.skipAction.toLowerCase()}</strong>
                    ${Utils.formatDate(o.startsAt)} – ${Utils.formatDate(o.endsAt)}${o.active ? ' (active)' : ''}
                    <span class="re-note">· ${o.reason} (${o.createdByDisplayName})</span></span>
                <button type="button" class="btn btn-sm btn-ghost ar-remove-override" data-id="${o.overrideId}"
                        aria-label="Remove override: ${o.reason}"><i class="fas fa-times" aria-hidden="true"></i></button>
            </li>`).join('')}</ul>`);
    }

    // ---- errors and save

    function clearError() {
        $('#rule-editor-view .re-error').prop('hidden', true).text('');
        $('#rule-editor-view [aria-invalid]').removeAttr('aria-invalid');
    }

    function showError(field, message) {
        const key = ['name', 'environment', 'times', 'tz', 'holidays'].includes(field) ? field : 'form';
        $(`#re-err-${key}`).text(message).prop('hidden', false);
        const input = { name: '#ar-name', environment: '#ar-environment', tz: '#ar-timezone', holidays: '#re-holidays',
            times: s.mode === 'simple' ? '#re-start, #re-stop' : '#ar-windows input' }[key];
        if (input) $(input).attr('aria-invalid', 'true');
    }

    async function save() {
        if (s.saving) return;
        clearError();
        const f = formValues();
        const missing = problems(f);
        const keys = Object.keys(missing);
        if (keys.length) {
            keys.forEach(k => showError(k, missing[k]));
            const first = { name: '#ar-name', environment: '#ar-environment' }[keys[0]];
            $(first || (s.mode === 'simple' ? '#re-days .ar-day-pill' : '#ar-windows .ar-day-pill')).first().trigger('focus');
            return;
        }
        s.saving = true;
        const $btn = $('#ar-save-btn').prop('disabled', true).text('Saving…');
        try {
            const dto = dtoOf(f);
            if (s.rule) await call('PUT', Config.API.automationRules.update(s.rule.ruleId), dto);
            else await call('POST', Config.API.automationRules.create, dto);
            if (!current()) return;
            Notifications.show('Schedule saved', 'success');
            window.location.hash = '#/automation-rules';
        } catch (error) {
            if (!current()) return;
            const message = serverMessage(error, 'The schedule was not saved. Try again.');
            showError(fieldOf(message), message);
            s.saving = false;
            $btn.prop('disabled', false).text('Save schedule');
        }
    }

    // ---- events

    function bind() {
        const NS = '.ruleEditor';
        const $view = $('#rule-editor-view');
        $view.off(NS)
            .on('submit' + NS, '#ar-form', e => { e.preventDefault(); save(); })
            .on('click' + NS, '.ar-day-pill', function() {
                const on = !this.classList.contains('on');
                this.classList.toggle('on', on);
                this.setAttribute('aria-pressed', String(on));
                changed();
            })
            .on('input' + NS + ' change' + NS, '#re-times input, #ar-timezone, #re-holidays', () => changed())
            .on('click' + NS, '#re-advanced', () => setMode(s.mode === 'simple' ? 'advanced' : 'simple'))
            .on('click' + NS, '#ar-add-window', () => {
                const windows = readWindows();
                if (windows.length >= MAX_WINDOWS) return;
                s.windows = windows.concat([{ daysOfWeek: [], startTime: null, stopTime: null }]);
                renderTimes(s.windows.length - 1);
                changed();
            })
            .on('click' + NS, '.ar-remove-window', function() {
                const windows = readWindows();
                const index = Number($(this).closest('.ar-window').data('index'));
                windows.splice(index, 1);
                s.windows = windows;
                renderTimes(Math.max(0, index - 1));
                changed(0);
            })
            .on('change' + NS, '#ar-environment', () => { loadGroups(); changed(0); })
            .on('change' + NS, '#re-only-group', function() {
                $('#ar-scope-id').prop('hidden', !this.checked);
                if (this.checked) $('#ar-scope-id').trigger('focus');
            })
            .on('click' + NS, '#ar-suggest-btn', () => loadSuggestion())
            .on('click' + NS, '#ar-close-suggestion', () => { $('#ar-suggestion').prop('hidden', true).empty(); $('#ar-suggest-btn').trigger('focus'); })
            .on('click' + NS, '#ar-use-suggestion', () => {
                if (!s.suggestion) return;
                if (s.suggestion.timezone && Config.IANA_TIMEZONES.includes(s.suggestion.timezone)) $('#ar-timezone').val(s.suggestion.timezone);
                useWindows(s.suggestion.windows);
                $('#ar-suggestion').prop('hidden', true).empty();
                Notifications.show('Days and times filled from usage. Check them, then Save schedule.', 'info');
            })
            .on('click' + NS, '.ar-remove-override', function() {
                ApiClient.delete(Config.API.automationRules.deleteOverride(s.rule.ruleId, $(this).data('id')), QUIET)
                    .done(() => { if (current()) loadOverrides(); })
                    .fail(error => Notifications.showError(serverMessage(error, 'The override was not removed.')));
            });
    }

    return {
        load,
        model: { daysText, windowText, simpleOf, windowsFromSimple, runHours, hoursText, firstText, holidayChoices,
            holidayValue, parseHoliday, fieldOf, dtoOf, problems }
    };
})();

window.RuleEditor = RuleEditor;
