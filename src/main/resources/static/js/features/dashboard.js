/**
 * VM Self-Service Platform - Dashboard Feature
 * Role-aware dashboard backed by /api/v1/dashboard/summary. Everyone but administrators gets Home
 * (U06-T02, Dashboard.dc.html): a one-line summary, "Needs you" tiles and up to four environment
 * cards with power strips, refreshed quietly every 30 s. Administrators keep the fleet dashboard
 * until U14 replaces it.
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
            if (autoRefreshEnabled || !isAdmin(data)) {
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

    /** Auto-refresh tick: silent on errors, never renders after the user left, skips an unchanged summary. */
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
            renderDashboard(dashboardData);
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
        if (!isAdmin(data)) loadRecentActivity();
        initCharts(data);
        initTooltips();
        $(window).off('resize.dashboardCharts').on('resize.dashboardCharts', resizeCharts);
    }

    /** Runs when the user leaves the dashboard (ContentRouter.onLeave). */
    function teardown() {
        RealTime.stopPolling('dashboard');
        disposeTooltips();
        disposeCharts();
        $('#content-area').off('.dashboardJump .dashboardAutoRefresh');
        $(window).off('resize.dashboardCharts');
        isLoading = false;
    }

    function showLoading() {
        isLoading = true;
        $('#content-area').html(`
            <div class="content-view" id="dashboard-view">
                <div class="content-header">
                    <h1>${pageTitle()}</h1>
                    <p>Loading&hellip;</p>
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
                    <h1>${pageTitle()}</h1>
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

    /** Administrators see the Dashboard; everyone else Home (U06-T02). */
    function pageTitle() {
        return typeof Auth !== 'undefined' && Auth.isAdmin() ? 'Dashboard' : 'Home';
    }

    function isAdmin(data) {
        return !!data && data.persona === 'ADMIN';
    }

    function buildDashboardHtml(data) {
        if (!isAdmin(data)) return buildHomeHtml(data);
        const admin = true;
        const subtitle = admin ? 'Global AWS fleet overview' : 'Overview of your environments and VMs';
        return `
            <div class="content-view dashboard-persona ${admin ? 'dashboard-admin' : 'dashboard-user'}" id="dashboard-view">
                <div class="content-header d-flex justify-content-between align-items-start flex-shrink-0">
                    <div>
                        <h1>Dashboard</h1>
                        <p>${subtitle}</p>
                    </div>
                    <div class="dashboard-header-actions">
                        <button class="btn btn-ghost btn-sm" data-action="dashboard-refresh" title="Reload now">
                            <i class="fas fa-sync" aria-hidden="true"></i> Refresh
                        </button>
                        <button class="btn btn-ghost btn-sm ${autoRefreshEnabled ? 'active' : ''}"
                                id="dashboard-auto-refresh-toggle"
                                type="button"
                                aria-pressed="${autoRefreshEnabled}"
                                title="Reload every 30 seconds">
                            ${autoRefreshLabel()}
                        </button>
                    </div>
                </div>

                <div class="dashboard-body" tabindex="0" role="region" aria-label="Dashboard">
                    ${buildMetricStrip(data)}
                    ${buildAdminDashboard(data)}
                </div>
            </div>
        `;
    }

    function autoRefreshLabel() {
        return `<i class="fas ${autoRefreshEnabled ? 'fa-pause' : 'fa-play'}" aria-hidden="true"></i> ${autoRefreshEnabled ? 'Auto On' : 'Auto Off'}`;
    }

    function buildMetricStrip(data) {
        const summary = data.summary || {};
        const storage = data.storage || {};
        const coverage = data.coverage || {};
        // Administrators only; Home has no tiles (U06-T02).
        return `
            <div class="dashboard-kpi-grid">
                ${buildKpi('Environments', summary.environments, 'fa-layer-group', 'Environments you can see')}
                ${buildKpi('Total VMs', summary.totalVms, 'fa-server', 'Registered VMs in those environments')}
                ${buildKpi('Running', summary.runningVms, 'fa-play-circle', `${percent(summary.runningVms, summary.totalVms)}% of your VMs`)}
                ${buildKpi('Idle', summary.idleVms, 'fa-moon', 'Running VMs that have been barely used')}
                ${buildKpi('Storage', formatGiB(storage.totalAllocatedStorageGib), 'fa-hard-drive', 'Allocated EBS storage')}
                ${buildKpi('Coverage', `${coverage.metricsPercent || 0}%`, 'fa-signal', 'Share of VMs with recent metric samples')}
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

    // ---------------------------------------------------------------- Home (U06-T02)

    function money(value) {
        if (value === null || value === undefined || value === '') return '—';
        const amount = Number(value);
        return Number.isFinite(amount)
            ? '$' + amount.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '—';
    }

    function buildHomeHtml(data) {
        const cards = data.environments || [];
        const t = HomeModel.totals(cards);
        const summary = cards.length
            ? Utils.html`<span class="mono">${t.running}</span> of <span class="mono">${t.total}</span> VMs running across `
                + Utils.escapeHtml(`${t.environments} environment${t.environments === 1 ? '' : 's'}`)
                + Utils.html` · <span class="mono">${money(data.summary && data.summary.monthToDateCost)}</span> this month`
            : 'Nothing to show yet.';
        return `
            <div class="content-view page home-page" id="dashboard-view">
                ${PageHeader.markup({
                    title: 'Home',
                    summaryHtml: summary,
                    actionsHtml: '<a class="btn btn-secondary" href="#/request-access">Request access to an environment</a>',
                })}
                ${buildNeedsYou(data.needsYou || [])}
                ${cards.length ? buildHomeCards(cards) : buildHomeEmpty()}
                ${buildHomeLower(data.idleVms || [])}
            </div>
        `;
    }

    // ---------------------------------------------------------------- Home's lower panels (U06-T03)

    let recentCache = null; // {at, items} or {at, error}
    const RECENT_FRESH_MS = 60000;

    function buildHomeLower(idleVms) {
        const rows = HomeModel.idleRows(idleVms, 2);
        const idle = rows.length ? `
            <section class="home-panel home-idle" aria-labelledby="home-idle-title">
                <h2 id="home-idle-title">Running but barely used</h2>
                <p class="home-panel-sub">CPU, network and disk have been nearly idle. Stopping them saves money.</p>
                ${rows.map(vm => {
                    const cpu = vm.cpuUtilization === null || vm.cpuUtilization === undefined
                        ? '' : `${Number(vm.cpuUtilization).toFixed(1)}%`;
                    const rate = vm.hourlyRate === null || vm.hourlyRate === undefined
                        ? '' : Utils.html`<span class="mono home-idle-rate">${money(vm.hourlyRate)}/h</span>`;
                    return Utils.html`<div class="home-row">
                            <span class="home-row-main"><b>${vm.name}</b> <span class="home-row-sub">· ${vm.environmentName}${cpu ? ' · CPU' : ''}</span>`
                        + (cpu ? Utils.html` <span class="mono">${cpu}</span>` : '') + '</span>'
                        + rate
                        + Utils.html`<a class="home-row-link" href="#/environments/${encodeURIComponent(vm.environmentId)}?vm=${encodeURIComponent(vm.vmId)}"
                                aria-label="Review ${vm.name}">Review</a>
                        </div>`;
                }).join('')}
            </section>` : '';
        return `
            <div class="home-lower${idle ? '' : ' home-lower-single'}">
                ${idle}
                <section class="home-panel home-recent" aria-labelledby="home-recent-title">
                    <div class="home-panel-head">
                        <h2 id="home-recent-title">Your recent activity</h2>
                        <button type="button" class="btn btn-link home-panel-link" data-action="home-all-activity">All activity</button>
                    </div>
                    <div id="home-recent-body" aria-live="polite"><p class="home-panel-sub">Loading&hellip;</p></div>
                </section>
            </div>
        `;
    }

    /** Fill "Your recent activity" from the last four entries; reused for a minute across quiet refreshes. */
    function loadRecentActivity() {
        if (recentCache && Date.now() - recentCache.at < RECENT_FRESH_MS) {
            fillRecent(recentCache);
            return;
        }
        const t = ContentRouter.token();
        ApiClient.get(Config.API.users.myActivity(4), { suppressGlobalError: true })
            .done(items => {
                recentCache = { at: Date.now(), items: Array.isArray(items) ? items.slice(0, 4) : [] };
                if (ContentRouter.isCurrent(t)) fillRecent(recentCache);
            })
            .fail(() => {
                if (ContentRouter.isCurrent(t)) fillRecent({ error: true });
            });
    }

    function fillRecent(state) {
        const $body = $('#home-recent-body');
        if (!$body.length) return;
        if (state.error) {
            $body.html('<p class="home-panel-sub">Activity is not available right now.</p>');
            return;
        }
        if (!state.items.length) {
            $body.html('<p class="home-panel-sub">Nothing yet. Starts, stops and access changes show up here.</p>');
            return;
        }
        const now = new Date();
        $body.html(state.items.map(item => {
            const result = HomeModel.resultLabel(item);
            return Utils.html`<div class="home-row">
                    <span class="mono home-row-time">${HomeModel.activityTime(item.occurredAt, now)}</span>
                    <span class="home-row-main">${HomeModel.activityText(item)}</span>`
                + (result ? Utils.html`<span class="home-result home-result-${result.kind}">${result.label}</span>` : '')
                + '</div>';
        }).join(''));
    }

    // Two tiles as in Dashboard.dc.html; the rest are counted in a link to My account (U06-T06).
    const NEEDS_YOU_TILES = 2;

    function buildNeedsYou(items) {
        const now = new Date();
        const all = items.map(item => HomeModel.needsYouItem(item, now)).filter(Boolean);
        const tiles = all.slice(0, NEEDS_YOU_TILES).map(tile => {
            const text = Utils.html`<span class="needs-you-text">${tile.before}<b>${tile.environment}</b>${tile.middle}`
                + Utils.html`<span class="mono">${tile.at}</span>${tile.after}</span>`
                + Utils.html`<b class="needs-you-action">${tile.action}</b>`;
            const dot = '<span class="needs-you-dot" aria-hidden="true"></span>';
            return tile.href
                ? Utils.html`<a class="needs-you-tile" href="${tile.href}">` + dot + text + '</a>'
                : Utils.html`<button type="button" class="needs-you-tile" data-action="home-extend-access" data-access-id="${tile.extend}">`
                    + dot + text + '</button>';
        });
        if (!tiles.length) return '';
        const rest = all.length - tiles.length;
        const more = rest > 0
            ? `<button type="button" class="btn btn-link needs-you-more" data-action="home-needs-more">${rest} more ending soon</button>`
            : '';
        return `<section class="needs-you" aria-label="Needs you">${tiles.join('')}${more}</section>`;
    }

    function buildHomeCards(cards) {
        const now = new Date();
        const items = HomeModel.pickCards(cards, 4).map(card => {
            const chip = HomeModel.chip(card);
            const strip = PowerStrip.render(PowerStrip.fromGroupStates(card.groupStates || []), { large: true });
            return Utils.html`<a class="home-card" href="#/environments/${encodeURIComponent(card.environmentId)}">
                    <div class="home-card-head">
                        <div class="home-card-names">
                            <div class="home-card-name">${card.displayName || card.name}</div>
                            <div class="home-card-slug mono">${card.name}</div>
                        </div>
                        <span class="chip chip-${chip.kind}">${chip.label}</span>
                    </div>`
                + strip
                + Utils.html`<div class="home-card-line">${HomeModel.cardLine(card, now)}</div>
                    <div class="home-card-foot">
                        <span><span class="mono home-card-cost">${money(card.monthToDateCost)}</span> this month</span>
                        <span class="home-card-access">${HomeModel.accessLabel(card.myAccess)}</span>
                    </div>
                </a>`;
        });
        return `
            <section class="home-envs" aria-labelledby="home-envs-title">
                <div class="home-envs-head">
                    <h2 id="home-envs-title">Your environments</h2>
                    <a href="#/my-environments">See all as a list</a>
                </div>
                <div class="home-grid">${items.join('')}</div>
            </section>
        `;
    }

    function buildHomeEmpty() {
        return `
            <section class="home-empty panel" aria-labelledby="home-empty-title">
                <h2 id="home-empty-title">You have no environments yet.</h2>
                <p>Ask for access and an environment admin will review your request.</p>
                <a class="btn btn-primary" href="#/request-access">Request access to an environment</a>
            </section>
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

    function buildSchedulerHealthPanel(rows, prominent = false) {
        const visibleRows = prominent ? rows : rows.slice(0, 4);
        // Every job, its own schedule and (admins only) the instance holding its lock (E12-T05).
        const body = visibleRows.length ? visibleRows.map(row => {
            const ran = row.freshnessSeconds === null || row.freshnessSeconds === undefined
                ? (row.status === 'DISABLED' ? 'switched off' : 'never ran')
                : `${formatAge(row.freshnessSeconds)} ago`;
            return Utils.html`
            <div class="dashboard-scheduler-row" title="${row.owner ? 'Lock owner: ' + row.owner : ''}">
                <span class="dashboard-dot ${schedulerTone(row.status)}"></span>
                <div>
                    <strong>${row.name || '-'}</strong>
                    <small>${ran} · ${formatInterval(row.expectedIntervalSeconds)}</small>
                </div>
                <em class="${schedulerTone(row.status)}">${row.status || 'UNKNOWN'}</em>
            </div>`;
        }).join('') : `<div class="dashboard-empty-small">No scheduler data yet</div>`;

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

    /** "every 5 min", "hourly", "daily", "weekly" from an interval in seconds. */
    function formatInterval(seconds) {
        if (!seconds) return '';
        if (seconds >= 7 * 86400) return 'weekly';
        if (seconds >= 86400) return 'daily';
        if (seconds === 3600) return 'hourly';
        if (seconds >= 3600) return `every ${Math.round(seconds / 3600)} h`;
        return `every ${Math.max(1, Math.round(seconds / 60))} min`;
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

    }

    function initCharts(data) {
        if (!window.echarts) return;
        chart('dash-util-trend', buildTrendOption(data));
        chart('dash-util-cpu', buildSingleTrendOption(data, 'cpuUtilization', 'CPU %', '#2563eb', 'percent'));
        chart('dash-util-memory', buildSingleTrendOption(data, 'memoryUtilization', 'Memory %', '#7c3aed', 'percent'));
        chart('dash-util-network', buildSingleTrendOption(data, 'networkBytes', 'Network MB', '#10b981', 'mb'));
        chart('dash-vm-state', buildDonutOption(data.vmStatusCounts || {}, ['#10b981', '#64748b', '#f59e0b', '#ef4444'], true));
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

    // VM states keep one colour each wherever they appear (green only ever means running).
    const STATE_COLORS = { RUNNING: '#10b981', STOPPED: '#94a3b8', STARTING: '#3b82f6', STOPPING: '#f59e0b',
        PENDING: '#3b82f6', ERROR: '#ef4444', UNKNOWN: '#cbd5e1' };

    function stateLabel(key) {
        const k = String(key || '');
        return k.charAt(0) + k.slice(1).toLowerCase().replace(/_/g, ' ');
    }

    function buildDonutOption(values, colors, byState) {
        const data = Object.entries(values).map(([name, value]) => byState
            ? { name: stateLabel(name), value, itemStyle: STATE_COLORS[name] ? { color: STATE_COLORS[name] } : undefined }
            : { name, value });
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
                ${autoRefreshLabel()}
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
            recommendations: data.recommendations,
            needsYou: data.needsYou
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
        getData
    };
})();

Actions.register('dashboard-refresh', () => Dashboard.refresh());
// Home's "Ask for more time": My Account on the Access tab with that grant's extension form (U06-T02).
Actions.register('home-all-activity', () => MyAccount.open('activity'));
Actions.register('home-needs-more', () => MyAccount.open('access'));
Actions.register('home-extend-access', el => MyAccount.open('access', { extend: el.getAttribute('data-access-id') }));
