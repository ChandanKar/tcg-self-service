/**
 * Savings (E17-T07, U15-T06 Savings.dc.html): what stopping and resizing saved in a month after the
 * storage and IPs that keep running, what is still on the table, cost efficiency against the AWS
 * median and how much of it rests on AWS billing; savings by cause as bars, the 90-day efficiency
 * line and savings by environment with its team. Admin only. Each panel loads on its own and shows
 * its own error with Retry.
 */
const Savings = (function() {
    'use strict';

    const TREND_DAYS = 90;
    const PAGE_SIZE = 10;
    const AWS_MEDIAN = 83;
    /** The causes in the board's order, with the names people use. */
    const CAUSES = [['SCHEDULE', 'Schedules'], ['IDLE_STOP', 'Idle auto-stop'], ['LEASE', 'Leases ending'],
        ['MANUAL_STOP', 'Stopped by hand'], ['RIGHTSIZING', 'Rightsizing'], ['AUTOMATION', 'Automation and budgets']];

    let pageToken = null;
    let state = null;

    function isActive() {
        return pageToken !== null && ContentRouter.isCurrent(pageToken);
    }

    function fetchJson(url) {
        return new Promise((resolve, reject) => {
            ApiClient.get(url, { quietServerErrors: true }).done(resolve).fail(reject);
        });
    }

    function money0(v) {
        return '$' + Math.round(Number(v || 0)).toLocaleString('en-US');
    }

    function currentMonth(now) {
        const d = now || new Date();
        return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
    }

    function monthOptions(selected) {
        const d = new Date();
        const out = [];
        for (let i = 0; i < 12; i++) {
            const m = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() - i, 1));
            const value = `${m.getUTCFullYear()}-${String(m.getUTCMonth() + 1).padStart(2, '0')}`;
            const label = m.toLocaleDateString([], { month: 'long', year: 'numeric', timeZone: 'UTC' });
            out.push(`<option value="${value}" ${value === selected ? 'selected' : ''}>${Utils.escapeHtml(label)}</option>`);
        }
        return out.join('');
    }

    // ---------------------------------------------------------------- model (node-tested)

    /**
     * "Based on": how much of the month AWS billing covers. Days with billing over the days of the
     * month so far (the whole month once it is over).
     */
    function basis(check, month, now) {
        const c = check || {};
        const today = now || new Date();
        const [y, m] = String(month).split('-').map(Number);
        const length = new Date(Date.UTC(y, m, 0)).getUTCDate();
        const elapsed = month === currentMonth(today) ? today.getUTCDate() : length;
        const days = Number(c.daysWithActuals || 0);
        const share = elapsed > 0 ? Math.min(100, Math.round(days / elapsed * 100)) : 0;
        if (!days) return { label: 'Estimates', note: 'No AWS billing figures for this month yet', share: 0 };
        return { label: share >= 50 ? 'Mostly AWS billing' : 'Mostly estimates', note: `${share}% billed, the rest estimated`, share };
    }

    /** The causes in order with their net saving and a bar width; Automation only when it saved something. */
    function causeBars(byCause) {
        const net = new Map((byCause || []).map(r => [r.key, Math.max(0, Number(r.net) || 0)]));
        const rows = CAUSES.filter(([key]) => key !== 'AUTOMATION' || net.get(key) > 0)
            .map(([key, label]) => ({ key, label, value: net.get(key) || 0 }));
        const most = Math.max(1, ...rows.map(r => r.value));
        return rows.map(r => ({ ...r, width: Math.round(r.value / most * 1000) / 10 }));
    }

    /** The efficiency line in a 440 × 150 box (0–100%), with the AWS median's height. */
    function efficiencyLine(trend, width = 440, height = 150) {
        const points = (trend || []).filter(p => p.efficiencyPercent !== null && p.efficiencyPercent !== undefined);
        const y = v => Math.round((height - 10 - (Number(v) / 100) * (height - 20)) * 10) / 10;
        const step = points.length > 1 ? width / (points.length - 1) : 0;
        return {
            points: points.map((p, i) => `${Math.round(i * step * 10) / 10},${y(p.efficiencyPercent)}`).join(' '),
            median: y(AWS_MEDIAN),
            first: points.length ? Math.round(Number(points[0].efficiencyPercent)) : null,
            last: points.length ? Math.round(Number(points[points.length - 1].efficiencyPercent)) : null
        };
    }

    // ---------------------------------------------------------------- load

    function load() {
        pageToken = ContentRouter.token();
        if (!Auth.isAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3">Access denied. Admin only.</div>');
            return;
        }
        const q = ContentRouter.query ? ContentRouter.query() : {};
        state = { month: /^\d{4}-\d{2}$/.test(q.month || '') ? q.month : currentMonth(), data: {}, errors: {},
            sort: { by: 'net', dir: -1 }, page: 1 };
        render();
        fetchAll();
    }

    const REQUESTS = {
        summary: () => `${Config.API.savings.summary}?month=${state.month}`,
        byEnvironment: () => `${Config.API.savings.byEnvironment}?month=${state.month}`,
        trend: () => `${Config.API.savings.trend}?days=${TREND_DAYS}`
    };

    function fetchAll(keys) {
        const wanted = keys || Object.keys(REQUESTS);
        const token = pageToken;
        return Promise.allSettled(wanted.map(k => fetchJson(REQUESTS[k]()))).then(results => {
            if (token !== pageToken || !isActive()) return;
            results.forEach((r, i) => {
                const k = wanted[i];
                if (r.status === 'fulfilled') {
                    state.data[k] = r.value;
                    delete state.errors[k];
                } else {
                    state.errors[k] = r.reason || true;
                }
            });
            render();
        });
    }

    // ---------------------------------------------------------------- render

    function render() {
        const s = state.data.summary;
        const dataMenu = typeof Menu !== 'undefined' ? Menu.markup({ id: 'savings-data-menu', label: 'Data actions', items: [
            { text: 'Rebuild the last 30 days', action: 'savings-backfill' }] }) : '';
        $('#content-area').html(`
            <div class="content-view page page-frame savings-page">
                ${PageHeader.markup({ title: 'Savings', sectionNav: PageHeader.costNav('savings'),
                    summaryHtml: 'What stopping and resizing saved, after the storage and IP costs that keep running.',
                    actionsHtml: Utils.html`<div class="savings-toolbar">
                        <label class="visually-hidden" for="savings-month">Month</label>
                        <select id="savings-month" class="form-select savings-month">${Utils.raw(monthOptions(state.month))}</select>
                        <button type="button" class="btn btn-outline-secondary" data-action="savings-export">Export month, Excel</button>
                        ${Utils.raw(dataMenu)}</div>`,
                    helpPage: typeof HelpLink !== 'undefined' ? 'savings' : null, helpLabel: 'Savings' })}
                <div class="savings-body page-fill" data-scroll-label="Savings">
                ${panel('summary', () => tiles(s), 'the summary')}
                ${s && s.fullyCoveredEntries > 0 ? `<p class="cost-note savings-note" role="status">Some scheduled hours show no
                    compute saving because Savings Plans or Reserved Instances already pay for them.</p>` : ''}
                <div class="savings-main">
                    ${panel('byEnvironment', () => byEnvironment(state.data.byEnvironment), 'savings by environment')}
                    <div class="savings-side" data-scroll-label="Saved by and cost efficiency">
                        ${panel('summary', () => causes(s), 'savings by cause')}
                        ${panel('trend', () => efficiency(state.data.trend), 'the efficiency trend')}
                    </div>
                </div>
                </div>
            </div>`);
        // U20-T01: the figures stay; By environment takes the height left with rows fitted to the window,
        // the two cards beside it scroll in their own column when needed (nothing is squeezed).
        const side = document.querySelector('#content-area .savings-side');
        if (side && typeof Modals !== 'undefined' && Modals.watchScroll) Modals.watchScroll(side, side, 'Saved by and cost efficiency');
        attachFit();
    }

    /** Rows a page of By environment follow the window: measured once a visit and when it is resized (U20-T01). */
    function attachFit() {
        const panel = document.querySelector('#content-area .savings-envs');
        if (!panel || typeof ListPanel === 'undefined' || !panel.querySelector('tbody tr')) return;
        const apply = count => {
            if (!count || count === pageSize()) return;
            const first = (state.page - 1) * pageSize();
            state.fit = count;
            state.page = ListPanel.pageKeepingFirst(first, count);
            render();
        };
        const handle = ListPanel.attach(panel, { rowSelector: 'tbody tr', rowHeight: 44, min: 3, max: 15, onFit: apply });
        if (!state.fitted) {
            state.fitted = true;
            apply(handle.fitCount());
        }
    }

    function pageSize() {
        return state.fit || PAGE_SIZE;
    }

    function panel(key, build, label) {
        if (state.errors[key]) {
            return `<section class="cost-card">${UiStates.failed({ what: label, xhr: state.errors[key], retryAction: 'savings-retry', retryData: { key }, className: 'cost-panel-error' })}</section>`;
        }
        if (state.data[key] === undefined) {
            return '<p class="cost-note savings-loading">Loading…</p>';
        }
        return build();
    }

    function tiles(s) {
        const b = basis(s.actualsCheck, state.month);
        const check = s.actualsCheck || {};
        const quality = check.variancePercent === null || check.variancePercent === undefined ? ''
            : `Estimates were ${Math.abs(check.variancePercent)}% ${check.variancePercent > 0 ? 'above' : 'below'} AWS billing over ${check.daysWithActuals} day(s)`;
        const share = s.offHoursShare ? ` VMs without a schedule are counted as off ${Math.round(s.offHoursShare * 100)}% of the time.` : '';
        const tile = (key, label, value, note, title, cls) => Utils.html`<div class="savings-tile" data-kpi="${key}" title="${title || ''}">
                <span class="cost-kpi-label">${label}</span><span class="savings-tile-value ${cls || ''}">${value}</span>
                <span class="cost-kpi-note">${note}</span></div>`;
        return `<section class="savings-tiles" aria-label="${Utils.escapeHtml(monthName(state.month))}">
                ${tile('net', 'Saved, net', money0(s.realisedNet), `${money0(s.realisedGross)} before running storage and IPs`, '', 'cost-mono savings-green')}
                ${tile('table', 'Still on the table', s.potentialSavings == null ? '—' : money0(s.potentialSavings),
                    'a month, each VM counted once', 'Idle, rightsizing or off-hours, whichever is largest per VM.' + share, 'cost-mono')}
                ${tile('efficiency', 'Cost efficiency', s.efficiencyPercent == null ? '—' : `${Math.round(Number(s.efficiencyPercent))}%`,
                    `AWS customers' median is about ${AWS_MEDIAN}%`, '1 − potential savings / optimisable spend', 'cost-mono')}
                ${tile('basis', 'Based on', b.label, b.note, quality, 'savings-tile-words')}
            </section>`;
    }

    function monthName(month) {
        const [y, m] = String(month).split('-').map(Number);
        return new Date(Date.UTC(y, m - 1, 1)).toLocaleDateString([], { month: 'long', year: 'numeric', timeZone: 'UTC' });
    }

    function causes(s) {
        const rows = causeBars(s.byCause);
        return Utils.html`<section class="cost-card savings-causes" aria-labelledby="savings-cause-title">
                <h2 id="savings-cause-title">Saved by</h2>
                ${Utils.raw(rows.map(r => Utils.html`<div class="savings-cause" data-cause="${r.key}"><span>${r.label}</span>
                    <span class="savings-cause-bar"><span style="width:${String(r.width)}%"></span></span>
                    <span class="cost-mono">${money0(r.value)}</span></div>`).join(''))}
                <p class="cost-note savings-cause-note">Counts only stops the platform made. VMs stopped outside it are not counted.</p>
            </section>`;
    }

    function efficiency(trend) {
        const line = efficiencyLine(trend);
        const body = line.points
            ? `<div class="savings-chart-box"><svg id="savings-trend-svg" viewBox="0 0 440 150" role="img" preserveAspectRatio="none"
                    aria-label="Cost efficiency from ${line.first}% to ${line.last}% over ${TREND_DAYS} days">
                    <line x1="0" y1="140" x2="440" y2="140" class="savings-axis"></line>
                    <line x1="0" y1="${line.median}" x2="440" y2="${line.median}" class="savings-median"></line>
                    <polyline points="${line.points}" class="savings-line"></polyline>
                </svg><span class="savings-median-label" style="top: calc(${(line.median / 150 * 100).toFixed(1)}% - 18px)" aria-hidden="true">AWS median ${AWS_MEDIAN}%</span></div>`
            : '<p class="cost-note">No efficiency figures yet; they are taken every night.</p>';
        return `<section class="cost-card savings-efficiency" aria-labelledby="savings-eff-title">
                <h2 id="savings-eff-title">Cost efficiency, ${TREND_DAYS} days</h2>${body}</section>`;
    }

    function byEnvironment(rows) {
        const sort = state.sort;
        const sorted = (rows || []).slice().sort((a, b) => {
            const av = sort.by === 'label' ? String(a.label || '') : Number(a[sort.by] || 0);
            const bv = sort.by === 'label' ? String(b.label || '') : Number(b[sort.by] || 0);
            return (av > bv ? 1 : av < bv ? -1 : 0) * sort.dir;
        });
        const head = (by, label, numeric) => Utils.html`<th scope="col"${Utils.raw(numeric ? ' class="cost-num"' : '')}
            aria-sort="${sort.by === by ? (sort.dir > 0 ? 'ascending' : 'descending') : 'none'}"><button type="button" class="savings-sort"
            data-action="savings-sort" data-by="${by}">${label}</button></th>`;
        const size = pageSize();
        const pages = Math.max(1, Math.ceil(sorted.length / size));
        state.page = Math.min(Math.max(1, state.page), pages);
        const page = Pagination.slice(sorted, state.page, size);
        const body = page.length ? page.map(r => Utils.html`<tr>
                <td><a class="rs-name" href="#/environments/${r.key}">${r.label || r.key}</a></td>
                <td class="savings-team">${r.team || '—'}</td>
                <td class="cost-num cost-mono">${Math.round(Number(r.hours || 0)).toLocaleString('en-US')}</td>
                <td class="cost-num cost-mono">${money0(r.gross)}</td>
                <td class="cost-num cost-mono savings-green">${money0(r.net)}</td>
            </tr>`).join('') : '<tr><td colspan="5" class="text-muted text-center py-3">No savings recorded this month.</td></tr>';
        return `<section class="list-panel rs-table-card savings-envs" aria-labelledby="savings-env-title">
                <div class="list-panel-head"><h2 id="savings-env-title">By environment</h2></div>
                <div class="cost-scroll list-panel-body"><table class="cost-table savings-env-table">
                    <thead><tr>${head('label', 'Environment')}<th scope="col">Team</th>${head('hours', 'Hours off', true)}${head('gross', 'Gross', true)}${head('net', 'Net', true)}</tr></thead>
                    <tbody>${body}</tbody></table></div>
                ${sorted.length > size ? `<div class="list-panel-foot">${Pagination.buildPager({ page: state.page, totalItems: sorted.length, pageSize: size, action: 'savings-page' })}</div>` : ''}
            </section>`;
    }

    // ---------------------------------------------------------------- actions

    function changeMonth(month) {
        state.month = month;
        state.data = {};
        state.errors = {};
        state.page = 1;
        if (window.history && window.history.replaceState) {
            window.history.replaceState(null, '', `#/savings?month=${month}`);
        }
        render();
        fetchAll();
    }

    function exportMonth() {
        fetch(`${Config.API.savings.export}?month=${state.month}`, { credentials: 'same-origin' })
            .then(r => {
                if (!r.ok) throw new Error(String(r.status));
                return r.blob();
            })
            .then(blob => Utils.downloadBlob(blob, `savings-ledger-${state.month}.xlsx`))
            .catch(() => Notifications.error('Could not export the savings ledger.'));
    }

    function backfill() {
        Modals.confirm('Rebuild the last 30 days', 'Rebuild the savings ledger for the last 30 days? This can take a minute.', () => {
            ApiClient.post(`${Config.API.savings.backfill}?days=30`, {}, { suppressGlobalError: true })
                .done(() => {
                    Notifications.success('Savings ledger rebuilt for the last 30 days.');
                    if (isActive()) { state.data = {}; render(); fetchAll(); }
                })
                .fail(() => Notifications.error('Could not rebuild the savings ledger.'));
        }, { confirmText: 'Rebuild', confirmClass: 'btn-primary' });
    }

    function sortBy(by) {
        state.sort = { by, dir: state.sort.by === by ? -state.sort.dir : (by === 'label' ? 1 : -1) };
        state.page = 1;
        render();
    }

    function toPage(page) {
        state.page = Math.max(1, Number(page) || 1);
        render();
    }

    if (typeof $ !== 'undefined') $(document).on('change', '#savings-month', function() { changeMonth(this.value); });

    return {
        load,
        exportMonth,
        backfill,
        retry: key => { delete state.errors[key]; delete state.data[key]; render(); fetchAll([key]); },
        sortBy,
        toPage,
        model: { basis, causeBars, efficiencyLine }
    };
})();

if (typeof window !== 'undefined') window.Savings = Savings;

if (typeof Actions !== 'undefined') Actions.registerAll({
    'savings-export': () => Savings.exportMonth(),
    'savings-backfill': () => Savings.backfill(),
    'savings-retry': el => Savings.retry(el.dataset.key),
    'savings-sort': el => Savings.sortBy(el.dataset.by),
    'savings-page': el => Savings.toPage(el.dataset.page)
});
