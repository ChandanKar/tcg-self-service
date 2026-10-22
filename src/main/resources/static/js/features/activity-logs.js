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
    /** Actions a page on the page frame: as many as fit, at most PAGE_SIZE (U18-T02); null until measured. */
    let fitSize = null;
    let fitPanel = null;
    let fitChecked = false; // measured once a visit (and on resize): day headings vary by page
    const pageSize = () => fitSize || PAGE_SIZE;
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
        return `<div class="activity-page page page-frame">
            ${PageHeader.markup({ title: 'My activity', summaryHtml: 'What you started, stopped, locked and requested.',
                actionsHtml: exportBtn, helpPage: 'activity-logs', helpLabel: 'Activity logs' })}
            <div class="activity-filters">
                <div class="activity-periods" role="group" aria-label="Period">${periods}</div>
                <button type="button" class="btn btn-outline-secondary activity-filters-btn" id="al-filters-btn"
                    aria-expanded="false" aria-controls="al-filter-sheet">${filtersLabel()}</button>
                <div class="activity-filter-sheet" id="al-filter-sheet" role="group" aria-labelledby="al-filter-sheet-title">
                    <div class="activity-sheet-head"><h2 id="al-filter-sheet-title">Filters</h2>
                        <button type="button" class="btn btn-primary btn-sm" id="al-filters-done">Done</button></div>
                    <label class="activity-select">Environment
                        <select class="form-select form-select-sm" id="al-environment-filter">${envOptions}</select></label>
                    <label class="activity-select">Action
                        <select class="form-select form-select-sm" id="al-action-filter">${catOptions}</select></label>
                </div>
                <div class="activity-dates" id="al-custom-date-range" ${state.period === 'dates' ? '' : 'hidden'}>
                    <label>From <input type="date" class="form-control form-control-sm" id="al-start-date-input" value="${Utils.escapeHtml(state.customStart)}"></label>
                    <label>To <input type="date" class="form-control form-control-sm" id="al-end-date-input" value="${Utils.escapeHtml(state.customEnd)}"></label>
                    <button type="button" class="btn btn-primary btn-sm" id="al-apply-custom-range-btn">Show</button>
                </div>
            </div>
            <div class="activity-card list-panel page-fill" id="al-results" aria-live="polite"></div>
        </div>`;
    }

    /** The phone's Filters button names how many filters are on (U16-T04). */
    function filtersLabel() {
        const n = (state.environmentId ? 1 : 0) + (state.category ? 1 : 0);
        return n ? `Filters · ${n}` : 'Filters';
    }

    /** Phone: the Environment and Action filters open as a sheet from the Filters button. */
    function openFilterSheet(open) {
        $('#al-filter-sheet').toggleClass('is-open', open);
        $('#al-filters-btn').attr('aria-expanded', String(open)).text(filtersLabel());
        if (open) $('#al-environment-filter').trigger('focus');
        else $('#al-filters-btn').trigger('focus');
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
            // U17-T01: the shared states; the filtered one names the filters it applies.
            return filtered
                ? UiStates.filtered({ title: 'No actions match these filters', text: `${filterNames()}: nothing of this kind ${when}.`,
                    clearLabel: 'Show all actions', clearAction: 'activity-clear-filters' })
                : UiStates.empty({ title: `Nothing ${when}`, text: 'What you start, stop, lock or request shows up here.' });
        }
        const body = rowsWithDays(logs).map(r => r.day !== undefined
            ? Utils.html`<tr class="activity-day"><th colspan="4" scope="rowgroup">${r.day}</th></tr>`
            : rowMarkup(r.log)).join('');
        return `<div class="activity-table-wrap list-panel-body" tabindex="0" role="region" aria-label="Your actions, scrolls"><table class="activity-table">
                <thead><tr><th scope="col" class="activity-time">Time</th><th scope="col">What</th>
                    <th scope="col">Environment</th><th scope="col" class="activity-result">Result</th></tr></thead>
                <tbody>${body}</tbody></table></div>
            <div class="activity-foot list-panel-foot">
                <span class="activity-count" id="al-count">${Utils.escapeHtml(countLine(total, state.period, state.customStart, state.customEnd))}</span>
                <div class="pagination-bar-wrap" id="al-pagination"></div>
            </div>`;
    }

    /** "Environment Payments Dev · Start and stop", the filters in force. */
    function filterNames() {
        const env = environments.find(e => e.id === state.environmentId);
        const cat = CATEGORIES.find(c => c[0] === state.category);
        return [env ? `Environment ${env.name}` : '', state.category && cat ? cat[1] : ''].filter(Boolean).join(' · ');
    }

    /**
     * Rows a page follow the window (U18-T02): measured once the first page shows and again when a resize
     * settles (day headings on the page count as space taken); a new count reloads the page that keeps the
     * first action in view.
     */
    function attachFit() {
        const panel = document.getElementById('al-results');
        if (!panel || !panel.querySelector('.list-panel-body') || typeof ListPanel === 'undefined') return;
        if (fitPanel) fitPanel.destroy();
        const apply = count => {
            if (!count || count === pageSize()) return;
            const first = state.page * pageSize();
            fitSize = count;
            state.page = ListPanel.pageKeepingFirst(first, count) - 1;
            drawResults(ContentRouter.token());
        };
        fitPanel = ListPanel.attach(panel, { rowSelector: 'tbody tr:not(.activity-day)', rowHeight: 41, min: 4, max: PAGE_SIZE, onFit: apply });
        if (!fitChecked) {
            fitChecked = true;
            apply(fitPanel.fitCount());
        }
    }

    function errorMarkup(xhr) {
        return UiStates.failed({ what: 'Your activity', xhr, retryAction: 'activity-retry' });
    }

    function drawResults(t) {
        $('#al-results').attr('aria-busy', 'true');
        return fetchLogs(state.page, pageSize()).then(data => {
            if (!ContentRouter.isCurrent(t)) return;
            $('#al-results').html(resultsMarkup(data)).attr('aria-busy', 'false');
            attachFit();
            if ((data.totalElements || 0) > pageSize()) {
                Pagination.renderNumbered('#al-pagination', {
                    page: data.number || 0, totalItems: data.totalElements, pageSize: pageSize(),
                    itemLabel: 'actions', zeroIndexed: true, bindAncestor: '#content-area',
                    onPageChange: p => { state.page = p; drawResults(ContentRouter.token()); }
                });
            }
        }).catch(xhr => {
            if (!ContentRouter.isCurrent(t)) return;
            $('#al-results').html(errorMarkup(xhr)).attr('aria-busy', 'false');
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
            .on('change' + NS, '#al-environment-filter', function() { state.environmentId = $(this).val(); $('#al-filters-btn').text(filtersLabel()); reload(); })
            .on('change' + NS, '#al-action-filter', function() { state.category = $(this).val(); $('#al-filters-btn').text(filtersLabel()); reload(); })
            .on('click' + NS, '#al-filters-btn', () => openFilterSheet(true))
            .on('click' + NS, '#al-filters-done', () => openFilterSheet(false))
            .on('keydown' + NS, '#al-filter-sheet.is-open', e => { if (e.key === 'Escape') openFilterSheet(false); })
            .on('click' + NS, '#content-area [data-action="activity-clear-filters"]', function() {
                state.environmentId = '';
                state.category = '';
                $('#al-environment-filter, #al-action-filter').val('');
                $('#al-filters-btn').text(filtersLabel());
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
        fitChecked = false;
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
