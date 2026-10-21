/**
 * VM Self-Service Platform - Dashboard Feature
 * Role-aware dashboard backed by /api/v1/dashboard/summary. Everyone but administrators gets Home
 * (U06-T02, Dashboard.dc.html): a one-line summary, "Needs you" tiles and up to four environment
 * cards with power strips. Administrators get their own Home (U14-T01, admin-home.js): needs
 * attention, fleet right now, spend this month and platform health. Both refresh quietly every 30 s.
 * The old dashboard's charts moved to System health's Fleet use tab (U14-T09, fleet-use.js).
 */
const Dashboard = (function() {
    'use strict';

    let dashboardData = null;
    /** Home is laid out for a phone under this width (U16-T02). */
    const PHONE_QUERY = '(max-width: 768px)';
    let isLoading = false;
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
            startAutoRefresh();
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
        lastRenderPhone = isPhone();
        disposeTooltips();
        $('#content-area').html(buildDashboardHtml(data));
        bindEvents();
        if (isAdmin(data)) AdminHome.fill(data); else if (!lastRenderPhone) loadRecentActivity();
        initTooltips();
    }

    // Crossing the phone width redraws Home in the other form (U16-T02), from the data it already has.
    let lastRenderPhone = null;
    if (typeof window !== 'undefined' && window.matchMedia) {
        const media = window.matchMedia(PHONE_QUERY);
        const redraw = () => {
            if (lastRenderPhone === null || lastRenderPhone === isPhone()) return;
            if (!document.getElementById('dashboard-view') || !dashboardData || isAdmin(dashboardData)) return;
            renderDashboard(dashboardData);
        };
        if (media.addEventListener) media.addEventListener('change', redraw); else if (media.addListener) media.addListener(redraw);
    }

    /** Runs when the user leaves the dashboard (ContentRouter.onLeave). */
    function teardown() {
        RealTime.stopPolling('dashboard');
        disposeTooltips();
        $('#content-area').off('.dashboardJump');
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

    /** Everyone lands on Home (U06-T02, U14-T01). */
    function pageTitle() {
        return 'Home';
    }

    function isAdmin(data) {
        return !!data && data.persona === 'ADMIN';
    }

    function buildDashboardHtml(data) {
        return isAdmin(data) ? AdminHome.html(data) : buildHomeHtml(data);
    }

    // ---------------------------------------------------------------- Home (U06-T02)

    function money(value) {
        if (value === null || value === undefined || value === '') return '—';
        const amount = Number(value);
        return Number.isFinite(amount)
            ? '$' + amount.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '—';
    }

    /** Home on a phone (U16-T02): the same data in the PhoneHome.dc.html form. */
    function isPhone() {
        return typeof window !== 'undefined' && window.matchMedia && window.matchMedia(PHONE_QUERY).matches;
    }

    function buildPhoneHomeHtml(data) {
        const cards = data.environments || [];
        const t = HomeModel.totals(cards);
        const now = new Date();
        const summary = cards.length
            ? Utils.html`<span class="mono">${t.running}</span>/<span class="mono">${t.total}</span> VMs running · `
                + Utils.html`<span class="mono">${money(data.summary && data.summary.monthToDateCost)}</span> this month`
            : 'Nothing to show yet.';
        const alert = HomeModel.phoneAlert(data.needsYou || [], now);
        const alertHtml = alert ? Utils.html`<a class="phone-home-alert" href="${alert.href}">
                <span class="needs-you-dot" aria-hidden="true"></span>
                <span class="phone-home-alert-text">${alert.environment} stops at <span class="mono">${alert.at}</span></span>
                <b>Extend</b></a>` : '';
        const items = HomeModel.pickCards(cards, 4).map(card => {
            const chip = HomeModel.chip(card);
            const strip = PowerStrip.render(PowerStrip.fromGroupStates(card.groupStates || []), { large: true });
            return Utils.html`<a class="phone-home-card" href="#/environments/${encodeURIComponent(card.environmentId)}">
                    <div class="phone-home-card-head"><b>${card.displayName || card.name}</b>
                        <span class="chip chip-${chip.kind}">${chip.label}</span></div>`
                + strip
                + Utils.html`<div class="phone-home-line">${HomeModel.cardLine(card, now)}</div></a>`;
        }).join('');
        const more = cards.length > 4 ? `<a class="phone-home-more" href="#/my-environments">All ${cards.length} environments</a>` : '';
        return `
            <div class="content-view page home-page phone-home" id="dashboard-view">
                ${PageHeader.markup({ title: 'Home', summaryHtml: summary })}
                ${alertHtml}
                ${cards.length ? `<section class="phone-home-cards" aria-label="Your environments">${items}${more}</section>` : buildHomeEmpty()}
            </div>
        `;
    }

    function buildHomeHtml(data) {
        if (isPhone()) return buildPhoneHomeHtml(data);
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

    function bindEvents() {
        $('#content-area').off('click.dashboardJump', '[data-dashboard-jump]')
            .on('click.dashboardJump', '[data-dashboard-jump]', function() {
                const target = document.getElementById($(this).data('dashboard-jump'));
                if (!target) return;
                target.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
                target.classList.add('dashboard-panel-highlight');
                setTimeout(() => target.classList.remove('dashboard-panel-highlight'), 1100);
            });
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

    function startAutoRefresh() {
        // Page scoped: stops when the user leaves the dashboard.
        RealTime.startPolling('dashboard', silentRefresh, 30000, { immediate: false, pageScoped: true });
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

    return {
        load,
        refresh,
        getData
    };
})();

Actions.register('dashboard-refresh', () => Dashboard.refresh());
// Home's "Ask for more time": My Account on the Access tab with that grant's extension form (U06-T02).
Actions.register('home-all-activity', () => ContentRouter.navigate('activity-logs'));
Actions.register('home-needs-more', () => MyAccount.open('access'));
Actions.register('home-extend-access', el => MyAccount.open('access', { extend: el.getAttribute('data-access-id') }));
