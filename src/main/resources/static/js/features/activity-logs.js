/**
 * VM Self-Service Platform - My activity (U09-T04, MyActivity.dc.html)
 * What you started, stopped, locked and requested, as plain sentences grouped by day: Time, What,
 * Environment and a Done / Failed / Sent chip (a failure shows its reason under the sentence).
 * Period buttons (24 hours, 7 days, 30 days, Dates…), Environment and Action group selects (the
 * group is filtered on the server, so the pages count right), 10 to a page, Export CSV.
 */

const ActivityLogs = (function() {
    'use strict';

    const PAGE_SIZE = 10;
    const EXPORT_PAGE_SIZE = 500;
    const EXPORT_MAX_ROWS = 10000;
    const PERIODS = [['24h', '24 hours'], ['7d', '7 days'], ['30d', '30 days'], ['dates', 'Dates…']];
    const RANGE_HOURS = { '24h': 24, '7d': 24 * 7, '30d': 24 * 30 };
    const CATEGORIES = [['', 'All'], ['OPERATIONS', 'Start and stop'], ['LOCKS', 'Locks'], ['ACCESS', 'Access']];
    const NS = '.myActivity';

    // ---- pure model (node tests: src/test/js/activity-describe.test.mjs) ----

    // The sentences live in core/audit-text.js (AuditText), shared with the audit log (U12-T08).
    const describe = log => AuditText.what(log);
    const plural = (n, one, many) => AuditText.plural(n, one, many);
    const hoursText = minutes => AuditText.hoursText(minutes);

    function sameDay(a, b) {
        return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
    }

    /** "Today", "Yesterday", then "Friday 17 October" (local dates; the year when it is not this one). */
    function dayLabel(date, now) {
        const d = new Date(date);
        const n = now || new Date();
        if (sameDay(d, n)) return 'Today';
        const y = new Date(n.getFullYear(), n.getMonth(), n.getDate() - 1);
        if (sameDay(d, y)) return 'Yesterday';
        const opts = { weekday: 'long', day: 'numeric', month: 'long' };
        if (d.getFullYear() !== n.getFullYear()) opts.year = 'numeric';
        return d.toLocaleDateString('en-GB', opts).replace(',', '');
    }

    function timeText(date) {
        const d = new Date(date);
        return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
    }

    /** Logs (newest first) as rows with a {day} row before each new local day. */
    function rowsWithDays(logs, now) {
        const out = [];
        let last = null;
        (logs || []).forEach(log => {
            const label = log.createdAt ? dayLabel(log.createdAt, now) : '';
            if (label !== last) { out.push({ day: label }); last = label; }
            out.push({ log });
        });
        return out;
    }

    function shortDate(iso) {
        const [y, m, d] = String(iso).split('-').map(Number);
        return new Date(y, m - 1, d).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
    }

    /** "9 actions in the last 7 days", "1 action from 1 Oct to 15 Oct". */
    function countLine(total, period, start, end) {
        const what = plural(total, 'action');
        if (period === 'dates' && start && end) {
            return start === end ? `${what} on ${shortDate(start)}` : `${what} from ${shortDate(start)} to ${shortDate(end)}`;
        }
        const label = (PERIODS.find(p => p[0] === period) || PERIODS[1])[1];
        return `${what} in the last ${label}`;
    }

    // ---- page ----

    const state = {
        period: '7d', customStart: '', customEnd: '', from: '', to: '',
        environmentId: '', category: '', page: 0
    };
    let environments = [];

    function setPresetRange(period) {
        const hours = RANGE_HOURS[period] || RANGE_HOURS['7d'];
        const now = new Date();
        state.from = new Date(now.getTime() - hours * 3600 * 1000).toISOString();
        state.to = now.toISOString();
    }

    /** Local calendar days: from local midnight of start to local midnight after end. */
    function setCustomRange(startDate, endDate) {
        const [sy, sm, sd] = startDate.split('-').map(Number);
        const [ey, em, ed] = endDate.split('-').map(Number);
        state.from = new Date(sy, sm - 1, sd).toISOString();
        state.to = new Date(ey, em - 1, ed + 1).toISOString();
        state.customStart = startDate;
        state.customEnd = endDate;
    }

    function normalizePage(data) {
        if (!data || !data.page) return data || { content: [], totalElements: 0 };
        return { ...data, totalElements: data.page.totalElements ?? 0, number: data.page.number ?? 0 };
    }

    function fetchLogs(page, size) {
        const params = new URLSearchParams({ page, size });
        if (state.from) params.append('from', state.from);
        if (state.to) params.append('to', state.to);
        if (state.environmentId) params.append('environmentId', state.environmentId);
        if (state.category) params.append('category', state.category);
        return new Promise((resolve, reject) => {
            ApiClient.get(`${Config.API.audit.myLogs}?${params.toString()}`, { suppressGlobalError: true })
                .done(data => resolve(normalizePage(data)))
                .fail(xhr => reject(xhr));
        });
    }

    function fetchEnvironments() {
        return new Promise(resolve => {
            ApiClient.get(Config.API.access.myEnvironments, { suppressGlobalError: true })
                .done(data => {
                    const byId = new Map();
                    (Array.isArray(data) ? data : []).forEach(a => {
                        if (a.environmentId && a.environmentName) byId.set(a.environmentId, a.environmentName);
                    });
                    resolve(Array.from(byId, ([id, name]) => ({ id, name }))
                        .sort((x, y) => x.name.localeCompare(y.name)));
                })
                .fail(() => resolve([]));
        });
    }

    function environmentOf(log) {
        if (log.environmentName) return log.environmentName;
        const type = String(log.targetType || '').toLowerCase();
        if (type === 'environment' || type === 'environment_access' || type === 'lock') return log.targetName || '';
        return '';
    }

    function pageMarkup() {
        const periods = PERIODS.map(([key, label]) => Utils.html`<button type="button" class="activity-period"
                data-al-period="${key}" aria-pressed="${state.period === key ? 'true' : 'false'}">${label}</button>`).join('');
        const envOptions = ['<option value="">All</option>'].concat(environments.map(e => Utils.html`<option value="${e.id}"
                ${state.environmentId === e.id ? 'selected' : ''}>${e.name}</option>`)).join('');
        const catOptions = CATEGORIES.map(([key, label]) => Utils.html`<option value="${key}"
                ${state.category === key ? 'selected' : ''}>${label}</option>`).join('');
        const exportBtn = `<button type="button" class="btn btn-outline-secondary activity-export" id="al-export-logs-btn">
                <svg width="15" height="15" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8"
                    stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M10 3v10M5.5 8.5 10 13l4.5-4.5M4 17h12"></path></svg>
                Export CSV</button>`;
        return `<div class="activity-page">
            ${PageHeader.markup({ title: 'My activity', summaryHtml: 'What you started, stopped, locked and requested.',
                actionsHtml: exportBtn, helpPage: 'activity-logs', helpLabel: 'Activity logs' })}
            <div class="activity-filters">
                <div class="activity-periods" role="group" aria-label="Period">${periods}</div>
                <label class="activity-select">Environment
                    <select class="form-select form-select-sm" id="al-environment-filter">${envOptions}</select></label>
                <label class="activity-select">Action
                    <select class="form-select form-select-sm" id="al-action-filter">${catOptions}</select></label>
                <div class="activity-dates" id="al-custom-date-range" ${state.period === 'dates' ? '' : 'hidden'}>
                    <label>From <input type="date" class="form-control form-control-sm" id="al-start-date-input" value="${Utils.escapeHtml(state.customStart)}"></label>
                    <label>To <input type="date" class="form-control form-control-sm" id="al-end-date-input" value="${Utils.escapeHtml(state.customEnd)}"></label>
                    <button type="button" class="btn btn-primary btn-sm" id="al-apply-custom-range-btn">Show</button>
                </div>
            </div>
            <div class="activity-card" id="al-results" aria-live="polite"></div>
        </div>`;
    }

    function rowMarkup(log) {
        const r = describe(log);
        const note = r.note ? Utils.html`<div class="activity-note">${r.note}</div>` : '';
        return Utils.html`<tr class="activity-row">
                <td class="activity-time"><time datetime="${log.createdAt || ''}" title="${log.createdAt ? new Date(log.createdAt).toLocaleString() : ''}">${log.createdAt ? timeText(log.createdAt) : ''}</time></td>
                <td class="activity-what">${r.text}` + note + Utils.html`</td>
                <td class="activity-env">${environmentOf(log) || '—'}</td>
                <td class="activity-result"><span class="activity-chip" data-tone="${r.tone}">${r.result}</span></td>
            </tr>`;
    }

    function resultsMarkup(data) {
        const logs = data.content || [];
        const total = data.totalElements || 0;
        if (!logs.length) {
            const filtered = state.environmentId || state.category;
            const when = countLine(0, state.period, state.customStart, state.customEnd).replace(/^0 actions /, '');
            return filtered
                ? Utils.html`<div class="activity-empty">
                    <p class="activity-empty-title">No actions match these filters</p>
                    <p>Nothing of this kind ${when}.</p>
                    <button type="button" class="btn btn-outline-secondary btn-sm" id="al-clear-filters-btn">Show all actions</button>
                </div>`
                : Utils.html`<div class="activity-empty">
                    <p class="activity-empty-title">Nothing ${when}</p>
                    <p>What you start, stop, lock or request shows up here.</p>
                </div>`;
        }
        const body = rowsWithDays(logs).map(r => r.day !== undefined
            ? Utils.html`<tr class="activity-day"><th colspan="4" scope="rowgroup">${r.day}</th></tr>`
            : rowMarkup(r.log)).join('');
        return `<div class="activity-table-wrap"><table class="activity-table">
                <thead><tr><th scope="col" class="activity-time">Time</th><th scope="col">What</th>
                    <th scope="col">Environment</th><th scope="col" class="activity-result">Result</th></tr></thead>
                <tbody>${body}</tbody></table></div>
            <div class="activity-foot">
                <span class="activity-count" id="al-count">${Utils.escapeHtml(countLine(total, state.period, state.customStart, state.customEnd))}</span>
                <div class="pagination-bar-wrap" id="al-pagination"></div>
            </div>`;
    }

    function errorMarkup() {
        return `<div class="activity-empty" role="alert">
                <p class="activity-empty-title">Your activity didn't load</p>
                <p>The server didn't answer. The filters still work.</p>
                <button type="button" class="btn btn-outline-secondary btn-sm" data-action="activity-retry">Try again</button>
            </div>`;
    }

    function drawResults(t) {
        $('#al-results').attr('aria-busy', 'true');
        return fetchLogs(state.page, PAGE_SIZE).then(data => {
            if (!ContentRouter.isCurrent(t)) return;
            $('#al-results').html(resultsMarkup(data)).attr('aria-busy', 'false');
            if ((data.totalElements || 0) > PAGE_SIZE) {
                Pagination.renderNumbered('#al-pagination', {
                    page: data.number || 0, totalItems: data.totalElements, pageSize: PAGE_SIZE,
                    itemLabel: 'actions', zeroIndexed: true, bindAncestor: '#content-area',
                    onPageChange: p => { state.page = p; drawResults(ContentRouter.token()); }
                });
            }
        }).catch(() => {
            if (!ContentRouter.isCurrent(t)) return;
            $('#al-results').html(errorMarkup()).attr('aria-busy', 'false');
        });
    }

    function reload() {
        state.page = 0;
        return drawResults(ContentRouter.token());
    }

    function bind() {
        $(document).off(NS)
            .on('click' + NS, '#content-area [data-al-period]', function() {
                const key = this.getAttribute('data-al-period');
                state.period = key;
                $('#content-area [data-al-period]').attr('aria-pressed', 'false');
                this.setAttribute('aria-pressed', 'true');
                const $dates = $('#al-custom-date-range');
                if (key === 'dates') {
                    $dates.prop('hidden', false);
                    $('#al-start-date-input').trigger('focus');
                    return;
                }
                $dates.prop('hidden', true);
                setPresetRange(key);
                reload();
            })
            .on('click' + NS, '#al-apply-custom-range-btn', function() {
                const start = $('#al-start-date-input').val();
                const end = $('#al-end-date-input').val();
                if (!start || !end) { Notifications.show('Pick both dates.', 'warning'); return; }
                if (start > end) { Notifications.show('The first date must be on or before the second.', 'warning'); return; }
                setCustomRange(start, end);
                reload();
            })
            .on('change' + NS, '#al-environment-filter', function() { state.environmentId = $(this).val(); reload(); })
            .on('change' + NS, '#al-action-filter', function() { state.category = $(this).val(); reload(); })
            .on('click' + NS, '#al-clear-filters-btn', function() {
                state.environmentId = '';
                state.category = '';
                $('#al-environment-filter, #al-action-filter').val('');
                reload();
            })
            .on('click' + NS, '#al-export-logs-btn', () => exportLogs());
        ContentRouter.onLeave(() => $(document).off(NS));
    }

    /** Router loader (see the page contract in core/router.js). */
    async function loadMyActivityLogs() {
        const t = ContentRouter.token();
        if (state.period !== 'dates') setPresetRange(state.period);
        state.page = 0;
        Loading.showPage('Loading your activity…');
        environments = await fetchEnvironments();
        if (!ContentRouter.isCurrent(t)) return;
        $('#content-area').html(pageMarkup());
        bind();
        await drawResults(t);
    }

    async function exportLogs() {
        const t = ContentRouter.token();
        const fileName = `my-activity-${new Date().toISOString().split('T')[0]}.csv`;
        const headers = ['Time', 'What', 'Environment', 'Result', 'Note', 'Action', 'Target', 'Details'];
        const rows = [];
        try {
            // Page through (500 a page, at most 10,000 rows): the server caps the page size.
            for (let page = 0; rows.length < EXPORT_MAX_ROWS; page++) {
                const data = await fetchLogs(page, EXPORT_PAGE_SIZE);
                const logs = data.content || [];
                logs.forEach(log => {
                    const r = describe(log);
                    rows.push([log.createdAt, r.text, environmentOf(log), r.result, r.note,
                        log.action || '', log.targetName || '', log.details || '']);
                });
                const totalPages = Math.ceil((data.totalElements || 0) / EXPORT_PAGE_SIZE);
                if (page + 1 >= totalPages || logs.length === 0) break;
            }
            if (!ContentRouter.isCurrent(t)) return;
            const list = rows.slice(0, EXPORT_MAX_ROWS);
            Utils.downloadCsv(fileName, headers, list);
            Notifications.show(`Exported ${plural(list.length, 'action')}.`, 'success');
        } catch (error) {
            Notifications.show("Couldn't export your activity. Try again.", 'danger');
        }
    }

    return {
        loadMyActivityLogs,
        reload: () => drawResults(ContentRouter.token()),
        model: { describe, dayLabel, rowsWithDays, countLine, hoursText }
    };
})();

if (typeof window !== 'undefined') window.ActivityLogs = ActivityLogs;

if (typeof Actions !== 'undefined') Actions.register('activity-retry', () => ActivityLogs.reload());
