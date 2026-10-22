/**
 * VM Self-Service Platform - Environment alerts (U07-T07, EnvAlerts.dc.html)
 * Every alert an environment page can show, in one stack under the header, most urgent first:
 * red (a frozen budget, someone else's lock), navy (my request waiting for approval), gold (the
 * lease ends within an hour, yesterday's spend spike, VMs changed outside the platform, an EKS
 * cluster whose AWS region is not known, U19-T04), grey (VM
 * states may be out of date). Each source sets or clears its own alert when its data arrives
 * (EnvAlerts.set(kind, alert | null)) and the stack re-renders; nothing shows, and nothing takes
 * space, when there is nothing to report. The rules are pure functions (EnvAlerts.model), tested in
 * src/test/js/env-alerts.test.mjs.
 *
 * An alert: { kind, tone, title, detail, action?: { label, href } | { label, dataAction, data } }.
 */
const EnvAlerts = (function() {
    'use strict';

    const TONES = ['red', 'navy', 'gold', 'grey'];
    const KINDS = ['budget', 'lock', 'approval', 'lease', 'cost', 'drift', 'region', 'sync'];
    const KIND_LABEL = { budget: 'Budget', lock: 'Lock', approval: 'Approval', lease: 'Lease', cost: 'Cost', drift: 'Drift',
        region: 'Region', sync: 'Sync' };
    /** VM states are read every 5 min; three missed reads (15 min) is out of date. */
    const STALE_AFTER_MIN = 15;

    /** Most urgent first: by tone, then by kind. */
    function rank(alerts) {
        const order = (list, v) => { const i = list.indexOf(v); return i < 0 ? list.length : i; };
        return (alerts || []).filter(Boolean).slice()
            .sort((a, b) => order(TONES, a.tone) - order(TONES, b.tone) || order(KINDS, a.kind) - order(KINDS, b.kind));
    }

    /** "2 hours ago", "45 minutes ago", "3 days ago". */
    function ago(ms, now) {
        const minutes = Math.max(0, Math.round((now - ms) / 60000));
        if (minutes < 60) return `${minutes} minute${minutes === 1 ? '' : 's'} ago`;
        const hours = Math.round(minutes / 60);
        if (hours < 48) return `${hours} hour${hours === 1 ? '' : 's'} ago`;
        const days = Math.round(hours / 24);
        return `${days} day${days === 1 ? '' : 's'} ago`;
    }

    /**
     * Whether the VMs' states are out of date: the newest lastStateSyncAt is older than
     * staleAfterMin. VMs never read at all do not count (the page shows them as unknown).
     * Returns {stale, lastMs}.
     */
    function staleSync(vms, now = Date.now(), staleAfterMin = STALE_AFTER_MIN) {
        const times = (vms || []).map(v => v && v.lastStateSyncAt ? new Date(v.lastStateSyncAt).getTime() : NaN)
            .filter(t => !Number.isNaN(t));
        if (!times.length) return { stale: false, lastMs: null };
        const lastMs = Math.max(...times);
        return { stale: now - lastMs > staleAfterMin * 60000, lastMs };
    }

    /** The sync alert, or null: grey, with System health for administrators only. */
    function syncAlert(vms, isAdmin, now = Date.now()) {
        const s = staleSync(vms, now);
        if (!s.stale) return null;
        return {
            kind: 'sync', tone: 'grey', title: 'VM states may be out of date.',
            detail: `They were last read from AWS ${ago(s.lastMs, now)}; the platform keeps trying.`,
            action: isAdmin ? { label: 'System health', href: '#/system-health' } : null,
        };
    }

    /** The drift alert, or null: gold, naming the first VM changed outside the platform. */
    function driftAlert(vms) {
        const drifted = (vms || []).filter(v => v && v.stateDriftDetected === true);
        if (!drifted.length) return null;
        const first = drifted[0];
        const name = first.displayName || first.name;
        const others = drifted.length - 1;
        return {
            kind: 'drift', tone: 'gold',
            title: `${drifted.length} VM${drifted.length === 1 ? '' : 's'} changed outside the platform:`,
            detail: `${name}${others ? ` and ${others} more` : ''} ${others ? 'are' : 'is'} not in the state the platform last set.`,
            action: { label: 'Details', dataAction: 'env-alert-vm', data: { 'vm-id': first.vmId } },
        };
    }

    /**
     * The region alert, or null (U19-T04): gold, when nothing says which AWS region the environment's
     * cluster is in; whoever can change its settings gets the way to set it.
     */
    function regionAlert(env, canAdminister) {
        if (!env || env.regionMissing !== true) return null;
        return {
            kind: 'region', tone: 'gold', title: "This cluster's AWS region isn't known.",
            detail: "It isn't synced with AWS, and its node groups can't be started or stopped, until the region is set.",
            action: canAdminister && env.environmentId
                ? { label: 'Set the region', href: `#/environments/${encodeURIComponent(env.environmentId)}/settings/details` } : null,
        };
    }

    // ---------------------------------------------------------------- the stack

    let alerts = {};
    let envId = null;

    /** Start a fresh stack for an environment page. */
    function reset(id) {
        envId = id;
        alerts = {};
        draw();
    }

    /** Set (or with null, clear) one kind's alert for the environment on screen. */
    function set(kind, alert, forEnvId) {
        if (forEnvId && envId && forEnvId !== envId) return;
        if (alert) alerts[kind] = { ...alert, kind };
        else delete alerts[kind];
        draw();
    }

    function actionHtml(action) {
        if (!action) return '';
        if (action.href) return Utils.html`<a class="env-alert-action" href="${action.href}">${action.label}</a>`;
        const data = Object.entries(action.data || {})
            .map(([k, v]) => ` data-${Utils.escapeHtml(k)}="${Utils.escapeHtml(String(v))}"`).join('');
        return Utils.html`<button type="button" class="btn btn-link env-alert-action" data-action="${action.dataAction}"` + data + Utils.html`>${action.label}</button>`;
    }

    function draw() {
        const el = document.getElementById('env-alerts');
        if (!el) return;
        el.innerHTML = rank(Object.values(alerts)).map(a => Utils.html`
            <div class="env-alert tone-${a.tone}" role="${a.tone === 'red' ? 'alert' : 'status'}" data-kind="${a.kind}"${a.id ? Utils.raw(Utils.html` id="${a.id}"`) : ''}>
                <span class="env-alert-dot" aria-hidden="true"></span>
                <span class="env-alert-text"><b>${a.title}</b> ${a.detail || ''}</span>
                <span class="env-alert-side">${Utils.raw(actionHtml(a.action))}<span class="env-alert-kind">${KIND_LABEL[a.kind] || ''}</span></span>
            </div>`).join('');
    }

    function current() {
        return rank(Object.values(alerts));
    }

    return { reset, set, current, model: { rank, ago, staleSync, syncAlert, driftAlert, regionAlert } };
})();

if (typeof window !== 'undefined') window.EnvAlerts = EnvAlerts;
