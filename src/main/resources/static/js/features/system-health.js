/**
 * VM Self-Service Platform - System health (U14-T02, SystemHealth.dc.html), administrators only.
 * Now: four figures (VM state sync, drift in 24 h, actions in 24 h with the success rate, scheduled
 * jobs on time) and the scheduled jobs (GET /monitoring/scheduler-health: Job, Runs, Last run,
 * Status), problems first or all, 10 a page. Header (U20-T09): Check VM states now (every VM, EC2,
 * EKS node groups and RDS, read from AWS; says what it found), and Sync EKS clusters when an EKS
 * environment exists (node groups found, added and updated). Fleet use (?tab=fleet, U14-T09, fleet-use.js) shows CPU, memory and network
 * over 24 hours, the fleet's state, metrics coverage and risk and compliance, asked for only when
 * opened. Reports (?tab=reports) keeps the earlier 24-hour tables (audit summary, state changes:
 * one table with where each change came from and 'Only outside the platform' for drift, lock
 * compliance, VM operations, email log) and asks for them only when opened. Every window is the last 24 hours as exact instants (E12-T06).
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
        s = { token: ContentRouter.token(), tab: ['reports', 'fleet', 'regions'].includes(q.tab) ? q.tab : 'now', jobs: null, view: 'problems',
            page: 1, reportsLoaded: false, fleetLoaded: false, regionsLoaded: false, eks: false };
        renderShell();
        loadNow();
        if (s.tab === 'reports') loadReports();
        if (s.tab === 'fleet') loadFleet();
        if (s.tab === 'regions') loadRegions();
    }

    function current() {
        return s && ContentRouter.isCurrent(s.token);
    }

    /** The line under the title follows the tab (SystemHealth / SystemHealthFleet boards, U17-T10). */
    const SUMMARY = {
        now: "Whether the platform's background work is running on time.",
        fleet: 'How hard the fleet is working and what needs a look. Last 24 hours, all environments.',
        regions: 'Which AWS regions each account has enabled, what the last scans found there, and services a region does not offer.'
    };

    function renderShell() {
        const tabs = [['now', 'Now'], ['fleet', 'Fleet use'], ['regions', 'Regions'], ['reports', 'Reports']];
        $('#content-area').html(`
        <div class="content-view sh-page" id="system-health-view">
            ${PageHeader.markup({ title: 'System health',
                summaryHtml: SUMMARY[s.tab] || SUMMARY.now,
                // The syncs belong to Now; the boards show no actions on Fleet use.
                actionsHtml: `<span id="sh-now-actions" class="sh-now-actions"${s.tab === 'now' ? '' : ' hidden'}>
                    <button type="button" class="btn btn-secondary" id="trigger-eks-sync-btn" data-action="sh-trigger-eks-sync" hidden
                        title="Reads each EKS cluster's node groups from AWS: adds new ones and updates their size and state">Sync EKS clusters</button>
                    <button type="button" class="btn btn-primary" id="trigger-sync-btn" data-action="sh-trigger-sync"
                        title="Reads every VM's state from AWS (EC2, EKS node groups and RDS) and records what changed outside the platform">Check VM states now</button></span>`,
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
            <section class="sh-panel" id="sh-panel-regions" role="tabpanel" aria-labelledby="sh-tab-regions"${s.tab === 'regions' ? '' : ' hidden'}>
                <p class="sh-note">Loading the regions&hellip;</p>
            </section>
            <section class="sh-panel" id="sh-panel-reports" role="tabpanel" aria-labelledby="sh-tab-reports"${s.tab === 'reports' ? '' : ' hidden'}>
                <p class="sh-note">Loading the reports&hellip;</p>
            </section>
        </div>`);
        $('#system-health-view').off('.sh')
            .on('click.sh', '[data-sh-tab]', function() { switchTab(this.dataset.shTab); })
            .on('click.sh', '[data-sh-view]', function() { s.view = this.dataset.shView; s.page = 1; drawJobs(); })
            .on('click.sh', '[data-action="sh-page"]', function() { s.page = Number(this.dataset.page) || 1; drawJobs(); })
            .on('click.sh', '[data-action="sh-region-page"]', function() {
                if (this.disabled) return;
                s.regionPage = Number(this.dataset.page) || 1;
                drawRegions();
            });
    }

    function switchTab(tab) {
        if (!s || tab === s.tab) return;
        s.tab = tab;
        $('.sh-tab').each(function() { this.setAttribute('aria-selected', String(this.dataset.shTab === tab)); });
        ['now', 'fleet', 'regions', 'reports'].forEach(key => $(`#sh-panel-${key}`).prop('hidden', tab !== key));
        $('#system-health-view .page-summary').first().text(SUMMARY[tab] || SUMMARY.now);
        $('#sh-now-actions').prop('hidden', tab !== 'now');
        try {
            history.replaceState(null, '', tab === 'now' ? '#/system-health' : `#/system-health?tab=${tab}`);
        } catch (e) { /* the address keeps the old tab */ }
        if (tab === 'reports') loadReports();
        if (tab === 'fleet') loadFleet();
        if (tab === 'regions') loadRegions();
    }

    // ------------------------------------------------------------------ Regions (U19-T06, U19-T08)

    /** The scans grouped by account and region: failures first, then by account and region. */
    function regionRows(scans) {
        const byKey = {};
        (scans || []).forEach(sc => {
            const key = `${sc.accountRef}|${sc.region}`;
            const row = byKey[key] || (byKey[key] = { accountName: sc.accountName, region: sc.region, found: {}, error: null,
                refused: false, scannedAt: null });
            row.found[sc.kind] = sc.found;
            if (sc.error && !row.error) { row.error = sc.error; row.refused = !!sc.refused; }
            if (!row.scannedAt || String(sc.scannedAt) > String(row.scannedAt)) row.scannedAt = sc.scannedAt;
        });
        return Object.values(byKey).sort((a, b) => (a.error ? 0 : 1) - (b.error ? 0 : 1)
            || String(a.accountName).localeCompare(String(b.accountName)) || String(a.region).localeCompare(String(b.region)));
    }

    /** "12 instances · 2 clusters", or "Nothing found": the largest count any scan of that kind saw. */
    function foundText(found) {
        let ec2 = 0;
        let eks = 0;
        Object.entries(found || {}).forEach(([kind, n]) => {
            if (kind.startsWith('EC2')) ec2 = Math.max(ec2, Number(n) || 0);
            if (kind.startsWith('EKS')) eks = Math.max(eks, Number(n) || 0);
        });
        const parts = [];
        if (ec2) parts.push(plural(ec2, 'instance', 'instances'));
        if (eks) parts.push(plural(eks, 'cluster', 'clusters'));
        return parts.length ? parts.join(' · ') : 'Nothing found';
    }

    /** A note's kind in words. */
    function noteKind(kind) {
        return { NOT_IN_REGION: 'Not offered there', NOT_OPTED_IN: 'Not turned on', ACCESS_DENIED: 'Access refused',
            WRONG_REGION: 'In another region', OTHER: 'Failed' }[kind] || 'Failed';
    }

    async function loadRegions() {
        if (s.regionsLoaded) return;
        s.regionsLoaded = true;
        const token = s.token;
        let data;
        try {
            data = await ApiClient.get(Config.API.monitoring.regions, { suppressGlobalError: true });
        } catch (xhr) {
            if (!ContentRouter.isCurrent(token)) return;
            s.regionsLoaded = false;
            $('#sh-panel-regions').html(UiStates.failed({ what: 'The regions', xhr, onRetry: () => loadRegions() }));
            return;
        }
        if (!ContentRouter.isCurrent(token)) return;
        s.regions = data || {};
        s.regionPage = 1;
        drawRegions();
    }

    function accountHtml(a, now) {
        const enabled = a.enabled && a.enabled.length
            ? `${plural(a.enabled.length, 'enabled region', 'enabled regions')}${a.readAt ? `, read ${lastRun(a.readAt, now)}` : ''}`
            : 'Enabled regions not read yet';
        return Utils.html`<li class="sh-region-account"><b>${a.accountName}</b><span title="${(a.enabled || []).join(', ')}">${enabled}</span>`
            + (a.limit && a.limit.length ? Utils.html`<span>Limited to ${a.limit.join(', ')}</span>` : '')
            + (a.refused && a.refused.length ? Utils.html`<span class="sh-late-text">Refused access: ${a.refused.join(', ')}</span>` : '')
            + '</li>';
    }

    function scanRowHtml(r, now) {
        const status = r.error
            ? Utils.html`<span class="sh-pill sh-pill-${r.refused ? 'off' : 'late'}">${r.refused ? 'Refused access' : 'Not read'}</span> <span class="sh-region-why">${r.error}</span>`
            : '<span class="sh-pill sh-pill-ok">Read</span>';
        return Utils.html`<tr><td class="sh-mono">${r.region}</td><td>${r.accountName || ''}</td><td>${foundText(r.found)}</td>
            <td class="sh-mono">${lastRun(r.scannedAt, now)}</td><td>` + status + '</td></tr>';
    }

    function noteHtml(n) {
        return Utils.html`<li><span class="sh-pill sh-pill-late">${noteKind(n.kind)}</span>
            <span><b>${n.service}</b>${n.region ? ` · ${n.region}` : ''}${n.accountName ? ` · ${n.accountName}` : ''}: ${n.message}</span></li>`;
    }

    function drawRegions() {
        const d = s.regions || {};
        const accounts = d.accounts || [];
        const rows = regionRows(d.scans);
        const pages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
        s.regionPage = Math.min(Math.max(1, s.regionPage || 1), pages);
        const now = new Date();
        const accountsHtml = accounts.length ? accounts.map(a => accountHtml(a, now)).join('') : '<li class="sh-note">No active AWS account.</li>';
        const excluded = (d.excluded || []).length ? Utils.html`<p class="sh-note">Left out everywhere: ${d.excluded.join(', ')} (Settings › AWS).</p>` : '';
        const scansHtml = rows.length ? `<div class="sh-table-scroll"><table class="sh-jobs-table" id="sh-region-table">
                <thead><tr><th scope="col">Region</th><th scope="col">Account</th><th scope="col">Found</th><th scope="col">Last scan</th><th scope="col">Status</th></tr></thead>
                <tbody>${Pagination.slice(rows, s.regionPage, PAGE_SIZE).map(r => scanRowHtml(r, now)).join('')}</tbody></table></div>
                ${rows.length > PAGE_SIZE ? `<div class="sh-foot">${Pagination.buildPager({ page: s.regionPage, pageSize: PAGE_SIZE,
                    totalItems: rows.length, action: 'sh-region-page', key: 'regions' })}</div>` : ''}`
            : UiStates.empty({ title: 'No scan has run yet', text: 'Discovery, EKS sync and Found in AWS scan every enabled region when they run.' });
        const notes = d.notes || [];
        const notesHtml = notes.length ? `<ul class="sh-region-notes">${notes.map(noteHtml).join('')}</ul>`
            : '<p class="sh-note">Every service answered in every region it was asked in.</p>';
        $('#sh-panel-regions').html(`
            <section class="sh-card" aria-labelledby="sh-region-accounts-title">
                <div class="sh-card-head"><h2 id="sh-region-accounts-title">Accounts</h2></div>
                <ul class="sh-region-accounts">${accountsHtml}</ul>${excluded}
            </section>
            <section class="sh-card" aria-labelledby="sh-region-scans-title">
                <div class="sh-card-head"><h2 id="sh-region-scans-title">Last scans by region</h2></div>
                ${scansHtml}
            </section>
            <section class="sh-card" aria-labelledby="sh-region-notes-title">
                <div class="sh-card-head"><h2 id="sh-region-notes-title">Services by region</h2></div>
                ${notesHtml}
            </section>`);
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
            <div class="sh-tile"><div class="sh-tile-label">Changed outside the platform, 24 h</div>
                <div class="sh-tile-value">${t.drift.value}</div>
                <div class="sh-tile-sub">changes (starts and stops made in AWS)</div></div>
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
            $('#sh-jobs-body').html(UiStates.failed({ what: 'The scheduled jobs', onRetry: () => loadNow() }));
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
            canAdmin ? fetchStateChanges() : Promise.resolve([]),
            canAdmin ? fetchDriftEvents(range) : Promise.resolve({ events: [], total: 0 }),
            canAdmin ? fetchAuditReport(range) : Promise.resolve(null),
            canAdmin ? fetchLockReport(range) : Promise.resolve([]),
            canAdmin ? fetchVmOpsReport(range) : Promise.resolve([]),
            fetchEmailLog()
        ]);
        if (!ContentRouter.isCurrent(token)) return;
        s.changes = { list: Array.isArray(stateChanges) ? stateChanges : [], outsideOnly: false, page: 1 };
        $('#sh-panel-reports').html(reportsHtml({ canAdmin, driftCount: drift.total || 0, auditReport, lockReport, vmOpsReport, emailLog }));
        drawChanges();
    }

    // ─── state changes (U20-T09: one table; drift is a change found outside the platform) ─────

    /** Where a change came from, in words. */
    function sourceText(c) {
        if (c.drift || c.changeSource === 'state_sync') return 'Outside the platform';
        if (c.changeSource === 'operation') return c.changedByUsername ? `Platform, by ${c.changedByUsername}` : 'Platform';
        return c.changedByUsername || 'Platform';
    }

    /** The rows shown: everything, or only what changed outside the platform. */
    function changeRows(list, outsideOnly) {
        return (list || []).filter(c => !outsideOnly || c.drift || c.changeSource === 'state_sync');
    }

    function drawChanges() {
        const c = s.changes;
        const $box = $('#sh-changes');
        if (!c || !$box.length) return;
        const rows = changeRows(c.list, c.outsideOnly);
        const pages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
        c.page = Math.min(Math.max(1, c.page), pages);
        const shown = Pagination.slice(rows, c.page, PAGE_SIZE);
        $box.html(rows.length ? `<div class="sh-table-wrapper"><table class="table table-hover mb-0" id="sh-changes-table">
                <thead><tr><th scope="col">VM</th><th scope="col">From</th><th scope="col"><span class="visually-hidden">to</span></th>
                    <th scope="col">To</th><th scope="col">Where it came from</th><th scope="col">When</th></tr></thead>
                <tbody>${shown.map(r => `<tr${r.drift || r.changeSource === 'state_sync' ? ' class="sh-change-outside"' : ''}>
                    <td>${Utils.escapeHtml(r.vmName || r.vmId || '—')}</td>
                    <td>${stateBadge(r.previousStatus)}</td>
                    <td class="text-center text-muted px-0"><i class="fas fa-arrow-right" aria-hidden="true"></i></td>
                    <td>${stateBadge(r.newStatus)}</td>
                    <td>${Utils.escapeHtml(sourceText(r))}</td>
                    <td class="text-muted" title="${absTime(r.changedAt)}">${relativeTime(r.changedAt)}</td></tr>`).join('')}</tbody></table></div>
            ${rows.length > PAGE_SIZE ? Pagination.buildPager({ page: c.page, pageSize: PAGE_SIZE, totalItems: rows.length,
                action: 'sh-changes-page', key: 'changes' }) : ''}`
            : UiStates.empty({ title: c.outsideOnly ? 'Nothing changed outside the platform' : 'No recent state changes' }));
    }

    function setChangesOutsideOnly(on) {
        if (!s.changes) return;
        s.changes.outsideOnly = !!on;
        s.changes.page = 1;
        drawChanges();
    }

    function changesPage(page) {
        if (!s.changes) return;
        s.changes.page = Number(page) || 1;
        drawChanges();
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

    /** "Checked 42 VMs: 2 changed outside the platform, 1 couldn't be read." (U20-T09: the check has finished). */
    function syncResultText(r) {
        const n = Number(r && r.totalVmsSynced) || 0;
        const drift = Number(r && r.driftDetected) || 0;
        const errors = Number(r && r.syncErrors) || 0;
        const parts = [drift ? `${plural(drift, 'changed', 'changed')} outside the platform` : 'nothing changed outside the platform'];
        if (errors) parts.push(`${errors} couldn't be read`);
        return `Checked ${plural(n, 'VM', 'VMs')}: ${parts.join(', ')}.`;
    }

    function triggerSync() {
        const $btn = $('#trigger-sync-btn');
        $btn.prop('disabled', true).text('Checking…');

        apiPost(Config.API.monitoring.triggerSync)
            .done(result => {
                Notifications.show(syncResultText(result), result && result.syncErrors ? 'warning' : 'success');
                reloadLaterIfCurrent(1000);
            })
            .fail(xhr => {
                if (xhr && xhr.status === 409) {
                    Notifications.show('A check is already running. Its result shows here when it ends.', 'warning');
                } else {
                    Notifications.show("The VM states couldn't be checked. Try again.", 'error');
                }
                $btn.prop('disabled', false).text('Check VM states now');
            });
    }

    function triggerEksSync() {
        const $btn = $('#trigger-eks-sync-btn');
        $btn.prop('disabled', true).text('Syncing EKS clusters…');

        apiPost(Config.API.monitoring.triggerEksSync)
            .done(data => {
                const n = data && data.nodeGroupsSynced != null ? data.nodeGroupsSynced : 0;
                Notifications.show(`EKS clusters synced: ${plural(n, 'node group', 'node groups')} read from AWS.`, 'success');
                reloadLaterIfCurrent(1000);
            })
            .fail(xhr => {
                if (xhr && xhr.status === 503) {
                    Notifications.show('EKS is not available: check the AWS credentials.', 'warning');
                } else {
                    Notifications.show("The EKS clusters couldn't be synced. Try again.", 'error');
                }
            })
            .always(() => {
                $btn.prop('disabled', false).text('Sync EKS clusters');
            });
    }

    // ─── Reports ──────────────────────────────────────────────────────────────

    function reportsHtml({ canAdmin, driftCount, auditReport, lockReport, vmOpsReport, emailLog }) {

        const auditTotal    = auditReport ? (auditReport.totalActions || 0) : 0;
        // Counted by the server over the whole window (E12-T06), not from the 20-row sample.
        const auditSuccess  = auditReport ? Number(auditReport.successfulActions) || 0 : 0;
        const auditFailures = auditReport ? Number(auditReport.failedActions) || 0 : 0;
        const decided       = auditSuccess + auditFailures;
        // U22-T09: never "100%" when something failed (staging: 642 of 645).
        const successRate   = decided > 0 ? Math.min(auditFailures > 0 ? 99 : 100, Math.round((auditSuccess / decided) * 100)) : null;
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

            ${canAdmin ? `
            <!-- State changes (U20-T09): the platform's and those found outside it, in one table -->
            <div class="card sh-table-card mb-3">
                <div class="card-header d-flex flex-wrap align-items-center gap-2">
                    <i class="fas fa-exchange-alt text-info" aria-hidden="true"></i>
                    <strong>State changes</strong>
                    <span class="sh-changes-count">${Utils.escapeHtml(plural(driftCount, 'change', 'changes'))} outside the platform in 24 hours</span>
                    <label class="re-check ms-auto"><input type="checkbox" id="sh-changes-outside" data-action-change="sh-changes-outside">
                        Only outside the platform</label>
                </div>
                <div class="card-body" id="sh-changes"></div>
            </div>` : ''}

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

    function buildComplianceTable(logs, columns) {
        if (!logs || logs.length === 0) {
            return UiStates.empty({ title: 'No events in the last 24 hours' });
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
            return UiStates.empty({ title: 'No emails sent yet (or notification.email.enabled is off)' });
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

    return { load, triggerSync, triggerEksSync, setChangesOutsideOnly, changesPage,
        model: { span, lastRun, status, ordered, tiles, regionRows, foundText, noteKind, syncResultText, sourceText, changeRows } };
})();

if (typeof window !== 'undefined') window.SystemHealth = SystemHealth;

if (typeof Actions !== 'undefined') Actions.registerAll({
    'sh-trigger-sync': () => SystemHealth.triggerSync(),
    'sh-trigger-eks-sync': () => SystemHealth.triggerEksSync(),
    'sh-reload': () => ContentRouter.reload(),
    'sh-changes-page': el => SystemHealth.changesPage(el.dataset.page)
});
if (typeof Actions !== 'undefined') Actions.register('sh-changes-outside', el => SystemHealth.setChangesOutsideOnly(el.checked), 'change');
