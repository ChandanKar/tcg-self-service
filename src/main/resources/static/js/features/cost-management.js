/**
 * Cost Management Feature Module (Admin only)
 * Estimated fleet spend: KPI summary, spend breakdowns, idle waste, rightsizing
 * candidates and a spend trend — backed by /api/v1/cost-management/**.
 * U15-T01 (CostOverview.dc.html): tabs Overview / Rightsizing / Commitments / Carbon / Unusual spend
 * (?tab=, Carbon only while it is on) with Setup at the end; only the open tab's data is asked for.
 * The toolbar is two menus: Data actions (fill in the last 30 days, write cost tags, fetch AWS
 * billing; each confirms first, switched-off ones hidden) and Export (this page as PDF or PNG, VM
 * cost detail as Excel, the month for finance as a FOCUS CSV).
 * U15-T02: Overview has five figures (month to date with its share from AWS billing, the month-end
 * forecast with AWS's, idle waste, rightsizing, priced VMs), daily spend over 14 days / 90 days /
 * 18 months, spend by environment, team, account or VM type as bars (?by=), and the biggest savings
 * to act on across rightsizing and idle VMs.
 */
const CostManagement = (function() {
    'use strict';

    const PAGE_SIZE = 10;
    /** The Rightsizing tab lists 8 suggestions a page (U15-T03). */
    const RS_PAGE_SIZE = 8;
    const TREND_DAYS = 90;
    const EXPORT_SIZE = 100000;

    // Fixed categorical order — never cycled/reassigned per filter. Last color is reserved for
    // the "Other" residual bucket so it always reads as neutral rather than competing for attention.
    const CATEGORICAL_COLORS = ['#2563eb', '#059669', '#d97706', '#7c3aed', '#0891b2', '#db2777', '#65a30d', '#0d9488', '#94a3b8'];

    let chartRegistry = new Map();
    let exporting = false;

    /** The tabs, their panels in page order, and the feature a tab needs (U15-T01). */
    const TABS = [
        { key: 'overview', label: 'Overview', panels: ['kpi', 'trend', 'breakdown', 'savings', 'workloads', 'idle', 'reconciliation'] },
        { key: 'rightsizing', label: 'Rightsizing', panels: ['rightsizing', 'spot'] },
        { key: 'commitments', label: 'Commitments', panels: ['commitments'], feature: 'costReservations' },
        { key: 'carbon', label: 'Carbon', panels: ['carbon'], feature: 'carbon' },
        { key: 'anomalies', label: 'Unusual spend', panels: ['anomalies'] }
    ];
    /** Panels drawn side by side on a wide screen. */
    const PAIRS = [['trend', 'breakdown']];
    const BREAKDOWN_BY = [['environment', 'Environment'], ['team', 'Team'], ['account', 'Account'], ['type', 'VM type']];
    const BAR_ROWS = 6;

    function tabsShown() {
        return TABS.filter(t => !t.feature || (typeof Capabilities !== 'undefined' && Capabilities.has(t.feature)));
    }

    function tabOf(key) {
        return tabsShown().find(t => t.key === key) || tabsShown()[0];
    }

    /** The data keys behind a tab's panels. */
    function tabKeys(tab) {
        return [...new Set(tab.panels.flatMap(name => PANELS[name].keys))];
    }

    let state = {
        /** The open tab and the tabs whose data has been asked for (U15-T01). */
        tab: 'overview',
        loadedTabs: {},
        summary: null,
        spendByEnvironment: [],
        spendByAccount: [],
        eksEnvironments: [],
        commitments: null,
        // E37-T03: carbon by environment for carbonMonth (null: the newest month with data); null while off
        carbon: null,
        carbonMonth: null,
        spendByVmType: [],
        teamTrend: [],
        trend: [],
        forecast: null,
        // E35-T02: '14 days' (the trend with both forecasts) or '18 months' (AWS's monthly forecast)
        forecastRange: '90',
        /** Spend broken down by environment, team, account or type (U15-T02). */
        breakdownBy: 'environment',
        longRange: null,
        reconciliation: emptyPageState(),
        reconciliationAll: [],
        idle: emptyPageState(),
        rightsizing: emptyPageState(),
        /** Kind of change ('' all, SMALLER, NEWER, GRAVITON, LARGER) and agreement ('' or AWS), U15-T03. */
        rightsizingKind: '',
        rightsizingAgreement: '',
        /** The first page of every suggestion, for Overview's biggest savings (U15-T02). */
        rightsizingTop: emptyPageState(),
        anomalies: [],
        spotOpportunities: [],
        anomalyMonitors: [],
        // Requests that failed, by key: their panels show an error with Retry (E08-T09).
        errors: {}
    };

    function emptyPageState() {
        return { content: [], totalPages: 0, totalElements: 0, page: 0 };
    }

    // Navigation token of the current visit (see the page contract in core/router.js).
    let pageToken = null;

    function isActive() {
        return pageToken !== null && ContentRouter.isCurrent(pageToken);
    }

    /** Router loader. */
    function load() {
        pageToken = ContentRouter.token();
        ContentRouter.onLeave(teardown);
        if (!Auth.isAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3">Access denied. Admin only.</div>');
            return;
        }
        state.loadedTabs = {};
        restorePageStateFromHash();
        showLoading();
        fetchAll();
    }

    /** Runs when the user leaves Cost Management (ContentRouter.onLeave). */
    function teardown() {
        disposeCharts();
        $(window).off('resize.costManagementCharts');
        $('#content-area').off('click.rightsizingApply click.costRetry');
    }

    // ---- hash-based sub-context (per-table page survives refresh/bookmark) ----

    function restorePageStateFromHash() {
        const params = ContentRouter.query();
        state.tab = tabOf(params.tab).key;
        state.breakdownBy = BREAKDOWN_BY.some(([k]) => k === params.by) ? params.by : 'environment';
        state.idle.page = parsePageParam(params.idle);
        state.rightsizing.page = parsePageParam(params.rightsizing);
        state.rightsizingKind = RightsizingKind.FILTERS.some(([k]) => k && k === params.kind) ? params.kind : '';
        state.rightsizingAgreement = params.agreement === 'AWS' ? 'AWS' : '';
        state.reconciliation.page = parsePageParam(params.reconciliation);
    }

    function parsePageParam(value) {
        const n = parseInt(value, 10);
        return Number.isFinite(n) && n >= 0 ? n : 0;
    }

    function syncHash() {
        const params = new URLSearchParams();
        if (state.tab !== 'overview') params.set('tab', state.tab);
        if (state.breakdownBy !== 'environment') params.set('by', state.breakdownBy);
        if (state.idle.page) params.set('idle', state.idle.page);
        if (state.rightsizing.page) params.set('rightsizing', state.rightsizing.page);
        if (state.rightsizingKind) params.set('kind', state.rightsizingKind);
        if (state.rightsizingAgreement) params.set('agreement', state.rightsizingAgreement);
        if (state.reconciliation.page) params.set('reconciliation', state.reconciliation.page);
        const query = params.toString();
        const hash = '#/cost-management' + (query ? `?${query}` : '');
        if (window.location.hash !== hash) {
            history.replaceState(null, '', hash);
        }
    }

    // ---- data loading ----

    function fetchJson(url, options) {
        return new Promise((resolve, reject) => {
            ApiClient.get(url, options).done(resolve).fail(reject);
        });
    }

    // Panel requests show their own error with Retry; no global toast for a server error.
    const PANEL_REQUEST = { quietServerErrors: true };
    // A feature that may be switched off (404): no toast at all.
    const OPTIONAL_REQUEST = { quietServerErrors: true, suppressGlobalError: true };

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

    // Client-side paging for endpoints that return the full list already (reconciliation is one
    // row per environment — small and bounded, unlike the per-VM tables — so it's cheaper to slice
    // in the browser than to add server-side Pageable support for what's already an in-memory
    // aggregation on the backend).
    function paginateArray(items, page, size) {
        const totalElements = items.length;
        const totalPages = size > 0 ? Math.ceil(totalElements / size) : 0;
        const safePage = Math.min(Math.max(page, 0), Math.max(totalPages - 1, 0));
        return {
            content: items.slice(safePage * size, safePage * size + size),
            totalPages,
            totalElements,
            page: safePage
        };
    }

    /**
     * Every request behind the page, by key (E08-T09). apply() stores a successful response;
     * a failed one only marks state.errors[key], so one bad endpoint never blanks the page.
     */
    const REQUESTS = {
        summary: { url: () => Config.API.costManagement.summary, apply: d => { state.summary = d; } },
        spendByEnvironment: { url: () => Config.API.costManagement.spendByEnvironment, apply: d => { state.spendByEnvironment = d || []; } },
        spendByVmType: { url: () => Config.API.costManagement.spendByVmType, apply: d => { state.spendByVmType = d || []; } },
        spendByAccount: { url: () => Config.API.costManagement.spendByAccount, apply: d => { state.spendByAccount = d || []; } },
        teamTrend: { url: () => Config.API.costManagement.spendTrendByTeam(TREND_DAYS), apply: d => { state.teamTrend = d || []; } },
        trend: { url: () => Config.API.costManagement.spendTrend(TREND_DAYS), apply: d => { state.trend = d || []; } },
        reconciliation: {
            url: () => Config.API.costManagement.reconciliation(TREND_DAYS),
            apply: d => {
                state.reconciliationAll = d || [];
                state.reconciliation = paginateArray(state.reconciliationAll, state.reconciliation.page || 0, PAGE_SIZE);
            }
        },
        commitments: { url: () => Config.API.costManagement.commitmentInsights, feature: 'costReservations',
            apply: d => { state.commitments = d || null; } },
        // 404 while carbon is off: the panel stays hidden instead of showing an error
        carbon: { url: () => Config.API.costManagement.carbon(state.carbonMonth), optional: true, feature: 'carbon',
            apply: d => { state.carbon = d && d.month ? d : null; } },
        forecast: { url: () => Config.API.costManagement.forecast(30, 14), apply: d => { state.forecast = d || null; } },
        idle: { url: () => Config.API.costManagement.idleWaste(state.idle.page, PAGE_SIZE), apply: d => { state.idle = mapPage(d); } },
        rightsizing: { url: () => rightsizingUrl(state.rightsizing.page), apply: d => { state.rightsizing = mapPage(d); } },
        rightsizingTop: { url: () => Config.API.costManagement.rightsizing(0, PAGE_SIZE), apply: d => { state.rightsizingTop = mapPage(d); } },
        anomalies: { url: () => Config.API.costAnomalies.list(ANOMALY_DAYS), apply: d => { state.anomalies = (d && d.content) || []; } },
        // Spot savings need captured Spot prices: not asked for while spot.pricing.enabled is off (U15-T03).
        spotOpportunities: { url: () => Config.API.costManagement.spotOpportunities(0, 100), feature: 'spotPricing',
            apply: d => { state.spotOpportunities = (d && d.content) || []; } },
        anomalyMonitors: { url: () => Config.API.costAnomalies.monitors, apply: d => { state.anomalyMonitors = d || []; } },
        eksEnvironments: {
            url: () => Config.API.environments.list,
            apply: d => {
                const list = Array.isArray(d) ? d : ((d && d.content) || []);
                state.eksEnvironments = list.filter(e => String(e.serviceType || '').toUpperCase() === 'EKS');
            }
        }
    };

    /** Days of anomalies the Anomalies panel lists (E21-T04). */
    const ANOMALY_DAYS = 30;

    /** Fetch the given keys; resolves once all settled, recording failures in state.errors. */
    function fetchKeys(requested) {
        // Data of switched-off features is not asked for (U02-T03); its panel stays hidden as on a 404.
        const keys = requested.filter(key => {
            const feature = REQUESTS[key].feature;
            if (feature && (typeof Capabilities === 'undefined' || !Capabilities.has(feature))) {
                REQUESTS[key].apply(null);
                return false;
            }
            return true;
        });
        return Promise.allSettled(keys.map(key => fetchJson(REQUESTS[key].url(),
                REQUESTS[key].optional ? OPTIONAL_REQUEST : PANEL_REQUEST))).then(results => {
            results.forEach((result, i) => {
                const key = keys[i];
                if (result.status === 'rejected' && REQUESTS[key].optional && result.reason && result.reason.status === 404) {
                    delete state.errors[key];
                    REQUESTS[key].apply(null);
                    return;
                }
                if (result.status === 'fulfilled') {
                    delete state.errors[key];
                    REQUESTS[key].apply(result.value);
                } else {
                    state.errors[key] = true;
                    console.error(`Failed to load cost data (${key}):`, result.reason);
                }
            });
            return results;
        });
    }

    /** Loads the open tab's data, then draws the page (U15-T01: the other tabs wait until opened). */
    function fetchAll() {
        const token = ContentRouter.token();
        const tab = tabOf(state.tab);
        state.errors = {};
        const keys = tabKeys(tab);
        fetchKeys(keys).then(() => {
            if (!ContentRouter.isCurrent(token) || !isActive()) return;
            // A feature that answers 404 is off, not failed (the Carbon tab's only key, U15-T01). The whole
            // page fails only when several requests all did; one failed request is its panel's, with Retry (U15-T04).
            if (keys.length > 1 && keys.every(key => state.errors[key])) {
                showError('Failed to load cost data. Please try again.');
                return;
            }
            state.loadedTabs[tab.key] = true;
            render();
        });
    }

    /** Opens a tab: its data the first time, then its panels and charts. */
    function openTab(key) {
        const tab = tabOf(key);
        state.tab = tab.key;
        syncHash();
        if (state.loadedTabs[tab.key]) {
            drawTab(tab);
            return;
        }
        $(`#cost-tabs-panel-${tab.key}`).html('<p class="cost-note">Loading&hellip;</p>');
        const token = pageToken;
        fetchKeys(tabKeys(tab)).then(() => {
            if (!ContentRouter.isCurrent(token) || !isActive()) return;
            state.loadedTabs[tab.key] = true;
            if (state.tab === tab.key) drawTab(tab);
        });
    }

    function tabHtml(tab) {
        let html = '';
        tab.panels.forEach(name => {
            const pair = PAIRS.find(p => p.includes(name) && p.every(n => tab.panels.includes(n)));
            if (pair) {
                if (name === pair[0]) html += `<div class="cost-pair">${pair.map(panelSlot).join('')}</div>`;
                return;
            }
            html += panelSlot(name);
        });
        return html;
    }

    /** Draws one tab's panels and charts (the tab is visible, so its charts get their size). */
    function drawTab(tab) {
        tab.panels.forEach(name => (PANELS[name].charts ? PANELS[name].charts() : []).forEach(([id]) => disposeChart(id)));
        $(`#cost-tabs-panel-${tab.key}`).html(tabHtml(tab));
        initCharts(tab);
        tab.panels.forEach(name => {
            if (!panelFailed(name) && PANELS[name].after) PANELS[name].after();
        });
    }

    const TABLE_CONFIG = {
        idle: { url: (p) => Config.API.costManagement.idleWaste(p, PAGE_SIZE), render: () => renderIdleTable() },
        rightsizing: { url: (p) => rightsizingUrl(p), render: () => renderRightsizingTable() },
        reconciliation: { clientPaged: true, render: () => renderReconciliationTable() }
    };

    function changePage(tableKey, page) {
        if (page < 0) return;
        const cfg = TABLE_CONFIG[tableKey];
        if (!cfg) return;
        if (cfg.clientPaged) {
            state[tableKey] = paginateArray(state[tableKey + 'All'], page, PAGE_SIZE);
            syncHash();
            cfg.render();
            return;
        }
        fetchJson(cfg.url(page)).then(pageData => {
            if (!isActive()) return; // syncHash would otherwise rewrite the URL of the page the user moved to
            state[tableKey] = mapPage(pageData);
            syncHash();
            cfg.render();
        }).catch(error => {
            if (!isActive()) return;
            console.error(`Failed to load ${tableKey} page:`, error);
            Notifications.error('Failed to load page. Please try again.');
        });
    }

    // ---- render ----

    function showLoading() {
        $('#content-area').html(`
            <div class="content-view" id="cost-management-view">
                <div class="content-header">
                    <h1>Cost management</h1>
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
                    <h1>Cost management</h1>
                    <p>Estimated spend across the fleet</p>
                </div>
                <div class="error-state">
                    <i class="fas fa-exclamation-triangle fa-3x text-warning"></i>
                    <h4 class="mt-3">Unable to Load Cost Data</h4>
                    <p>${Utils.escapeHtml(message)}</p>
                    <button class="btn btn-primary" data-action="cost-reload">
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
        const tab = tabOf(state.tab);
        initCharts(tab);
        tab.panels.forEach(name => {
            if (!panelFailed(name) && PANELS[name].after) PANELS[name].after();
        });
        if (typeof Tabs !== 'undefined') Tabs.bind(document.getElementById('cost-tabs'), key => openTab(key));
    }

    function capable(feature) {
        return typeof Capabilities !== 'undefined' && Capabilities.has(feature);
    }

    function buildPageHtml() {
        const tabs = tabsShown();
        const active = tabOf(state.tab).key;
        const dataMenu = Menu.markup({ id: 'cost-data-menu', label: 'Data actions', items: [
            { id: 'cost-backfill-btn', text: 'Fill in the last 30 days' },
            { id: 'cost-reconcile-tags-btn', text: 'Write cost tags now', hidden: !capable('costTagging') },
            { id: 'cost-ingest-actuals-btn', text: 'Fetch AWS billing now', hidden: !capable('costActuals') }] });
        const exportMenu = Menu.markup({ id: 'cost-export-menu', label: 'Export', items: [
            { id: 'cost-export-pdf-btn', text: 'This page as PDF' },
            { id: 'cost-export-png-btn', text: 'This page as PNG' },
            { id: 'cost-export-detail', text: 'VM cost detail, Excel', href: Config.API.costManagement.vmDetailExport(), download: `vm-cost-detail-${todayStamp()}.xlsx` },
            { text: 'Month for finance, FOCUS CSV…', action: 'cost-focus-export' }] });
        return `
            <div class="content-view cost-management-view" id="cost-management-view">
                ${PageHeader.markup({ title: 'Cost management',
                    summaryHtml: 'Estimated from run time and AWS list prices; replaced by AWS billing figures where they exist.',
                    actionsHtml: `<div class="cost-toolbar no-print">${dataMenu}${exportMenu}</div>`,
                    helpPage: typeof HelpLink !== 'undefined' ? 'cost-management' : null, helpLabel: 'Cost management' })}
                <div class="cost-tabs-row no-print">
                    ${Tabs.markup({ id: 'cost-tabs', label: 'Cost', active, tabs: tabs.map(t => ({ key: t.key, label: t.label })) })}
                    <a class="cost-setup-link" href="#/cost-setup">Setup</a>
                </div>
                <div class="cost-report" id="cost-report">
                    ${tabs.map(t => Tabs.panel({ id: 'cost-tabs', key: t.key, active }, t.key === active ? tabHtml(t) : '')).join('')}
                </div>
            </div>
        `;
    }

    // ---- panels: each renders on its own, or an error with Retry (E08-T09) ----

    const PANELS = {
        kpi: { keys: ['summary'], label: 'the cost summary', build: () => buildKpiStrip(state.summary) },
        breakdown: {
            keys: ['spendByEnvironment', 'spendByVmType', 'spendByAccount', 'teamTrend'], label: 'spend by environment',
            build: () => buildBreakdown()
        },
        savings: { keys: ['rightsizingTop', 'idle'], label: 'the biggest savings', build: () => buildTopSavings() },
        trend: {
            keys: ['trend', 'forecast'], label: 'the spend trend', chartPanel: true, wide: true,
            build: () => buildTrendChart(),
            charts: () => [['cost-chart-trend', state.forecastRange === 'long' && state.longRange
                ? buildLongRangeOption(state.longRange) : buildTrendOption(state.trend)]]
        },
        commitments: { keys: ['commitments'], label: 'commitment insights', build: () => buildCommitmentsPanel() },
        reconciliation: { keys: ['reconciliation'], label: 'estimated vs. actual cost', build: () => buildReconciliationTable() },
        idle: { keys: ['idle'], label: 'idle VMs', build: () => buildIdleWasteTable() },
        rightsizing: { keys: ['rightsizing', 'summary'], label: 'rightsizing suggestions', build: () => buildRightsizingTable() },
        spot: { keys: ['spotOpportunities'], label: 'Spot opportunities', build: () => buildSpotPanel() },
        carbon: { keys: ['carbon'], label: 'carbon footprint', build: () => buildCarbonPanel() },
        anomalies: { keys: ['anomalies', 'anomalyMonitors'], label: 'cost anomalies', build: () => buildAnomaliesPanel() },
        workloads: {
            keys: ['eksEnvironments'], label: 'EKS workload cost', build: () => buildWorkloadsPanel(),
            after: () => mountWorkloads()
        }
    };

    // ---- carbon (E37-T03): attributed by cost share, published monthly by AWS with a delay ----
    function kg(v) {
        if (v === null || v === undefined) return '<span class="text-muted">-</span>';
        const n = Number(v);
        return Utils.escapeHtml(n >= 100 ? Math.round(n).toLocaleString() : n.toLocaleString(undefined, { maximumFractionDigits: 2 }));
    }

    function monthLabel(m) {
        return new Date(m + '-01T00:00:00Z').toLocaleDateString([], { month: 'long', year: 'numeric', timeZone: 'UTC' });
    }

    const MONTHS_SHORT = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

    /** The last six months of the trend as stacked bars: matched and not matched, heights in px of `height`. */
    function carbonBars(trend, height = 54) {
        const last = (trend || []).slice(-6).map(p => ({ month: p.month, at: Number(p.attributedKg) || 0, un: Number(p.unallocatedKg) || 0 }));
        const most = Math.max(1, ...last.map(p => p.at + p.un));
        return last.map(p => ({ label: MONTHS_SHORT[Number(p.month.slice(5, 7)) - 1] || p.month, total: p.at + p.un,
            at: Math.round(p.at / most * height), un: Math.round(p.un / most * height) }));
    }

    function buildCarbonPanel() {
        const c = state.carbon;
        // On its own tab (U15-T01) an empty panel says why instead of leaving the tab blank.
        if (!c) return '<p class="cost-note" id="cost-carbon-empty">No carbon figures yet. AWS publishes them once a month, a few months behind.</p>';
        const options = (c.availableMonths || []).map(m =>
            `<option value="${Utils.escapeHtml(m)}"${m === c.month ? ' selected' : ''}>${Utils.escapeHtml(monthLabel(m))}</option>`).join('');
        const rows = (c.rows || []).slice().sort((a, b) => Number(a.unallocated) - Number(b.unallocated));
        const most = Math.max(1, ...rows.map(r => Number(r.mbmKg) || 0));
        const body = rows.map(r => `
            <tr class="${r.unallocated ? 'carbon-unallocated' : ''}" data-carbon-env="${Utils.escapeHtml(r.environmentId || 'unallocated')}">
                <td class="carbon-env">${r.unallocated ? 'Not matched to an environment' : Utils.escapeHtml(r.environmentName)}</td>
                <td><span class="carbon-bar-cell"><span class="carbon-bar"><span style="width:${((Number(r.mbmKg) || 0) / most * 100).toFixed(1)}%"></span></span>
                    <span class="cost-mono">${kg(r.mbmKg)}</span></span></td>
                <td class="cost-num cost-mono">${kg(r.lbmKg)}</td>
                <td class="cost-num cost-mono">${r.cost == null ? '<span class="text-muted">—</span>' : money0(r.cost)}</td>
                <td class="cost-num cost-mono">${r.kgPerDollar == null ? '<span class="text-muted">—</span>' : (Number(r.kgPerDollar) * 100).toFixed(1)}</td>
            </tr>`).join('');
        const bars = carbonBars(c.trend);
        const strip = bars.length ? `<div class="cost-kpi carbon-strip" role="img"
                aria-label="Emissions over the last ${bars.length} months, from ${Math.round(bars[0].total).toLocaleString()} to ${Math.round(bars[bars.length - 1].total).toLocaleString()} kg">
                <span class="cost-kpi-label">Last ${bars.length} months</span>
                <span class="carbon-strip-bars">${bars.map(b => `<span class="carbon-strip-col"><span class="carbon-strip-un" style="height:${b.un}px"></span>
                    <span class="carbon-strip-at" style="height:${b.at}px"></span><span class="carbon-strip-label">${b.label}</span></span>`).join('')}</span>
            </div>` : '';
        const figure = (key, label, value, unit, note) => `<div class="cost-kpi" data-kpi="${key}"><span class="cost-kpi-label">${label}</span>
                <span><span class="cost-kpi-value">${value}</span>${unit ? ` <span class="cost-kpi-unit">${unit}</span>` : ''}</span>
                <span class="cost-kpi-note">${note}</span></div>`;
        return `
            <section class="carbon-panel" aria-labelledby="cost-carbon-title">
                <h2 class="visually-hidden" id="cost-carbon-title">Carbon footprint, ${Utils.escapeHtml(monthLabel(c.month))}</h2>
                <div class="carbon-head">
                    <p class="cost-intro">From AWS's monthly carbon footprint export, not an estimate of ours. Each environment gets a share of
                        its region and service's emissions in line with its cost.</p>
                    <div class="carbon-controls">
                        <label class="carbon-month" for="cost-carbon-month">Month
                            <select id="cost-carbon-month" class="form-select form-select-sm" data-action-change="cost-carbon-month">${options}</select></label>
                        <a class="btn btn-ghost btn-sm" id="cost-carbon-export" href="${Utils.escapeHtml(Config.API.costManagement.carbonExport(c.month))}" download>Excel</a>
                    </div>
                </div>
                <div class="carbon-figures" id="cost-carbon-summary">
                    ${figure('mbm', 'Market-based', c.totalMbmKg == null ? '—' : kg(c.totalMbmKg), c.totalMbmKg == null ? '' : 'kg CO\u2082e', 'Counts the renewable energy AWS buys')}
                    ${figure('lbm', 'Location-based', c.totalLbmKg == null ? '—' : kg(c.totalLbmKg), c.totalLbmKg == null ? '' : 'kg CO\u2082e', "The local grid's average")}
                    ${figure('matched', 'Matched to environments', c.coveragePct == null ? '—' : `${Math.round(Number(c.coveragePct))}%`, '', 'The rest is shared accounts and services')}
                    ${strip}
                </div>
                <div class="cost-card carbon-table-card"><div class="cost-scroll">
                    <table class="cost-table carbon-table" id="cost-carbon-table">
                        <thead><tr><th scope="col">Environment</th><th scope="col" class="carbon-mbm-col">Market-based, kg</th>
                            <th scope="col" class="cost-num">Location-based, kg</th><th scope="col" class="cost-num">Cost</th>
                            <th scope="col" class="cost-num">kg per $100</th></tr></thead>
                        <tbody>${body}</tbody>
                    </table>
                </div></div>
            </section>`;
    }

    function setCarbonMonth(month) {
        state.carbonMonth = month || null;
        fetchKeys(['carbon']).then(() => { if (isActive()) renderPanel('carbon'); });
    }

    /**
     * On-demand VMs and node groups of Spot-eligible environments with what Spot would have saved
     * over 30 days and AWS's placement score (E31-T04); hidden when there are none (or Spot is off).
     */
    function buildSpotPanel() {
        const rows = state.spotOpportunities || [];
        if (!rows.length) return '';
        const money = v => v == null ? '<span class="text-muted">Unknown</span>' : Utils.escapeHtml(Utils.formatCurrency(Number(v)));
        const body = rows.map(r => `
            <tr data-spot-vm="${Utils.escapeHtml(r.vmId)}">
                <td class="cost-vm-cell" title="${Utils.escapeHtml(r.environmentName || '')}">${Utils.escapeHtml(r.name || r.vmId)}</td>
                <td>${r.kind === 'EKS_NODE_GROUP' ? `EKS node group${r.nodeCount ? ` · ${Number(r.nodeCount)} node${r.nodeCount === 1 ? '' : 's'}` : ''}` : 'EC2 VM'}</td>
                <td>${Utils.escapeHtml(r.instanceType || '-')}</td>
                <td class="cost-num">${money(r.onDemandMonthly)}</td>
                <td class="cost-num">${money(r.spotMonthly)}</td>
                <td class="cost-num">${money(r.savings)}</td>
                <td class="cost-num">${r.placementScore != null ? `<span class="spot-score" title="AWS placement score: 10 is most likely to get the capacity">${Number(r.placementScore)}/10</span>` : '<span class="text-muted">-</span>'}</td>
                <td>${r.eligible ? '<span class="badge text-bg-success">Can convert</span>' : `<span class="text-muted small">${Utils.escapeHtml(r.reason || 'Not eligible')}</span>`}</td>
            </tr>`).join('');
        return `
            <section class="cost-table-card" aria-labelledby="cost-spot-title">
                <div class="dashboard-panel-head">
                    <div class="cost-panel-head-text">
                        <h2 id="cost-spot-title">Spot opportunities</h2>
                        <small>${rows.length} on-demand VM(s) and node group(s) in environments that allow Spot · last 30 days</small>
                    </div>
                </div>
                <div class="table-responsive">
                    <table class="table table-baseline cost-table" id="cost-spot-table">
                        <thead><tr><th>VM / node group</th><th>Kind</th><th>Type</th><th class="cost-num">On demand</th>
                            <th class="cost-num">On Spot</th><th class="cost-num">Saving</th><th class="cost-num">Placement</th><th>Conversion</th></tr></thead>
                        <tbody>${body}</tbody>
                    </table>
                </div>
            </section>`;
    }

    /** Namespace and workload cost of one EKS environment (E26-T05); hidden without EKS environments. */
    function buildWorkloadsPanel() {
        const envs = state.eksEnvironments || [];
        if (envs.length === 0) return '';
        const options = envs.map(e => Utils.html`<option value="${e.environmentId}">${e.displayName || e.name}</option>`).join('');
        return Utils.html`
            <section class="cost-table-card" aria-labelledby="cost-workloads-title">
                <div class="dashboard-panel-head d-flex justify-content-between align-items-center flex-wrap gap-2">
                    <h2 id="cost-workloads-title">EKS Workloads</h2>
                    <select class="form-select form-select-sm w-auto" id="cost-eks-env" data-action-change="cost-eks-env"
                            aria-label="EKS environment">${Utils.raw(options)}</select>
                </div>
                <div id="cost-eks-workloads" data-eks-workloads></div>
            </section>`;
    }

    function mountWorkloads(environmentId) {
        const $select = $('#cost-eks-env');
        if (!$select.length || typeof EksWorkloads === 'undefined') return;
        const id = environmentId || $select.val();
        const days = $('#cost-eks-workloads .eks-workloads-days').val();
        EksWorkloads.mount($('#cost-eks-workloads'), d => Config.API.costManagement.eksWorkloadCost(id, d), days);
    }

    /** AWS cost anomalies across environments, with owners' verdicts and monitor coverage (E21-T04). */
    function buildAnomaliesPanel() {
        const linked = state.anomalyMonitors.filter(m => m.environmentId).length;
        const filter = state.anomalyFilter || '';
        const envs = [...new Map(state.anomalies.filter(a => a.environmentId)
            .map(a => [a.environmentId, a.environmentName || a.environmentId])).entries()];
        const list = filter ? state.anomalies.filter(a => a.environmentId === filter) : state.anomalies;
        return Utils.html`
            <section class="cost-table-card" aria-labelledby="cost-anomalies-title">
                <div class="d-flex justify-content-between align-items-center flex-wrap gap-2 mb-2">
                    <div>
                        <h2 class="h6 mb-0" id="cost-anomalies-title">Cost anomalies (last ${ANOMALY_DAYS} days)</h2>
                        <div class="small text-muted">${linked} monitor${linked === 1 ? '' : 's'} linked to environments ·
                            <a href="#/cost-setup">Cost Setup</a></div>
                    </div>
                    <div>
                        <label class="visually-hidden" for="cost-anomaly-env">Environment</label>
                        <select class="form-select form-select-sm" id="cost-anomaly-env">
                            <option value="">All environments</option>
                            ${Utils.raw(envs.map(([id, name]) => Utils.html`<option value="${id}" ${id === filter ? 'selected' : ''}>${name}</option>`).join(''))}
                        </select>
                    </div>
                </div>
                ${Utils.raw(CostAnomalies.tableHtml(list, true, 'No anomalies reported by AWS Cost Anomaly Detection'))}
            </section>`;
    }

    function panelFailed(name) {
        return PANELS[name].keys.some(key => state.errors[key]);
    }

    function panelSlot(name) {
        return `<div class="cost-panel-slot" data-cost-panel="${name}">${buildPanel(name)}</div>`;
    }

    function buildPanel(name) {
        return panelFailed(name) ? buildPanelError(name) : PANELS[name].build();
    }

    function buildPanelError(name) {
        const panel = PANELS[name];
        const cls = panel.chartPanel ? `dashboard-chart-panel${panel.wide ? ' dashboard-wide' : ''}` : 'cost-table-card';
        return Utils.html`
            <section class="${cls}">
                <div class="cost-panel-error" role="alert">
                    <i class="fas fa-triangle-exclamation"></i>
                    <span>Couldn't load ${panel.label}.</span>
                    <button type="button" class="btn btn-sm btn-ghost cost-retry" data-key="${name}">
                        <i class="fas fa-sync"></i> Retry
                    </button>
                </div>
            </section>`;
    }

    /** Re-render one panel in place and (re)draw its charts. */
    function renderPanel(name) {
        const panel = PANELS[name];
        (panel.charts ? panel.charts() : []).forEach(([id]) => disposeChart(id));
        $(`[data-cost-panel="${name}"]`).html(buildPanel(name));
        if (!panelFailed(name) && panel.charts && window.echarts) {
            panel.charts().forEach(([id, option]) => chart(id, option));
        }
        if (!panelFailed(name) && panel.after) panel.after();
    }

    /** Retry only the failed requests behind one panel. */
    function retryPanel(name, $btn) {
        const keys = PANELS[name].keys.filter(key => state.errors[key]);
        $btn.prop('disabled', true);
        fetchKeys(keys.length ? keys : PANELS[name].keys).then(() => {
            if (!isActive()) return;
            renderPanel(name);
            if (panelFailed(name)) {
                Notifications.error(`Still couldn't load ${PANELS[name].label}.`);
            }
        });
    }

    function buildChartPanel(title, subtitle, chartId) {
        return `
            <section class="dashboard-chart-panel">
                <div class="dashboard-panel-head">
                    <h2>${Utils.escapeHtml(title)}</h2>
                    <small>${Utils.escapeHtml(subtitle)}</small>
                </div>
                <div id="${chartId}" class="dashboard-chart"></div>
            </section>
        `;
    }

    // ---- KPI strip ----

    // ---- pure model (node tests: src/test/js/cost-overview.test.mjs) ----

    function dayOf(value) {
        const t = String(value || '').slice(0, 10);
        return /^\d{4}-\d{2}-\d{2}$/.test(t) ? t : null;
    }

    function monthPrefix(now) {
        return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
    }

    /**
     * This month so far from the daily trend: each day's AWS billing figure where it exists, else the
     * estimate; and the share of the days that have a billing figure.
     */
    function monthToDate(trend, now = new Date()) {
        const prefix = monthPrefix(now);
        const days = (trend || []).filter(r => (dayOf(r.date) || '').startsWith(prefix));
        let value = 0;
        let billed = 0;
        days.forEach(r => {
            const actual = r.actualCost !== null && r.actualCost !== undefined;
            value += Number(actual ? r.actualCost : r.estimatedCost) || 0;
            if (actual) billed++;
        });
        return { days: days.length, value, billedShare: days.length ? Math.round((billed / days.length) * 100) : 0 };
    }

    /**
     * The month-end forecast: month to date plus the average of the last 7 days for each day left;
     * AWS's, when its daily forecast covers every day left in the month.
     */
    function monthEnd(trend, forecast, now = new Date()) {
        const mtd = monthToDate(trend, now);
        const recent = (trend || []).filter(r => dayOf(r.date)).slice(-7);
        const daily = recent.length ? recent.reduce((sum, r) => sum + (Number(r.actualCost ?? r.estimatedCost) || 0), 0) / recent.length : 0;
        const daysInMonth = new Date(now.getFullYear(), now.getMonth() + 1, 0).getDate();
        const left = Math.max(0, daysInMonth - now.getDate());
        const platform = mtd.days || recent.length ? mtd.value + daily * left : null;
        const prefix = monthPrefix(now);
        const today = `${prefix}-${String(now.getDate()).padStart(2, '0')}`;
        const awsDays = ((forecast && forecast.awsDaily) || []).filter(p => (dayOf(p.periodStart) || '').startsWith(prefix) && dayOf(p.periodStart) > today);
        const aws = left > 0 && awsDays.length >= left ? mtd.value + awsDays.reduce((sum, p) => sum + (Number(p.mean) || 0), 0) : null;
        return { platform, aws };
    }

    /** Bars: the largest BAR_ROWS by cost and the rest as "Other". */
    function topBars(rows, max = BAR_ROWS) {
        const sorted = (rows || []).filter(r => Number(r.cost) > 0).slice().sort((a, b) => Number(b.cost) - Number(a.cost));
        const top = sorted.slice(0, max).map(r => ({ label: r.label, cost: Number(r.cost) }));
        const rest = sorted.slice(max).reduce((sum, r) => sum + Number(r.cost), 0);
        if (rest > 0) top.push({ label: 'Other', cost: rest, other: true });
        return top;
    }

    /** Each team's cost this month, from the per-team daily trend. */
    function teamsThisMonth(teamTrend, now = new Date()) {
        const prefix = monthPrefix(now);
        const totals = new Map();
        (teamTrend || []).forEach(p => {
            if (!(dayOf(p.date) || '').startsWith(prefix)) return;
            const team = p.team || 'No team';
            totals.set(team, (totals.get(team) || 0) + (Number(p.estimatedCost) || 0));
        });
        return [...totals].map(([label, cost]) => ({ label, cost }));
    }

    /** The three biggest savings across rightsizing and idle VMs, in plain words. */
    function topSavings(rightsizing, idle, max = 3) {
        const pct = v => `${Math.round(Number(v))}%`;
        const fromRightsizing = (rightsizing || []).filter(r => Number(r.estimatedMonthlySavings) > 0).map(r => ({
            what: r.vmName || r.vmId, where: r.environmentName || '',
            suggestion: `${r.currentInstanceType || '?'} → ${r.suggestedInstanceType || '?'}`
                + (r.peakCpuUtilization != null ? `, CPU peaks at ${pct(r.peakCpuUtilization)}` : ''),
            saves: Number(r.estimatedMonthlySavings) }));
        const fromIdle = (idle || []).filter(r => Number(r.monthlyIdleCost) > 0).map(r => ({
            what: r.vmName || r.vmId, where: r.environmentName || '',
            suggestion: 'Stop it when idle, or add a schedule',
            saves: Number(r.monthlyIdleCost) }));
        return fromRightsizing.concat(fromIdle).sort((a, b) => b.saves - a.saves).slice(0, max);
    }

    function money0(v) {
        return '$' + Math.round(Number(v) || 0).toLocaleString('en-US');
    }

    // ---- the five figures (U15-T02) ----

    function buildKpiStrip(summary) {
        if (!summary) return '';
        const mtd = monthToDate(state.trend);
        const end = monthEnd(state.trend, state.forecast);
        const fee = Number(summary.eksControlPlaneCost || 0);
        const last30 = `Last 30 days ${Utils.formatCurrency(summary.totalMonthlyCost || 0)}${fee > 0 ? `, incl. ${Utils.formatCurrency(fee)} EKS control plane` : ''}.`;
        const unpriced = Math.max(0, (summary.totalVmCount || 0) - (summary.costKnownVmCount || 0));
        const spot = Number(summary.spotSavings || 0);
        const tiles = [
            ['Month to date', mtd.days ? money0(mtd.value) : '—',
                (mtd.days ? (mtd.billedShare ? `${mtd.billedShare}% from AWS billing. ` : 'Estimated. ') : 'No figures for this month yet. ') + last30, 'mtd'],
            ['Forecast', end.platform !== null ? money0(end.platform) : '—',
                end.aws !== null ? `AWS forecasts ${money0(end.aws)}` : (end.platform !== null ? 'From the last 7 days' : 'Needs a few days of history'), 'forecast'],
            ['Idle waste', money0(summary.idleWasteMonthlyCost), `a month, ${summary.idleVmCount || 0} VM${summary.idleVmCount === 1 ? '' : 's'} barely used`, 'idle'],
            ['Rightsizing', money0(summary.rightsizingPotentialSavings),
                `a month, ${summary.rightsizingCandidateCount || 0} VM${summary.rightsizingCandidateCount === 1 ? '' : 's'} too large`
                + ((summary.scaleUpCandidateCount || 0) > 0 ? `, ${summary.scaleUpCandidateCount} need more` : ''), 'rightsizing'],
            ['Priced VMs', `${summary.costKnownVmCount || 0} of ${summary.totalVmCount || 0}`,
                (unpriced ? `${unpriced} ${unpriced === 1 ? 'has' : 'have'} no list price` : 'Every VM has a list price')
                + ((summary.approximatePriceVmCount || 0) > 0 ? `, ${summary.approximatePriceVmCount} approximate` : ''), 'priced']
        ];
        if ((summary.spotVmCount || 0) > 0 || spot > 0) {
            const interruptions = Number(summary.spotInterruptions30d || 0);
            tiles.push(['Spot savings, 30 days', money0(spot), `${summary.spotVmCount || 0} on Spot, ${interruptions} interruption${interruptions === 1 ? '' : 's'}`, 'spot']);
        }
        return `<section class="cost-kpi-grid" aria-label="This month">${tiles.map(([label, value, note, key]) => Utils.html`
            <div class="cost-kpi" data-kpi="${key}"><span class="cost-kpi-label">${label}</span>
                <span class="cost-kpi-value">${value}</span><span class="cost-kpi-note">${note}</span></div>`).join('')}</section>`;
    }

    // ---- spend by environment, team, account or type (U15-T02) ----

    function breakdownRows(by) {
        if (by === 'team') return teamsThisMonth(state.teamTrend);
        const source = by === 'account' ? state.spendByAccount : by === 'type' ? state.spendByVmType : state.spendByEnvironment;
        return (source || []).map(r => ({ label: r.dimensionLabel || r.dimensionKey || 'Unknown', cost: Number(r.cost) || 0 }));
    }

    function buildBreakdown() {
        // The account choice only when spend spans more than one AWS account (E28-T11).
        const choices = BREAKDOWN_BY.filter(([k]) => k !== 'account' || (state.spendByAccount || []).length > 1);
        if (!choices.some(([k]) => k === state.breakdownBy)) state.breakdownBy = 'environment';
        const rows = topBars(breakdownRows(state.breakdownBy));
        const most = Math.max(1, ...rows.map(r => r.cost));
        const period = state.breakdownBy === 'team' ? 'this month' : 'last 30 days';
        return Utils.html`<section class="cost-card cost-breakdown" aria-labelledby="cost-by-title">
                <div class="cost-card-head"><h2 id="cost-by-title"><label for="cost-by">By</label>
                    <select class="form-select form-select-sm" id="cost-by" data-action-change="cost-breakdown-by">${Utils.raw(choices.map(([k, label]) =>
                        Utils.html`<option value="${k}"${Utils.raw(k === state.breakdownBy ? ' selected' : '')}>${label === 'VM type' ? label : label.toLowerCase()}</option>`).join(''))}</select></h2>
                    <small>${period}, estimated</small></div>
                ${Utils.raw(rows.length ? rows.map(r => Utils.html`<div class="cost-bar-row${Utils.raw(r.other ? ' cost-bar-other' : '')}">
                    <span class="cost-bar-name" title="${r.label}">${r.label}</span>
                    <span class="cost-bar"><span style="width:${(r.cost / most * 100).toFixed(1)}%"></span></span>
                    <span class="cost-mono">${money0(r.cost)}</span></div>`).join('')
                    : '<p class="cost-note">No spend recorded yet.</p>')}
            </section>`;
    }

    function setBreakdownBy(by) {
        state.breakdownBy = BREAKDOWN_BY.some(([k]) => k === by) ? by : 'environment';
        syncHash();
        renderPanel('breakdown');
    }

    // ---- the biggest savings to act on (U15-T02) ----

    function buildTopSavings() {
        const rows = topSavings(state.rightsizingTop.content, state.idle.content);
        const all = state.rightsizingTop.totalElements || 0;
        return Utils.html`<section class="cost-card cost-savings" aria-labelledby="cost-savings-title">
                <div class="cost-card-head"><h2 id="cost-savings-title">Biggest savings to act on</h2>
                    ${Utils.raw(all ? Utils.html`<a href="${Config.API.costManagement.rightsizingExport()}" download="rightsizing-${todayStamp()}.xlsx">All ${all} suggestion${all === 1 ? '' : 's'}, Excel</a>` : '')}</div>
                ${Utils.raw(rows.length ? Utils.html`<div class="cost-scroll"><table class="cost-savings-table" id="cost-savings-table">
                    <thead><tr><th scope="col">What</th><th scope="col">Where</th><th scope="col">Suggestion</th><th scope="col" class="cost-num">Saves a month</th></tr></thead>
                    <tbody>${Utils.raw(rows.map(r => Utils.html`<tr><td class="cost-strong">${r.what}</td><td>${r.where}</td><td>${r.suggestion}</td>
                        <td class="cost-num cost-mono">${money0(r.saves)}</td></tr>`).join(''))}</tbody></table></div>`
                    : '<p class="cost-note">Nothing to save right now: no VM is too large or sitting idle.</p>')}
            </section>`;
    }

    // ---- breakdown & trend charts ----

    function buildTrendChart() {
        return `
            <section class="dashboard-chart-panel dashboard-wide">
                <div class="dashboard-panel-head d-flex justify-content-between align-items-start flex-wrap gap-2">
                    <div>
                        <h2>${state.forecastRange === 'long' ? 'Monthly spend, 18 months ahead' : `Daily spend, ${state.forecastRange === '14' ? '14' : '90'} days`}</h2>
                        <small id="cost-trend-subtitle">${trendSubtitle()}</small>
                    </div>
                    <div class="btn-group btn-group-sm" role="group" aria-label="Range">
                        ${[['14', '14 days'], ['90', '90 days'], ['long', '18 months']].map(([range, label]) => `<button type="button"
                            class="btn btn-outline-secondary ${state.forecastRange === range ? 'active' : ''}" data-action="cost-forecast-range"
                            data-range="${range}" aria-pressed="${state.forecastRange === range}">${label}</button>`).join('')}
                    </div>
                </div>
                <div id="cost-chart-trend" class="dashboard-chart"></div>
            </section>
        `;
    }

    function trendSubtitle() {
        if (state.forecastRange === 'long') {
            const lr = state.longRange;
            if (!lr) return 'Loading the 18-month forecast&hellip;';
            return lr.awsGeneratedOn
                ? `AWS forecast generated ${Utils.escapeHtml(lr.awsGeneratedOn)}; covers resources tagged tcg:managed-by &mdash; with the platform's run-rate`
                : 'No AWS forecast captured yet (it runs weekly) &mdash; the platform run-rate only';
        }
        const base = `Estimated, last ${state.forecastRange === '14' ? 14 : TREND_DAYS} days`;
        const forecast = state.forecast;
        if (!forecast) return base;
        const aws = forecast.awsGeneratedOn && (forecast.awsDaily || []).length
            ? ` &mdash; AWS forecast generated ${Utils.escapeHtml(forecast.awsGeneratedOn)}; covers resources tagged tcg:managed-by`
            : ' &mdash; no AWS forecast captured yet';
        if (!forecast.sufficientHistory) {
            const daysNeeded = Math.max(0, forecast.minHistoryDaysRequired - forecast.historyDaysUsed);
            return `${base} &mdash; platform forecast needs ${daysNeeded} more day(s) of history${aws}`;
        }
        const forecastDayCount = forecast.points.filter(p => p.isForecast).length;
        return `${base} &mdash; with a ${forecastDayCount}-day platform forecast${aws}`;
    }

    /** The FOCUS export dialog (E35-T03): one of the last 13 months and the basis, then a download. */
    /** Month for finance (U15-T01, CostDialogs.dc.html): a FOCUS 1.2 CSV of one month, AWS billing or estimated. */
    function openFocusExport() {
        const now = new Date();
        const months = [];
        for (let i = 0; i < 13; i++) {
            const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - i, 1));
            months.push([d.toISOString().slice(0, 7), d.toLocaleDateString('en-GB', { month: 'long', year: 'numeric', timeZone: 'UTC' })]);
        }
        const radio = (value, title, text, checked) => `<label class="cost-radio"><input class="form-check-input" type="radio" name="focus-basis"
                id="focus-basis-${value}" value="${value}"${checked ? ' checked' : ''}><span><b>${title}</b><span>${text}</span></span></label>`;
        Modals.show({
            id: 'focusExportModal',
            title: 'Month for finance',
            body: `<p class="small text-muted">A FOCUS 1.2 CSV, the open billing format finance tools read.</p>
                <div class="mb-3"><label class="form-label small mb-1" for="focus-month">Month</label>
                    <select class="form-select form-select-sm" id="focus-month">${months.map(([m, name], i) =>
                        `<option value="${m}" ${i === 1 ? 'selected' : ''}>${name}${i === 0 ? ' (so far)' : ''}</option>`).join('')}</select></div>
                <fieldset><legend class="form-label small mb-1">Figures</legend>
                    ${radio('actual', 'AWS billing', 'What AWS charged, per environment; complete a few days after month end', true)}
                    ${radio('estimated', 'Estimated', 'From run time and list prices, per resource; available straight away', false)}
                </fieldset>`,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Download CSV', class: 'btn-primary', id: 'focus-download' }
            ]
        });
        $('#focus-download').on('click', () => {
            const month = $('#focus-month').val();
            const basis = $('input[name="focus-basis"]:checked').val() || 'actual';
            const link = document.createElement('a');
            link.href = Config.API.costManagement.focusExport(month, basis);
            link.download = `focus-${basis}-${month}.csv`;
            document.body.appendChild(link);
            link.click();
            link.remove();
            Modals.hideWhenReady(document.getElementById('focusExportModal'));
        });
    }

    /** '14 days' or '18 months' (E35-T02); the long range is fetched the first time it is shown. */
    function setForecastRange(range) {
        state.forecastRange = range === 'long' ? 'long' : range === '14' || range === 'short' ? '14' : '90';
        $('#cost-chart-trend').closest('section').find('h2').first().text(state.forecastRange === 'long'
            ? 'Monthly spend, 18 months ahead' : `Daily spend, ${state.forecastRange} days`);
        $('[data-action="cost-forecast-range"]').each(function() {
            const on = this.dataset.range === state.forecastRange;
            $(this).toggleClass('active', on).attr('aria-pressed', String(on));
        });
        const draw = () => {
            $('#cost-trend-subtitle').html(trendSubtitle());
            chart('cost-chart-trend', state.forecastRange === 'long' && state.longRange
                ? buildLongRangeOption(state.longRange) : buildTrendOption(state.trend));
        };
        if (state.forecastRange === 'long' && !state.longRange) {
            draw();
            fetchJson(Config.API.costManagement.forecastLongRange, { suppressGlobalError: true })
                .then(data => { state.longRange = data || { awsMonthly: [], platformRunRate: [] }; if (state.forecastRange === 'long') draw(); })
                .catch(() => {
                    state.longRange = { awsMonthly: [], platformRunRate: [], failed: true };
                    if (state.forecastRange === 'long') draw();
                });
            return;
        }
        draw();
    }

    /** AWS's monthly forecast with its interval as bars and whiskers, and the platform run-rate as a line. */
    function buildLongRangeOption(lr) {
        if (lr.failed) return emptyChartOption("Couldn't load the 18-month forecast.");
        const aws = lr.awsMonthly || [];
        const runRate = lr.platformRunRate || [];
        const months = (aws.length ? aws.map(p => p.periodStart) : runRate.map(r => r.periodStart));
        if (!months.length) return emptyChartOption('No forecast yet: AWS forecasts are captured weekly and the run-rate needs cost history.');
        const label = d => {
            const dt = new Date(d + 'T00:00:00');
            return isNaN(dt) ? d : dt.toLocaleDateString(undefined, { month: 'short', year: '2-digit' });
        };
        const awsBy = new Map(aws.map(p => [p.periodStart, p]));
        const runBy = new Map(runRate.map(r => [r.periodStart, r]));
        const num = v => (v === null || v === undefined ? null : Number(v));
        const series = [];
        if (aws.length) {
            series.push({ name: 'AWS interval low', type: 'line', stack: 'aws-band', symbol: 'none', silent: true,
                lineStyle: { opacity: 0 }, data: months.map(m => num(awsBy.get(m) && awsBy.get(m).lower)) });
            series.push({ name: 'AWS forecast (80% interval)', type: 'line', stack: 'aws-band', symbol: 'none',
                lineStyle: { opacity: 0 }, areaStyle: { opacity: 0.18, color: CATEGORICAL_COLORS[3] },
                data: months.map(m => { const p = awsBy.get(m); return p && p.lower != null && p.upper != null ? Number(p.upper) - Number(p.lower) : null; }) });
            series.push({ name: 'AWS forecast', type: 'bar', barMaxWidth: 22, itemStyle: { color: CATEGORICAL_COLORS[3] },
                data: months.map(m => num(awsBy.get(m) && awsBy.get(m).mean)) });
        }
        if (runRate.length) {
            series.push({ name: 'Platform run-rate', type: 'line', symbol: 'diamond', symbolSize: 7,
                lineStyle: { type: 'dashed', color: CATEGORICAL_COLORS[2] }, itemStyle: { color: CATEGORICAL_COLORS[2] },
                data: months.map(m => num(runBy.get(m) && runBy.get(m).amount)) });
        }
        return {
            legend: { bottom: 0, textStyle: chartTextStyle(), data: series.map(s => s.name).filter(n => n !== 'AWS interval low') },
            tooltip: {
                trigger: 'axis', confine: true,
                formatter: params => {
                    const idx = params && params[0] ? params[0].dataIndex : -1;
                    const m = months[idx];
                    if (!m) return '';
                    const p = awsBy.get(m);
                    const r = runBy.get(m);
                    const lines = [label(m)];
                    if (p) lines.push(`AWS forecast: ${Utils.formatCurrency(Number(p.mean) || 0)}`
                        + (p.lower != null && p.upper != null ? ` (${Utils.formatCurrency(Number(p.lower))} to ${Utils.formatCurrency(Number(p.upper))})` : ''));
                    if (r) lines.push(`Platform run-rate: ${Utils.formatCurrency(Number(r.amount) || 0)}`);
                    return lines.join('<br/>');
                }
            },
            grid: { left: 60, right: 24, top: 18, bottom: 48 },
            xAxis: { type: 'category', data: months.map(label), axisLabel: chartTextStyle() },
            yAxis: { type: 'value', axisLabel: { ...chartTextStyle(), formatter: v => compactCurrency(v) },
                splitLine: { lineStyle: { color: '#eef2f7' } } },
            series
        };
    }

    // ---- commitments (E27-T03): display only, purchases stay in AWS ----

    function titleCase(v) {
        return String(v || '').toLowerCase().split('_').map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
    }

    const TERMS = { ONE_YEAR: '1 year', THREE_YEARS: '3 years' };
    const PAYMENTS = { NO_UPFRONT: 'no upfront', PARTIAL_UPFRONT: 'partial', ALL_UPFRONT: 'all upfront' };
    const REC_NAMES = { COMPUTE_SP: 'Compute Savings Plan', EC2_INSTANCE_SP: 'EC2 Instance Savings Plan', EC2_RI: 'Reserved Instance' };
    const OVER_COMMIT = 'More than the always-running floor: schedules would waste part of it';
    const FLAG_TEXT = {
        UNDER_UTILISED: "Some of what you've already committed to goes unused. Use it before buying more.",
        SCHEDULES_ALREADY_COVERED: 'Most scheduled hours are already paid for by commitments, so more schedules save little.'
    };

    /** The four coverage and use figures, each with its bar: navy for coverage, green for use from 90%, gold below. */
    function commitmentFigures(coverage) {
        const c = coverage || {};
        const num = v => v === null || v === undefined ? null : Math.round(Number(v));
        const used = (v, good, unused) => v == null ? 'No coverage snapshot yet' : v >= 90 ? good : `${100 - v}% ${unused}`;
        return [
            ['sp-coverage', 'Savings Plans coverage', num(c.spCoveragePercent), 'cover', 'of eligible compute spend'],
            ['sp-used', 'Savings Plans used', num(c.spUtilizationPercent), 'use', ['of what you committed to', 'of the commitment unused']],
            ['ri-coverage', 'Reserved Instance coverage', num(c.riCoveragePercent), 'cover', 'of eligible instance hours'],
            ['ri-used', 'Reserved Instances used', num(c.riUtilizationPercent), 'use', ['of reserved hours', 'of reserved hours unused']]
        ].map(([key, label, value, kind, note]) => ({
            key, label, value, text: value == null ? '—' : `${value}%`, bar: value == null ? 0 : Math.max(0, Math.min(100, value)),
            tone: kind === 'cover' ? 'cover' : value != null && value >= 90 ? 'good' : 'low',
            note: kind === 'cover' ? (value == null ? 'No coverage snapshot yet' : note) : used(value, note[0], note[1])
        }));
    }

    /** "Covers EC2 in any region, family or size", "m6i family in ap-south-1", "4 × c6i.xlarge, ap-south-1". */
    function recommendationDetail(r) {
        if (r.type === 'COMPUTE_SP') return 'Covers EC2 in any region, family or size';
        const lines = Array.isArray(r.details) ? r.details : [];
        const d = lines[0];
        if (!d) return '';
        const more = lines.length > 1 ? ` and ${lines.length - 1} more` : '';
        if (r.type === 'EC2_RI' && d.instanceType) {
            return `${d.count != null ? `${Number(d.count)} × ` : ''}${d.instanceType}${d.region ? `, ${d.region}` : ''}${more}`;
        }
        return d.instanceFamily ? `${d.instanceFamily} family${d.region ? ` in ${d.region}` : ''}${more}` : '';
    }

    function termText(r) {
        return [TERMS[r.term] || titleCase(r.term), PAYMENTS[r.paymentOption] || titleCase(r.paymentOption).toLowerCase()].filter(Boolean).join(' · ');
    }

    function shortDate(iso) {
        if (!iso) return '';
        const d = new Date(iso + 'T00:00:00Z');
        return Number.isNaN(d.getTime()) ? iso : `${d.getUTCDate()} ${MONTHS_SHORT[d.getUTCMonth()]}`;
    }

    function buildCommitmentsPanel() {
        const c = state.commitments;
        if (!c) return '';
        const recs = c.recommendations || [];
        const flags = c.riskFlags || [];
        const overCommit = new Set(flags.filter(f => f.code === 'OVER_COMMIT' && f.recommendationType).map(f => f.recommendationType));
        const figures = commitmentFigures(c.coverage).map(f => Utils.html`<div class="cost-kpi" data-kpi="${f.key}">
                <span class="cost-kpi-label">${f.label}</span><span class="cost-kpi-value">${f.text}</span>
                <span class="commit-bar"><span class="commit-bar-${f.tone}" style="width:${String(f.bar)}%"></span></span>
                <span class="cost-kpi-note">${f.note}</span></div>`).join('');
        const first = recs[0];
        const basis = first ? `Based on ${first.lookbackDays || c.windowDays} days of use · generated ${shortDate(first.generatedOn)}` : '';
        const table = recs.length === 0
            ? `<p class="commitments-empty cost-note">AWS hasn't suggested anything yet; suggestions are captured every Monday.
                   <a href="#/cost-setup">Check Cost setup</a></p>`
            : Utils.html`<div class="cost-scroll"><table class="cost-table commit-table" id="cost-commitments-table">
                <thead><tr><th scope="col">Type</th><th scope="col">Term · payment</th><th scope="col" class="cost-num">Commit / hour</th>
                    <th scope="col" class="cost-num">Upfront</th><th scope="col" class="cost-num">Saves a month</th></tr></thead>
                <tbody>${Utils.raw(recs.map(r => Utils.html`<tr data-rec="${r.type}">
                    <td><b class="commit-type">${REC_NAMES[r.type] || r.type}</b><span class="rs-sub">${recommendationDetail(r)}</span>
                        ${Utils.raw(overCommit.has(r.type) ? Utils.html`<span class="commit-risk" data-flag="OVER_COMMIT">${OVER_COMMIT}</span>` : '')}</td>
                    <td class="commit-term">${termText(r)}</td>
                    <td class="cost-num cost-mono">${r.hourlyCommitment == null ? '—' : Utils.formatCurrency(Number(r.hourlyCommitment))}</td>
                    <td class="cost-num cost-mono">${r.upfrontCost == null ? '—' : money0(r.upfrontCost)}</td>
                    <td class="cost-num cost-mono rs-saves">${r.estimatedMonthlySavings == null ? '—' : money0(r.estimatedMonthlySavings)}<span class="rs-sub">${r.estimatedSavingsPercent == null ? '' : `${Math.round(Number(r.estimatedSavingsPercent))}%`}</span></td>
                </tr>`).join(''))}</tbody></table></div>`;
        const hours = c.scheduledAvoidedHours == null ? null : Math.round(Number(c.scheduledAvoidedHours));
        const schedules = hours == null || c.grossScheduledSaving == null
            ? `<p class="commit-aside-text">No schedule savings recorded in the last ${c.windowDays} days yet. Hours you've committed to are paid whether VMs run or not.</p>`
            : `<p class="commit-aside-text">Schedules here stopped VMs for <b class="cost-mono">${hours.toLocaleString()} h</b> in the last ${c.windowDays} days, saving
                <b class="cost-mono rs-save">${money0(c.grossScheduledSaving)}</b>, but hours you've committed to are paid whether VMs run or not.</p>`;
        const otherFlags = flags.filter(f => FLAG_TEXT[f.code]);
        return Utils.html`
            <section class="commitments-panel" aria-labelledby="cost-commitments-title">
                <h2 class="visually-hidden" id="cost-commitments-title">Commitments</h2>
                <p class="cost-intro commitments-note">Savings Plans and Reserved Instances bought in AWS. Read-only here: buy or change them in the AWS console. Last ${String(c.windowDays)} days.</p>
                <div class="commitments-kpis">${Utils.raw(figures)}</div>
                <div class="rs-layout commit-layout">
                    <section class="cost-card rs-table-card" aria-labelledby="cost-commit-recs-title">
                        <div class="commit-card-head"><h2 id="cost-commit-recs-title">What AWS suggests buying</h2><small>${basis}</small></div>
                        ${Utils.raw(table)}
                    </section>
                    <aside class="cost-card commit-aside" aria-labelledby="cost-commit-aside-title">
                        <h2 id="cost-commit-aside-title">Schedules and commitments</h2>
                        ${Utils.raw(schedules)}
                        <div class="commit-floor" id="cost-commitments-floor">
                            <span class="cost-kpi-label">Safe to commit, always running</span>
                            <span class="cost-mono commit-floor-value">${c.alwaysOnHourlyFloor == null ? '—' : `${Utils.formatCurrency(Number(c.alwaysOnHourlyFloor))} / hour`}</span>
                            <span class="cost-kpi-note" title="${c.floorNote || ''}">${c.floorVmCount
                                ? `${c.floorVmCount} VM${c.floorVmCount === 1 ? ' runs' : 's run'} even in the quietest hours. Commit up to this and schedules keep their full saving.`
                                : 'No VM runs in every hour yet.'}</span>
                        </div>
                        ${Utils.raw(c.scheduledHoursCoveredValue == null ? '' : Utils.html`<p class="cost-note" id="cost-commit-covered">${money0(c.scheduledHoursCoveredValue)} of stopped hours were already paid for by commitments.</p>`)}
                        ${Utils.raw(otherFlags.length ? Utils.html`<ul class="commitments-flags">${Utils.raw(otherFlags.map(f => Utils.html`<li data-flag="${f.code}">${FLAG_TEXT[f.code]}</li>`).join(''))}</ul>` : '')}
                    </aside>
                </div>
            </section>`;
    }

    function initCharts(tab) {
        if (!window.echarts) return;
        (tab ? tab.panels : Object.keys(PANELS)).forEach(name => {
            const panel = PANELS[name];
            if (panel.charts && !panelFailed(name)) {
                panel.charts().forEach(([id, option]) => chart(id, option));
            }
        });
        setTimeout(resizeCharts, 40);
    }

    /** Draw a chart, first disposing any instance already on that element (no leaks, E08-T09). */
    function chart(id, option) {
        const el = document.getElementById(id);
        if (!el || !window.echarts) return;
        disposeChart(id);
        const existing = echarts.getInstanceByDom(el);
        if (existing) existing.dispose();
        const instance = echarts.init(el, null, { renderer: 'svg' });
        instance.setOption(option);
        chartRegistry.set(id, instance);
    }

    function disposeChart(id) {
        const instance = chartRegistry.get(id);
        if (instance) {
            instance.dispose();
            chartRegistry.delete(id);
        }
    }

    function disposeCharts() {
        chartRegistry.forEach(instance => instance.dispose());
        chartRegistry.clear();
    }

    function resizeCharts() {
        chartRegistry.forEach(instance => instance.resize());
    }

    function buildTrendOption(allRows) {
        // 14 days: the last two weeks before the forecast; 90 days: all of the trend (U15-T02).
        const rows = state.forecastRange === '14' ? allRows.slice(-14) : allRows;
        if (!rows.length) return emptyChartOption('No cost history yet — use Data actions › Fill in the last 30 days.');

        // Forecast points come from a separate endpoint/state slice (state.forecast) — only the
        // future-dated subset is used here, since the history it also returns duplicates rows
        // already sourced from /spend-trend (which additionally carries the Actual Cost series
        // the forecast response doesn't).
        const forecastPoints = (state.forecast && state.forecast.sufficientHistory)
            ? state.forecast.points.filter(p => p.isForecast)
            : [];
        // AWS's forecast for the same days (E35-T02); it alone sets the future days when the
        // platform has too little history for its own.
        const awsPoints = (state.forecast && state.forecast.awsDaily) || [];
        const forecastDates = forecastPoints.length ? forecastPoints.map(p => p.date) : awsPoints.map(p => p.periodStart);
        const awsByDate = new Map(awsPoints.map(p => [p.periodStart, p]));
        const awsAt = (d, key) => { const p = awsByDate.get(d); return p && p[key] !== null && p[key] !== undefined ? Number(p[key]) : null; };

        const historyLabels = rows.map(r => (r.date || '').slice(5));
        const forecastLabels = forecastDates.map(d => (d || '').slice(5));
        const labels = historyLabels.concat(forecastLabels);

        const estimatedValues = rows.map(r => Number(r.estimatedCost) || 0)
            .concat(forecastDates.map(() => null));
        // actualCost is null until Cost Management Phase 2 (real Cost Explorer ingestion) has
        // populated it — render the series only once at least one point has real data, so an
        // all-null trend doesn't draw a flat $0 line that looks like a real (zero-cost) answer.
        const hasAnyActual = rows.some(r => r.actualCost !== null && r.actualCost !== undefined);
        const actualValues = rows.map(r => (r.actualCost !== null && r.actualCost !== undefined) ? Number(r.actualCost) : null)
            .concat(forecastDates.map(() => null));
        // Forecast series: null everywhere in the historical range except the very last historical
        // point (seeded with that day's estimated cost so the dashed line connects seamlessly to
        // the solid one), then the projected values.
        const forecastValues = rows.map((r, i) => i === rows.length - 1 ? (Number(r.estimatedCost) || 0) : null)
            .concat(forecastPoints.map(p => Number(p.cost) || 0));

        // "Today" divider between real history and projection — the clearest way to separate
        // two dashed series (Actual, Forecast) that sit close together on the CVD spectrum
        // (validated: ΔE 7.9, in the 6-8 floor band that requires a secondary encoding beyond
        // color alone). Actual only ever has values in the historical range and Forecast only
        // ever has values after it, so this line makes that existing gap explicit instead of
        // leaving the viewer to infer it.
        const todayMarkLine = forecastDates.length ? {
            silent: true,
            symbol: 'none',
            lineStyle: { type: 'dashed', color: '#94a3b8', width: 1 },
            label: { formatter: 'Today', color: chartTextStyle().color, fontFamily: chartTextStyle().fontFamily },
            data: [{ xAxis: rows.length - 1 }]
        } : undefined;

        const series = [{
            name: 'Estimated Cost',
            type: 'line',
            smooth: true,
            showSymbol: estimatedValues.length < 60,
            areaStyle: { opacity: 0.08 },
            data: estimatedValues,
            markLine: todayMarkLine
        }];
        if (hasAnyActual) {
            series.push({
                name: 'Actual Cost',
                type: 'line',
                smooth: true,
                showSymbol: estimatedValues.length < 60,
                symbol: 'circle',
                connectNulls: true,
                lineStyle: { type: 'dashed' },
                data: actualValues
            });
        }
        if (forecastPoints.length) {
            series.push({
                name: 'Platform forecast',
                type: 'line',
                // Diamond symbol is a second, color-independent way to tell Forecast apart from
                // Actual (both dashed, both non-primary) — on top of the "Today" divider above.
                showSymbol: true,
                symbol: 'diamond',
                symbolSize: 8,
                connectNulls: true,
                lineStyle: { type: 'dashed', color: CATEGORICAL_COLORS[2] },
                itemStyle: { color: CATEGORICAL_COLORS[2] },
                data: forecastValues
            });
        }

        if (awsPoints.length) {
            const none = rows.map(() => null);
            // A confidence band: an invisible lower line with the interval's height stacked on it.
            series.push({ name: 'AWS interval low', type: 'line', stack: 'aws-band', symbol: 'none', silent: true,
                lineStyle: { opacity: 0 }, data: none.concat(forecastDates.map(d => awsAt(d, 'lower'))) });
            series.push({ name: 'AWS forecast (80% interval)', type: 'line', stack: 'aws-band', symbol: 'none',
                lineStyle: { opacity: 0 }, areaStyle: { opacity: 0.18, color: CATEGORICAL_COLORS[3] },
                data: none.concat(forecastDates.map(d => {
                    const lo = awsAt(d, 'lower'); const up = awsAt(d, 'upper');
                    return lo !== null && up !== null ? up - lo : null;
                })) });
            series.push({ name: 'AWS forecast', type: 'line', symbol: 'triangle', symbolSize: 7, showSymbol: true,
                lineStyle: { type: 'dashed', color: CATEGORICAL_COLORS[3] }, itemStyle: { color: CATEGORICAL_COLORS[3] },
                data: none.concat(forecastDates.map(d => awsAt(d, 'mean'))) });
        }

        const hasLegend = hasAnyActual || forecastPoints.length > 0 || awsPoints.length > 0;

        return {
            color: [CATEGORICAL_COLORS[0], CATEGORICAL_COLORS[1]],
            legend: hasLegend ? { bottom: 0, textStyle: chartTextStyle(),
                data: series.map(s => s.name).filter(n => n !== 'AWS interval low') } : undefined,
            tooltip: {
                trigger: 'axis',
                confine: true,
                formatter: params => {
                    const point = params && params[0];
                    if (!point) return '';
                    const idx = point.dataIndex;
                    if (idx < rows.length) {
                        const row = rows[idx];
                        const lines = [`${row.date || ''}`, `Estimated: ${Utils.formatCurrency(Number(row.estimatedCost) || 0)}`];
                        if (hasAnyActual) {
                            lines.push(row.actualCost != null
                                ? `Actual: ${Utils.formatCurrency(Number(row.actualCost))}`
                                : 'Actual: not yet ingested');
                        }
                        return lines.join('<br/>');
                    }
                    const forecastPoint = forecastPoints[idx - rows.length];
                    const day = forecastDates[idx - rows.length];
                    const lines = day ? [`${day} (projected)`] : [];
                    if (forecastPoint) lines.push(`Platform forecast: ${Utils.formatCurrency(Number(forecastPoint.cost) || 0)}`);
                    const awsPoint = day && awsByDate.get(day);
                    if (awsPoint) {
                        lines.push(`AWS forecast: ${Utils.formatCurrency(Number(awsPoint.mean) || 0)}`
                            + (awsPoint.lower != null && awsPoint.upper != null
                                ? ` (${Utils.formatCurrency(Number(awsPoint.lower))} to ${Utils.formatCurrency(Number(awsPoint.upper))})` : ''));
                    }
                    return lines.join('<br/>');
                }
            },
            grid: { left: 52, right: 24, top: 18, bottom: hasLegend ? 48 : 32 },
            xAxis: { type: 'category', boundaryGap: false, data: labels, axisLabel: chartTextStyle() },
            yAxis: {
                type: 'value',
                axisLabel: { ...chartTextStyle(), formatter: v => compactCurrency(v) },
                splitLine: { lineStyle: { color: '#eef2f7' } }
            },
            series
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

    // ---- reconciliation (estimated vs. actual, Cost Management Phase 2) ----

    function buildReconciliationTable() {
        if (!state.reconciliation.totalElements) return '';
        return buildTableCard({
            title: 'Estimated vs. Actual Cost',
            subtitle: 'Real AWS billing data, per environment, where ingested. Variance compares only days that have actual billing data.',
            tableId: 'cost-reconciliation-table',
            bodyId: 'cost-reconciliation-table-body',
            paginationId: 'cost-reconciliation-pagination',
            columns: ['Environment', { label: 'Estimated', numeric: true }, { label: 'Actual', numeric: true }, { label: 'Variance', numeric: true }, { label: 'Coverage', numeric: true }],
            rowsHtml: buildReconciliationRows(state.reconciliation.content),
            pageState: state.reconciliation,
            tableKey: 'reconciliation',
            exportUrl: Config.API.costManagement.reconciliationExport(TREND_DAYS)
        });
    }

    function buildReconciliationRows(rows) {
        if (!rows.length) {
            return `<tr><td colspan="5" class="text-center text-muted py-4">No cost reconciliation data found.</td></tr>`;
        }
        return rows.map(row => {
            const hasActual = row.actualCost !== null && row.actualCost !== undefined;
            const hasVariance = row.variancePercent !== null && row.variancePercent !== undefined;
            const varianceText = hasVariance
                ? Utils.escapeHtml(`${row.variancePercent >= 0 ? '+' : ''}${row.variancePercent}%`)
                : '<span title="No actual billing data yet">&mdash;</span>';
            const coverage = row.daysInWindow ? `${row.daysWithActuals || 0}/${row.daysInWindow} days` : '-';
            return `
                <tr>
                    <td>${Utils.escapeHtml(row.environmentName || '-')}</td>
                    <td class="cost-num">${Utils.formatCurrency(Number(row.estimatedCost) || 0)}</td>
                    <td class="cost-num">${hasActual ? Utils.formatCurrency(Number(row.actualCost)) : '<span class="text-muted">Not yet ingested</span>'}</td>
                    <td class="cost-num">${varianceText}</td>
                    <td class="cost-num">${Utils.escapeHtml(coverage)}</td>
                </tr>
            `;
        }).join('');
    }

    function renderReconciliationTable() {
        $('#cost-reconciliation-table-body').html(buildReconciliationRows(state.reconciliation.content));
        $('#cost-reconciliation-pagination').html(buildPagination('reconciliation', state.reconciliation));
    }

    // ---- tables ----

    function buildTableCard(opts) {
        return `
            <section class="cost-table-card">
                <div class="dashboard-panel-head">
                    <div class="cost-panel-head-text">
                        <h2>${Utils.escapeHtml(opts.title)}</h2>
                        <small>${Utils.escapeHtml(opts.subtitle)}</small>
                    </div>
                    <a class="cost-export-excel-btn" href="${opts.exportUrl}" target="_blank" rel="noopener"
                       title="Download as Excel" aria-label="Download ${Utils.escapeHtml(opts.title)} as Excel">
                        <i class="fas fa-file-excel"></i>
                    </a>
                </div>
                ${opts.toolbarHtml || ''}
                <div class="table-responsive">
                    <table class="table table-baseline cost-table" id="${opts.tableId}">
                        <thead>
                            <tr>${opts.columns.map(c => {
                                const label = typeof c === 'string' ? c : c.label;
                                const cls = (typeof c !== 'string' && c.numeric) ? ' class="cost-num"' : '';
                                return `<th${cls}>${Utils.escapeHtml(label)}</th>`;
                            }).join('')}</tr>
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
            action: 'cost-change-page',
            key: tableKey
        });
    }

    function costCell(amount, costKnown, approximate) {
        const formatted = Utils.formatCurrency(Number(amount) || 0);
        if (costKnown === false) {
            return `${formatted} <i class="fas fa-triangle-exclamation cost-partial-icon" title="Instance pricing unknown for this VM — storage cost only"></i>`;
        }
        if (approximate === true) {
            return `${formatted} <span class="badge badge-approx" title="Rate from another region — approximate">&asymp;</span>`;
        }
        return formatted;
    }

    // Idle & Waste

    function buildIdleWasteTable() {
        return buildTableCard({
            title: 'Idle & Waste, Ranked by Cost Impact',
            subtitle: `${state.idle.totalElements} idle VM(s)`,
            tableId: 'cost-idle-table',
            bodyId: 'cost-idle-table-body',
            paginationId: 'cost-idle-pagination',
            columns: ['VM', 'Group', { label: 'Monthly Cost', numeric: true }, { label: 'Latest CPU', numeric: true }, 'Idle Since', { label: 'Monthly Idle', numeric: true }],
            rowsHtml: buildIdleRows(state.idle.content),
            pageState: state.idle,
            tableKey: 'idle',
            exportUrl: Config.API.costManagement.idleWasteExport()
        });
    }

    function buildIdleRows(rows) {
        if (!rows.length) {
            return `<tr><td colspan="6" class="text-center text-muted py-4">No idle VMs found.</td></tr>`;
        }
        return rows.map(row => `
            <tr>
                <td class="cost-vm-cell" title="${Utils.escapeHtml(row.environmentName || 'Unknown environment')}">${Utils.escapeHtml(row.vmName || row.vmId)}${eksNodes(row)}
                    ${typeof JiraTickets !== 'undefined' ? '<div>' + JiraTickets.cell('IDLE', row.vmId, row.jiraKey, row.jiraUrl) + '</div>' : ''}</td>
                <td>${Utils.escapeHtml(row.groupName || '-')}</td>
                <td class="cost-num">${costCell(row.monthlyCost, row.costKnown)}</td>
                <td class="cost-num">${row.latestCpuUtilization != null ? Number(row.latestCpuUtilization).toFixed(1) + '%' : '-'}</td>
                <td>${row.idleSince ? Utils.formatRelativeTime(row.idleSince) : '-'}</td>
                <td class="cost-num">${costCell(row.monthlyIdleCost, row.costKnown)}</td>
            </tr>
        `).join('');
    }

    function renderIdleTable() {
        $('#cost-idle-table-body').html(buildIdleRows(state.idle.content));
        $('#cost-idle-pagination').html(buildPagination('idle', state.idle));
    }

    // Rightsizing

    /** One page of suggestions with the tab's filters. */
    function rightsizingUrl(page) {
        return Config.API.costManagement.rightsizing(page, RS_PAGE_SIZE, '', state.rightsizingKind, state.rightsizingAgreement);
    }

    /** "14 VMs are bigger than they need to be · $1,180 a month to save" (scale-down suggestions that save). */
    function rightsizingHeadline() {
        const s = state.summary;
        if (!s) return `${state.rightsizing.totalElements || 0} suggestion${state.rightsizing.totalElements === 1 ? '' : 's'}`;
        const n = s.rightsizingCandidateCount || 0;
        if (!n) return 'No VM is bigger than it needs to be';
        return `<b class="cost-mono">${n}</b> VM${n === 1 ? ' is' : 's are'} bigger than ${n === 1 ? 'it needs' : 'they need'} to be
            · <b class="cost-mono rs-save">${money0(s.rightsizingPotentialSavings)}</b> a month to save`;
    }

    function buildRightsizingFilters() {
        const larger = (state.summary && state.summary.scaleUpCandidateCount > 0) || state.rightsizingKind === 'LARGER';
        const kinds = RightsizingKind.FILTERS.filter(([k]) => k !== 'LARGER' || larger).map(([k, label]) =>
            `<option value="${k}"${k === state.rightsizingKind ? ' selected' : ''}>${label}</option>`).join('');
        const agree = [['', 'Any source'], ['AWS', 'AWS agrees']].map(([k, label]) =>
            `<option value="${k}"${k === state.rightsizingAgreement ? ' selected' : ''}>${label}</option>`).join('');
        return `<div class="rs-controls">
                <select class="form-select form-select-sm" id="rs-kind" aria-label="Kind of change" data-action-change="cost-rs-kind">${kinds}</select>
                <select class="form-select form-select-sm" id="rs-agreement" aria-label="Agreement" data-action-change="cost-rs-agreement">${agree}</select>
                <a class="btn btn-ghost btn-sm" id="rs-excel" href="${Config.API.costManagement.rightsizingExport()}" download="rightsizing-${todayStamp()}.xlsx">Excel</a>
            </div>`;
    }

    function buildRightsizingTable() {
        const safe = safeResizeOn();
        return `<section class="rs-panel" aria-labelledby="rs-headline">
                <div class="rs-head">
                    <p class="rs-headline" id="rs-headline">${rightsizingHeadline()}</p>
                    ${buildRightsizingFilters()}
                </div>
                <div class="rs-layout">
                    <div class="cost-card rs-table-card">
                        <div class="cost-scroll"><table class="cost-table rs-table" id="cost-rightsizing-table">
                            <thead><tr><th scope="col">VM</th><th scope="col">Change</th><th scope="col">Peak CPU / memory</th>
                                <th scope="col" class="cost-num">Saves a month</th><th scope="col"><span class="visually-hidden">Action</span></th></tr></thead>
                            <tbody id="cost-rightsizing-table-body">${buildRightsizingRows(state.rightsizing.content)}</tbody>
                        </table></div>
                        <div id="cost-rightsizing-pagination">${rightsizingPager()}</div>
                    </div>
                    <aside class="cost-card rs-aside" aria-labelledby="rs-how-title">
                        <h2 id="rs-how-title">How a safe resize works</h2>
                        <ol>
                            <li>You confirm the new size; the VM must not be locked or mid-operation.</li>
                            <li>Stops the VM, changes its size, starts it again.</li>
                            <li>If it isn't healthy within 10 minutes, it goes back to the old size and starts.</li>
                        </ol>
                        <p class="cost-note">Suggestions use the last 30 days of CPU and memory. Savings count only once a resize has finished.</p>
                        ${safe ? '' : `<p class="rs-off" id="rs-safe-off">Safe resize is switched off, so no VM can be resized from here.
                            An administrator can turn it on in <a href="#/settings">Settings</a>.</p>`}
                    </aside>
                </div>
            </section>`;
    }

    function rightsizingPager() {
        if (!state.rightsizing.totalElements) return '';
        return Pagination.buildPager({ page: state.rightsizing.page + 1, totalItems: state.rightsizing.totalElements,
            pageSize: RS_PAGE_SIZE, action: 'cost-rs-page', key: 'rightsizing' });
    }

    function safeResizeOn() {
        return typeof Capabilities !== 'undefined' && Capabilities.has('safeResize');
    }

    function setRightsizingFilter(which, value) {
        if (which === 'kind') {
            state.rightsizingKind = RightsizingKind.FILTERS.some(([k]) => k === value) ? value : '';
        } else {
            state.rightsizingAgreement = value === 'AWS' ? 'AWS' : '';
        }
        changePage('rightsizing', 0);
    }

    /** "EKS · 3 nodes" for a node-group row (E26-T02). */
    function eksNodes(row) {
        return row.nodeCount ? ` <span class="badge text-bg-light border eks-nodes">EKS · ${Number(row.nodeCount)} node${row.nodeCount === 1 ? '' : 's'}</span>` : '';
    }

    /** A whole percentage, or null (pct, above, keeps one decimal for commitments). */
    function wholePct(v) {
        return v == null ? null : `${Math.round(Number(v))}%`;
    }

    /** "22% / 38%": peak CPU and memory, memory a dash with why when the CloudWatch agent is missing. */
    function peakCell(row) {
        const mem = row.memoryDataAvailable && row.peakMemoryUtilization != null ? wholePct(row.peakMemoryUtilization)
            : '<span class="text-muted rightsizing-mem-unknown" title="CloudWatch agent not reporting memory">—</span>';
        return `${wholePct(row.peakCpuUtilization) || '—'} / ${mem}`;
    }

    function savesCell(row) {
        if (row.estimatedMonthlySavings == null) return '<span class="text-muted">Unknown</span>';
        const approx = row.priceApproximate ? '<span title="Rate from another region — approximate">&asymp;</span>' : '';
        const partial = row.costKnown === false ? ' <i class="fas fa-triangle-exclamation cost-partial-icon" title="Instance pricing unknown for this VM — storage cost only"></i>' : '';
        return approx + money0(row.estimatedMonthlySavings) + partial;
    }

    /** The VM, linked to its details in its environment (EKS node groups have no VM page). */
    function vmCell(row, i) {
        const name = Utils.escapeHtml(row.vmName || row.vmId);
        const link = row.nodeCount || !row.environmentId ? `<span class="rs-name">${name}</span>`
            : `<a class="rs-name" href="#/environments/${encodeURIComponent(row.environmentId)}?vm=${encodeURIComponent(row.vmId)}">${name}</a>`;
        return `${link}${eksNodes(row)}<span class="rs-sub">${Utils.escapeHtml(row.environmentName || 'Unknown environment')}</span>${optionsToggle(row, i)}`;
    }

    /** "m5.2xlarge → m6a.xlarge" over "Smaller, newer · AWS agrees". */
    function changeCell(row) {
        if (!row.suggestedInstanceType) {
            const tooFull = (row.reasons || []).includes('MEMORY_WOULD_NOT_FIT');
            return `<span class="cost-mono">${Utils.escapeHtml(row.currentInstanceType || '-')}</span>
                <span class="rs-sub"${tooFull ? ' data-reason="MEMORY_WOULD_NOT_FIT"' : ''}>${tooFull
                    ? 'A smaller type would not hold the memory peak' : 'No smaller type found'}</span>`;
        }
        const kind = RightsizingKind.label(row);
        const agree = agreementBadge(row);
        return `<span class="cost-mono">${Utils.escapeHtml(row.currentInstanceType || '-')} → ${Utils.escapeHtml(row.suggestedInstanceType)}</span>
            <span class="rs-sub">${Utils.escapeHtml(kind)}${kind && agree ? ' · ' : ''}${agree}</span>`;
    }

    function buildRightsizingRows(rows) {
        if (!rows.length) {
            const filtered = state.rightsizingKind || state.rightsizingAgreement;
            return `<tr><td colspan="5" class="text-center text-muted py-4">${filtered
                ? 'No suggestion matches these filters.' : 'No VM needs a different size right now.'}</td></tr>`;
        }
        return rows.map((row, i) => `
            <tr data-rs-vm="${Utils.escapeHtml(row.vmId)}">
                <td class="rs-vm">${vmCell(row, i)}</td>
                <td class="rs-change">${changeCell(row)}</td>
                <td class="cost-mono rs-peak">${peakCell(row)}</td>
                <td class="cost-num cost-mono rs-saves">${savesCell(row)}</td>
                <td class="rs-action">${buildRightsizingApplyButton(row)}
                    ${typeof JiraTickets !== 'undefined' ? '<div class="mt-1">' + JiraTickets.cell('RIGHTSIZING', row.vmId, row.jiraKey, row.jiraUrl) + '</div>' : ''}</td>
            </tr>
            ${alternativesRow(row, i)}
        `).join('');
    }

    const AGREEMENT_TITLES = {
        AGREE: 'Compute Optimizer and the local rules point the same way',
        ONLY_AWS: 'Only Compute Optimizer flags this VM',
        DISAGREE: 'Compute Optimizer and the local rules point different ways'
    };

    /** What AWS Compute Optimizer thinks of the suggestion (E30-T05); nothing when only the local rule speaks. */
    function agreementBadge(row) {
        const text = RightsizingKind.agreementText(row);
        if (!text) return '';
        return `<span class="rightsizing-agreement${row.agreement === 'DISAGREE' ? ' rs-disagree' : ''}" data-agreement="${Utils.escapeHtml(row.agreement)}"
            title="${AGREEMENT_TITLES[row.agreement] || ''}">${text}</span>`;
    }

    function rowKey(row, i) {
        return 'rs-alt-' + String(row.vmId || i).replace(/[^A-Za-z0-9_-]/g, '') + '-' + i;
    }

    function optionsToggle(row, i) {
        const n = Array.isArray(row.alternatives) ? row.alternatives.length : 0;
        if (!n) return '';
        return `<div><button type="button" class="btn btn-link btn-sm p-0 rightsizing-options-toggle" data-action="cost-rightsizing-options"
                    aria-expanded="false" aria-controls="${rowKey(row, i)}">Options (${n})</button></div>`;
    }

    const KIND_LABELS = { RIGHTSIZE: 'Rightsize', NEWER_GENERATION: 'Newer generation', GRAVITON: 'Graviton (ARM64)' };

    function optionApply(row, o) {
        if (!o.applicable) {
            return `<span class="text-muted small rightsizing-option-why">${Utils.escapeHtml(o.applicableReason || 'Not applicable')}</span>`;
        }
        return applyButton(row, o.instanceType);
    }

    /**
     * Apply opens the checks dialog (E30-T07) for a running or stopped VM; a running one can only
     * go ahead with the safe stop-resize-start.
     */
    function applyButton(row, targetType) {
        const safe = safeResizeOn();
        const usable = safe && (row.vmStatus === 'STOPPED' || row.vmStatus === 'RUNNING');
        const why = !safe ? 'Safe resize is switched off in Settings'
            : usable ? 'Change the instance type (checks run first)' : 'The VM must be running or stopped';
        return `<button type="button" class="btn btn-link btn-sm rightsizing-apply-btn" ${usable ? '' : 'disabled'}
                    title="${why}"
                    data-vm-id="${Utils.escapeHtml(row.vmId)}" data-vm-name="${Utils.escapeHtml(row.vmName || row.vmId)}"
                    data-vm-status="${Utils.escapeHtml(row.vmStatus || '')}"
                    data-current-type="${Utils.escapeHtml(row.currentInstanceType || '-')}"
                    data-target-type="${Utils.escapeHtml(targetType)}">Resize safely…</button>`;
    }

    /** The alternatives of one recommendation, shown under its row on demand (E30-T05). */
    function alternativesRow(row, i) {
        const options = Array.isArray(row.alternatives) ? row.alternatives : [];
        if (!options.length) return '';
        const body = options.map(o => `
            <tr data-option="${Utils.escapeHtml(o.instanceType)}">
                <td>${Utils.escapeHtml(o.instanceType)}</td>
                <td>${Utils.escapeHtml(KIND_LABELS[o.kind] || o.kind || '')}${o.source === 'compute-optimizer' ? ' <span class="badge bg-primary">AWS</span>' : ''}</td>
                <td>${Utils.escapeHtml(o.architecture || '-')}${o.requiresArchitectureChange
                    ? ' <i class="fas fa-exclamation-triangle text-warning rightsizing-arch-warning" title="Requires an image built for this architecture" aria-label="Requires another CPU architecture"></i>' : ''}</td>
                <td class="cost-num">${o.monthlyCostAfter != null ? Utils.formatCurrency(Number(o.monthlyCostAfter)) : '<span class="text-muted">Unknown</span>'}</td>
                <td class="cost-num">${o.monthlySavings != null ? Utils.formatCurrency(Number(o.monthlySavings)) : '<span class="text-muted">-</span>'}</td>
                <td class="cost-num">${o.performanceRisk != null ? Number(o.performanceRisk).toFixed(1) : '-'}</td>
                <td>${optionApply(row, o)}</td>
            </tr>`).join('');
        return `<tr class="rightsizing-alternatives" id="${rowKey(row, i)}" hidden>
            <td colspan="5">
                <table class="table table-sm mb-0 rightsizing-options-table" aria-label="Options for ${Utils.escapeHtml(row.vmName || row.vmId)}">
                    <thead><tr><th>Type</th><th>Option</th><th>Architecture</th><th class="cost-num">Cost after</th>
                        <th class="cost-num">Saving</th><th class="cost-num">AWS risk</th><th>Action</th></tr></thead>
                    <tbody>${body}</tbody>
                </table>
            </td>
        </tr>`;
    }

    function toggleOptions(btn) {
        const target = document.getElementById(btn.getAttribute('aria-controls'));
        if (!target) return;
        const open = btn.getAttribute('aria-expanded') !== 'true';
        btn.setAttribute('aria-expanded', String(open));
        target.hidden = !open;
    }

    /** Memory utilisation, or a dash explaining the CloudWatch agent is not reporting it (E30-T03). */
    function memoryCell(row, value) {
        if (!row.memoryDataAvailable || value == null) {
            return '<span class="text-muted rightsizing-mem-unknown" title="CloudWatch agent not reporting memory">—</span>';
        }
        return Number(value).toFixed(1) + '%';
    }

    function buildRightsizingApplyButton(row) {
        if (!row.suggestedInstanceType) {
            return '<span class="text-muted small">No suggestion</span>';
        }
        if (row.nodeCount) {
            // EKS node groups change their instance type in EKS (node group / launch template), E26-T02.
            return '<span class="text-muted small">Change the node group in EKS</span>';
        }
        if (row.applicable === false) {
            // e.g. a Graviton option needs an ARM64 image first (E30-T05)
            return `<span class="text-muted small rightsizing-not-applicable">${Utils.escapeHtml(row.applicableReason || 'Not applicable here')}</span>`;
        }
        return applyButton(row, row.suggestedInstanceType);
    }

    function renderRightsizingTable() {
        $('#cost-rightsizing-table-body').html(buildRightsizingRows(state.rightsizing.content));
        $('#cost-rightsizing-pagination').html(rightsizingPager());
    }

    // VM Cost Detail

    function bindEvents() {
        $(document).off('change.costAnomalies').on('change.costAnomalies', '#cost-anomaly-env', function() {
            state.anomalyFilter = $(this).val();
            renderPanel('anomalies');
        });
        $('#cost-backfill-btn').off('click').on('click', () => confirmAction('Fill in the last 30 days',
            "Estimates each of the last 30 days' cost from the VMs' run time, for the trend chart. Days that already have figures are kept.",
            'Fill in', triggerBackfill));
        $('#cost-reconcile-tags-btn').off('click').on('click', () => confirmAction('Write cost tags now',
            'Adds the tcg:managed-by, tcg:environment and tcg:team tags to your AWS resources, so AWS billing can split costs by them.',
            'Write tags', triggerTagReconciliation));
        $('#cost-ingest-actuals-btn').off('click').on('click', () => confirmAction('Fetch AWS billing now',
            'Reads the last 30 days of actual costs from AWS Cost Explorer. AWS charges a small fee for each request.',
            'Fetch billing', triggerActualsIngestion));
        $('#cost-export-png-btn').off('click').on('click', exportPng);
        $('#cost-export-pdf-btn').off('click').on('click', exportPdf);
        $(window).off('resize.costManagementCharts').on('resize.costManagementCharts', resizeCharts);

        // Delegated (not direct) so it survives renderRightsizingTable()'s partial re-render of
        // just the table body/pagination, not only the full-page render() that calls bindEvents.
        $('#content-area').off('click.rightsizingApply')
            .on('click.rightsizingApply', '.rightsizing-apply-btn', function() {
                if ($(this).prop('disabled')) return;
                applyRightsizing($(this).data());
            });
        $('#content-area').off('click.costRetry')
            .on('click.costRetry', '.cost-retry', function() {
                retryPanel(String($(this).data('key')), $(this));
            });
    }

    const APPLY_MODAL = 'rightsizingApplyModal';
    const RESIZE_POLL = 'rightsizingResize';
    const RESIZE_POLL_MS = 5000;

    function call(method, url, body) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, body || null, { suppressGlobalError: true }).done(resolve).fail(reject);
        });
    }

    function serverMessage(e, fallback) {
        return (e && e.responseJSON && e.responseJSON.message) || fallback;
    }

    /**
     * Apply (E30-T07): the dialog lists every pre-check of the safe resize; Confirm is enabled
     * only when they pass and, for a running VM, once the admin accepts the stop and restart.
     * With safe resize off, a stopped VM can still change type the old way. A safe resize is
     * followed until it completes or rolls back.
     */
    function applyRightsizing(data) {
        const running = data.vmStatus === 'RUNNING';
        const body = `
            <p class="mb-2">Change <strong>${Utils.escapeHtml(String(data.vmName))}</strong> from
                <strong>${Utils.escapeHtml(String(data.currentType))}</strong> to <strong>${Utils.escapeHtml(String(data.targetType))}</strong>.</p>
            <ul class="list-unstyled rs-checks mb-2" id="rs-checks" aria-live="polite">
                <li class="text-muted small"><i class="fas fa-spinner fa-spin" aria-hidden="true"></i> Running the checks…</li>
            </ul>
            <div class="small text-muted mb-2" id="rs-mode-note"></div>
            ${running ? `<div class="form-check rs-restart">
                <input class="form-check-input" type="checkbox" id="rs-restart">
                <label class="form-check-label" for="rs-restart">Stop, resize and start again</label>
                <div class="form-text">The VM will be stopped and started; typically 2–5 minutes of downtime. If it will not start on the new type, it goes back to the old one.</div>
            </div>` : ''}
            <div class="rs-progress mt-3" id="rs-progress" role="status" aria-live="polite" hidden></div>`;
        Modals.show({
            id: APPLY_MODAL,
            title: 'Change instance type',
            body,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true, id: 'rs-apply-cancel' },
                { text: 'Apply', class: 'btn-primary', id: 'rs-apply-confirm', disabled: true }
            ]
        });
        let mode = null; // 'safe' | 'stopped' | null
        const refresh = () => {
            const restartOk = !running || $('#rs-restart').is(':checked');
            $('#rs-apply-confirm').prop('disabled', !mode || (mode === 'safe' && !restartOk));
        };
        $(`#${APPLY_MODAL}`).on('change', '#rs-restart', refresh);
        call('GET', Config.API.costManagement.rightsizingPrecheck(data.vmId, data.targetType)).then(checks => {
            checks = checks || [];
            $('#rs-checks').html(checks.map(c => `<li class="rs-check ${c.passed ? 'text-success' : 'text-danger'}" data-check="${Utils.escapeHtml(c.name)}">
                    <i class="fas ${c.passed ? 'fa-check-circle' : 'fa-times-circle'}" aria-hidden="true"></i>
                    <span class="visually-hidden">${c.passed ? 'Passed' : 'Failed'}:</span>
                    <strong>${Utils.escapeHtml(c.name)}</strong> <span class="text-body">${Utils.escapeHtml(c.message || '')}</span></li>`).join(''));
            const failed = checks.filter(c => !c.passed);
            if (!failed.length) {
                mode = 'safe';
            } else if (!running && failed.every(c => c.name === 'Safe resize')) {
                mode = 'stopped';
                $('#rs-mode-note').text('Safe resize is off: the type changes while the VM stays stopped.');
            } else {
                $('#rs-mode-note').text('Fix the failed checks to apply this change.');
            }
            refresh();
        }).catch(e => {
            $('#rs-checks').html(`<li class="text-danger">${Utils.escapeHtml(serverMessage(e, "Couldn't run the checks."))}</li>`);
        });
        $('#rs-apply-confirm').on('click', function() {
            if (!mode) return;
            $(this).prop('disabled', true);
            $('#rs-restart').prop('disabled', true);
            const payload = { vmId: data.vmId, targetInstanceType: data.targetType, restartIfRunning: mode === 'safe' };
            call('POST', Config.API.costManagement.applyRightsizing(), payload).then(res => {
                if (mode === 'stopped' || !res || !res.resizeId) {
                    Notifications.success(`Instance type change applied for ${data.vmName}.`);
                    Modals.hideWhenReady(document.getElementById(APPLY_MODAL));
                    changePage('rightsizing', state.rightsizing.page);
                    return;
                }
                followResize(res.resizeId, data);
            }).catch(e => {
                $('#rs-progress').prop('hidden', false).html(`<div class="alert alert-danger mb-0">${Utils.escapeHtml(serverMessage(e, 'The change could not be applied.'))}</div>`);
            });
        });
    }

    const STATUS_TEXT = {
        REQUESTED: 'Requested', STOPPING: 'Stopping', RESIZING: 'Changing the type', STARTING: 'Starting',
        COMPLETED: 'Completed', ROLLING_BACK: 'Rolling back', ROLLED_BACK: 'Rolled back', FAILED: 'Failed'
    };

    /** Polls a safe resize until it finishes; stops when the user leaves the page (E30-T07). */
    function followResize(resizeId, data) {
        const show = (r) => {
            const $p = $('#rs-progress').prop('hidden', false);
            if (!r.finished) {
                $p.html(`<div class="d-flex align-items-center gap-2"><i class="fas fa-spinner fa-spin" aria-hidden="true"></i>
                    <span><strong class="rs-status">${Utils.escapeHtml(STATUS_TEXT[r.status] || r.status)}</strong> —
                    <span class="rs-stage">${Utils.escapeHtml(r.stage || '')}</span></span></div>`);
                return;
            }
            const ok = r.status === 'COMPLETED';
            $p.html(`<div class="alert ${ok ? 'alert-success' : 'alert-warning'} mb-0">
                <strong class="rs-status">${Utils.escapeHtml(STATUS_TEXT[r.status] || r.status)}</strong>: <span class="rs-stage">${Utils.escapeHtml(r.stage || '')}</span>
                ${r.errorMessage ? `<div class="small mt-1 rs-error">${Utils.escapeHtml(r.errorMessage)}</div>` : ''}</div>`);
            $('#rs-apply-cancel').text('Close');
        };
        show({ status: 'REQUESTED', stage: 'Requested', finished: false });
        RealTime.startPolling(RESIZE_POLL, () => {
            call('GET', Config.API.costManagement.rightsizingResize(resizeId)).then(r => {
                show(r);
                if (!r.finished) return;
                RealTime.stopPolling(RESIZE_POLL);
                if (r.status === 'COMPLETED') {
                    Notifications.success(`${data.vmName} now runs on ${r.newInstanceType}.`);
                } else {
                    Notifications.showError(`${data.vmName}: ${STATUS_TEXT[r.status] || r.status}. ${r.errorMessage || ''}`);
                }
                if (isActive()) changePage('rightsizing', state.rightsizing.page);
            }).catch(() => { /* keep polling; a transient error must not stop following the resize */ });
        }, RESIZE_POLL_MS, { pageScoped: true });
    }

    /** Each data action says what it does and runs only after a yes (U15-T01). */
    function confirmAction(title, text, confirmText, run) {
        Modals.confirm(title, text, run, { confirmText, confirmClass: 'btn-primary' });
    }

    function triggerBackfill() {
        const $btn = $('#cost-backfill-btn');
        $btn.prop('disabled', true).text('Filling in…');
        ApiClient.post(Config.API.costManagement.backfill(30), null)
            .done(() => {
                Notifications.success('Cost history backfilled for the last 30 days.');
                Promise.all([
                    fetchJson(Config.API.costManagement.spendTrend(TREND_DAYS)),
                    fetchJson(Config.API.costManagement.forecast(30, 14))
                ]).then(([trend, forecast]) => {
                    if (!isActive()) return;
                    state.trend = trend || [];
                    state.forecast = forecast || null;
                    chart('cost-chart-trend', buildTrendOption(state.trend));
                });
            })
            .fail(() => {
                Notifications.error('Failed to backfill cost history.');
            })
            .always(() => {
                $btn.prop('disabled', false).text('Fill in the last 30 days');
            });
    }

    function triggerTagReconciliation() {
        const $btn = $('#cost-reconcile-tags-btn');
        $btn.prop('disabled', true).text('Writing tags…');
        ApiClient.post(Config.API.costManagement.tagsReconcile, null)
            .done(result => {
                if (result.total === 0) {
                    Notifications.info('Cost-allocation tagging is disabled (cost.tagging.enabled=false) — nothing to reconcile.');
                } else {
                    Notifications.success(`Tag reconciliation complete — ${result.tagged} tagged, ${result.failed} failed, ${result.total} total.`);
                }
            })
            .fail(() => {
                Notifications.error('Failed to reconcile cost-allocation tags.');
            })
            .always(() => {
                $btn.prop('disabled', false).text('Write cost tags now');
            });
    }

    function triggerActualsIngestion() {
        const $btn = $('#cost-ingest-actuals-btn');
        $btn.prop('disabled', true).text('Fetching billing…');
        ApiClient.post(Config.API.costManagement.actualsBackfill(30), null)
            .done(result => {
                if (result.updated === 0 && result.skippedNoEnvironment === 0 && result.skippedNoSnapshotRow === 0) {
                    Notifications.info('No actual costs ingested — cost.actuals.enabled is likely false, or AWS is not configured.');
                    $btn.prop('disabled', false).text('Fetch AWS billing now');
                    return;
                }
                Notifications.success(`Actual cost ingestion complete — ${result.updated} day/environment row(s) updated.`);
                if (isActive()) fetchAll();
            })
            .fail(() => {
                Notifications.error('Failed to ingest actual costs.');
                $btn.prop('disabled', false).text('Fetch AWS billing now');
            });
    }

    // ---- export ----

    function todayStamp() {
        return new Date().toISOString().split('T')[0];
    }

    /** Every row of the open tab's tables, for an export of this page. */
    function fetchFullTableData() {
        const panels = tabOf(state.tab).panels;
        const all = (name, url) => panels.includes(name) ? fetchJson(url) : Promise.resolve(null);
        return Promise.all([
            all('idle', Config.API.costManagement.idleWaste(0, EXPORT_SIZE)),
            all('rightsizing', Config.API.costManagement.rightsizing(0, EXPORT_SIZE))
        ]);
    }

    function exportPdf() {
        if (exporting) return;
        exporting = true;

        fetchFullTableData().then(([idleAll, rightsizingAll]) => {
            const idleRows = (idleAll && idleAll.content) || [];
            const rightsizingRows = (rightsizingAll && rightsizingAll.content) || [];

            if (idleAll) {
                $('#cost-idle-table-body').html(buildIdleRows(idleRows));
                $('#cost-idle-pagination').html(`<span class="text-muted small">All ${idleRows.length} row(s) shown for export</span>`);
            }
            if (rightsizingAll) {
                $('#cost-rightsizing-table-body').html(buildRightsizingRows(rightsizingRows));
                $('#cost-rightsizing-pagination').html(`<span class="text-muted small">All ${rightsizingRows.length} row(s) shown for export</span>`);
            }
            $('#cost-reconciliation-table-body').html(buildReconciliationRows(state.reconciliationAll));
            $('#cost-reconciliation-pagination').html(`<span class="text-muted small">All ${state.reconciliationAll.length} row(s) shown for export</span>`);

            const restore = () => {
                if (idleAll) renderIdleTable();
                if (rightsizingAll) renderRightsizingTable();
                renderReconciliationTable();
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

        fetchFullTableData().then(([idleAll, rightsizingAll]) => {
            const source = document.getElementById(`cost-tabs-panel-${tabOf(state.tab).key}`) || document.getElementById('cost-report');
            const clone = source.cloneNode(true);
            clone.id = 'cost-report-export-clone';
            clone.style.position = 'fixed';
            clone.style.top = '-10000px';
            clone.style.left = '0';
            clone.style.width = source.offsetWidth + 'px';
            clone.style.background = '#ffffff';

            const idleRows = (idleAll && idleAll.content) || [];
            const rightsizingRows = (rightsizingAll && rightsizingAll.content) || [];

            const idleBody = clone.querySelector('#cost-idle-table-body');
            const rightsizingBody = clone.querySelector('#cost-rightsizing-table-body');
            const reconciliationBody = clone.querySelector('#cost-reconciliation-table-body');
            if (idleBody && idleAll) idleBody.innerHTML = buildIdleRows(idleRows);
            if (rightsizingBody && rightsizingAll) rightsizingBody.innerHTML = buildRightsizingRows(rightsizingRows);
            if (reconciliationBody) reconciliationBody.innerHTML = buildReconciliationRows(state.reconciliationAll);
            clone.querySelectorAll('.pagination-bar-wrap').forEach(el => {
                el.innerHTML = '<span class="text-muted small">All rows shown for export</span>';
            });

            // html2canvas cannot reliably snapshot live SVG-rendered ECharts, so swap each
            // chart <div> for a static <img> taken from the chart's own dataURL first.
            ['cost-chart-trend'].forEach(id => {
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
        changePage,
        showWorkloads: mountWorkloads,
        setRightsizingFilter,
        toggleOptions,
        setForecastRange,
        openFocusExport,
        setCarbonMonth,
        setBreakdownBy,
        model: { monthToDate, monthEnd, topBars, teamsThisMonth, topSavings, commitmentFigures, recommendationDetail, termText, carbonBars }
    };
})();

// Make available globally
if (typeof window !== 'undefined') window.CostManagement = CostManagement;

if (typeof Actions !== 'undefined') Actions.registerAll({
    'cost-reload': () => ContentRouter.reload(),
    'cost-change-page': el => CostManagement.changePage(el.dataset.pageKey, Number(el.dataset.page)),
    'cost-rs-page': el => CostManagement.changePage('rightsizing', Number(el.dataset.page) - 1),
    'cost-rightsizing-options': el => CostManagement.toggleOptions(el),
    'cost-forecast-range': el => CostManagement.setForecastRange(el.dataset.range),
    'cost-focus-export': () => CostManagement.openFocusExport()
});
if (typeof Actions !== 'undefined') {
    Actions.register('cost-eks-env', el => CostManagement.showWorkloads(el.value), 'change');
    Actions.register('cost-rs-kind', el => CostManagement.setRightsizingFilter('kind', el.value), 'change');
    Actions.register('cost-rs-agreement', el => CostManagement.setRightsizingFilter('agreement', el.value), 'change');
    Actions.register('cost-carbon-month', el => CostManagement.setCarbonMonth(el.value), 'change');
    Actions.register('cost-breakdown-by', el => CostManagement.setBreakdownBy(el.value), 'change');
}
