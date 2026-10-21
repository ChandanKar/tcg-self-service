/**
 * VM Self-Service Platform - System health (U14-T02, SystemHealth.dc.html), administrators only.
 * Now: four figures (VM state sync, drift in 24 h, actions in 24 h with the success rate, scheduled
 * jobs on time) and the scheduled jobs (GET /monitoring/scheduler-health: Job, Runs, Last run,
 * Status), problems first or all, 10 a page. Header: Sync VM state now, and Sync EKS now when an
 * EKS environment exists. Fleet use (?tab=fleet, U14-T09, fleet-use.js) shows CPU, memory and network
 * over 24 hours, the fleet's state, metrics coverage and risk and compliance, asked for only when
 * opened. Reports (?tab=reports) keeps the earlier 24-hour tables (audit summary,
 * state changes, drift, lock compliance, VM operations, email log) and asks for them only when
 * opened. Every window is the last 24 hours as exact instants (E12-T06).
 */

const SystemHealth = (function () {
    'use strict';

    const PAGE_SIZE = 10;
    let s = null; // {token, tab, jobs, view, page, reportsLoaded, eks}

    // ─── pure model (node tests: src/test/js/system-health.test.mjs) ─────────

    function plural(n, one, many) {
        return `${Number(n).toLocaleString('en-US')} ${n === 1 ? one : (many || one + 's')}`;
    }

    /** "45 s", "4 min", "3 h", "2 days". */
    function span(seconds) {
        const x = Math.max(0, Number(seconds) || 0);
        if (x < 60) return `${Math.round(x)} s`;
        if (x < 3600) return `${Math.round(x / 60)} min`;
        if (x < 86400) return `${Math.round(x / 3600)} h`;
        return plural(Math.round(x / 86400), 'day');
    }

    /** "Today 09:40", "Yesterday 01:00", "18 Oct 01:00", or "—" for a job that never ran. */
    function lastRun(ts, now = new Date()) {
        if (!ts) return '—';
        const d = new Date(ts);
        if (!Number.isFinite(d.getTime())) return '—';
        const time = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
        const day = new Date(d.getFullYear(), d.getMonth(), d.getDate());
        const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
        const diff = Math.round((today - day) / 86400000);
        if (diff === 0) return `Today ${time}`;
        if (diff === 1) return `Yesterday ${time}`;
        return `${d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })} ${time}`;
    }

    /** The status pill: {text, kind} with kind ok, late or off. */
    function status(job) {
        if (job.status === 'OFF') return { text: 'Switched off', kind: 'off' };
        if (job.status === 'LATE') {
            return { text: job.lateBySeconds === null || job.lateBySeconds === undefined ? 'Never ran' : `${span(job.lateBySeconds)} late`, kind: 'late' };
        }
        return { text: job.running ? 'Running' : 'On time', kind: 'ok' };
    }

    /** The jobs in the chosen order: problems first (as the server sends them) or the catalog's order. */
    function ordered(jobs, view) {
        const list = (jobs || []).slice();
        return view === 'all' ? list.sort((a, b) => a.position - b.position) : list;
    }

    /** The four figures of the Now tab. */
    function tiles({ syncStatus, jobs, driftCount, stats }, now = new Date()) {
        const sync = syncStatus || {};
        const list = jobs || [];
        const stateJob = list.find(j => j.jobName === 'vm_state_sync');
        const synced = sync.lastSyncTime ? (now.getTime() - new Date(sync.lastSyncTime).getTime()) / 1000 : null;
        const enabled = list.filter(j => j.status !== 'OFF');
        const late = list.filter(j => j.status === 'LATE').length;
        return {
            sync: {
                value: sync.syncInProgress ? 'Syncing now' : synced === null ? 'Not yet' : `${span(synced)} ago`,
                fresh: synced !== null && (!stateJob || stateJob.status !== 'LATE'),
                sub: `${plural(Number(sync.totalVmsSynced) || 0, 'VM')}${stateJob ? `, ${stateJob.cadence.charAt(0).toLowerCase()}${stateJob.cadence.slice(1)}` : ''}`
            },
            drift: { value: driftCount === null || driftCount === undefined ? '—' : String(driftCount) },
            actions: stats ? { value: Number(stats.total || 0).toLocaleString('en-US'), rate: `${Number(stats.successRate) || 0}% succeeded` }
                : { value: '—', rate: 'Not available' },
            jobs: { value: `${enabled.length - late}/${enabled.length}`, sub: late ? `${late} late` : 'All on time', late }
        };
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    /** "from=...&to=..." for an exact instant range (E12-T06). */
    function rangeQuery(range) {
        return `from=${encodeURIComponent(range.from)}&to=${encodeURIComponent(range.to)}`;
    }

    function lastDay() {
        const to = new Date();
        return { from: new Date(to.getTime() - 86400000).toISOString(), to: to.toISOString() };
    }

    function relativeTime(ts) {
        if (!ts) return '—';
        const diff = Date.now() - new Date(ts).getTime();
        const m = Math.floor(diff / 60000);
        if (m < 1)  return 'Just now';
        if (m < 60) return `${m}m ago`;
        const h = Math.floor(m / 60);
        if (h < 24) return `${h}h ago`;
        return `${Math.floor(h / 24)}d ago`;
    }

    function absTime(ts) {
        if (!ts) return '—';
        return new Date(ts).toLocaleString();
    }

    function stateBadge(state) {
        if (!state) return '<span class="badge bg-secondary">—</span>';
        const map = {
            RUNNING:  'bg-success',
            STOPPED:  'bg-danger',
            UNKNOWN:  'bg-secondary',
            NOT_FOUND:'bg-warning',
            TERMINATED:'bg-dark'
        };
        return `<span class="badge ${map[state] || 'bg-secondary'}">${Utils.escapeHtml(state)}</span>`;
    }

    function actionLabel(action) {
        if (!action) return '—';
        return action.replace(/_/g, ' ')
            .toLowerCase()
            .replace(/\b\w/g, c => c.toUpperCase());
    }

    // ─── fetch helpers ────────────────────────────────────────────────────────

    function apiFetch(url, fallback) {
        return new Promise(resolve => {
            ApiClient.get(url, { suppressGlobalError: true })
                .done(resolve)
                .fail(() => resolve(fallback));
        });
    }

    function apiPost(url) {
        return ApiClient.post(url, {}, { suppressGlobalError: true });
    }

    function fetchSyncStatus() {
        return apiFetch(Config.API.monitoring.syncStatus, {
            inProgress: false, lastSyncTime: null, vmCount: 0, driftCount: 0, errorCount: 0
        });
    }

    function fetchStateChanges() {
        return apiFetch(Config.API.monitoring.stateChanges, []);
    }

    /** Drift events in the range: the first page of rows plus the total across the range. */
    function fetchDriftEvents(range, size = 20) {
        return new Promise(resolve => {
            ApiClient.get(`${Config.API.monitoring.driftEvents}?page=0&size=${size}&${rangeQuery(range)}`,
                { suppressGlobalError: true })
                .done(data => {
                    const events = Array.isArray(data) ? data : ((data && data.content) || []);
                    const total = data && data.page && data.page.totalElements != null
                        ? Number(data.page.totalElements) : events.length;
                    resolve({ events, total });
                })
                .fail(() => resolve({ events: [], total: null }));
        });
    }

    function fetchAuditReport(range) {
        return apiFetch(`${Config.API.audit.report}?${rangeQuery(range)}`, null);
    }

    function fetchLockReport(range) {
        return apiFetch(`${Config.API.audit.lockReport}?${rangeQuery(range)}`, []);
    }

    function fetchVmOpsReport(range) {
        return apiFetch(`${Config.API.audit.vmReport}?${rangeQuery(range)}`, []);
    }

    function fetchEmailLog() {
        return new Promise(resolve => {
            ApiClient.get(`${Config.API.notifications.emailLog}?page=0&size=20`, { suppressGlobalError: true })
                .done(data => resolve((data && data.content) || []))
                .fail(() => resolve([]));
        });
    }

    // ─── public: load ─────────────────────────────────────────────────────────

    /** Router loader (also used after a sync, which reloads through the router). */
    function load() {
        const q = ContentRouter.query ? ContentRouter.query() : {};
        s = { token: ContentRouter.token(), tab: ['reports', 'fleet'].includes(q.tab) ? q.tab : 'now', jobs: null, view: 'problems',
            page: 1, reportsLoaded: false, fleetLoaded: false, eks: false };
        renderShell();
        loadNow();
        if (s.tab === 'reports') loadReports();
        if (s.tab === 'fleet') loadFleet();
    }

    function current() {
        return s && ContentRouter.isCurrent(s.token);
    }

    function renderShell() {
        const tabs = [['now', 'Now'], ['fleet', 'Fleet use'], ['reports', 'Reports']];
        $('#content-area').html(`
        <div class="content-view sh-page" id="system-health-view">
            ${PageHeader.markup({ title: 'System health',
                summaryHtml: "Whether the platform's background work is running on time.",
                actionsHtml: `<button type="button" class="btn btn-secondary" id="trigger-eks-sync-btn" data-action="sh-trigger-eks-sync" hidden>Sync EKS now</button>
                    <button type="button" class="btn btn-primary" id="trigger-sync-btn" data-action="sh-trigger-sync">Sync VM state now</button>`,
                helpPage: typeof HelpLink !== 'undefined' ? 'system-health' : null, helpLabel: 'System health' })}
            <div class="sh-tabs" role="tablist" aria-label="System health">${tabs.map(([key, label]) => `<button type="button" role="tab"
                class="sh-tab" id="sh-tab-${key}" data-sh-tab="${key}" aria-controls="sh-panel-${key}" aria-selected="${key === s.tab}">${label}</button>`).join('')}</div>
            <section class="sh-panel" id="sh-panel-now" role="tabpanel" aria-labelledby="sh-tab-now"${s.tab === 'now' ? '' : ' hidden'}>
                <div class="sh-tiles" id="sh-tiles"><p class="sh-note">Loading&hellip;</p></div>
                <section class="sh-card sh-jobs" aria-labelledby="sh-jobs-title">
                    <div class="sh-card-head"><h2 id="sh-jobs-title">Scheduled jobs</h2>
                        <div class="sh-toggle" role="group" aria-label="Show" id="sh-jobs-view"></div></div>
                    <div id="sh-jobs-body"></div>
                </section>
            </section>
            <section class="sh-panel" id="sh-panel-fleet" role="tabpanel" aria-labelledby="sh-tab-fleet"${s.tab === 'fleet' ? '' : ' hidden'}></section>
            <section class="sh-panel" id="sh-panel-reports" role="tabpanel" aria-labelledby="sh-tab-reports"${s.tab === 'reports' ? '' : ' hidden'}>
                <p class="sh-note">Loading the reports&hellip;</p>
            </section>
        </div>`);
        $('#system-health-view').off('.sh')
            .on('click.sh', '[data-sh-tab]', function() { switchTab(this.dataset.shTab); })
            .on('click.sh', '[data-sh-view]', function() { s.view = this.dataset.shView; s.page = 1; drawJobs(); })
            .on('click.sh', '[data-action="sh-page"]', function() { s.page = Number(this.dataset.page) || 1; drawJobs(); });
    }

    function switchTab(tab) {
        if (!s || tab === s.tab) return;
        s.tab = tab;
        $('.sh-tab').each(function() { this.setAttribute('aria-selected', String(this.dataset.shTab === tab)); });
        ['now', 'fleet', 'reports'].forEach(key => $(`#sh-panel-${key}`).prop('hidden', tab !== key));
        try {
            history.replaceState(null, '', tab === 'now' ? '#/system-health' : `#/system-health?tab=${tab}`);
        } catch (e) { /* the address keeps the old tab */ }
        if (tab === 'reports') loadReports();
        if (tab === 'fleet') loadFleet();
    }

    /** The Fleet use tab: asked for the first time it is opened. */
    function loadFleet() {
        if (s.fleetLoaded || typeof FleetUse === 'undefined') return;
        s.fleetLoaded = true;
        FleetUse.render('#sh-panel-fleet');
    }

    async function loadNow() {
        const token = s.token;
        const range = lastDay();
        const statsUrl = `${Config.API.audit.stats}?${rangeQuery(range)}`;
        const [syncStatus, jobs, drift, stats, envs] = await Promise.all([
            fetchSyncStatus(),
            apiFetch(Config.API.monitoring.schedulerHealth, null),
            fetchDriftEvents(range, 1),
            apiFetch(statsUrl, null),
            apiFetch(Config.API.environments.list, [])
        ]);
        if (!ContentRouter.isCurrent(token)) return;
        s.jobs = jobs;
        s.eks = (Array.isArray(envs) ? envs : []).some(e => e.serviceType === 'EKS' && e.isActive !== false);
        $('#trigger-eks-sync-btn').prop('hidden', !s.eks);
        const t = tiles({ syncStatus, jobs, driftCount: drift.total, stats });
        $('#sh-tiles').html(Utils.html`
            <div class="sh-tile"><div class="sh-tile-label">VM state sync</div>
                <div class="sh-tile-value"><span class="sh-dot sh-dot-${t.sync.fresh ? 'ok' : 'late'}" aria-hidden="true"></span>${t.sync.value}</div>
                <div class="sh-tile-sub">${t.sync.sub}</div></div>
            <div class="sh-tile"><div class="sh-tile-label">Drift, 24 h</div>
                <div class="sh-tile-value">${t.drift.value}</div>
                <div class="sh-tile-sub">VMs changed outside the platform</div></div>
            <div class="sh-tile"><div class="sh-tile-label">Actions, 24 h</div>
                <div class="sh-tile-value">${t.actions.value}</div>
                <div class="sh-tile-sub">${t.actions.rate} · <a href="#/audit-logs-all">Audit log</a></div></div>
            <div class="sh-tile" id="sh-tile-jobs"><div class="sh-tile-label">Scheduled jobs</div>
                <div class="sh-tile-value">${jobs ? t.jobs.value : '—'}</div>
                <div class="sh-tile-sub${Utils.raw(t.jobs.late ? ' sh-late-text' : '')}">${jobs ? t.jobs.sub : 'Not available'}</div></div>`);
        drawJobs();
    }

    function drawJobs() {
        if (!s.jobs) {
            $('#sh-jobs-view').html('');
            $('#sh-jobs-body').html('<p class="sh-note sh-pad">The scheduled jobs are not available right now.</p>');
            return;
        }
        $('#sh-jobs-view').html(`<button type="button" class="sh-toggle-btn" data-sh-view="problems" aria-pressed="${s.view === 'problems'}">Problems first</button>`
            + `<button type="button" class="sh-toggle-btn" data-sh-view="all" aria-pressed="${s.view === 'all'}">All ${s.jobs.length}</button>`);
        const list = ordered(s.jobs, s.view);
        const pages = Math.max(1, Math.ceil(list.length / PAGE_SIZE));
        s.page = Math.min(Math.max(1, s.page), pages);
        const now = new Date();
        const rows = Pagination.slice(list, s.page, PAGE_SIZE).map(j => {
            const st = status(j);
            return Utils.html`<tr data-job="${j.jobName}"><td class="sh-job-name">${j.name}</td><td>${j.cadence}</td>
                <td class="sh-mono">${lastRun(j.lastRunAt, now)}</td><td><span class="sh-pill sh-pill-${st.kind}">${st.text}</span></td></tr>`;
        }).join('');
        $('#sh-jobs-body').html(`<div class="sh-table-scroll"><table class="sh-jobs-table" id="sh-jobs-table">
                <thead><tr><th scope="col">Job</th><th scope="col">Runs</th><th scope="col">Last run</th><th scope="col">Status</th></tr></thead>
                <tbody>${rows}</tbody></table></div>
            ${list.length > PAGE_SIZE ? `<div class="sh-foot">${Pagination.buildPager({ page: s.page, pageSize: PAGE_SIZE, totalItems: list.length,
                action: 'sh-page', key: 'jobs' })}</div>` : ''}`);
    }

    /** The Reports tab: asked for the first time it is opened. */
    async function loadReports() {
        if (s.reportsLoaded) return;
        s.reportsLoaded = true;
        const token = s.token;
        const range = lastDay();
        const canAdmin = Auth.isEnvAdmin();
        const [stateChanges, drift, auditReport, lockReport, vmOpsReport, emailLog] = await Promise.all([
            fetchStateChanges(),
            canAdmin ? fetchDriftEvents(range) : Promise.resolve({ events: [], total: 0 }),
            canAdmin ? fetchAuditReport(range) : Promise.resolve(null),
            canAdmin ? fetchLockReport(range) : Promise.resolve([]),
            canAdmin ? fetchVmOpsReport(range) : Promise.resolve([]),
            fetchEmailLog()
        ]);
        if (!ContentRouter.isCurrent(token)) return;
        $('#sh-panel-reports').html(reportsHtml({ canAdmin, stateChanges, driftEvents: drift.events,
            driftCount: drift.total || 0, auditReport, lockReport, vmOpsReport, emailLog }));
    }

    // ─── trigger sync ─────────────────────────────────────────────────────────

    /** Reload System health after a delay, unless the user has left by then. */
    function reloadLaterIfCurrent(ms) {
        const t = ContentRouter.token();
        const id = setTimeout(() => {
            if (ContentRouter.isCurrent(t)) ContentRouter.reload();
        }, ms);
        ContentRouter.onLeave(() => clearTimeout(id));
    }

    function triggerSync() {
        const $btn = $('#trigger-sync-btn');
        $btn.prop('disabled', true).text('Syncing…');

        apiPost(Config.API.monitoring.triggerSync)
            .done(() => {
                Notifications.show('State sync started. Refreshing in 3 s…', 'success');
                reloadLaterIfCurrent(3000);
            })
            .fail(xhr => {
                if (xhr && xhr.status === 409) {
                    Notifications.show('A sync is already running.', 'warning');
                } else {
                    Notifications.show('Failed to trigger sync', 'error');
                }
                $btn.prop('disabled', false).text('Sync VM state now');
            });
    }

    function triggerEksSync() {
        const $btn = $('#trigger-eks-sync-btn');
        $btn.prop('disabled', true).text('Syncing EKS…');

        apiPost(Config.API.monitoring.triggerEksSync)
            .done(data => {
                const n = data && data.nodeGroupsSynced != null ? data.nodeGroupsSynced : 0;
                Notifications.show(`EKS sync done: ${n} node group${n === 1 ? '' : 's'} checked.`, 'success');
                reloadLaterIfCurrent(2000);
            })
            .fail(xhr => {
                if (xhr && xhr.status === 503) {
                    Notifications.show('EKS is not available: check the AWS credentials.', 'warning');
                } else {
                    Notifications.show('EKS sync failed', 'error');
                }
            })
            .always(() => {
                $btn.prop('disabled', false).text('Sync EKS now');
            });
    }

    // ─── Reports ──────────────────────────────────────────────────────────────

    function reportsHtml({ canAdmin, stateChanges, driftEvents, driftCount, auditReport, lockReport, vmOpsReport, emailLog }) {

        const auditTotal    = auditReport ? (auditReport.totalActions || 0) : 0;
        // Counted by the server over the whole window (E12-T06), not from the 20-row sample.
        const auditSuccess  = auditReport ? Number(auditReport.successfulActions) || 0 : 0;
        const auditFailures = auditReport ? Number(auditReport.failedActions) || 0 : 0;
        const decided       = auditSuccess + auditFailures;
        const successRate   = decided > 0 ? Math.round((auditSuccess / decided) * 100) : null;
        const successText   = successRate === null ? '—' : `${successRate}%`;

        const topEnvList = (auditReport && auditReport.environmentActivities) || [];
        const topEnv     = topEnvList.length > 0
            ? (topEnvList[0].environmentName || topEnvList[0].environmentId || '—')
            : '—';

        return `
            ${canAdmin ? `
            <!-- Audit Summary -->
            <div class="card sh-summary-card mb-3">
                <div class="card-header d-flex align-items-center gap-2">
                    <i class="fas fa-chart-bar text-primary"></i>
                    <strong>Audit Summary — Last 24 Hours</strong>
                </div>
                <div class="card-body">
                    <div class="row text-center g-0">
                        <div class="col border-end">
                            <div class="sh-stat-value">${auditTotal.toLocaleString()}</div>
                            <div class="sh-stat-label">Total Actions</div>
                        </div>
                        <div class="col border-end">
                            <div class="sh-stat-value text-success">${auditSuccess.toLocaleString()}</div>
                            <div class="sh-stat-label">Successes</div>
                        </div>
                        <div class="col border-end">
                            <div class="sh-stat-value text-danger">${auditFailures.toLocaleString()}</div>
                            <div class="sh-stat-label">Failures</div>
                        </div>
                        <div class="col border-end">
                            <div class="sh-stat-value">${successText}</div>
                            <div class="sh-stat-label">Success Rate</div>
                        </div>
                        <div class="col">
                            <div class="sh-stat-value text-truncate" title="${Utils.escapeHtml(topEnv)}">${Utils.escapeHtml(topEnv)}</div>
                            <div class="sh-stat-label">Most Active Env</div>
                        </div>
                    </div>
                </div>
            </div>` : ''}

            <!-- State Changes + Drift side-by-side -->
            <div class="row g-2 mb-3">
                <div class="col-md-6">
                    <div class="card sh-table-card h-100">
                        <div class="card-header d-flex align-items-center gap-2">
                            <i class="fas fa-exchange-alt text-info"></i>
                            <strong>Recent State Changes</strong>
                            <span class="badge bg-secondary ms-auto">${stateChanges.length}</span>
                        </div>
                        <div class="card-body">
                            ${buildStateChangesTable(stateChanges)}
                        </div>
                    </div>
                </div>
                ${canAdmin ? `
                <div class="col-md-6">
                    <div class="card sh-table-card h-100">
                        <div class="card-header d-flex align-items-center gap-2">
                            <i class="fas fa-exclamation-triangle text-warning"></i>
                            <strong>Drift Events (24h)</strong>
                            <span class="badge bg-warning text-dark ms-auto">${driftCount}</span>
                        </div>
                        <div class="card-body">
                            ${buildDriftTable(driftEvents)}
                        </div>
                    </div>
                </div>` : ''}
            </div>

            ${canAdmin ? `
            <!-- Lock Compliance Report -->
            <div class="card sh-compliance-card mb-3">
                <div class="card-header d-flex align-items-center gap-2">
                    <i class="fas fa-lock text-warning"></i>
                    <strong>Lock Compliance Report — Last 24 Hours</strong>
                    <span class="badge bg-secondary ms-auto">${lockReport.length} events</span>
                </div>
                <div class="card-body">
                    ${buildComplianceTable(lockReport, ['User', 'Action', 'Environment', 'Target', 'Time'])}
                </div>
            </div>

            <!-- VM Operations Report -->
            <div class="card sh-compliance-card mb-3">
                <div class="card-header d-flex align-items-center gap-2">
                    <i class="fas fa-play-circle text-success"></i>
                    <strong>VM Operations Report — Last 24 Hours</strong>
                    <span class="badge bg-secondary ms-auto">${vmOpsReport.length} events</span>
                </div>
                <div class="card-body">
                    ${buildComplianceTable(vmOpsReport, ['User', 'Action', 'Environment', 'Target', 'Result', 'Time'])}
                </div>
            </div>` : ''}

            <!-- Email Log: who we've emailed -->
            <div class="card sh-compliance-card mb-3">
                <div class="card-header d-flex align-items-center gap-2">
                    <i class="fas fa-envelope text-info"></i>
                    <strong>Email Notifications Sent</strong>
                    <span class="badge bg-secondary ms-auto">${emailLog.length} recent</span>
                </div>
                <div class="card-body">
                    ${buildEmailLogTable(emailLog)}
                </div>
            </div>

`;
    }

    // ─── table builders ───────────────────────────────────────────────────────

    function buildStateChangesTable(changes) {
        if (!changes || changes.length === 0) {
            return emptyState('No recent state changes');
        }
        const rows = changes.slice(0, 20).map(c => `
            <tr>
                <td>${Utils.escapeHtml(c.vmName || c.vmId || '—')}</td>
                <td>${stateBadge(c.previousStatus)}</td>
                <td class="text-center text-muted px-0"><i class="fas fa-arrow-right"></i></td>
                <td>${stateBadge(c.newStatus)}</td>
                <td class="text-muted">${Utils.escapeHtml(c.changedByUsername || 'System')}</td>
                <td class="text-muted" title="${absTime(c.changedAt)}">${relativeTime(c.changedAt)}</td>
            </tr>`).join('');
        return `
            <div class="sh-table-wrapper">
                <table class="table table-hover mb-0">
                    <thead><tr><th>VM</th><th>From</th><th></th><th>To</th><th>Changed By</th><th>When</th></tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>`;
    }

    function buildDriftTable(events) {
        if (!events || events.length === 0) {
            return emptyState('No drift events in last 24 hours', true);
        }
        const rows = events.slice(0, 20).map(e => `
            <tr>
                <td>${Utils.escapeHtml(e.vmName || e.vmId || '—')}</td>
                <td>${stateBadge(e.previousStatus)}</td>
                <td class="text-center text-muted px-0"><i class="fas fa-arrow-right"></i></td>
                <td>${stateBadge(e.newStatus)}</td>
                <td class="text-muted" title="${absTime(e.changedAt)}">${relativeTime(e.changedAt)}</td>
            </tr>`).join('');
        return `
            <div class="sh-table-wrapper">
                <table class="table table-hover mb-0">
                    <thead><tr><th>VM</th><th>Expected</th><th></th><th>Actual</th><th>When</th></tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>`;
    }

    function buildComplianceTable(logs, columns) {
        if (!logs || logs.length === 0) {
            return emptyState('No events in the last 24 hours');
        }
        const hasResult = columns.includes('Result');
        const rows = logs.map(log => {
            const resultCell = hasResult
                ? `<td class="text-center">${log.success !== false
                    ? '<i class="fas fa-check-circle text-success"></i>'
                    : '<i class="fas fa-times-circle text-danger"></i>'}</td>`
                : '';
            return `
                <tr>
                    <td>${Utils.escapeHtml(log.userDisplayName || log.userEmail || 'System')}</td>
                    <td><span class="badge bg-secondary">${Utils.escapeHtml(log.actionDisplay || actionLabel(log.action))}</span></td>
                    <td>${Utils.escapeHtml(log.environmentName || log.environmentId || '—')}</td>
                    <td>${Utils.escapeHtml(log.targetName || '—')}</td>
                    ${resultCell}
                    <td class="text-muted" title="${absTime(log.createdAt)}">${relativeTime(log.createdAt)}</td>
                </tr>`;
        }).join('');
        const headerCells = columns.map(c => `<th>${c}</th>`).join('');
        return `
            <div class="sh-compliance-wrapper">
                <table class="table table-hover mb-0">
                    <thead><tr>${headerCells}</tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>`;
    }

    /** "Who have we emailed" — every real send attempt (suppressed sends aren't logged at all). */
    function buildEmailLogTable(logs) {
        if (!logs || logs.length === 0) {
            return emptyState('No emails sent yet (or notification.email.enabled is off)');
        }
        const rows = logs.map(log => {
            const resultIcon = log.success
                ? '<i class="fas fa-check-circle text-success"></i>'
                : '<i class="fas fa-times-circle text-danger" title="' + Utils.escapeHtml(log.errorMessage || 'Send failed') + '"></i>';
            const recipients = log.recipients || '';
            const recipientsShort = recipients.length > 60 ? recipients.slice(0, 60) + '…' : recipients;
            return `
                <tr>
                    <td class="text-muted" title="${absTime(log.sentAt)}">${relativeTime(log.sentAt)}</td>
                    <td>${log.notificationType ? `<span class="badge bg-secondary">${Utils.escapeHtml(actionLabel(log.notificationType))}</span>` : '—'}</td>
                    <td>${Utils.escapeHtml(log.environmentId || '—')}</td>
                    <td title="${Utils.escapeHtml(recipients)}">${Utils.escapeHtml(recipientsShort)} <span class="text-muted">(${Utils.escapeHtml(log.recipientCount)})</span></td>
                    <td class="text-truncate" style="max-width:280px" title="${Utils.escapeHtml(log.subject || '')}">${Utils.escapeHtml(log.subject || '—')}</td>
                    <td class="text-center">${resultIcon}</td>
                </tr>`;
        }).join('');
        return `
            <div class="sh-compliance-wrapper">
                <table class="table table-hover mb-0">
                    <thead><tr><th>Sent</th><th>Type</th><th>Environment</th><th>Recipients</th><th>Subject</th><th class="text-center">Result</th></tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>`;
    }

    // ─── shared ──────────────────────────────────────────────────────────────

    function emptyState(msg, success = false) {
        const cls = success ? 'text-success' : 'text-muted';
        return `<div class="sh-empty ${cls}">
                    <i class="fas fa-inbox"></i>
                    ${Utils.escapeHtml(msg)}
                </div>`;
    }

    return { load, triggerSync, triggerEksSync, model: { span, lastRun, status, ordered, tiles } };
})();

if (typeof window !== 'undefined') window.SystemHealth = SystemHealth;

if (typeof Actions !== 'undefined') Actions.registerAll({
    'sh-trigger-sync': () => SystemHealth.triggerSync(),
    'sh-trigger-eks-sync': () => SystemHealth.triggerEksSync(),
    'sh-reload': () => ContentRouter.reload()
});
