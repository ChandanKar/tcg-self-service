/**
 * VM Self-Service Platform - Administrator's Home (U14-T01, AdminDashboard.dc.html)
 * "38 of 112 VMs running across 21 environments · $6,284 this month, heading for $9,950", then
 * Needs attention (access requests, approvals, failed operations in 24 h, VMs to review, budgets
 * almost used, late scheduled jobs; tiles with nothing hidden), Fleet right now (the 7 environments
 * with the most running VMs and their power strips), Spend this month (spent so far, the month-end
 * forecast and, with budgets, the plan line; the two largest environments) and one platform-health
 * line linking to System health. Dashboard draws the page from /dashboard/summary; this module adds
 * /dashboard/attention and the month's daily spend, kept for a minute across quiet refreshes.
 */
const AdminHome = (function() {
    'use strict';

    const FLEET_ROWS = 7;
    const EXTRAS_FRESH_MS = 60000;
    const SYNC_BEHIND_MS = 30 * 60000;
    /** System health's Reports with only the changes made outside the platform, 24 h (U23-T09). */
    const DRIFT_HREF = '#/system-health?tab=reports&changes=outside';

    let extras = null; // {at, attention, trend, attentionFailed, trendFailed}
    const fleetGroups = new Map(); // environmentId -> power strip groups, to redraw a row's strip
    const MIN_NAME_PX = 96;
    let fitTimer = null;

    // ---- pure model (node tests: src/test/js/admin-home.test.mjs) ----

    function plural(n, one, many) {
        return `${Number(n).toLocaleString('en-US')} ${n === 1 ? one : (many || one + 's')}`;
    }

    function whole(value) {
        return '$' + Math.round(Number(value) || 0).toLocaleString('en-US');
    }

    /** "45 min", "3 h", "2 days" for a span in seconds. */
    function span(seconds) {
        const s = Math.max(0, Number(seconds) || 0);
        if (s < 3600) return plural(Math.max(1, Math.round(s / 60)), 'min', 'min');
        if (s < 86400) return `${Math.round(s / 3600)} h`;
        return plural(Math.round(s / 86400), 'day');
    }

    function since(at, now) {
        const t = at ? new Date(at).getTime() : NaN;
        return Number.isFinite(t) ? span((now.getTime() - t) / 1000) : null;
    }

    /** The Needs attention tiles with something in them, in the mockup's order. */
    function tiles(att, now = new Date()) {
        if (!att) return [];
        const out = [];
        const add = (key, count, title, detail, href, tone) => { if (count > 0) out.push({ key, count, title, detail, href, tone }); };
        const waiting = w => (w && w.oldestAt ? `Oldest waiting ${since(w.oldestAt, now)}` : '');
        const req = att.accessRequests || {};
        add('access', Number(req.count) || 0, 'Access requests', waiting(req), '#/pending-requests', 'navy');
        const appr = att.approvals || {};
        add('approvals', Number(appr.count) || 0, 'Approvals', waiting(appr), '#/approvals', 'navy');
        const failed = att.failedOperations || {};
        const stuck = Number(failed.stuck) || 0;
        const where = (failed.environmentNames || []).join(', ');
        add('failed', (Number(failed.count24h) || 0) + stuck, 'Failed operations, 24 h',
            [where, stuck ? `${stuck} stopped reporting` : ''].filter(Boolean).join(' · '), '#/audit-logs-all?result=failed&range=24h', 'red');
        const vms = att.vmsToReview || {};
        const drift = Number(vms.drift) || 0;
        const found = Number(vms.pendingReview) || 0;
        add('vms', drift + found, 'VMs to review',
            [drift ? `${drift} changed outside the platform, 24 h` : '', found ? `${found} found by discovery` : ''].filter(Boolean).join(', '),
            // U23-T09: the changes are listed in System health's Reports (the registry showed nothing about them).
            drift ? DRIFT_HREF : '#/vm-registry', 'gold');
        const budgets = att.budgets || {};
        const near = Number(budgets.nearLimit) || 0;
        const risk = budgetRisk(budgets.top);
        add('budgets', near, risk.heading ? (near === 1 ? 'Budget heading over' : 'Budgets heading over')
            : (near === 1 ? 'Budget almost used' : 'Budgets almost used'), risk.detail, '#/budgets', 'red');
        const jobs = att.lateJobs || {};
        const late = Number(jobs.count) || 0;
        add('jobs', late, late === 1 ? 'Late scheduled job' : 'Late scheduled jobs', jobText(jobs.worst), '#/system-health', 'gold');
        // U19-T04: EKS environments nobody gave a region are not synced; the link opens the first one's settings.
        const regions = att.regionsUnknown || {};
        const unknown = Number(regions.count) || 0;
        add('regions', unknown, unknown === 1 ? 'Cluster without a region' : 'Clusters without a region',
            (regions.environmentNames || []).join(', '),
            regions.firstEnvironmentId ? `#/environments/${encodeURIComponent(regions.firstEnvironmentId)}/settings/details` : '#/my-environments', 'gold');
        return out;
    }

    /**
     * The budget most at risk in words (U23-T10): "ISE heading for 131% of its budget" when its month-end
     * forecast passes the amount and is the larger figure, else "ISE at 62%". Staging said "Budget almost
     * used: ISE at 62%" while ISE's forecast was $65.57 of its $50. heading: the forecast is the risk
     * and less than 90% is spent, so the tile is not "almost used".
     */
    function budgetRisk(top) {
        if (!top) return { detail: '', heading: false };
        const used = Number(top.percentUsed) || 0;
        const forecast = top.forecastPercent === null || top.forecastPercent === undefined ? null : Number(top.forecastPercent);
        if (forecast !== null && Number.isFinite(forecast) && forecast > 100 && forecast > used) {
            return { detail: `${top.scopeName} heading for ${Math.round(forecast)}% of its budget`, heading: used < 90 };
        }
        return { detail: `${top.scopeName} at ${Math.round(used)}%`, heading: false };
    }

    /** "Cost snapshot, 2 h late" or "Cost snapshot, never ran". */
    function jobText(job) {
        if (!job) return '';
        return `${job.label}, ${job.lateBySeconds === null || job.lateBySeconds === undefined ? 'never ran' : `${span(job.lateBySeconds)} late`}`;
    }

    /** The environments with the most running VMs (then by name) that have a power strip. */
    function fleet(cards, max = FLEET_ROWS) {
        return (cards || []).filter(c => (c.groupStates || []).length)
            .slice()
            .sort((a, b) => (Number(b.runningVms) || 0) - (Number(a.runningVms) || 0)
                || String(a.displayName || a.name).localeCompare(String(b.displayName || b.name)))
            .slice(0, max);
    }

    /** The two environments that cost most this month. */
    function topSpenders(cards, max = 2) {
        return (cards || []).filter(c => Number(c.monthToDateCost) > 0)
            .slice().sort((a, b) => Number(b.monthToDateCost) - Number(a.monthToDateCost)).slice(0, max);
    }

    function dateOf(value) {
        if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}/.test(value)) {
            const [y, m, d] = value.slice(0, 10).split('-').map(Number);
            return new Date(y, m - 1, d);
        }
        const d = new Date(value);
        return Number.isFinite(d.getTime()) ? new Date(d.getFullYear(), d.getMonth(), d.getDate()) : null;
    }

    /**
     * The month so far and where it is heading: this month's days added up day by day, each from AWS
     * billing where it has arrived, else the estimate (U20-T10); spent (with billing, the days' sum;
     * else the server's month to date, else the days' sum); and the forecast: spent plus the average
     * of the last 7 days with data for every day left in the month. Also where the figure comes from
     * and the first day with data, so a month that started without data says so.
     * With the server's month (U23-T01, /cost-management/month) spent, the forecast and its basis are
     * the server's, the figures Cost management and My account show; the days only draw the line.
     */
    function spend(points, monthToDate, plan, now = new Date(), fleet = null) {
        const year = now.getFullYear();
        const month = now.getMonth();
        const daysInMonth = new Date(year, month + 1, 0).getDate();
        const today = now.getDate();
        const billed = p => p.actualCost !== null && p.actualCost !== undefined && Number.isFinite(Number(p.actualCost));
        const days = (points || []).map(p => ({ date: dateOf(p.date), billed: billed(p),
                cost: billed(p) ? Number(p.actualCost) : Number(p.estimatedCost) || 0 }))
            .filter(p => p.date && p.date.getFullYear() === year && p.date.getMonth() === month)
            .sort((a, b) => a.date - b.date);
        let running = 0;
        const cumulative = days.map(p => { running += p.cost; return { day: p.date.getDate(), value: running }; });
        const billedDays = days.filter(p => p.billed).length;
        const known = monthToDate !== null && monthToDate !== undefined && Number.isFinite(Number(monthToDate));
        const spent = billedDays || !known ? running : Number(monthToDate);
        const recent = days.slice(-7);
        const daily = recent.length ? recent.reduce((sum, p) => sum + p.cost, 0) / recent.length : (today ? spent / today : 0);
        const daysLeft = Math.max(0, daysInMonth - today);
        const forecast = spent + daily * daysLeft;
        const planAmount = plan && Number(plan.amount) > 0 ? Number(plan.amount) : null;
        if (fleet && fleet.monthToDate !== null && fleet.monthToDate !== undefined) {
            const fleetSpent = Number(fleet.monthToDate) || 0;
            const averaged = Number(fleet.averagedDays) || 0;
            const fleetDaily = averaged ? Number(fleet.dailyAverage) || 0 : (today ? fleetSpent / today : 0);
            const left = Math.max(0, Number(fleet.daysLeft) || 0);
            const billedFleet = Number(fleet.billedDays) || 0;
            return { spent: fleetSpent,
                forecast: fleet.forecast !== null && fleet.forecast !== undefined ? Number(fleet.forecast) : fleetSpent + fleetDaily * left,
                plan: planAmount, budgetCount: plan ? Number(plan.budgetCount) || 0 : 0,
                today, daysInMonth, cumulative, hasData: days.length > 0 || fleetSpent > 0,
                billedDays: billedFleet, estimatedDays: Number(fleet.estimatedDays) || 0,
                firstDay: fleet.firstDay || null, averagedDays: averaged, daily: fleetDaily, daysLeft: left };
        }
        return { spent, forecast, plan: planAmount, budgetCount: plan ? Number(plan.budgetCount) || 0 : 0,
            today, daysInMonth, cumulative, hasData: days.length > 0 || spent > 0,
            billedDays, estimatedDays: days.length - billedDays, firstDay: days.length ? days[0].date.getDate() : null,
            averagedDays: recent.length, daily, daysLeft };
    }

    /**
     * Where the figure comes from (U20-T10): "Estimated from instance prices", "From AWS billing" or
     * "AWS billing for 12 days, estimated for 3"; then "data from 11 Oct" when the month's first days
     * have none.
     */
    function sourceText(m, now = new Date()) {
        if (!m || !m.hasData) return '';
        const parts = [m.billedDays === 0 ? 'Estimated from instance prices'
            : m.estimatedDays === 0 ? 'From AWS billing'
                : `AWS billing for ${plural(m.billedDays, 'day')}, estimated for ${m.estimatedDays}`];
        if (m.firstDay && m.firstDay > 1) {
            parts.push(`data from ${new Date(now.getFullYear(), now.getMonth(), m.firstDay)
                .toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })}`);
        }
        return parts.join(' · ');
    }

    /** How the forecast is made: "Spent so far plus the average of the last 7 days ($312 a day) for the 12 days left." */
    function forecastText(m) {
        if (!m || !m.hasData) return '';
        if (!m.daysLeft) return 'The month ends today: the forecast is what is spent.';
        const basis = m.averagedDays ? `the average of the last ${plural(m.averagedDays, 'day')}` : 'the average so far';
        return `Spent so far plus ${basis} (${whole(m.daily)} a day) for the ${plural(m.daysLeft, 'day')} left.`;
    }

    /** The spend chart: spent so far (navy), the forecast to month end (gold, dashed), the plan pace (grey, dashed). */
    function spendSvg(m) {
        const W = 440;
        const BASE = 140;
        const TOP = 10;
        const max = Math.max(m.plan || 0, m.forecast, m.spent, 1) * 1.05;
        const x = day => ((day / m.daysInMonth) * W).toFixed(1);
        const y = v => (BASE - (v / max) * (BASE - TOP)).toFixed(1);
        const line = [`0,${BASE}`].concat(m.cumulative.filter(p => p.day < m.today).map(p => `${x(p.day)},${y(p.value)}`))
            .concat([`${x(m.today)},${y(m.spent)}`]).join(' ');
        const label = `Spend this month: ${whole(m.spent)} by day ${m.today}, heading for ${whole(m.forecast)}`
            + (m.plan ? `; budgets add up to ${whole(m.plan)}` : '');
        return `<svg class="ah-spend-chart" viewBox="0 0 ${W} 150" role="img" aria-label="${Utils.escapeHtml(label)}" preserveAspectRatio="none">
                <line class="ah-axis" x1="0" y1="${BASE}" x2="${W}" y2="${BASE}"></line>
                ${m.plan ? `<line class="ah-plan" x1="0" y1="${BASE}" x2="${W}" y2="${y(m.plan)}"></line>` : ''}
                <polyline class="ah-spent" points="${line}"></polyline>
                <polyline class="ah-forecast" points="${x(m.today)},${y(m.spent)} ${W},${y(m.forecast)}"></polyline>
                <circle class="ah-today" cx="${x(m.today)}" cy="${y(m.spent)}" r="4"></circle>
            </svg>`;
    }

    /**
     * One line for platform health: healthy, or the first problem. Late jobs and a state sync more
     * than 30 minutes old are gold; a job more than a day late (or that never ran) is red.
     */
    function health(att, now = new Date()) {
        if (!att || !att.health) return null;
        const h = att.health;
        const jobs = att.lateJobs || {};
        const synced = since(h.lastStateSyncAt, now);
        const facts = [`${Number(h.jobsOnTime) || 0} of ${plural(Number(h.jobsEnabled) || 0, 'scheduled job')} on time`,
            synced ? `state synced ${synced} ago` : 'state not synced yet',
            `${plural(Number(h.driftEvents24h) || 0, 'VM')} changed outside the platform in 24 h`];
        if (Number(jobs.count) > 0) {
            const worst = jobs.worst || {};
            const bad = worst.lateBySeconds === null || worst.lateBySeconds === undefined || worst.lateBySeconds > 86400;
            return { tone: bad ? 'bad' : 'warn',
                lead: Number(jobs.count) === 1 ? '1 scheduled job is late.' : `${jobs.count} scheduled jobs are late.`,
                text: [jobText(worst)].concat(facts).join(' · ') };
        }
        const syncAge = h.lastStateSyncAt ? now.getTime() - new Date(h.lastStateSyncAt).getTime() : Infinity;
        if (syncAge > SYNC_BEHIND_MS) {
            return { tone: 'warn', lead: 'State sync is behind.', text: facts.join(' · ') };
        }
        return { tone: 'ok', lead: 'Platform healthy.', text: facts.join(' · ') };
    }

    // ---- page ----

    function summaryHtml(data, m) {
        const s = data.summary || {};
        const envs = Number(s.environments) || 0;
        let html = Utils.html`<span class="mono">${Number(s.runningVms) || 0}</span> of <span class="mono">${Number(s.totalVms) || 0}</span> VMs running across `
            + Utils.escapeHtml(plural(envs, 'environment'));
        if (s.monthToDateCost !== null && s.monthToDateCost !== undefined) {
            html += Utils.html` · <span class="mono">${whole(s.monthToDateCost)}</span> this month`;
            if (m && m.hasData) html += Utils.html`, heading for <span class="mono">${whole(m.forecast)}</span>`;
        }
        return html;
    }

    function html(data) {
        const s = data.summary || {};
        const rows = fleet(data.environments);
        fleetGroups.clear();
        rows.forEach(c => fleetGroups.set(String(c.environmentId), PowerStrip.fromGroupStates(c.groupStates || [])));
        const fleetRows = rows.length ? `<div class="ah-fleet-list" data-scroll-label="Fleet right now">${rows.map(c => Utils.html`<a class="ah-fleet-row" href="#/environments/${encodeURIComponent(c.environmentId)}" data-env="${String(c.environmentId)}">
                <span class="ah-fleet-name">${c.displayName || c.name}</span>`
            + PowerStrip.render(fleetGroups.get(String(c.environmentId)), {}) + '</a>').join('')}</div>`
            : '<p class="ah-note">No environments yet. <a href="#/my-environments">Environments</a> is where you add one.</p>';
        return `
            <div class="content-view page page-frame home-page admin-home" id="dashboard-view">
                ${PageHeader.markup({ title: 'Home', summaryHtml: `<span id="ah-summary">${summaryHtml(data, cachedSpend(data))}</span>` })}
                <section class="ah-card ah-attention" aria-labelledby="ah-att-title">
                    <h2 id="ah-att-title">Needs attention</h2>
                    <div id="ah-tiles" aria-live="polite"><p class="ah-note ah-pad">Loading&hellip;</p></div>
                </section>
                <div class="ah-pair page-fill">
                    <section class="ah-card ah-fleet" aria-labelledby="ah-fleet-title">
                        <div class="ah-card-head"><h2 id="ah-fleet-title">Fleet right now</h2>
                            <a href="#/my-environments">${Utils.escapeHtml(`All ${plural(Number(s.environments) || 0, 'environment')}`)}</a></div>
                        ${fleetRows}
                    </section>
                    <section class="ah-card ah-spend" aria-labelledby="ah-spend-title">
                        <div class="ah-card-head"><h2 id="ah-spend-title">Spend this month</h2><a href="#/cost-management">Cost management</a></div>
                        <div id="ah-spend-body"><p class="ah-note">Loading&hellip;</p></div>
                    </section>
                </div>
                <div id="ah-health"></div>
            </div>`;
    }

    function cachedSpend(data) {
        return extras && extras.trend ? spend(extras.trend, (data.summary || {}).monthToDateCost,
            extras.attention && extras.attention.monthPlan, new Date(), extras.month) : null;
    }

    /**
     * A row whose lights leave the name too little room shows the strip as a bar (U20-T02): many groups
     * make a strip wider than the card, and the card never scrolls sideways. Checked after each draw and
     * when the window is resized, so a wider window brings the lights back.
     */
    function fitFleet() {
        document.querySelectorAll('#dashboard-view .ah-fleet-row[data-env]').forEach(row => {
            const groups = fleetGroups.get(row.dataset.env);
            const strip = row.querySelector('.power-strip');
            if (!groups || !strip) return;
            strip.outerHTML = PowerStrip.render(groups, {});
            const name = row.querySelector('.ah-fleet-name');
            if (row.scrollWidth > row.clientWidth + 1 || (name && name.clientWidth < MIN_NAME_PX)) {
                row.querySelector('.power-strip').outerHTML = PowerStrip.render(groups, { bar: true });
            }
        });
    }

    if (typeof window !== 'undefined' && window.addEventListener) {
        window.addEventListener('resize', () => {
            clearTimeout(fitTimer);
            fitTimer = setTimeout(() => { if (document.querySelector('#dashboard-view.admin-home')) fitFleet(); }, 150);
        });
    }

    /** Fills attention, spend and health; fetches them at most once a minute. */
    function fill(data) {
        fitFleet();
        if (extras && Date.now() - extras.at < EXTRAS_FRESH_MS) {
            draw(data);
            return;
        }
        const token = ContentRouter.token();
        const get = url => new Promise(resolve => {
            ApiClient.get(url, { suppressGlobalError: true }).done(v => resolve({ ok: true, v })).fail(() => resolve({ ok: false }));
        });
        const dayOfMonth = new Date().getDate();
        Promise.all([get(Config.API.dashboard.attention), get(Config.API.costManagement.spendTrend(dayOfMonth)),
            get(Config.API.costManagement.month)]).then(([att, trend, month]) => {
            extras = { at: Date.now(), attention: att.ok ? att.v : null, attentionFailed: !att.ok,
                trend: trend.ok ? (trend.v || []) : null, trendFailed: !trend.ok, month: month.ok ? month.v : null };
            if (ContentRouter.isCurrent(token)) draw(data);
        });
    }

    function draw(data) {
        const att = extras.attention;
        const now = new Date();
        const list = tiles(att, now);
        $('#ah-tiles').html(extras.attentionFailed ? '<p class="ah-note ah-pad">What needs attention is not available right now.</p>'
            : list.length ? `<div class="ah-tile-grid">${list.map(t => Utils.html`<a class="ah-tile" href="${t.href}" data-tile="${t.key}">
                    <span class="ah-badge ah-badge-${t.tone}">${t.count}</span>
                    <span class="ah-tile-text"><span class="ah-tile-title">${t.title}</span><span class="ah-tile-detail">${t.detail}</span></span>
                    <svg width="16" height="16" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m8 5 5 5-5 5"></path></svg>
                </a>`).join('')}</div>`
            : '<p class="ah-note ah-pad ah-all-clear">Nothing needs you right now.</p>');

        const summary = data.summary || {};
        const m = extras.trend ? spend(extras.trend, summary.monthToDateCost, att && att.monthPlan, now, extras.month) : null;
        $('#ah-summary').html(summaryHtml(data, m));
        let body;
        if (!m) {
            body = '<p class="ah-note">Spend is not available right now.</p>';
        } else if (!m.hasData) {
            body = '<p class="ah-note">No cost data for this month yet. <a href="#/cost-setup">Cost setup</a> shows how it is collected.</p>';
        } else {
            const top = topSpenders(data.environments);
            body = spendSvg(m)
                + Utils.html`<div class="ah-legend">
                    <span><span class="ah-key ah-key-spent"></span> Spent <span class="mono">${whole(m.spent)}</span></span>
                    <span title="${forecastText(m)}"><span class="ah-key ah-key-forecast"></span> Forecast <span class="mono">${whole(m.forecast)}</span></span>`
                + (m.plan ? Utils.html`<span><span class="ah-key ah-key-plan"></span> Budgets, <span class="mono">${whole(m.plan)}</span></span>` : '')
                + '</div>'
                + Utils.html`<p class="ah-spend-source" id="ah-spend-source">${sourceText(m, now)}. ${forecastText(m)}</p>`
                + (top.length ? `<div class="ah-top">${top.map(c => Utils.html`<a class="ah-top-row" href="#/environments/${encodeURIComponent(c.environmentId)}">
                        <span>${c.displayName || c.name}</span><span class="mono">${whole(c.monthToDateCost)}</span></a>`).join('')}</div>` : '');
        }
        $('#ah-spend-body').html(body);

        const h = health(att, now);
        $('#ah-health').html(h ? Utils.html`<a class="ah-health ah-health-${h.tone}" href="#/system-health">
                <span class="ah-dot" aria-hidden="true"></span>
                <span class="ah-health-text"><b>${h.lead}</b> <span>${h.text}</span></span>
                <span class="ah-health-link">System health</span></a>` : '');
    }

    function reset() {
        extras = null;
    }

    return { html, fill, reset, model: { tiles, budgetRisk, fleet, topSpenders, spend, spendSvg, health, span, jobText, sourceText, forecastText } };
})();

if (typeof window !== 'undefined') window.AdminHome = AdminHome;
