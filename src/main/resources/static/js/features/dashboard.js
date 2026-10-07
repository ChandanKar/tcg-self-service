/**
 * VM Self-Service Platform - Dashboard Feature
 * Role-aware dashboard backed by /api/v1/dashboard/summary.
 */
const Dashboard = (function() {
    'use strict';

    let dashboardData = null;
    let isLoading = false;
    let autoRefreshEnabled = false;
    let chartRegistry = new Map();
    let lastRenderSignature = null;

    /** Router loader (see the page contract in core/router.js). */
    async function load() {
        const t = ContentRouter.token();
        ContentRouter.onLeave(teardown);
        RealTime.stopPolling('dashboard');
        showLoading();

        try {
            const data = await fetchDashboardData();
            if (!ContentRouter.isCurrent(t)) return;
            dashboardData = data;
            renderDashboard(dashboardData);
            if (autoRefreshEnabled) {
                startAutoRefresh();
            }
        } catch (error) {
            if (!ContentRouter.isCurrent(t)) return;
            console.error('Failed to load dashboard:', error);
            showError(error.status === 403
                ? 'You do not have access to dashboard data.'
                : 'Failed to load dashboard data. Please try again.');
        }
    }

    /** Auto-refresh tick: silent on errors, never renders after the user left, keeps the search. */
    async function silentRefresh() {
        if (isLoading) return;
        const t = ContentRouter.token();
        try {
            const freshData = await fetchDashboardData({ silent: true });
            if (!ContentRouter.isCurrent(t)) return;
            const freshSignature = dashboardSignature(freshData);
            dashboardData = freshData;
            if (freshSignature === lastRenderSignature) {
                return;
            }
            const search = $('#dashboard-env-search').val();
            renderDashboard(dashboardData);
            if (search) {
                $('#dashboard-env-search').val(search).trigger('input');
            }
        } catch (error) {
            console.warn('Silent dashboard refresh failed:', error && error.status);
        }
    }

    function fetchDashboardData(options = {}) {
        return new Promise((resolve, reject) => {
            const request = options.silent
                ? RealTime.pollGet(Config.API.dashboard.summary)
                : ApiClient.get(Config.API.dashboard.summary);
            request.done(resolve).fail(reject);
        });
    }

    function renderDashboard(data) {
        isLoading = false;
        lastRenderSignature = dashboardSignature(data);
        disposeTooltips();
        disposeCharts();
        $('#content-area').html(buildDashboardHtml(data));
        bindEvents();
        initCharts(data);
        initTooltips();
        $(window).off('resize.dashboardCharts').on('resize.dashboardCharts', resizeCharts);
    }

    /** Runs when the user leaves the dashboard (ContentRouter.onLeave). */
    function teardown() {
        RealTime.stopPolling('dashboard');
        disposeTooltips();
        disposeCharts();
        $('#content-area').off('.dashboardJump .dashboardAutoRefresh .dashboardSearch');
        $(window).off('resize.dashboardCharts');
        isLoading = false;
    }

    function showLoading() {
        isLoading = true;
        $('#content-area').html(`
            <div class="content-view" id="dashboard-view">
                <div class="content-header">
                    <h1>Dashboard</h1>
                    <p>Loading your fleet view</p>
                </div>
                <div class="loading-state">
                    <div class="spinner-border text-primary" role="status">
                        <span class="visually-hidden">Loading...</span>
                    </div>
                    <p>Loading dashboard data...</p>
                </div>
            </div>
        `);
    }

    function showError(message) {
        isLoading = false;
        disposeCharts();
        $('#content-area').html(`
            <div class="content-view" id="dashboard-view">
                <div class="content-header">
                    <h1>Dashboard</h1>
                    <p>Overview of your environments and VMs</p>
                </div>
                <div class="error-state">
                    <i class="fas fa-exclamation-triangle fa-3x text-warning"></i>
                    <h4 class="mt-3">Unable to Load Dashboard</h4>
                    <p>${Utils.escapeHtml(message)}</p>
                    <button class="btn btn-primary" data-action="dashboard-refresh">
                        <i class="fas fa-sync"></i> Try Again
                    </button>
                </div>
            </div>
        `);
    }

    function buildDashboardHtml(data) {
        const admin = data.persona === 'ADMIN';
        const subtitle = admin ? 'Global AWS fleet overview' : 'Overview of your environments and VMs';
        return `
            <div class="content-view dashboard-persona ${admin ? 'dashboard-admin' : 'dashboard-user'}" id="dashboard-view">
                <div class="content-header d-flex justify-content-between align-items-start flex-shrink-0">
                    <div>
                        <h1>Dashboard</h1>
                        <p>${subtitle}</p>
                    </div>
                    <button class="btn btn-ghost btn-sm" data-action="dashboard-refresh" title="Refresh">
                        <i class="fas fa-sync"></i> Refresh
                    </button>
                    <button class="btn btn-outline-secondary btn-sm ${autoRefreshEnabled ? 'active' : ''}"
                            id="dashboard-auto-refresh-toggle"
                            type="button"
                            aria-pressed="${autoRefreshEnabled}"
                            title="Auto refresh">
                        <i class="fas ${autoRefreshEnabled ? 'fa-pause' : 'fa-play'}"></i>
                        ${autoRefreshEnabled ? 'Auto On' : 'Auto Off'}
                    </button>
                </div>

                <div class="dashboard-body">
                    ${buildMetricStrip(data)}
                    ${admin ? buildAdminDashboard(data) : buildUserDashboard(data)}
                </div>
            </div>
        `;
    }

    function buildMetricStrip(data) {
        const summary = data.summary || {};
        const storage = data.storage || {};
        const coverage = data.coverage || {};
        // Admin dashboard no longer has an Idle VMs panel to jump to (removed with the
        // Global Utilization chart split) — only the non-admin dashboard's panel still exists.
        const idleTarget = data.persona === 'ADMIN' ? null : 'dashboard-idle-vms';
        return `
            <div class="dashboard-kpi-grid">
                ${buildKpi('Environments', summary.environments, 'fa-layer-group', 'Accessible active environments')}
                ${buildKpi('Total VMs', summary.totalVms, 'fa-server', 'Registered active VMs')}
                ${buildKpi('Running', summary.runningVms, 'fa-play-circle', `${percent(summary.runningVms, summary.totalVms)}% of fleet`)}
                ${buildKpi('Idle', summary.idleVms, 'fa-moon', 'VMs with sustained low utilization', idleTarget)}
                ${buildKpi('Storage', formatGiB(storage.totalAllocatedStorageGib), 'fa-hard-drive', 'Allocated EBS storage')}
                ${buildKpi('Coverage', `${coverage.metricsPercent || 0}%`, 'fa-signal', 'Metric sample coverage')}
            </div>
        `;
    }

    function buildKpi(label, value, icon, title, targetId) {
        return `
            <div class="dashboard-kpi ${targetId ? 'dashboard-kpi-action' : ''}"
                 ${targetId ? `data-dashboard-jump="${Utils.escapeHtml(targetId)}"` : ''}
                 data-bs-toggle="tooltip" data-bs-placement="bottom" title="${Utils.escapeHtml(title || label)}">
                <i class="fas ${icon}"></i>
                <div>
                    <strong>${value ?? 0}</strong>
                    <span>${Utils.escapeHtml(label)}</span>
                </div>
            </div>
        `;
    }

    function buildUserDashboard(data) {
        return `
            <div class="dashboard-layout dashboard-layout-user">
                <section class="dashboard-chart-panel dashboard-wide">
                    <div class="dashboard-panel-head">
                        <h2>Utilization Trend</h2>
                        <small>Last 24h</small>
                    </div>
                    <div id="dash-util-trend" class="dashboard-chart"></div>
                </section>
                <section class="dashboard-chart-panel">
                    <div class="dashboard-panel-head">
                        <h2>VM State</h2>
                        <small>${data.summary?.totalVms || 0} VMs</small>
                    </div>
                    <div id="dash-vm-state" class="dashboard-chart"></div>
                </section>
                <section class="dashboard-chart-panel">
                    <div class="dashboard-panel-head">
                        <h2>Storage Mix</h2>
                        <small>${data.storage?.volumeCount || 0} volumes</small>
                    </div>
                    <div id="dash-storage-mix" class="dashboard-chart"></div>
                </section>
                ${buildEnvironmentPanel(data.environments || [])}
                ${buildListPanel('Idle VMs', data.idleVms || [], 'idle')}
            </div>
        `;
    }

    function buildAdminDashboard(data) {
        return `
            <div class="dashboard-layout dashboard-layout-admin">
                <section class="dashboard-chart-panel dashboard-full">
                    <div class="dashboard-panel-head">
                        <h2>Global Utilization</h2>
                        <small>Last 24h</small>
                    </div>
                    <div class="dashboard-trio-grid">
                        <div class="dashboard-trio-item">
                            <h3>CPU</h3>
                            <div id="dash-util-cpu" class="dashboard-chart"></div>
                        </div>
                        <div class="dashboard-trio-item">
                            <h3>Memory</h3>
                            <div id="dash-util-memory" class="dashboard-chart"></div>
                        </div>
                        <div class="dashboard-trio-item">
                            <h3>Network</h3>
                            <div id="dash-util-network" class="dashboard-chart"></div>
                        </div>
                    </div>
                </section>
                <section class="dashboard-chart-panel dashboard-wide">
                    <div class="dashboard-panel-head">
                        <h2>Fleet State</h2>
                        <small>${data.summary?.totalVms || 0} VMs</small>
                    </div>
                    <div id="dash-vm-state" class="dashboard-chart"></div>
                </section>
                <section class="dashboard-chart-panel dashboard-wide">
                    <div class="dashboard-panel-head">
                        <h2>Cloud Coverage</h2>
                        <small>collection health</small>
                    </div>
                    <div id="dash-coverage" class="dashboard-chart"></div>
                </section>
                ${buildSchedulerHealthPanel(data.schedulerHealth || [], true)}
                ${buildRiskCompliancePanel(data.riskCompliance || [])}
            </div>
        `;
    }

    function buildEnvironmentPanel(environments) {
        return `
            <section class="dashboard-table-panel dashboard-wide">
                <div class="dashboard-panel-head">
                    <h2>Environments</h2>
                    <input type="text" class="form-control form-control-sm" id="dashboard-env-search" placeholder="Search...">
                </div>
                <div class="dashboard-env-table">
                    <table class="table table-hover mb-0">
                        <thead>
                            <tr>
                                <th>Environment</th>
                                <th>Type</th>
                                <th>VMs</th>
                                <th>CPU</th>
                                <th>Storage</th>
                                <th>Status</th>
                            </tr>
                        </thead>
                        <tbody id="dashboard-env-rows">
                            ${buildEnvironmentRows(environments)}
                        </tbody>
                    </table>
                </div>
            </section>
        `;
    }

    function buildEnvironmentRows(environments) {
        if (!environments.length) {
            return `<tr><td colspan="6" class="text-center text-muted py-4">No environments available</td></tr>`;
        }
        return environments.map(env => {
            const running = env.runningVms || 0;
            const total = env.totalVms || 0;
            const statusClass = total === 0 ? 'secondary' : running === total ? 'success' : running === 0 ? 'secondary' : 'warning';
            return `
                <tr class="dashboard-env-row" data-name="${Utils.escapeHtml((env.name || '').toLowerCase())}">
                    <td>
                        <strong>${Utils.escapeHtml(env.displayName || env.name || '-')}</strong>
                        <small>${Utils.escapeHtml(env.name || '')}</small>
                    </td>
                    <td>${Utils.escapeHtml(env.serviceType || 'EC2')}</td>
                    <td>${running}/${total}</td>
                    <td>${formatPercent(env.avgCpuUtilization)}</td>
                    <td>${formatGiB(env.allocatedStorageGib)}</td>
                    <td><span class="badge bg-${statusClass}">${env.locked ? 'Locked' : 'Open'}</span></td>
                </tr>
            `;
        }).join('');
    }

    function buildListPanel(title, rows, type, prominent = false) {
        const visibleRows = (type === 'low-utilization' || type === 'idle') ? rows : rows.slice(0, 4);
        const panelId = type === 'low-utilization' ? 'dashboard-low-utilization'
            : type === 'idle' ? 'dashboard-idle-vms'
                : '';
        const body = visibleRows.length ? visibleRows.map(row => {
            if (type === 'recommendation') {
                return `
                    <div class="dashboard-list-row">
                        <span class="dashboard-dot ${Utils.escapeHtml(row.tone || 'info')}"></span>
                        <div>
                            <strong>${Utils.escapeHtml(row.title || '-')}</strong>
                            <small>${Utils.escapeHtml(row.description || '')}</small>
                        </div>
                        <em>${row.count ?? 0}</em>
                    </div>
                `;
            }
            return `
                <div class="dashboard-list-row">
                    <span class="dashboard-dot warning"></span>
                    <div>
                        <strong>${Utils.escapeHtml(row.name || '-')}</strong>
                        <small>${Utils.escapeHtml(row.environmentName || '')}</small>
                    </div>
                    <em>${type === 'low-utilization' ? `${formatPercent(row.cpuUtilization)} / ${formatIdle(row.idleDurationMinutes)}` : formatIdle(row.idleDurationMinutes)}</em>
                </div>
            `;
        }).join('') : `<div class="dashboard-empty-small">No items to show</div>`;

        return `
            <section class="dashboard-list-panel ${prominent ? 'dashboard-list-panel-prominent dashboard-wide' : ''} ${panelId ? 'dashboard-list-panel-scroll' : ''}" ${panelId ? `id="${panelId}"` : ''}>
                <div class="dashboard-panel-head">
                    <h2>${Utils.escapeHtml(title)}</h2>
                    <small>${rows.length}</small>
                </div>
                <div>${body}</div>
            </section>
        `;
    }

    function buildSchedulerHealthPanel(rows, prominent = false) {
        const visibleRows = prominent ? rows : rows.slice(0, 4);
        const body = visibleRows.length ? visibleRows.map(row => `
            <div class="dashboard-scheduler-row">
                <span class="dashboard-dot ${schedulerTone(row.status)}"></span>
                <div>
                    <strong>${Utils.escapeHtml(row.name || '-')}</strong>
                    <small>${formatAge(row.freshnessSeconds)} ago</small>
                </div>
                <em class="${schedulerTone(row.status)}">${Utils.escapeHtml(row.status || 'UNKNOWN')}</em>
            </div>
        `).join('') : `<div class="dashboard-empty-small">No scheduler data yet</div>`;

        return `
            <section class="dashboard-list-panel ${prominent ? 'dashboard-list-panel-prominent dashboard-wide dashboard-list-panel-scroll' : ''}" ${prominent ? 'id="dashboard-scheduler-health"' : ''}>
                <div class="dashboard-panel-head">
                    <h2>Scheduler Health</h2>
                    <small>${rows.length}</small>
                </div>
                <div>${body}</div>
            </section>
        `;
    }

    function buildRiskCompliancePanel(rows) {
        const total = rows.reduce((sum, row) => sum + (Number(row.count) || 0), 0);

        return `
            <section class="dashboard-chart-panel dashboard-wide" id="dashboard-risk-compliance-panel">
                <div class="dashboard-panel-head">
                    <h2>Risk & Compliance</h2>
                    <small>${total} signals</small>
                </div>
                <div id="dash-risk-compliance" class="dashboard-chart dashboard-risk-chart"></div>
            </section>
        `;
    }

    function compactRiskText(row) {
        const title = (row.title || '').toLowerCase();
        if (title.includes('encryption')) return 'EBS';
        if (title.includes('drift')) return 'State';
        if (title.includes('metric')) return 'CloudWatch';
        if (title.includes('idle')) return 'Waste';
        if (title.includes('inventory')) return 'Inventory';
        return row.description || '';
    }

    function schedulerTone(status) {
        switch ((status || '').toUpperCase()) {
            case 'HEALTHY':
                return 'success';
            case 'RUNNING':
                return 'primary';
            case 'LATE':
                return 'warning';
            case 'STALE':
            case 'NEVER':
                return 'danger';
            default:
                return 'info';
        }
    }

    function riskToneColor(tone) {
        switch ((tone || '').toLowerCase()) {
            case 'success':
                return '#10b981';
            case 'warning':
                return '#f59e0b';
            case 'danger':
                return '#ef4444';
            case 'primary':
                return '#2563eb';
            case 'info':
            default:
                return '#3b82f6';
        }
    }

    function bindEvents() {
        $('#content-area').off('click.dashboardJump', '[data-dashboard-jump]')
            .on('click.dashboardJump', '[data-dashboard-jump]', function() {
                const target = document.getElementById($(this).data('dashboard-jump'));
                if (!target) return;
                target.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
                target.classList.add('dashboard-panel-highlight');
                setTimeout(() => target.classList.remove('dashboard-panel-highlight'), 1100);
            });

        $('#content-area').off('click.dashboardAutoRefresh', '#dashboard-auto-refresh-toggle')
            .on('click.dashboardAutoRefresh', '#dashboard-auto-refresh-toggle', function() {
                setAutoRefresh(!autoRefreshEnabled);
            });

        $('#content-area').off('input.dashboardSearch', '#dashboard-env-search')
            .on('input.dashboardSearch', '#dashboard-env-search', function() {
                const term = ($(this).val() || '').toLowerCase().trim();
                $('#dashboard-env-rows .dashboard-env-row').each(function() {
                    const name = $(this).data('name') || '';
                    $(this).toggle(!term || name.includes(term));
                });
            });
    }

    function initCharts(data) {
        if (!window.echarts) return;
        chart('dash-util-trend', buildTrendOption(data));
        chart('dash-util-cpu', buildSingleTrendOption(data, 'cpuUtilization', 'CPU %', '#2563eb', 'percent'));
        chart('dash-util-memory', buildSingleTrendOption(data, 'memoryUtilization', 'Memory %', '#7c3aed', 'percent'));
        chart('dash-util-network', buildSingleTrendOption(data, 'networkBytes', 'Network MB', '#10b981', 'mb'));
        chart('dash-vm-state', buildDonutOption(data.vmStatusCounts || {}, ['#10b981', '#64748b', '#f59e0b', '#ef4444']));
        chart('dash-storage-mix', buildDonutOption(data.volumeTypeCounts || {}, ['#2563eb', '#10b981', '#f59e0b', '#7c3aed', '#3b82f6']));
        chart('dash-coverage', buildGaugeOption(data.coverage?.metricsPercent || 0));
        chart('dash-risk-compliance', buildRiskComplianceOption(data.riskCompliance || []));
        setTimeout(resizeCharts, 40);
    }

    function chart(id, option) {
        const el = document.getElementById(id);
        if (!el || !window.echarts) return;
        const instance = echarts.init(el, null, { renderer: 'svg' });
        instance.setOption(option);
        chartRegistry.set(id, instance);
    }

    function buildTrendOption(data) {
        const points = data.utilizationTrend || [];
        const labels = points.map(point => point.label);
        return {
            color: ['#2563eb', '#10b981', '#f59e0b'],
            tooltip: {
                trigger: 'axis',
                confine: true,
                valueFormatter: value => value
            },
            legend: { bottom: 0, textStyle: chartTextStyle() },
            grid: { left: 36, right: 38, top: 18, bottom: 42 },
            xAxis: { type: 'category', boundaryGap: false, data: labels, axisLabel: chartTextStyle() },
            yAxis: [
                {
                    type: 'value',
                    name: 'CPU',
                    min: 0,
                    max: 100,
                    axisLabel: { ...chartTextStyle(), formatter: '{value}%' },
                    splitLine: { lineStyle: { color: '#eef2f7' } }
                },
                {
                    type: 'value',
                    name: 'MB',
                    position: 'right',
                    min: 0,
                    scale: true,
                    axisLabel: { ...chartTextStyle(), formatter: compactNumber },
                    splitLine: { show: false }
                }
            ],
            series: [
                {
                    name: 'CPU %',
                    type: 'line',
                    yAxisIndex: 0,
                    smooth: true,
                    data: points.map(point => Number(point.cpuUtilization || 0)),
                    tooltip: { valueFormatter: value => `${Math.round(Number(value) || 0)}%` }
                },
                {
                    name: 'Network MB',
                    type: 'line',
                    yAxisIndex: 1,
                    smooth: true,
                    data: points.map(point => bytesToMb((point.networkInBytes || 0) + (point.networkOutBytes || 0))),
                    tooltip: { valueFormatter: value => `${compactNumber(value)} MB` }
                },
                {
                    name: 'Disk MB',
                    type: 'line',
                    yAxisIndex: 1,
                    smooth: true,
                    data: points.map(point => bytesToMb((point.diskReadBytes || 0) + (point.diskWriteBytes || 0))),
                    tooltip: { valueFormatter: value => `${compactNumber(value)} MB` }
                }
            ]
        };
    }

    /**
     * Single-series variant of buildTrendOption used by the admin dashboard's split
     * CPU / Memory / Network mini-charts (one metric each, in one row) instead of the combined
     * multi-axis chart the non-admin dashboard still uses.
     */
    function buildSingleTrendOption(data, field, seriesName, color, unit) {
        const points = data.utilizationTrend || [];
        const labels = points.map(point => point.label);
        const isPercent = unit === 'percent';
        const values = points.map(point => extractTrendValue(point, field));
        return {
            color: [color],
            tooltip: {
                trigger: 'axis',
                confine: true,
                valueFormatter: value => isPercent ? `${Math.round(Number(value) || 0)}%` : `${compactNumber(value)} MB`
            },
            grid: { left: 36, right: 16, top: 18, bottom: 28 },
            xAxis: { type: 'category', boundaryGap: false, data: labels, axisLabel: chartTextStyle() },
            yAxis: {
                type: 'value',
                min: 0,
                max: isPercent ? 100 : undefined,
                scale: !isPercent,
                axisLabel: { ...chartTextStyle(), formatter: isPercent ? '{value}%' : compactNumber },
                splitLine: { lineStyle: { color: '#eef2f7' } }
            },
            series: [{
                name: seriesName,
                type: 'line',
                smooth: true,
                areaStyle: { opacity: 0.08 },
                data: values
            }]
        };
    }

    function extractTrendValue(point, field) {
        if (field === 'networkBytes') {
            return bytesToMb((point.networkInBytes || 0) + (point.networkOutBytes || 0));
        }
        const raw = point[field];
        return raw === null || raw === undefined ? null : Number(raw);
    }

    function buildDonutOption(values, colors) {
        const data = Object.entries(values).map(([name, value]) => ({ name, value }));
        return {
            color: colors,
            tooltip: { trigger: 'item', confine: true },
            legend: { bottom: 0, textStyle: chartTextStyle() },
            series: [{
                type: 'pie',
                radius: ['44%', '68%'],
                center: ['50%', '42%'],
                label: { formatter: '{b}\n{c}', fontSize: 11 },
                data: data.length ? data : [{ name: 'No data', value: 1 }]
            }]
        };
    }

    function buildGaugeOption(value) {
        return {
            color: ['#10b981'],
            series: [{
                type: 'gauge',
                min: 0,
                max: 100,
                progress: { show: true, width: 14 },
                axisLine: { lineStyle: { width: 14 } },
                axisTick: { show: false },
                splitLine: { show: false },
                axisLabel: { show: false },
                pointer: { width: 4 },
                detail: { formatter: '{value}%', fontSize: 24, color: '#172033' },
                title: { color: '#64748b', fontSize: 12 },
                data: [{ value, name: 'metrics' }]
            }]
        };
    }

    function buildRiskComplianceOption(rows) {
        const activeRows = rows
            .filter(row => (Number(row.count) || 0) > 0)
            .slice(0, 6);

        if (!activeRows.length) {
            return {
                graphic: {
                    type: 'text',
                    left: 'center',
                    top: 'middle',
                    style: {
                        text: 'No risk signals',
                        fill: '#64748b',
                        fontSize: 13,
                        fontWeight: 600
                    }
                }
            };
        }

        return {
            color: activeRows.map(row => riskToneColor(row.tone)),
            tooltip: {
                trigger: 'axis',
                axisPointer: { type: 'shadow' },
                confine: true,
                formatter: params => {
                    const point = params && params[0];
                    const row = point?.data?.raw || {};
                    return `
                        <strong>${Utils.escapeHtml(row.title || point?.name || '-')}</strong><br>
                        ${Utils.escapeHtml(row.description || '')}<br>
                        Count: ${Utils.escapeHtml(row.count ?? point?.value ?? 0)}
                    `;
                }
            },
            grid: { left: 82, right: 18, top: 12, bottom: 18 },
            xAxis: {
                type: 'value',
                minInterval: 1,
                axisLabel: chartTextStyle(),
                splitLine: { lineStyle: { color: '#eef2f7' } }
            },
            yAxis: {
                type: 'category',
                inverse: true,
                data: activeRows.map(row => compactRiskText(row)),
                axisLabel: chartTextStyle(),
                axisTick: { show: false },
                axisLine: { show: false }
            },
            series: [{
                type: 'bar',
                barWidth: 14,
                data: activeRows.map(row => ({
                    value: Number(row.count) || 0,
                    raw: row,
                    itemStyle: {
                        color: riskToneColor(row.tone),
                        borderRadius: [0, 6, 6, 0]
                    }
                })),
                label: {
                    show: true,
                    position: 'right',
                    color: '#334155',
                    fontSize: 11,
                    fontWeight: 700
                }
            }]
        };
    }

    function disposeCharts() {
        chartRegistry.forEach(instance => instance.dispose());
        chartRegistry.clear();
    }

    function resizeCharts() {
        chartRegistry.forEach(instance => instance.resize());
    }

    function disposeTooltips() {
        if (typeof bootstrap === 'undefined' || !bootstrap.Tooltip) return;
        document.querySelectorAll('#dashboard-view [data-bs-toggle="tooltip"]').forEach(el => {
            const existing = bootstrap.Tooltip.getInstance(el);
            if (existing) existing.dispose();
        });
    }

    function initTooltips() {
        document.querySelectorAll('#dashboard-view [data-bs-toggle="tooltip"]').forEach(el => {
            if (typeof bootstrap !== 'undefined' && bootstrap.Tooltip) {
                const existing = bootstrap.Tooltip.getInstance(el);
                if (existing) existing.dispose();
                new bootstrap.Tooltip(el, { trigger: 'hover' });
            }
        });
    }

    /** Refresh button: reload through the router so teardown runs first. */
    function refresh() {
        if (isLoading) return;
        ContentRouter.navigate('dashboard');
    }

    function setAutoRefresh(enabled) {
        autoRefreshEnabled = enabled;
        if (autoRefreshEnabled) {
            startAutoRefresh();
        } else {
            RealTime.stopPolling('dashboard');
        }
        updateAutoRefreshButton();
    }

    function startAutoRefresh() {
        // Page scoped: stops when the user leaves the dashboard.
        RealTime.startPolling('dashboard', silentRefresh, 30000, { immediate: false, pageScoped: true });
    }

    function updateAutoRefreshButton() {
        const $button = $('#dashboard-auto-refresh-toggle');
        if (!$button.length) return;
        $button.toggleClass('active', autoRefreshEnabled)
            .attr('aria-pressed', String(autoRefreshEnabled))
            .html(`
                <i class="fas ${autoRefreshEnabled ? 'fa-pause' : 'fa-play'}"></i>
                ${autoRefreshEnabled ? 'Auto On' : 'Auto Off'}
            `);
    }

    function getData() {
        return dashboardData;
    }

    function dashboardSignature(data) {
        if (!data) return '';
        const stable = {
            persona: data.persona,
            summary: data.summary,
            utilization: data.utilization,
            storage: data.storage,
            coverage: data.coverage,
            environments: data.environments,
            utilizationTrend: data.utilizationTrend,
            vmStatusCounts: data.vmStatusCounts,
            regionCounts: data.regionCounts,
            providerCounts: data.providerCounts,
            volumeTypeCounts: data.volumeTypeCounts,
            topBusyVms: data.topBusyVms,
            idleVms: data.idleVms,
            schedulerHealth: data.schedulerHealth,
            riskCompliance: data.riskCompliance,
            recommendations: data.recommendations
        };
        return JSON.stringify(stable);
    }

    function chartTextStyle() {
        return { fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif", color: '#64748b' };
    }

    function bytesToMb(bytes) {
        return Math.round((Number(bytes) || 0) / (1024 * 1024));
    }

    function compactNumber(value) {
        const numeric = Number(value) || 0;
        if (Math.abs(numeric) >= 1000) return `${(numeric / 1000).toFixed(1)}k`;
        return `${Math.round(numeric)}`;
    }

    function percent(value, total) {
        const safeTotal = Number(total) || 0;
        if (safeTotal === 0) return 0;
        return Math.round(((Number(value) || 0) / safeTotal) * 100);
    }

    function formatPercent(value) {
        if (value === null || value === undefined || value === '') return '-';
        const numeric = Number(value);
        return Number.isNaN(numeric) ? '-' : `${Math.round(numeric)}%`;
    }

    function formatGiB(value) {
        const numeric = Number(value) || 0;
        if (numeric >= 1024) return `${(numeric / 1024).toFixed(1)} TiB`;
        return `${numeric} GiB`;
    }

    function formatIdle(minutes) {
        const value = Number(minutes) || 0;
        if (value < 60) return `${value}m`;
        const hours = Math.floor(value / 60);
        const mins = value % 60;
        return mins ? `${hours}h ${mins}m` : `${hours}h`;
    }

    function formatAge(seconds) {
        const safeSeconds = Number(seconds);
        if (!Number.isFinite(safeSeconds)) return 'unknown';
        if (safeSeconds < 60) return `${Math.round(safeSeconds)}s`;
        const minutes = Math.floor(safeSeconds / 60);
        if (minutes < 60) return `${minutes}m`;
        const hours = Math.floor(minutes / 60);
        const remainingMinutes = minutes % 60;
        return remainingMinutes ? `${hours}h ${remainingMinutes}m` : `${hours}h`;
    }

    return {
        load,
        refresh,
        getData,
        _search: function(value) {
            $('#dashboard-env-search').val(value || '').trigger('input');
        }
    };
})();

Actions.register('dashboard-refresh', () => Dashboard.refresh());
