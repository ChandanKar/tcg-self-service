/**
 * VM Self-Service Platform - Audit log (E11-T05, U12-T08 AuditLog.dc.html)
 * Everything anyone did in the environments the person looks after, as sentences: When, Who
 * ('Schedule "Nightly batch"' for a rule's run), What (AuditText; a failure's reason as a note),
 * Environment and a Done / Failed chip; 10 a page. The counts ("230 actions in 7 days", "99%
 * succeeded", "2 failed", "busiest: CRM UAT") come from /audit/logs/stats for the same filters:
 * period (exact instants; Dates… for local days), person, environment, action category (GET
 * /audit/categories) and result, in one compact row (U20-T04). Export CSV pages through the filtered
 * rows and neutralises formulas; a preset period is re-read at the click, the list refreshed to match,
 * and the file named after the filters.
 */

const AllLogs = (function() {
    'use strict';

    const PAGE_SIZE = 10;
    /** Entries a page on the page frame: as many as fit, at most PAGE_SIZE (U18-T03); measured once a visit. */
    let fitSize = null;
    let fitPanel = null;
    let fitChecked = false;
    const pageSize = () => fitSize || PAGE_SIZE;
    const EXPORT_PAGE_SIZE = 500;
    const EXPORT_MAX_ROWS = 10000;
    const RANGE_HOURS = { '24h': 24, '7d': 24 * 7, '30d': 24 * 30 };
    const PERIODS = [['7d', 'Last 7 days'], ['24h', 'Last 24 hours'], ['30d', 'Last 30 days'], ['custom', 'Dates…']];
    const PERIOD_WORDS = { '24h': '24 hours', '7d': '7 days', '30d': '30 days' };
    const NS = '.auditLog';

    // ---- pure model (node tests: src/test/js/audit-log.test.mjs) ----

    function plural(n, one, many) {
        return `${Number(n).toLocaleString('en-US')} ${n === 1 ? one : (many || one + 's')}`;
    }

    function shortDate(iso) {
        const [y, m, d] = String(iso).split('-').map(Number);
        return new Date(y, m - 1, d).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
    }

    /** The counts line: "230 actions in 7 days", "99% succeeded", "2 failed", "busiest: CRM UAT". */
    function statsParts(stats, filters) {
        const total = Number(stats.total || 0);
        const span = filters.timeRange === 'custom' && filters.customStart && filters.customEnd
            ? (filters.customStart === filters.customEnd ? `on ${shortDate(filters.customStart)}`
                : `from ${shortDate(filters.customStart)} to ${shortDate(filters.customEnd)}`)
            : `in ${PERIOD_WORDS[filters.timeRange] || '7 days'}`;
        const parts = [{ value: total.toLocaleString('en-US'), label: `${total === 1 ? 'action' : 'actions'} ${span}` }];
        if (total) {
            parts.push({ value: `${stats.successRate ?? 0}%`, label: 'succeeded' });
            parts.push({ value: Number(stats.failures || 0).toLocaleString('en-US'), label: 'failed' });
            if (stats.topEnvironment && stats.topEnvironment.name) parts.push({ prefix: 'busiest: ', value: stats.topEnvironment.name, label: '' });
        }
        return parts;
    }

    function sameDay(a, b) {
        return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
    }

    /** "Today 08:02", "Yesterday 19:00", "Fri 19:00" within a week, else "12 Oct 19:00" (the year when not this one). */
    function whenText(at, now) {
        const d = new Date(at);
        const n = now || new Date();
        const time = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
        if (sameDay(d, n)) return `Today ${time}`;
        if (sameDay(d, new Date(n.getFullYear(), n.getMonth(), n.getDate() - 1))) return `Yesterday ${time}`;
        const startOfToday = new Date(n.getFullYear(), n.getMonth(), n.getDate()).getTime();
        if (startOfToday - d.getTime() < 6 * 86400000) return `${d.toLocaleDateString('en-GB', { weekday: 'short' })} ${time}`;
        const opts = { day: 'numeric', month: 'short' };
        if (d.getFullYear() !== n.getFullYear()) opts.year = 'numeric';
        return `${d.toLocaleDateString('en-GB', opts)} ${time}`;
    }

    function environmentOf(log) {
        if (log.environmentName || log.environmentId) return log.environmentName || log.environmentId;
        const type = String(log.targetType || '').toLowerCase();
        if (type === 'environment' || type === 'environment_access' || type === 'lock') return log.targetName || '';
        return '';
    }

    /** Query params shared by the list, the counts and the export. */
    function filterParams(filters) {
        const params = new URLSearchParams();
        if (filters.from) params.append('from', filters.from);
        if (filters.to) params.append('to', filters.to);
        if (filters.userId) params.append('userId', filters.userId);
        if (filters.environmentId) params.append('environmentId', filters.environmentId);
        if (filters.category) params.append('category', filters.category);
        if (filters.resultStatus === 'false') params.append('success', 'false');
        return params;
    }

    /** The filters in words, for the export's file name and message: ["Last 7 days", "Ana Ruiz", "Failed only"]. */
    function filterWords(f, names = {}) {
        const words = [f.timeRange === 'custom' && f.customStart && f.customEnd
            ? (f.customStart === f.customEnd ? f.customStart : `${f.customStart} to ${f.customEnd}`)
            : (PERIODS.find(([k]) => k === f.timeRange) || PERIODS[0])[1]];
        if (f.userId) words.push(names.person || 'One person');
        if (f.environmentId) words.push(names.environment || 'One environment');
        if (f.category) words.push(names.action || f.category);
        if (f.resultStatus === 'false') words.push('Failed only');
        return words;
    }

    /** "audit-log-2026-10-23-last-7-days-crm-uat-failed-only.csv" */
    function exportFileName(words, today) {
        const slug = words.join(' ').toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 80)
            .replace(/-+$/, '');
        return `audit-log-${today}${slug ? '-' + slug : ''}.csv`;
    }

    // ---- page ----

    const filters = {
        from: '', to: '', customStart: '', customEnd: '', timeRange: '7d',
        userId: '', userLabel: '', environmentId: '', category: '', resultStatus: '', page: 0
    };
    let users = [];          // ADMIN: full list; ENV_ADMIN: search results
    let environments = [];
    let categories = [];
    let stats = emptyStats();
    let token = null;

    function emptyStats() {
        return { total: 0, failures: 0, successRate: 0, topUser: null, topEnvironment: null };
    }

    function isAdmin() {
        return typeof Auth !== 'undefined' && Auth.isAdmin && Auth.isAdmin();
    }

    function current() {
        return token !== null && ContentRouter.isCurrent(token);
    }

    /** An exact range ending now. */
    function setPresetRange(range) {
        const hours = RANGE_HOURS[range] || RANGE_HOURS['7d'];
        const now = new Date();
        filters.from = new Date(now.getTime() - hours * 3600 * 1000).toISOString();
        filters.to = now.toISOString();
    }

    /** Local calendar days: from local midnight of start to local midnight after end. */
    function setCustomRange(startDate, endDate) {
        const [sy, sm, sd] = startDate.split('-').map(Number);
        const [ey, em, ed] = endDate.split('-').map(Number);
        filters.from = new Date(sy, sm - 1, sd).toISOString();
        filters.to = new Date(ey, em - 1, ed + 1).toISOString();
        filters.customStart = startDate;
        filters.customEnd = endDate;
    }

    function normalizePage(data) {
        if (!data || !data.page) return data || { content: [], totalElements: 0, number: 0 };
        return { ...data, totalElements: data.page.totalElements ?? 0, totalPages: data.page.totalPages ?? 0, number: data.page.number ?? 0 };
    }

    function get(url, quiet) {
        return new Promise((resolve, reject) => {
            ApiClient.get(url, quiet ? { suppressGlobalError: true } : undefined).done(resolve).fail(reject);
        });
    }

    function fetchLogs(page, size) {
        const params = filterParams(filters);
        params.append('page', page);
        params.append('size', size);
        return get(`${Config.API.audit.logs}?${params.toString()}`, true).then(normalizePage).catch(xhr => {
            if (xhr && xhr.status === 404) return { content: [], totalElements: 0, number: 0 };
            throw xhr;
        });
    }

    function fetchStats() {
        return get(`${Config.API.audit.stats}?${filterParams(filters).toString()}`, true).catch(() => emptyStats());
    }

    /** Router loader (see the page contract in core/router.js). */
    async function loadAllAuditLogs() {
        token = ContentRouter.token();
        if (!Auth.isEnvAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3">The audit log is for administrators and environment admins.</div>');
            return;
        }
        if (filters.timeRange !== 'custom') setPresetRange(filters.timeRange);
        filters.page = 0;
        Loading.showPage('Loading the audit log…');
        const t = token;
        const [userList, envList, categoryList] = await Promise.all([
            isAdmin() ? get(Config.API.users.list, true).catch(() => []) : Promise.resolve([]),
            get(Config.API.environments.list, true).catch(() => []),
            get(Config.API.audit.categories, true).catch(() => [])
        ]);
        if (!ContentRouter.isCurrent(t)) return;
        users = (Array.isArray(userList) ? userList : []).map(toUserOption);
        environments = (Array.isArray(envList) ? envList : [])
            .filter(e => isAdmin() || e.viewerCanAdminister !== false)
            .map(e => ({ id: e.environmentId || e.id, name: e.displayName || e.name }));
        categories = Array.isArray(categoryList) ? categoryList : [];
        $('#content-area').html(pageMarkup());
        bind();
        await reload(false);
    }

    function toUserOption(u) {
        return { id: u.userId || u.id, email: u.email, name: u.displayName || u.email };
    }

    function pageMarkup() {
        const exportBtn = `<button type="button" class="btn btn-outline-secondary activity-export" id="export-logs-btn">
                <svg width="15" height="15" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8"
                    stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M10 3v10M5.5 8.5 10 13l4.5-4.5M4 17h12"></path></svg>
                Export CSV</button>`;
        const periods = PERIODS.map(([k, l]) => `<option value="${k}"${filters.timeRange === k ? ' selected' : ''}>${l}</option>`).join('');
        const person = isAdmin()
            ? `<select class="form-select form-select-sm" id="user-filter" aria-label="Person"><option value="">Anyone</option>${users.map(u =>
                Utils.html`<option value="${u.id}"${Utils.raw(filters.userId === u.id ? ' selected' : '')}>${u.name}</option>`).join('')}</select>`
            // Environment admins: type-ahead over /users/search (the user list is for administrators).
            : Utils.html`<input type="search" class="form-control form-control-sm" id="user-search-input" list="audit-user-options"
                   placeholder="Anyone (type a name)" aria-label="Person" value="${filters.userLabel}">
                <datalist id="audit-user-options"></datalist>`;
        const envs = `<select class="form-select form-select-sm" id="environment-filter" aria-label="Environment"><option value="">All environments</option>${
            environments.map(e => Utils.html`<option value="${e.id}"${Utils.raw(filters.environmentId === e.id ? ' selected' : '')}>${e.name}</option>`).join('')}</select>`;
        const cats = `<select class="form-select form-select-sm" id="category-filter" aria-label="Action"><option value="">All actions</option>${
            categories.map(c => Utils.html`<option value="${c.category}"${Utils.raw(filters.category === c.category ? ' selected' : '')}>${c.label}</option>`).join('')}</select>`;
        const result = `<select class="form-select form-select-sm" id="result-filter" aria-label="Result">
                <option value="">Any result</option><option value="false"${filters.resultStatus === 'false' ? ' selected' : ''}>Failed only</option></select>`;
        fitChecked = false;
        return `<div class="activity-page page page-frame audit-page all-logs-container" id="audit-log-view">
            ${PageHeader.markup({ title: 'Audit log', summaryHtml: 'Everything anyone did in the environments you look after.',
                actionsHtml: exportBtn, helpPage: typeof HelpLink !== 'undefined' ? 'audit-logs-all' : null, helpLabel: 'Audit logs' })}
            <div class="audit-stats" id="all-logs-stats" aria-live="polite"></div>
            <div class="audit-filters">
                <select class="form-select form-select-sm" id="time-range-filter" aria-label="Period">${periods}</select>
                ${person}${envs}${cats}${result}
                <div class="activity-dates" id="custom-date-range" ${filters.timeRange === 'custom' ? '' : 'hidden'}>
                    <label>From <input type="date" class="form-control form-control-sm" id="start-date-input" value="${Utils.escapeHtml(filters.customStart)}"></label>
                    <label>To <input type="date" class="form-control form-control-sm" id="end-date-input" value="${Utils.escapeHtml(filters.customEnd)}"></label>
                    <button type="button" class="btn btn-primary btn-sm" id="apply-custom-range-btn">Show</button>
                </div>
            </div>
            <div class="activity-card list-panel page-fill" id="audit-results" aria-live="polite"></div>
        </div>`;
    }

    function statsMarkup() {
        return statsParts(stats, filters).map(p => Utils.html`<span>${p.prefix || ''}<b>${p.value}</b>${p.label ? ` ${p.label}` : ''}</span>`).join('');
    }

    function rowMarkup(log, now) {
        const r = AuditText.what(log);
        const note = r.note ? Utils.html`<div class="activity-note">${r.note}</div>` : '';
        const tone = r.tone === 'wait' ? 'ok' : r.tone;
        const result = r.result === 'Sent' ? 'Done' : r.result;
        return Utils.html`<tr class="activity-row">
                <td class="audit-when"><time datetime="${log.createdAt || ''}" title="${log.createdAt ? new Date(log.createdAt).toLocaleString() : ''}">${log.createdAt ? whenText(log.createdAt, now) : ''}</time></td>
                <td class="audit-who">${AuditText.who(log)}</td>
                <td class="activity-what">${r.text}` + note + Utils.html`</td>
                <td class="activity-env">${environmentOf(log) || '—'}</td>
                <td class="activity-result"><span class="activity-chip" data-tone="${tone}">${result}</span></td>
            </tr>`;
    }

    function resultsMarkup(data) {
        const logs = data.content || [];
        const total = data.totalElements || 0;
        const now = new Date();
        const filtered = filters.userId || filters.environmentId || filters.category || filters.resultStatus;
        const body = logs.length ? logs.map(log => rowMarkup(log, now)).join('')
            : `<tr class="audit-empty-row"><td colspan="5">${filtered
                ? UiStates.filtered({ title: 'Nothing matches these filters', text: 'Nothing of this kind in this period.', clearLabel: 'Show everything', clearId: 'clear-filters-btn' })
                : UiStates.empty({ title: 'Nothing in this period', text: 'What people do in the environments you look after shows up here.' })}</td></tr>`;
        return `<div class="activity-table-wrap list-panel-body" tabindex="0" role="region" aria-label="Audit entries, scrolls"><table class="activity-table audit-table" id="audit-table">
                <thead><tr><th scope="col" class="audit-when">When</th><th scope="col">Who</th><th scope="col">What</th>
                    <th scope="col">Environment</th><th scope="col" class="activity-result">Result</th></tr></thead>
                <tbody>${body}</tbody></table></div>
            <div id="all-logs-pagination" class="list-panel-foot">${total > 0 ? Pagination.buildPager({ page: (data.number || 0) + 1, pageSize: pageSize(),
                totalItems: total, action: 'audit-page', key: 'audit' }) : ''}</div>`;
    }

    /** Rows a page follow the window (U18-T03): measured once a visit and when a resize settles. */
    function attachFit() {
        const panel = document.getElementById('audit-results');
        if (!panel || !panel.querySelector('.list-panel-body') || typeof ListPanel === 'undefined') return;
        if (fitPanel) fitPanel.destroy();
        const apply = count => {
            if (!count || count === pageSize()) return;
            const first = filters.page * pageSize();
            fitSize = count;
            filters.page = ListPanel.pageKeepingFirst(first, count) - 1;
            reload(true);
        };
        fitPanel = ListPanel.attach(panel, { rowSelector: 'tbody tr:not(.activity-day)', rowHeight: 41, min: 4, max: PAGE_SIZE, onFit: apply });
        if (!fitChecked) {
            fitChecked = true;
            apply(fitPanel.fitCount());
        }
    }

    /** Reloads the rows (and the counts unless only the page changed). */
    async function reload(pageOnly) {
        const t = token;
        $('#audit-results').attr('aria-busy', 'true');
        try {
            const [data, s] = await Promise.all([fetchLogs(filters.page, pageSize()), pageOnly ? Promise.resolve(stats) : fetchStats()]);
            if (!ContentRouter.isCurrent(t)) return;
            stats = s || emptyStats();
            $('#all-logs-stats').html(statsMarkup());
            $('#audit-results').html(resultsMarkup(data)).attr('aria-busy', 'false');
            attachFit();
        } catch (error) {
            if (!ContentRouter.isCurrent(t)) return;
            $('#audit-results').html(UiStates.failed({ what: 'The audit log', xhr: error, retryAction: 'all-logs-retry' }))
                .attr('aria-busy', 'false');
        }
    }

    function fromFirstPage() {
        filters.page = 0;
        reload(false);
    }

    function bind() {
        const searchUsers = Utils.debounce(query => {
            ApiClient.get(Config.API.users.search(query), { suppressGlobalError: true }).done(list => {
                users = (Array.isArray(list) ? list : []).map(toUserOption);
                $('#audit-user-options').html(users.map(u => Utils.html`<option value="${u.name} <${u.email}>"></option>`).join(''));
            });
        }, 300);
        $(document).off(NS)
            .on('change' + NS, '#time-range-filter', function() {
                filters.timeRange = this.value;
                const custom = this.value === 'custom';
                $('#custom-date-range').prop('hidden', !custom);
                if (custom) { $('#start-date-input').trigger('focus'); return; }
                setPresetRange(this.value);
                fromFirstPage();
            })
            .on('click' + NS, '#apply-custom-range-btn', () => {
                const start = $('#start-date-input').val();
                const end = $('#end-date-input').val();
                if (!start || !end) { Notifications.show('Pick both dates.', 'warning'); return; }
                if (start > end) { Notifications.show('The first date must be on or before the second.', 'warning'); return; }
                setCustomRange(start, end);
                fromFirstPage();
            })
            .on('change' + NS, '#user-filter', function() { filters.userId = this.value; fromFirstPage(); })
            .on('input' + NS, '#user-search-input', function() {
                const value = this.value.trim();
                const match = users.find(u => `${u.name} <${u.email}>` === value);
                if (match) {
                    filters.userId = match.id;
                    filters.userLabel = value;
                    fromFirstPage();
                } else if (!value && filters.userId) {
                    filters.userId = '';
                    filters.userLabel = '';
                    fromFirstPage();
                } else if (value.length >= 2) {
                    searchUsers(value);
                }
            })
            .on('change' + NS, '#environment-filter', function() { filters.environmentId = this.value; fromFirstPage(); })
            .on('change' + NS, '#category-filter', function() { filters.category = this.value; fromFirstPage(); })
            .on('change' + NS, '#result-filter', function() { filters.resultStatus = this.value; fromFirstPage(); })
            .on('click' + NS, '#clear-filters-btn', () => {
                Object.assign(filters, { userId: '', userLabel: '', environmentId: '', category: '', resultStatus: '' });
                $('#user-filter, #environment-filter, #category-filter, #result-filter, #user-search-input').val('');
                fromFirstPage();
            })
            .on('click' + NS, '#audit-log-view [data-action="audit-page"]', function() {
                filters.page = (Number(this.dataset.page) || 1) - 1;
                reload(true);
            })
            .on('click' + NS, '#export-logs-btn', exportAuditLogs);
        ContentRouter.onLeave(() => $(document).off(NS));
    }

    /** The chosen person, environment and action by name, for the export. */
    function chosenNames() {
        const person = filters.userId ? (users.find(u => u.id === filters.userId) || {}).name
            || String(filters.userLabel || '').replace(/\s*<[^>]*>\s*$/, '') : '';
        const environment = filters.environmentId ? (environments.find(e => e.id === filters.environmentId) || {}).name : '';
        const action = filters.category ? (categories.find(c => c.category === filters.category) || {}).label : '';
        return { person, environment, action };
    }

    /**
     * Page through the filtered rows (500 at a time, at most 10,000) and download a CSV. A preset period
     * ("Last 7 days") ends now: it is re-read at the click and the list refreshed, so the file holds
     * what the filters select now and the screen shows the same (U20-T04).
     */
    async function exportAuditLogs() {
        const t = token;
        if (filters.timeRange !== 'custom') {
            setPresetRange(filters.timeRange);
            filters.page = 0;
            await reload(false);
            if (!ContentRouter.isCurrent(t)) return;
        }
        const words = filterWords(filters, chosenNames());
        const now = new Date();
        const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
        const fileName = exportFileName(words, today);
        const headers = ['When', 'Who', 'What', 'Environment', 'Result', 'Note', 'Action', 'Target', 'Details'];
        const rows = [];
        try {
            for (let page = 0; rows.length < EXPORT_MAX_ROWS; page++) {
                const data = await fetchLogs(page, EXPORT_PAGE_SIZE);
                const logs = data.content || [];
                logs.forEach(log => {
                    const r = AuditText.what(log);
                    rows.push([log.createdAt, AuditText.who(log), r.text, environmentOf(log), r.result === 'Sent' ? 'Done' : r.result,
                        r.note, log.action || '', log.targetName || '', log.details || log.errorMessage || '']);
                });
                const totalPages = data.totalPages || Math.ceil((data.totalElements || 0) / EXPORT_PAGE_SIZE);
                if (page + 1 >= totalPages || logs.length === 0) break;
            }
            if (!ContentRouter.isCurrent(t)) return;
            const list = rows.slice(0, EXPORT_MAX_ROWS);
            Utils.downloadCsv(fileName, headers, list);
            Notifications.show(`Exported ${plural(list.length, 'action')}: ${words.join(' · ')}.`, 'success');
        } catch (error) {
            Notifications.show("Couldn't export the audit log. Try again.", 'danger');
        }
    }

    return {
        loadAllAuditLogs,
        reload: () => reload(false),
        model: { statsParts, whenText, environmentOf, filterParams, filterWords, exportFileName }
    };
})();

if (typeof window !== 'undefined') window.AllLogs = AllLogs;

if (typeof Actions !== 'undefined') Actions.register('all-logs-retry', () => AllLogs.reload());
