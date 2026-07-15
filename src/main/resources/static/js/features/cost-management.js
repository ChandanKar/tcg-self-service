/**
 * Cost Management Feature Module (Admin only)
 * Estimated fleet spend: KPI summary, spend breakdowns, idle waste, rightsizing
 * candidates, per-VM cost detail, and a spend trend — backed by /api/v1/cost-management/**.
 */
const CostManagement = (function() {
    'use strict';

    const PAGE_SIZE = 10;
    const TREND_DAYS = 90;
    const EXPORT_SIZE = 100000;
    const TOP_N_SLICES = 8;

    // Fixed categorical order — never cycled/reassigned per filter. Last color is reserved for
    // the "Other" residual bucket so it always reads as neutral rather than competing for attention.
    const CATEGORICAL_COLORS = ['#2563eb', '#059669', '#d97706', '#7c3aed', '#0891b2', '#db2777', '#65a30d', '#0d9488', '#94a3b8'];

    let chartRegistry = new Map();
    let exporting = false;

    let state = {
        summary: null,
        spendByEnvironment: [],
        spendByVmType: [],
        teamTrend: [],
        trend: [],
        idle: emptyPageState(),
        rightsizing: emptyPageState(),
        detail: emptyPageState()
    };

    function emptyPageState() {
        return { content: [], totalPages: 0, totalElements: 0, page: 0 };
    }

    function load() {
        if (!Auth.isAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3">Access denied. Admin only.</div>');
            return;
        }
        restorePageStateFromHash();
        showLoading();
        fetchAll();
    }

    // ---- hash-based sub-context (per-table page survives refresh/bookmark) ----

    function restorePageStateFromHash() {
        const query = (window.location.hash.split('?')[1]) || '';
        const params = Utils.parseQueryString(query);
        state.idle.page = parsePageParam(params.idle);
        state.rightsizing.page = parsePageParam(params.rightsizing);
        state.detail.page = parsePageParam(params.detail);
    }

    function parsePageParam(value) {
        const n = parseInt(value, 10);
        return Number.isFinite(n) && n >= 0 ? n : 0;
    }

    function syncHash() {
        const params = new URLSearchParams();
        if (state.idle.page) params.set('idle', state.idle.page);
        if (state.rightsizing.page) params.set('rightsizing', state.rightsizing.page);
        if (state.detail.page) params.set('detail', state.detail.page);
        const query = params.toString();
        const hash = '#/cost-management' + (query ? `?${query}` : '');
        if (window.location.hash !== hash) {
            history.replaceState(null, '', hash);
        }
    }

    // ---- data loading ----

    function fetchJson(url) {
        return new Promise((resolve, reject) => {
            ApiClient.get(url).done(resolve).fail(reject);
        });
    }

    function mapPage(pageData) {
        // Spring Data serializes Page<T> as { content: [...], page: { size, number, totalElements, totalPages } }
        const meta = (pageData && pageData.page) || {};
        return {
            content: (pageData && pageData.content) || [],
            totalPages: meta.totalPages || 0,
            totalElements: meta.totalElements || 0,
            page: meta.number || 0
        };
    }

    function fetchAll() {
        Promise.all([
            fetchJson(Config.API.costManagement.summary),
            fetchJson(Config.API.costManagement.spendByEnvironment),
            fetchJson(Config.API.costManagement.spendByVmType),
            fetchJson(Config.API.costManagement.spendTrendByTeam(TREND_DAYS)),
            fetchJson(Config.API.costManagement.spendTrend(TREND_DAYS)),
            fetchJson(Config.API.costManagement.idleWaste(state.idle.page, PAGE_SIZE)),
            fetchJson(Config.API.costManagement.rightsizing(state.rightsizing.page, PAGE_SIZE)),
            fetchJson(Config.API.costManagement.vmDetail(state.detail.page, PAGE_SIZE))
        ]).then(([summary, byEnv, byType, teamTrend, trend, idle, rightsizing, detail]) => {
            state.summary = summary;
            state.spendByEnvironment = byEnv || [];
            state.spendByVmType = byType || [];
            state.teamTrend = teamTrend || [];
            state.trend = trend || [];
            state.idle = mapPage(idle);
            state.rightsizing = mapPage(rightsizing);
            state.detail = mapPage(detail);
            render();
        }).catch(error => {
            console.error('Failed to load cost management data:', error);
            showError('Failed to load cost data. Please try again.');
        });
    }

    const TABLE_CONFIG = {
        idle: { url: (p) => Config.API.costManagement.idleWaste(p, PAGE_SIZE), render: () => renderIdleTable() },
        rightsizing: { url: (p) => Config.API.costManagement.rightsizing(p, PAGE_SIZE), render: () => renderRightsizingTable() },
        detail: { url: (p) => Config.API.costManagement.vmDetail(p, PAGE_SIZE), render: () => renderDetailTable() }
    };

    function changePage(tableKey, page) {
        if (page < 0) return;
        const cfg = TABLE_CONFIG[tableKey];
        if (!cfg) return;
        fetchJson(cfg.url(page)).then(pageData => {
            state[tableKey] = mapPage(pageData);
            syncHash();
            cfg.render();
        }).catch(error => {
            console.error(`Failed to load ${tableKey} page:`, error);
            Notifications.error('Failed to load page. Please try again.');
        });
    }

    // ---- render ----

    function showLoading() {
        $('#content-area').html(`
            <div class="content-view" id="cost-management-view">
                <div class="content-header">
                    <h1>Cost Management</h1>
                    <p>Loading estimated spend&hellip;</p>
                </div>
                <div class="loading-state">
                    <div class="spinner-border text-primary" role="status">
                        <span class="visually-hidden">Loading...</span>
                    </div>
                    <p>Loading cost data...</p>
                </div>
            </div>
        `);
    }

    function showError(message) {
        disposeCharts();
        $('#content-area').html(`
            <div class="content-view" id="cost-management-view">
                <div class="content-header">
                    <h1>Cost Management</h1>
                    <p>Estimated spend across the fleet</p>
                </div>
                <div class="error-state">
                    <i class="fas fa-exclamation-triangle fa-3x text-warning"></i>
                    <h4 class="mt-3">Unable to Load Cost Data</h4>
                    <p>${Utils.escapeHtml(message)}</p>
                    <button class="btn btn-primary" onclick="CostManagement.load()">
                        <i class="fas fa-sync"></i> Try Again
                    </button>
                </div>
            </div>
        `);
    }

    function render() {
        disposeCharts();
        $('#content-area').html(buildPageHtml());
        bindEvents();
        initCharts();
    }

    function buildPageHtml() {
        return `
            <div class="content-view cost-management-view" id="cost-management-view">
                <div class="content-header d-flex justify-content-between align-items-start flex-wrap gap-2">
                    <div>
                        <h1>Cost Management</h1>
                        <p>Estimated spend across the fleet, from VM runtime and a static pricing reference &mdash; not live billing data.</p>
                    </div>
                    <div class="cost-toolbar no-print">
                        <button class="btn btn-ghost btn-sm" id="cost-backfill-btn"
                                title="Populate cost history for the trend chart">
                            <i class="fas fa-history"></i> Backfill 30 Days
                        </button>
                        <button class="btn btn-ghost btn-sm" id="cost-export-png-btn">
                            <i class="fas fa-image"></i> Export PNG
                        </button>
                        <button class="btn btn-ghost btn-sm" id="cost-export-pdf-btn">
                            <i class="fas fa-file-pdf"></i> Export PDF
                        </button>
                    </div>
                </div>

                <div class="cost-report" id="cost-report">
                    ${buildKpiStrip(state.summary)}
                    ${buildBreakdownCharts()}
                    ${buildTrendChart()}
                    ${buildIdleWasteTable()}
                    ${buildRightsizingTable()}
                    ${buildVmDetailTable()}
                </div>
            </div>
        `;
    }

    // ---- KPI strip ----

    function buildKpiStrip(summary) {
        if (!summary) return '';
        const deltaText = summary.monthOverMonthChangePercent === null || summary.monthOverMonthChangePercent === undefined
            ? 'No prior-period data yet'
            : `${summary.monthOverMonthChangePercent >= 0 ? '+' : ''}${summary.monthOverMonthChangePercent}% vs prior 30 days`;
        return `
            <div class="dashboard-kpi-grid cost-kpi-grid">
                ${buildKpi('Total Monthly Cost', Utils.formatCurrency(summary.totalMonthlyCost || 0), 'fa-dollar-sign', deltaText)}
                ${buildKpi('Idle Waste', Utils.formatCurrency(summary.idleWasteMonthlyCost || 0), 'fa-moon', `${summary.idleVmCount || 0} idle VM(s)`)}
                ${buildKpi('Rightsizing Potential', Utils.formatCurrency(summary.rightsizingPotentialSavings || 0), 'fa-compress-arrows-alt', `${summary.rightsizingCandidateCount || 0} candidate(s)`)}
                ${buildKpi('Pricing Coverage', `${summary.costKnownVmCount || 0} / ${summary.totalVmCount || 0}`, 'fa-tag', 'VMs with a known instance price')}
            </div>
        `;
    }

    function buildKpi(label, value, icon, subtitle) {
        return `
            <div class="dashboard-kpi">
                <i class="fas ${icon}"></i>
                <div>
                    <strong>${Utils.escapeHtml(String(value))}</strong>
                    <span>${Utils.escapeHtml(label)}</span>
                    <small class="cost-kpi-subtitle">${Utils.escapeHtml(subtitle)}</small>
                </div>
            </div>
        `;
    }

    // ---- breakdown & trend charts ----

    function buildBreakdownCharts() {
        return `
            <div class="cost-breakdown-layout">
                <section class="dashboard-chart-panel">
                    <div class="dashboard-panel-head">
                        <h2>Spend by Environment</h2>
                        <small>${state.spendByEnvironment.length} environment(s)</small>
                    </div>
                    <div id="cost-chart-env" class="dashboard-chart"></div>
                </section>
                <section class="dashboard-chart-panel">
                    <div class="dashboard-panel-head">
                        <h2>Spend by VM Type</h2>
                        <small>${state.spendByVmType.length} type(s)</small>
                    </div>
                    <div id="cost-chart-type" class="dashboard-chart"></div>
                </section>
                <section class="dashboard-chart-panel">
                    <div class="dashboard-panel-head">
                        <h2>Spend by Team</h2>
                        <small>Trend, last ${TREND_DAYS} days</small>
                    </div>
                    <div id="cost-chart-team" class="dashboard-chart"></div>
                </section>
            </div>
        `;
    }

    function buildTrendChart() {
        return `
            <section class="dashboard-chart-panel dashboard-wide">
                <div class="dashboard-panel-head">
                    <h2>Spend Trend</h2>
                    <small>Estimated, last ${TREND_DAYS} days</small>
                </div>
                <div id="cost-chart-trend" class="dashboard-chart"></div>
            </section>
        `;
    }

    function initCharts() {
        if (!window.echarts) return;
        chart('cost-chart-env', buildEnvironmentPieOption(state.spendByEnvironment));
        chart('cost-chart-type', buildVmTypePolarBarOption(state.spendByVmType));
        chart('cost-chart-team', buildTeamStackedLineOption(state.teamTrend));
        chart('cost-chart-trend', buildTrendOption(state.trend));
        setTimeout(resizeCharts, 40);
    }

    function chart(id, option) {
        const el = document.getElementById(id);
        if (!el || !window.echarts) return;
        const instance = echarts.init(el, null, { renderer: 'svg' });
        instance.setOption(option);
        chartRegistry.set(id, instance);
    }

    function disposeCharts() {
        chartRegistry.forEach(instance => instance.dispose());
        chartRegistry.clear();
    }

    function resizeCharts() {
        chartRegistry.forEach(instance => instance.resize());
    }

    /**
     * Collapses a dimension-breakdown list to its top N slices by cost plus one "Other" residual
     * bucket, so a chart never has to render an unbounded number of categories/series.
     */
    function topSlicesWithOther(rows, topN, labelFn, valueFn) {
        const sorted = [...rows].sort((a, b) => valueFn(b) - valueFn(a));
        const top = sorted.slice(0, topN);
        const rest = sorted.slice(topN);
        const otherTotal = rest.reduce((sum, r) => sum + valueFn(r), 0);
        const slices = top.map(r => ({ name: labelFn(r), value: valueFn(r) }));
        if (otherTotal > 0) {
            slices.push({ name: `Other (${rest.length})`, value: otherTotal });
        }
        return slices;
    }

    function buildEnvironmentPieOption(rows) {
        if (!rows.length) return emptyChartOption('No spend data yet.');
        const data = topSlicesWithOther(rows, TOP_N_SLICES, r => r.dimensionLabel, r => Number(r.cost) || 0);
        return {
            color: CATEGORICAL_COLORS,
            tooltip: { trigger: 'item', confine: true, valueFormatter: value => Utils.formatCurrency(value) },
            legend: { bottom: 0, type: 'scroll', textStyle: chartTextStyle() },
            series: [{
                type: 'pie',
                radius: ['38%', '68%'],
                center: ['50%', '42%'],
                label: { formatter: '{b}\n{d}%', fontSize: 11 },
                itemStyle: { borderColor: '#fff', borderWidth: 2 },
                data
            }]
        };
    }

    function buildVmTypePolarBarOption(rows) {
        if (!rows.length) return emptyChartOption('No spend data yet.');
        const sorted = [...rows].sort((a, b) => (Number(b.cost) || 0) - (Number(a.cost) || 0));
        const labels = sorted.map(r => r.dimensionLabel);
        const values = sorted.map(r => Number(r.cost) || 0);
        return {
            color: [CATEGORICAL_COLORS[0]],
            tooltip: { trigger: 'item', confine: true, valueFormatter: value => Utils.formatCurrency(value) },
            polar: { radius: ['20%', '75%'] },
            angleAxis: {
                type: 'value',
                axisLabel: { ...chartTextStyle(), formatter: v => compactCurrency(v) },
                splitLine: { lineStyle: { color: '#eef2f7' } }
            },
            radiusAxis: {
                type: 'category',
                data: labels,
                axisLabel: chartTextStyle()
            },
            series: [{
                type: 'bar',
                data: values,
                coordinateSystem: 'polar',
                barCategoryGap: '30%',
                itemStyle: { borderRadius: 4 }
            }]
        };
    }

    /**
     * Pivots flat (date, team, cost) rows into one stacked line series per team — capped to the
     * top N teams by total spend over the window (plus an "Other" series) so the chart never has
     * an unbounded number of stacked lines.
     */
    function buildTeamStackedLineOption(rows) {
        if (!rows.length) return emptyChartOption('No cost history yet — use "Backfill 30 Days" to populate it.');

        const dates = [...new Set(rows.map(r => r.date))].sort();

        const totalByTeam = new Map();
        for (const row of rows) {
            totalByTeam.set(row.team, (totalByTeam.get(row.team) || 0) + (Number(row.estimatedCost) || 0));
        }
        const rankedTeams = [...totalByTeam.entries()].sort((a, b) => b[1] - a[1]);
        const topTeams = new Set(rankedTeams.slice(0, TOP_N_SLICES).map(([team]) => team));
        const hasOther = rankedTeams.length > TOP_N_SLICES;

        const costByTeamAndDate = new Map();
        for (const row of rows) {
            const seriesName = topTeams.has(row.team) ? row.team : 'Other';
            if (!costByTeamAndDate.has(seriesName)) costByTeamAndDate.set(seriesName, new Map());
            const byDate = costByTeamAndDate.get(seriesName);
            byDate.set(row.date, (byDate.get(row.date) || 0) + (Number(row.estimatedCost) || 0));
        }

        const seriesNames = [...rankedTeams.filter(([team]) => topTeams.has(team)).map(([team]) => team)];
        if (hasOther) seriesNames.push('Other');

        const series = seriesNames.map((name, index) => ({
            name,
            type: 'line',
            stack: 'total',
            smooth: true,
            showSymbol: false,
            areaStyle: { opacity: 0.75 },
            lineStyle: { width: 1 },
            color: CATEGORICAL_COLORS[index % CATEGORICAL_COLORS.length],
            data: dates.map(date => {
                const byDate = costByTeamAndDate.get(name);
                return byDate ? Math.round((byDate.get(date) || 0) * 100) / 100 : 0;
            })
        }));

        return {
            tooltip: {
                trigger: 'axis',
                confine: true,
                valueFormatter: value => Utils.formatCurrency(value)
            },
            legend: { bottom: 0, type: 'scroll', textStyle: chartTextStyle() },
            grid: { left: 52, right: 24, top: 18, bottom: 56 },
            xAxis: {
                type: 'category',
                boundaryGap: false,
                data: dates.map(d => (d || '').slice(5)),
                axisLabel: chartTextStyle()
            },
            yAxis: {
                type: 'value',
                axisLabel: { ...chartTextStyle(), formatter: v => compactCurrency(v) },
                splitLine: { lineStyle: { color: '#eef2f7' } }
            },
            series
        };
    }

    function buildTrendOption(rows) {
        if (!rows.length) return emptyChartOption('No cost history yet — use "Backfill 30 Days" to populate it.');
        const labels = rows.map(r => (r.date || '').slice(5));
        const values = rows.map(r => Number(r.estimatedCost) || 0);
        return {
            color: ['#2563eb'],
            tooltip: {
                trigger: 'axis',
                confine: true,
                formatter: params => {
                    const point = params && params[0];
                    if (!point) return '';
                    const row = rows[point.dataIndex] || {};
                    return `${row.date || ''}<br/>Estimated: ${Utils.formatCurrency(Number(row.estimatedCost) || 0)}`;
                }
            },
            grid: { left: 52, right: 24, top: 18, bottom: 32 },
            xAxis: { type: 'category', boundaryGap: false, data: labels, axisLabel: chartTextStyle() },
            yAxis: {
                type: 'value',
                axisLabel: { ...chartTextStyle(), formatter: v => compactCurrency(v) },
                splitLine: { lineStyle: { color: '#eef2f7' } }
            },
            series: [{
                name: 'Estimated Cost',
                type: 'line',
                smooth: true,
                showSymbol: values.length < 60,
                areaStyle: { opacity: 0.08 },
                data: values
            }]
        };
    }

    function emptyChartOption(text) {
        return {
            graphic: {
                type: 'text',
                left: 'center',
                top: 'middle',
                style: { text, fill: '#64748b', fontSize: 13, fontWeight: 600 }
            }
        };
    }

    function chartTextStyle() {
        return { fontFamily: 'Inter, Segoe UI, Arial, sans-serif', color: '#64748b' };
    }

    function compactCurrency(value) {
        const n = Number(value) || 0;
        if (Math.abs(n) >= 1000) return `$${(n / 1000).toFixed(1)}k`;
        return `$${Math.round(n)}`;
    }

    // ---- tables ----

    function buildTableCard(opts) {
        return `
            <section class="cost-table-card">
                <div class="dashboard-panel-head">
                    <h2>${Utils.escapeHtml(opts.title)}</h2>
                    <small>${Utils.escapeHtml(opts.subtitle)}</small>
                </div>
                <div class="table-responsive">
                    <table class="table table-baseline cost-table" id="${opts.tableId}">
                        <thead>
                            <tr>${opts.columns.map(c => `<th>${Utils.escapeHtml(c)}</th>`).join('')}</tr>
                        </thead>
                        <tbody id="${opts.bodyId}">${opts.rowsHtml}</tbody>
                    </table>
                </div>
                <div class="pagination-bar-wrap" id="${opts.paginationId}">
                    ${buildPagination(opts.tableKey, opts.pageState)}
                </div>
            </section>
        `;
    }

    function buildPagination(tableKey, pageState) {
        const page = pageState.page || 0;
        return Pagination.buildSimpleMarkup({
            page,
            totalPages: pageState.totalPages || 0,
            totalItems: pageState.totalElements || 0,
            itemLabel: 'rows',
            prevOnClick: `CostManagement.changePage('${tableKey}', ${page - 1})`,
            nextOnClick: `CostManagement.changePage('${tableKey}', ${page + 1})`
        });
    }

    function costCell(amount, costKnown) {
        const formatted = Utils.formatCurrency(Number(amount) || 0);
        if (costKnown === false) {
            return `${formatted} <i class="fas fa-triangle-exclamation cost-partial-icon" title="Instance pricing unknown for this VM — storage cost only"></i>`;
        }
        return formatted;
    }

    function formatMinutes(minutes) {
        const n = Number(minutes) || 0;
        if (n < 60) return `${n}m`;
        const hours = Math.floor(n / 60);
        const mins = n % 60;
        return mins ? `${hours}h ${mins}m` : `${hours}h`;
    }

    // Idle & Waste

    function buildIdleWasteTable() {
        return buildTableCard({
            title: 'Idle & Waste, Ranked by Cost Impact',
            subtitle: `${state.idle.totalElements} idle VM(s)`,
            tableId: 'cost-idle-table',
            bodyId: 'cost-idle-table-body',
            paginationId: 'cost-idle-pagination',
            columns: ['VM', 'Environment', 'Group', 'Monthly Cost', 'Latest CPU', 'Idle Since', 'Idle Duration'],
            rowsHtml: buildIdleRows(state.idle.content),
            pageState: state.idle,
            tableKey: 'idle'
        });
    }

    function buildIdleRows(rows) {
        if (!rows.length) {
            return `<tr><td colspan="7" class="text-center text-muted py-4">No idle VMs found.</td></tr>`;
        }
        return rows.map(row => `
            <tr>
                <td>${Utils.escapeHtml(row.vmName || row.vmId)}</td>
                <td>${Utils.escapeHtml(row.environmentName || '-')}</td>
                <td>${Utils.escapeHtml(row.groupName || '-')}</td>
                <td class="cost-num">${costCell(row.monthlyCost, row.costKnown)}</td>
                <td class="cost-num">${row.latestCpuUtilization != null ? Number(row.latestCpuUtilization).toFixed(1) + '%' : '-'}</td>
                <td>${row.idleSince ? Utils.formatRelativeTime(row.idleSince) : '-'}</td>
                <td class="cost-num">${row.idleDurationMinutes != null ? formatMinutes(row.idleDurationMinutes) : '-'}</td>
            </tr>
        `).join('');
    }

    function renderIdleTable() {
        $('#cost-idle-table-body').html(buildIdleRows(state.idle.content));
        $('#cost-idle-pagination').html(buildPagination('idle', state.idle));
    }

    // Rightsizing

    function buildRightsizingTable() {
        return buildTableCard({
            title: 'Rightsizing Recommendations',
            subtitle: `${state.rightsizing.totalElements} candidate(s) — avg CPU < 5% and peak CPU < 30% over 30 days`,
            tableId: 'cost-rightsizing-table',
            bodyId: 'cost-rightsizing-table-body',
            paginationId: 'cost-rightsizing-pagination',
            columns: ['VM', 'Environment', 'Current → Suggested', 'Avg CPU', 'Peak CPU', 'Current Cost', 'Est. Savings'],
            rowsHtml: buildRightsizingRows(state.rightsizing.content),
            pageState: state.rightsizing,
            tableKey: 'rightsizing'
        });
    }

    function buildRightsizingRows(rows) {
        if (!rows.length) {
            return `<tr><td colspan="7" class="text-center text-muted py-4">No rightsizing candidates found.</td></tr>`;
        }
        return rows.map(row => `
            <tr>
                <td>${Utils.escapeHtml(row.vmName || row.vmId)}</td>
                <td>${Utils.escapeHtml(row.environmentName || '-')}</td>
                <td>${Utils.escapeHtml(row.currentInstanceType || '-')} &rarr; ${Utils.escapeHtml(row.suggestedInstanceType || 'n/a')}</td>
                <td class="cost-num">${row.avgCpuUtilization != null ? Number(row.avgCpuUtilization).toFixed(1) + '%' : '-'}</td>
                <td class="cost-num">${row.peakCpuUtilization != null ? Number(row.peakCpuUtilization).toFixed(1) + '%' : '-'}</td>
                <td class="cost-num">${costCell(row.currentMonthlyCost, true)}</td>
                <td class="cost-num">${row.estimatedMonthlySavings != null ? costCell(row.estimatedMonthlySavings, row.costKnown) : '<span class="text-muted">Unknown</span>'}</td>
            </tr>
        `).join('');
    }

    function renderRightsizingTable() {
        $('#cost-rightsizing-table-body').html(buildRightsizingRows(state.rightsizing.content));
        $('#cost-rightsizing-pagination').html(buildPagination('rightsizing', state.rightsizing));
    }

    // VM Cost Detail

    function buildVmDetailTable() {
        return buildTableCard({
            title: 'VM Cost Detail',
            subtitle: `${state.detail.totalElements} VM(s)`,
            tableId: 'cost-detail-table',
            bodyId: 'cost-detail-table-body',
            paginationId: 'cost-detail-pagination',
            columns: ['VM', 'Environment', 'Group', 'Status', 'Instance Type', 'Region', 'Runtime', 'Storage', 'Monthly Cost'],
            rowsHtml: buildDetailRows(state.detail.content),
            pageState: state.detail,
            tableKey: 'detail'
        });
    }

    function buildDetailRows(rows) {
        if (!rows.length) {
            return `<tr><td colspan="9" class="text-center text-muted py-4">No VMs found.</td></tr>`;
        }
        return rows.map(row => `
            <tr>
                <td>${Utils.escapeHtml(row.vmName || row.vmId)}</td>
                <td>${Utils.escapeHtml(row.environmentName || '-')}</td>
                <td>${Utils.escapeHtml(row.groupName || '-')}</td>
                <td>${Config.renderStatusBadge(row.status)}</td>
                <td>${Utils.escapeHtml(row.instanceType || '-')}</td>
                <td>${Utils.escapeHtml(row.region || '-')}</td>
                <td class="cost-num">${row.runtimeHours != null ? Number(row.runtimeHours).toFixed(1) + 'h' : '-'}</td>
                <td class="cost-num">${row.storageGib != null ? row.storageGib + ' GiB' : '-'}</td>
                <td class="cost-num">${costCell(row.monthlyCost, row.costKnown)}</td>
            </tr>
        `).join('');
    }

    function renderDetailTable() {
        $('#cost-detail-table-body').html(buildDetailRows(state.detail.content));
        $('#cost-detail-pagination').html(buildPagination('detail', state.detail));
    }

    // ---- events ----

    function bindEvents() {
        $('#cost-backfill-btn').off('click').on('click', triggerBackfill);
        $('#cost-export-png-btn').off('click').on('click', exportPng);
        $('#cost-export-pdf-btn').off('click').on('click', exportPdf);
        $(window).off('resize.costManagementCharts').on('resize.costManagementCharts', resizeCharts);
    }

    function triggerBackfill() {
        const $btn = $('#cost-backfill-btn');
        $btn.prop('disabled', true).html('<i class="fas fa-spinner fa-spin"></i> Backfilling&hellip;');
        ApiClient.post(Config.API.costManagement.backfill(30), null)
            .done(() => {
                Notifications.success('Cost history backfilled for the last 30 days.');
                fetchJson(Config.API.costManagement.spendTrend(TREND_DAYS)).then(trend => {
                    state.trend = trend || [];
                    chart('cost-chart-trend', buildTrendOption(state.trend));
                });
            })
            .fail(() => {
                Notifications.error('Failed to backfill cost history.');
            })
            .always(() => {
                $btn.prop('disabled', false).html('<i class="fas fa-history"></i> Backfill 30 Days');
            });
    }

    // ---- export ----

    function todayStamp() {
        return new Date().toISOString().split('T')[0];
    }

    function fetchFullTableData() {
        return Promise.all([
            fetchJson(Config.API.costManagement.idleWaste(0, EXPORT_SIZE)),
            fetchJson(Config.API.costManagement.rightsizing(0, EXPORT_SIZE)),
            fetchJson(Config.API.costManagement.vmDetail(0, EXPORT_SIZE))
        ]);
    }

    function exportPdf() {
        if (exporting) return;
        exporting = true;

        fetchFullTableData().then(([idleAll, rightsizingAll, detailAll]) => {
            const idleRows = (idleAll && idleAll.content) || [];
            const rightsizingRows = (rightsizingAll && rightsizingAll.content) || [];
            const detailRows = (detailAll && detailAll.content) || [];

            $('#cost-idle-table-body').html(buildIdleRows(idleRows));
            $('#cost-idle-pagination').html(`<span class="text-muted small">All ${idleRows.length} row(s) shown for export</span>`);
            $('#cost-rightsizing-table-body').html(buildRightsizingRows(rightsizingRows));
            $('#cost-rightsizing-pagination').html(`<span class="text-muted small">All ${rightsizingRows.length} row(s) shown for export</span>`);
            $('#cost-detail-table-body').html(buildDetailRows(detailRows));
            $('#cost-detail-pagination').html(`<span class="text-muted small">All ${detailRows.length} row(s) shown for export</span>`);

            const restore = () => {
                renderIdleTable();
                renderRightsizingTable();
                renderDetailTable();
                exporting = false;
                window.removeEventListener('afterprint', restore);
            };
            window.addEventListener('afterprint', restore);
            setTimeout(() => window.print(), 50);
        }).catch(error => {
            exporting = false;
            console.error('Failed to prepare PDF export:', error);
            Notifications.error('Failed to prepare export. Please try again.');
        });
    }

    function exportPng() {
        if (exporting) return;
        if (!window.html2canvas) {
            Notifications.error('PNG export is unavailable (html2canvas failed to load).');
            return;
        }
        exporting = true;
        Notifications.info('Preparing PNG export…');

        fetchFullTableData().then(([idleAll, rightsizingAll, detailAll]) => {
            const source = document.getElementById('cost-report');
            const clone = source.cloneNode(true);
            clone.id = 'cost-report-export-clone';
            clone.style.position = 'fixed';
            clone.style.top = '-10000px';
            clone.style.left = '0';
            clone.style.width = source.offsetWidth + 'px';
            clone.style.background = '#ffffff';

            const idleRows = (idleAll && idleAll.content) || [];
            const rightsizingRows = (rightsizingAll && rightsizingAll.content) || [];
            const detailRows = (detailAll && detailAll.content) || [];

            const idleBody = clone.querySelector('#cost-idle-table-body');
            const rightsizingBody = clone.querySelector('#cost-rightsizing-table-body');
            const detailBody = clone.querySelector('#cost-detail-table-body');
            if (idleBody) idleBody.innerHTML = buildIdleRows(idleRows);
            if (rightsizingBody) rightsizingBody.innerHTML = buildRightsizingRows(rightsizingRows);
            if (detailBody) detailBody.innerHTML = buildDetailRows(detailRows);
            clone.querySelectorAll('.pagination-bar-wrap').forEach(el => {
                el.innerHTML = '<span class="text-muted small">All rows shown for export</span>';
            });

            // html2canvas cannot reliably snapshot live SVG-rendered ECharts, so swap each
            // chart <div> for a static <img> taken from the chart's own dataURL first.
            ['cost-chart-env', 'cost-chart-type', 'cost-chart-team', 'cost-chart-trend'].forEach(id => {
                const liveEl = document.getElementById(id);
                const cloneEl = clone.querySelector('#' + id);
                const instance = chartRegistry.get(id);
                if (!liveEl || !cloneEl || !instance) return;
                const dataUrl = instance.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#ffffff' });
                const img = document.createElement('img');
                img.src = dataUrl;
                img.style.width = liveEl.offsetWidth + 'px';
                img.style.height = liveEl.offsetHeight + 'px';
                cloneEl.replaceWith(img);
            });

            document.body.appendChild(clone);

            html2canvas(clone, { scale: 2, backgroundColor: '#ffffff', useCORS: true })
                .then(canvas => {
                    canvas.toBlob(blob => {
                        if (blob) {
                            Utils.downloadBlob(blob, `cost-management-${todayStamp()}.png`);
                        } else {
                            Notifications.error('Failed to generate PNG.');
                        }
                        clone.remove();
                        exporting = false;
                    }, 'image/png');
                })
                .catch(error => {
                    console.error('PNG export failed:', error);
                    Notifications.error('Failed to generate PNG.');
                    clone.remove();
                    exporting = false;
                });
        }).catch(error => {
            exporting = false;
            console.error('Failed to prepare PNG export:', error);
            Notifications.error('Failed to prepare export. Please try again.');
        });
    }

    return {
        load,
        changePage
    };
})();

// Make available globally
window.CostManagement = CostManagement;
