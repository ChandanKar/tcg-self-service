/**
 * Savings leadership page (E17-T07): what the platform's schedules, leases, idle stops, manual stops
 * and rightsizing actually saved this month, next to what could still be saved and the Cost
 * Efficiency %. Admin only. Each panel loads on its own and shows its own error with Retry.
 */
const Savings = (function() {
    'use strict';

    const TREND_DAYS = 90;
    const CAUSE_COLORS = {
        SCHEDULE: '#2563eb', LEASE: '#7c3aed', IDLE_STOP: '#059669', MANUAL_STOP: '#d97706',
        RIGHTSIZING: '#0891b2', OTHER: '#64748b'
    };

    let pageToken = null;
    let state = null;
    const charts = new Map();

    function isActive() {
        return pageToken !== null && ContentRouter.isCurrent(pageToken);
    }

    function fetchJson(url) {
        return new Promise((resolve, reject) => {
            ApiClient.get(url, { quietServerErrors: true }).done(resolve).fail(reject);
        });
    }

    function money(v) {
        return Utils.formatCurrency(Number(v || 0));
    }

    function currentMonth() {
        const d = new Date();
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

    // ---------------------------------------------------------------- load

    function load() {
        pageToken = ContentRouter.token();
        ContentRouter.onLeave(teardown);
        if (!Auth.isAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3">Access denied. Admin only.</div>');
            return;
        }
        const q = ContentRouter.query ? ContentRouter.query() : {};
        state = { month: /^\d{4}-\d{2}$/.test(q.month || '') ? q.month : currentMonth(), data: {}, errors: {}, sort: {} };
        render();
        fetchAll();
    }

    function teardown() {
        charts.forEach(c => c.dispose());
        charts.clear();
        $(window).off('resize.savingsCharts');
    }

    const REQUESTS = {
        summary: () => `${Config.API.savings.summary}?month=${state.month}`,
        daily: () => `${Config.API.savings.daily}?month=${state.month}`,
        byEnvironment: () => `${Config.API.savings.byEnvironment}?month=${state.month}`,
        byTeam: () => `${Config.API.savings.byTeam}?month=${state.month}`,
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
                    state.errors[k] = true;
                }
            });
            render();
        });
    }

    // ---------------------------------------------------------------- render

    function render() {
        const s = state.data.summary;
        $('#content-area').html(Utils.html`
            <div class="content-view savings-page">
                <div class="content-header d-flex justify-content-between align-items-center flex-wrap gap-2">
                    <div>
                        <h1><i class="fas fa-piggy-bank me-2" aria-hidden="true"></i>Savings</h1>
                        <p class="text-muted mb-0">What the platform saved, what could still be saved, and how efficient the fleet is</p>
                    </div>
                    <div class="d-flex gap-2 flex-wrap align-items-center">
                        <label class="visually-hidden" for="savings-month">Month</label>
                        <select id="savings-month" class="form-select form-select-sm savings-month">${Utils.raw(monthOptions(state.month))}</select>
                        <button type="button" class="btn btn-sm btn-tonal btn-primary" data-action="savings-export">
                            <i class="fas fa-file-excel" aria-hidden="true"></i> Export month</button>
                        <button type="button" class="btn btn-sm btn-ghost" data-action="savings-backfill">
                            <i class="fas fa-history" aria-hidden="true"></i> Backfill 30 days</button>
                    </div>
                </div>
                ${Utils.raw(panel('summary', () => kpis(s), 'the summary'))}
                ${Utils.raw(s && s.fullyCoveredEntries > 0 ? `<div class="alert alert-info savings-note" role="status">
                    <i class="fas fa-info-circle me-1" aria-hidden="true"></i>
                    Some scheduled hours show no compute saving because Savings Plans/RIs already cover them.</div>` : '')}
                <div class="savings-charts">
                    ${Utils.raw(panel('daily', () => `<section class="dashboard-chart-panel"><h2 class="savings-panel-title">Net savings by cause</h2>
                        <div id="savings-cause-chart" class="savings-chart" role="img" aria-label="Net savings by cause per day"></div></section>`, 'daily savings'))}
                    ${Utils.raw(panel('trend', () => `<section class="dashboard-chart-panel"><h2 class="savings-panel-title">Cost Efficiency % (${TREND_DAYS} days)</h2>
                        <div id="savings-trend-chart" class="savings-chart" role="img" aria-label="Cost efficiency trend"></div></section>`, 'the efficiency trend'))}
                </div>
                <div class="savings-tables">
                    ${Utils.raw(panel('byEnvironment', () => table('byEnvironment', 'By environment', state.data.byEnvironment, true), 'savings by environment'))}
                    ${Utils.raw(panel('byTeam', () => table('byTeam', 'By team', state.data.byTeam, false), 'savings by team'))}
                </div>
            </div>`);
        drawCharts();
    }

    function panel(key, build, label) {
        if (state.errors[key]) {
            return Utils.html`<section class="cost-table-card"><div class="cost-panel-error" role="alert">
                <i class="fas fa-triangle-exclamation" aria-hidden="true"></i><span>Couldn't load ${label}.</span>
                <button type="button" class="btn btn-sm btn-ghost" data-action="savings-retry" data-key="${key}">
                    <i class="fas fa-sync" aria-hidden="true"></i> Retry</button></div></section>`;
        }
        if (state.data[key] === undefined) {
            return '<div class="savings-loading text-muted small p-2"><i class="fas fa-spinner fa-spin me-1" aria-hidden="true"></i>Loading…</div>';
        }
        return build();
    }

    function kpi(label, value, icon, subtitle, title) {
        return Utils.html`<div class="dashboard-kpi" title="${title || ''}">
                <i class="fas ${icon}" aria-hidden="true"></i>
                <div><strong>${value}</strong><span>${label}</span><small class="cost-kpi-subtitle">${subtitle}</small></div>
            </div>`;
    }

    function kpis(s) {
        const check = s.actualsCheck || {};
        const quality = check.variancePercent === null || check.variancePercent === undefined
            ? kpi('Estimate quality', '—', 'fa-scale-balanced', 'No actual billing for this month yet')
            : kpi('Estimate quality', `${check.variancePercent > 0 ? '+' : ''}${check.variancePercent}%`, 'fa-scale-balanced',
                `${check.withinTarget ? 'Within' : 'Outside'} the 15% target over ${check.daysWithActuals} day(s)`);
        const efficiency = s.efficiencyPercent === null || s.efficiencyPercent === undefined ? '—' : `${s.efficiencyPercent}%`;
        const share = s.offHoursShare ? ` Unscheduled VMs are assumed off ${Math.round(s.offHoursShare * 100)}% of the time.` : '';
        return `<div class="dashboard-kpi-grid cost-kpi-grid">
                ${kpi('Realised savings (net)', money(s.realisedNet), 'fa-piggy-bank', `${money(s.realisedGross)} before residual costs`)}
                ${kpi('Potential savings', s.potentialSavings === null || s.potentialSavings === undefined ? '—' : money(s.potentialSavings),
                    'fa-lightbulb', 'Largest open opportunity per VM, per month', 'Idle, rightsizing or off-hours, whichever is largest.' + share)}
                ${kpi('Cost Efficiency', efficiency, 'fa-gauge-high', 'AWS median is about 83%',
                    '1 - potential savings / optimisable spend; AWS median 83')}
                ${quality}
            </div>`;
    }

    function table(key, title, rows, linkEnvironments) {
        const sort = state.sort[key] || { by: 'net', dir: -1 };
        const sorted = (rows || []).slice().sort((a, b) => {
            const av = sort.by === 'label' ? String(a.label || '') : Number(a[sort.by] || 0);
            const bv = sort.by === 'label' ? String(b.label || '') : Number(b[sort.by] || 0);
            return (av > bv ? 1 : av < bv ? -1 : 0) * sort.dir;
        });
        const head = (by, label) => Utils.html`<th><button type="button" class="savings-sort" data-action="savings-sort"
            data-table="${key}" data-by="${by}" aria-sort="${sort.by === by ? (sort.dir > 0 ? 'ascending' : 'descending') : 'none'}">${label}</button></th>`;
        const body = sorted.length ? sorted.map(r => Utils.html`<tr>
                <td data-label="${linkEnvironments ? 'Environment' : 'Team'}">${linkEnvironments
                    ? Utils.raw(Utils.html`<a href="#/environments/${r.key}">${r.label || r.key}</a>`) : (r.label || r.key)}</td>
                <td data-label="Net">${money(r.net)}</td>
                <td data-label="Gross">${money(r.gross)}</td>
                <td data-label="Hours">${Number(r.hours || 0).toFixed(1)}</td>
            </tr>`).join('') : '<tr><td colspan="4" class="text-muted text-center py-3">No savings recorded this month</td></tr>';
        return `<section class="cost-table-card">
                <h2 class="savings-panel-title">${Utils.escapeHtml(title)}</h2>
                <div class="table-responsive"><table class="table table-sm table-cards mb-0">
                    <thead><tr>${head('label', linkEnvironments ? 'Environment' : 'Team')}${head('net', 'Net')}${head('gross', 'Gross')}${head('hours', 'Hours')}</tr></thead>
                    <tbody>${body}</tbody></table></div>
            </section>`;
    }

    // ---------------------------------------------------------------- charts

    function chart(id, option) {
        const el = document.getElementById(id);
        if (!el || !window.echarts) return;
        if (charts.has(id)) charts.get(id).dispose();
        const instance = echarts.init(el, null, { renderer: 'svg' });
        instance.setOption(option);
        charts.set(id, instance);
    }

    function drawCharts() {
        charts.forEach(c => c.dispose());
        charts.clear();
        const daily = state.data.daily;
        if (Array.isArray(daily)) {
            const days = [...new Set(daily.map(p => p.date))].sort();
            const causes = [...new Set(daily.map(p => p.cause))];
            chart('savings-cause-chart', {
                tooltip: { trigger: 'axis' },
                legend: { bottom: 0 },
                grid: { left: 50, right: 16, top: 16, bottom: 48 },
                xAxis: { type: 'category', data: days.map(d => d.slice(5)) },
                yAxis: { type: 'value', axisLabel: { formatter: v => '$' + v } },
                series: causes.map(c => ({
                    name: (daily.find(p => p.cause === c) || {}).label || c, type: 'bar', stack: 'net',
                    itemStyle: { color: CAUSE_COLORS[c] || '#64748b' },
                    data: days.map(d => {
                        const p = daily.find(x => x.date === d && x.cause === c);
                        return p ? Number(p.net) : 0;
                    })
                }))
            });
        }
        const trend = state.data.trend;
        if (Array.isArray(trend)) {
            chart('savings-trend-chart', {
                tooltip: { trigger: 'axis' },
                grid: { left: 44, right: 16, top: 16, bottom: 32 },
                xAxis: { type: 'category', data: trend.map(p => String(p.date).slice(5)) },
                yAxis: { type: 'value', min: 0, max: 100, axisLabel: { formatter: v => v + '%' } },
                series: [{ name: 'Cost Efficiency', type: 'line', smooth: true, data: trend.map(p => p.efficiencyPercent),
                    itemStyle: { color: '#059669' } }]
            });
        }
        $(window).off('resize.savingsCharts').on('resize.savingsCharts', () => charts.forEach(c => c.resize()));
    }

    // ---------------------------------------------------------------- actions

    function changeMonth(month) {
        state.month = month;
        state.data = {};
        state.errors = {};
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
        Modals.confirm('Backfill savings', 'Rebuild the savings ledger for the last 30 days? This can take a minute.', () => {
            ApiClient.post(`${Config.API.savings.backfill}?days=30`, {}, { suppressGlobalError: true })
                .done(() => {
                    Notifications.success('Savings ledger rebuilt for the last 30 days.');
                    if (isActive()) { state.data = {}; render(); fetchAll(); }
                })
                .fail(() => Notifications.error('Could not rebuild the savings ledger.'));
        }, { confirmText: 'Backfill', confirmClass: 'btn-primary' });
    }

    function sortBy(table, by) {
        const current = state.sort[table] || { by: 'net', dir: -1 };
        state.sort[table] = { by, dir: current.by === by ? -current.dir : (by === 'label' ? 1 : -1) };
        render();
    }

    $(document).on('change', '#savings-month', function() { changeMonth(this.value); });

    return {
        load,
        exportMonth,
        backfill,
        retry: key => { delete state.errors[key]; delete state.data[key]; render(); fetchAll([key]); },
        sortBy
    };
})();

window.Savings = Savings;

Actions.registerAll({
    'savings-export': () => Savings.exportMonth(),
    'savings-backfill': () => Savings.backfill(),
    'savings-retry': el => Savings.retry(el.dataset.key),
    'savings-sort': el => Savings.sortBy(el.dataset.table, el.dataset.by)
});
