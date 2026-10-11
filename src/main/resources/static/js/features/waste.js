/**
 * Waste page (E22-T03): what the daily sweep found (unattached volumes, unassociated IPs, gp2
 * volumes, long-stopped VMs, old snapshots) with estimated monthly cost, filters, dismiss and
 * reopen, a manual sweep and an Excel export. Admins only.
 */
const Waste = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    const PAGE_SIZE = 25;
    const TYPES = {
        UNATTACHED_VOLUME: { label: 'Unattached volumes', icon: 'fa-hdd' },
        UNASSOCIATED_IP: { label: 'Unassociated IPs', icon: 'fa-network-wired' },
        GP2_VOLUME: { label: 'gp2 → gp3', icon: 'fa-exchange-alt' },
        LONG_STOPPED_VM: { label: 'Long-stopped VMs', icon: 'fa-power-off' },
        OLD_SNAPSHOT: { label: 'Old snapshots', icon: 'fa-camera' }
    };

    /** The fix each finding type may get (E22-T05); long-stopped VMs get none. */
    const FIXES = {
        UNATTACHED_VOLUME: { type: 'DELETE_VOLUME', label: 'Delete (snapshot first)' },
        GP2_VOLUME: { type: 'MIGRATE_GP3', label: 'Migrate to gp3' },
        OLD_SNAPSHOT: { type: 'ARCHIVE_SNAPSHOT', label: 'Archive snapshot' },
        UNASSOCIATED_IP: { type: 'RELEASE_IP', label: 'Release IP' }
    };
    const FIX_LABEL = Object.fromEntries(Object.values(FIXES).map(f => [f.type, f.label]));

    let pageToken = null;
    let state = null;

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data, QUIET).done(resolve).fail(reject);
        });
    }

    function serverMessage(error, fallback) {
        return (error && error.responseJSON && error.responseJSON.message) || fallback;
    }

    function money(v) {
        return v === null || v === undefined ? '—' : Utils.formatCurrency(Number(v));
    }

    function age(ts) {
        if (!ts) return '—';
        const days = Math.floor((Date.now() - new Date(ts).getTime()) / 86_400_000);
        return days < 1 ? 'today' : `${days} day${days === 1 ? '' : 's'}`;
    }

    function load() {
        pageToken = ContentRouter.token();
        if (!Auth.isAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3">Access denied. Admin only.</div>');
            return;
        }
        state = { filters: { type: '', region: '', status: 'OPEN' }, page: 0, summary: null, findings: null, errors: {} };
        $('#content-area').html(`
            <div class="content-view waste-page" id="waste-view">
                <div class="content-header d-flex justify-content-between align-items-center flex-wrap gap-2">
                    <div>
                        <h1><i class="fas fa-recycle me-2" aria-hidden="true"></i>Waste</h1>
                        <p class="text-muted mb-0">Resources that cost money while doing nothing, from the daily sweep (estimated monthly cost)</p>
                    </div>
                    <div class="d-flex gap-2 flex-wrap">
                        <button type="button" class="btn btn-sm btn-ghost" data-action="waste-export"><i class="fas fa-file-excel" aria-hidden="true"></i> Export</button>
                        <button type="button" class="btn btn-sm btn-primary" data-action="waste-sweep" id="waste-sweep-btn"><i class="fas fa-search-dollar" aria-hidden="true"></i> Run sweep now</button>
                    </div>
                </div>
                <div id="waste-summary"></div>
                <div id="waste-actions"></div>
                <div id="waste-filters"></div>
                <div id="waste-table"></div>
            </div>`);
        refresh();
    }

    function refresh() {
        const token = pageToken;
        Promise.allSettled([call('GET', Config.API.waste.summary), call('GET', findingsUrl()),
            call('GET', Config.API.waste.actionsConfig), call('GET', Config.API.waste.actions)]).then(([summary, findings, config, actions]) => {
            if (!ContentRouter.isCurrent(token)) return;
            state.errors = {};
            state.actionsEnabled = config.status === 'fulfilled' && config.value && config.value.enabled;
            state.actions = actions.status === 'fulfilled' ? (actions.value || []) : [];
            if (summary.status === 'fulfilled') state.summary = summary.value; else state.errors.summary = true;
            if (findings.status === 'fulfilled') state.findings = findings.value; else state.errors.findings = true;
            draw();
        });
    }

    function findingsUrl() {
        const f = state.filters;
        const q = new URLSearchParams({ page: state.page, size: PAGE_SIZE });
        if (f.type) q.set('type', f.type);
        if (f.region) q.set('region', f.region);
        if (f.status) q.set('status', f.status);
        return `${Config.API.waste.findings}?${q.toString()}`;
    }

    function error(label) {
        return Utils.html`<div class="cost-panel-error" role="alert"><i class="fas fa-triangle-exclamation" aria-hidden="true"></i>
            <span>Couldn't load ${label}.</span><button type="button" class="btn btn-sm btn-ghost" data-action="waste-refresh">Retry</button></div>`;
    }

    function draw() {
        const s = state.summary;
        if (state.errors.summary) {
            $('#waste-summary').html(error('the waste totals'));
        } else if (s) {
            const off = s.sweepEnabled ? '' : `<div class="alert alert-info mb-3" role="status"><i class="fas fa-info-circle me-1" aria-hidden="true"></i>
                Waste sweeps are turned off: set <code>waste.sweep.enabled</code> (and grant the read-only EC2 permissions) to find waste daily.</div>`;
            const byType = Object.fromEntries((s.byType || []).map(t => [t.type, t]));
            const cards = Object.entries(TYPES).map(([type, meta]) => {
                const t = byType[type] || { count: 0, monthly: 0 };
                return Utils.html`<button type="button" class="dashboard-kpi waste-card ${state.filters.type === type ? 'active' : ''}"
                        data-action="waste-type" data-type="${type}" aria-pressed="${state.filters.type === type ? 'true' : 'false'}">
                    <i class="fas ${meta.icon}" aria-hidden="true"></i>
                    <div><strong>${money(t.monthly)}</strong><span>${meta.label}</span><small class="cost-kpi-subtitle">${t.count} open</small></div>
                </button>`;
            }).join('');
            $('#waste-summary').html(`${off}<div class="waste-total mb-2"><strong>${Utils.escapeHtml(money(s.monthlyTotal))}</strong>
                <span class="text-muted"> a month of open waste (estimated) · ${s.openCount} finding${s.openCount === 1 ? '' : 's'}</span></div>
                <div class="dashboard-kpi-grid waste-cards">${cards}</div>`);
            $('#waste-sweep-btn').prop('disabled', !s.sweepEnabled);
        }
        drawActions();
        drawFilters();
        drawTable();
    }

    /** Pending fixes (Approve for admins other than the proposer) and recent history with Rollback. */
    function drawActions() {
        const actions = state.actions || [];
        if (!state.actionsEnabled && !actions.length) {
            $('#waste-actions').html('');
            return;
        }
        const me = Auth.getUserId ? Auth.getUserId() : null;
        const pending = actions.filter(a => a.status === 'PROPOSED');
        const history = actions.filter(a => a.status !== 'PROPOSED').slice(0, 10);
        const pendingRows = pending.length ? pending.map(a => Utils.html`
            <li class="d-flex justify-content-between align-items-center flex-wrap gap-2 waste-action-row" data-action-id="${a.actionId}">
                <span><strong>${FIX_LABEL[a.actionType] || a.actionType}</strong> · ${a.resourceId} (${a.region})</span>
                <span class="d-flex gap-1">
                    ${Utils.raw(a.proposedBy === me ? '<span class="small text-muted">Waiting for another admin</span>'
                        : Utils.html`<button type="button" class="btn btn-sm btn-primary" data-action="waste-approve" data-id="${a.actionId}">Approve</button>`)}
                    <button type="button" class="btn btn-sm btn-ghost" data-action="waste-reject" data-id="${a.actionId}">Reject</button>
                </span></li>`).join('') : '<li class="text-muted small">Nothing waiting for approval.</li>';
        const historyRows = history.map(a => Utils.html`
            <li class="d-flex justify-content-between align-items-center flex-wrap gap-2 small">
                <span>${FIX_LABEL[a.actionType] || a.actionType} · ${a.resourceId} — <strong>${a.status.toLowerCase().replace('_', ' ')}</strong>${a.errorMessage ? ': ' + a.errorMessage : ''}</span>
                ${Utils.raw(a.status === 'EXECUTED' && a.rollbackSupported
                    ? Utils.html`<button type="button" class="btn btn-sm btn-ghost" data-action="waste-rollback" data-id="${a.actionId}">Rollback</button>`
                    : a.status === 'EXECUTED' ? '<span class="text-muted">not reversible</span>' : '')}
            </li>`).join('');
        $('#waste-actions').html(`<section class="card mb-3 waste-actions-card" aria-labelledby="waste-actions-title"><div class="card-body">
            <h2 class="h6" id="waste-actions-title"><i class="fas fa-tools me-1" aria-hidden="true"></i>Fix actions
                ${state.actionsEnabled ? '' : '<span class="badge text-bg-light border ms-1">turned off</span>'}</h2>
            <ul class="list-unstyled mb-2">${pendingRows}</ul>
            ${history.length ? `<details><summary class="small">Recent actions</summary><ul class="list-unstyled mt-2 mb-0">${historyRows}</ul></details>` : ''}
        </div></section>`);
    }

    function drawFilters() {
        const f = state.filters;
        const typeOptions = Object.entries(TYPES).map(([v, m]) => Utils.html`<option value="${v}" ${f.type === v ? 'selected' : ''}>${m.label}</option>`).join('');
        const statusOptions = ['OPEN', 'DISMISSED', 'RESOLVED', 'ACTION_PENDING'].map(v =>
            Utils.html`<option value="${v}" ${f.status === v ? 'selected' : ''}>${v.charAt(0) + v.slice(1).toLowerCase().replace('_', ' ')}</option>`).join('');
        $('#waste-filters').html(Utils.html`<div class="waste-filters d-flex gap-2 flex-wrap mb-2" role="search">
            <label class="visually-hidden" for="waste-type">Type</label>
            <select class="form-select form-select-sm" id="waste-type"><option value="">All types</option>${Utils.raw(typeOptions)}</select>
            <label class="visually-hidden" for="waste-region">Region</label>
            <input type="text" class="form-control form-control-sm" id="waste-region" placeholder="Region" value="${f.region}">
            <label class="visually-hidden" for="waste-status">Status</label>
            <select class="form-select form-select-sm" id="waste-status"><option value="">Any status</option>${Utils.raw(statusOptions)}</select>
        </div>`);
        $('#waste-type, #waste-status').on('change', function() {
            state.filters[this.id === 'waste-type' ? 'type' : 'status'] = $(this).val();
            state.page = 0;
            refresh();
        });
        $('#waste-region').on('change', function() {
            state.filters.region = $(this).val().trim();
            state.page = 0;
            refresh();
        });
    }

    function drawTable() {
        if (state.errors.findings) {
            $('#waste-table').html(error('the findings'));
            return;
        }
        const page = state.findings;
        if (!page) return;
        const rows = (page.content || []).length ? page.content.map(f => Utils.html`
            <tr>
                <td data-label="Resource"><div class="fw-semibold">${f.resourceName || f.resourceId}</div>
                    <div class="small text-muted">${TYPES[f.findingType] ? TYPES[f.findingType].label : f.findingType} · ${f.resourceId}</div></td>
                <td data-label="Region">${f.region}</td>
                <td data-label="Environment">${f.environmentId
                    ? Utils.raw(Utils.html`<a href="#/environments/${f.environmentId}">${f.environmentName || f.environmentId}</a>`) : '—'}</td>
                <td data-label="Waste for">${age(f.eligibleSince || f.firstSeenAt)}</td>
                <td data-label="Est. monthly">${money(f.estMonthlyCost)}</td>
                <td data-label="Status">${f.status.charAt(0) + f.status.slice(1).toLowerCase().replace('_', ' ')}${Utils.raw(f.dismissedReason
                    ? Utils.html`<div class="small text-muted">${f.dismissedReason}</div>` : '')}</td>
                <td data-label="" class="text-end text-nowrap">${Utils.raw(f.status === 'OPEN'
                    ? Utils.html`${Utils.raw(state.actionsEnabled && FIXES[f.findingType]
                        ? Utils.html`<button type="button" class="btn btn-sm btn-tonal btn-primary" data-action="waste-propose" data-id="${f.findingId}"
                            data-fix="${FIXES[f.findingType].type}" aria-label="${FIXES[f.findingType].label}: ${f.resourceId}">${FIXES[f.findingType].label}</button>` : '')}
                        ${Utils.raw(f.findingType === 'LONG_STOPPED_VM' && f.environmentId
                        ? Utils.html`<a class="btn btn-sm btn-ghost" href="#/environments/${f.environmentId}">Open VM</a>` : '')}
                        <button type="button" class="btn btn-sm btn-ghost" data-action="waste-dismiss" data-id="${f.findingId}"
                        aria-label="Dismiss ${f.resourceId}">Dismiss</button>`
                    : f.status === 'DISMISSED' ? Utils.html`<button type="button" class="btn btn-sm btn-ghost" data-action="waste-reopen"
                        data-id="${f.findingId}" aria-label="Reopen ${f.resourceId}">Reopen</button>` : '')}</td>
            </tr>`).join('')
            : '<tr><td colspan="7" class="text-muted text-center py-3">No findings</td></tr>';
        const meta = page.page || { number: 0, totalPages: 1, totalElements: 0 };
        const pager = meta.totalPages > 1 ? Utils.html`<div class="d-flex justify-content-between align-items-center mt-2 small">
            <span>${meta.totalElements} findings · page ${meta.number + 1} of ${meta.totalPages}</span>
            <span class="d-flex gap-1">
                <button type="button" class="btn btn-sm btn-ghost" data-action="waste-page" data-page="${meta.number - 1}" ${meta.number === 0 ? 'disabled' : ''}>Previous</button>
                <button type="button" class="btn btn-sm btn-ghost" data-action="waste-page" data-page="${meta.number + 1}" ${meta.number + 1 >= meta.totalPages ? 'disabled' : ''}>Next</button>
            </span></div>` : '';
        $('#waste-table').html(`<section class="card"><div class="card-body">
            <div class="table-responsive"><table class="table table-sm table-cards mb-0" id="waste-findings">
                <thead><tr><th>Resource</th><th>Region</th><th>Environment</th><th>Waste for</th><th>Est. monthly</th><th>Status</th><th><span class="visually-hidden">Actions</span></th></tr></thead>
                <tbody>${rows}</tbody></table></div>${pager}</div></section>`);
    }

    function dismiss(id) {
        Modals.prompt('Dismiss finding', 'Why is this not waste?', reason => {
            call('POST', Config.API.waste.dismiss(id), { reason: reason.trim() })
                .then(() => { Notifications.show('Finding dismissed.', 'success'); refresh(); })
                .catch(e => Notifications.showError(serverMessage(e, 'Could not dismiss the finding.')));
        }, { required: true, submitText: 'Dismiss', placeholder: 'e.g. kept for the DR drill' });
    }

    function reopen(id) {
        call('POST', Config.API.waste.reopen(id))
            .then(() => { Notifications.show('Finding reopened.', 'success'); refresh(); })
            .catch(e => Notifications.showError(serverMessage(e, 'Could not reopen the finding.')));
    }

    // ============= Fix actions (E22-T04/T05) =============

    function propose(findingId, fixType) {
        const f = ((state.findings && state.findings.content) || []).find(x => x.findingId === findingId);
        if (!f) return;
        Modals.confirm('Propose a fix', `${FIX_LABEL[fixType]} for ${f.resourceId}? Another admin must approve it before anything changes in AWS.`, () => {
            call('POST', Config.API.waste.propose(findingId), { type: fixType })
                .then(() => { Notifications.show('Fix proposed; another admin must approve it.', 'success'); refresh(); })
                .catch(e => Notifications.showError(serverMessage(e, 'Could not propose the fix.')));
        }, { confirmText: 'Propose' });
    }

    function approve(actionId) {
        const a = (state.actions || []).find(x => x.actionId === actionId);
        if (!a) return;
        const send = () => call('POST', Config.API.waste.approve(actionId))
            .then(() => { Notifications.show('Approved; the fix runs in the background.', 'success'); refresh(); })
            .catch(e => Notifications.showError(serverMessage(e, 'Could not approve the fix.')));
        if (a.actionType === 'RELEASE_IP' || a.actionType === 'DELETE_VOLUME') {
            let details = {};
            try { details = JSON.parse(a.findingDetails || '{}'); } catch (e) { details = {}; }
            const confirmText = a.actionType === 'RELEASE_IP' ? (details.publicIp || a.resourceId) : a.resourceId;
            DestructiveConfirm.show({
                title: a.actionType === 'RELEASE_IP' ? 'Release Elastic IP' : 'Delete unattached volume',
                message: a.actionType === 'RELEASE_IP'
                    ? `This releases ${confirmText}. AWS will not give the same address back: this cannot be undone.`
                    : `This snapshots ${a.resourceId}, waits for the snapshot, then deletes the volume. Rollback recreates it from the snapshot.`,
                impact: a.actionType === 'RELEASE_IP' ? ['The public IP is lost for good', 'Anything using it by address stops reaching it']
                    : ['The volume is deleted after its snapshot completes', 'The snapshot is kept (and billed) for rollback'],
                confirmText,
                actionText: 'Approve',
                onConfirm: send
            });
        } else {
            const note = a.actionType === 'MIGRATE_GP3' ? ' AWS allows one change per volume every 6 hours, so a rollback may have to wait.'
                : ' The archive tier bills at least 90 days.';
            Modals.confirm('Approve fix', `${FIX_LABEL[a.actionType]} for ${a.resourceId}?${note}`, send, { confirmText: 'Approve' });
        }
    }

    function reject(actionId) {
        call('POST', Config.API.waste.reject(actionId))
            .then(() => { Notifications.show('Fix rejected.', 'success'); refresh(); })
            .catch(e => Notifications.showError(serverMessage(e, 'Could not reject the fix.')));
    }

    function rollback(actionId) {
        Modals.confirm('Roll back fix', 'Undo this fix in AWS?', () => {
            call('POST', Config.API.waste.rollback(actionId))
                .then(() => { Notifications.show('Rolled back.', 'success'); refresh(); })
                .catch(e => Notifications.showError(serverMessage(e, 'Could not roll back the fix.')));
        }, { confirmText: 'Roll back' });
    }

    function sweep() {
        const $btn = $('#waste-sweep-btn').prop('disabled', true);
        call('POST', Config.API.waste.sweep)
            .then(() => { Notifications.show('Sweep finished.', 'success'); refresh(); })
            .catch(e => Notifications.showError(serverMessage(e, 'The sweep did not run.')))
            .finally(() => $btn.prop('disabled', false));
    }

    function exportFindings() {
        const q = new URLSearchParams();
        Object.entries(state.filters).forEach(([k, v]) => { if (v) q.set(k, v); });
        fetch(`${Config.API.waste.export}?${q.toString()}`, { credentials: 'same-origin' })
            .then(r => { if (!r.ok) throw new Error(String(r.status)); return r.blob(); })
            .then(blob => Utils.downloadBlob(blob, 'waste-findings.xlsx'))
            .catch(() => Notifications.showError('Could not export the findings.'));
    }

    return {
        load,
        refresh,
        dismiss,
        reopen,
        sweep,
        exportFindings,
        propose,
        approve,
        reject,
        rollback,
        filterType: type => { state.filters.type = state.filters.type === type ? '' : type; state.page = 0; refresh(); },
        page: p => { state.page = Math.max(0, p); refresh(); }
    };
})();

window.Waste = Waste;

Actions.registerAll({
    'waste-refresh': () => Waste.refresh(),
    'waste-dismiss': el => Waste.dismiss(el.dataset.id),
    'waste-reopen': el => Waste.reopen(el.dataset.id),
    'waste-sweep': () => Waste.sweep(),
    'waste-export': () => Waste.exportFindings(),
    'waste-type': el => Waste.filterType(el.dataset.type),
    'waste-page': el => Waste.page(Number(el.dataset.page)),
    'waste-propose': el => Waste.propose(el.dataset.id, el.dataset.fix),
    'waste-approve': el => Waste.approve(el.dataset.id),
    'waste-reject': el => Waste.reject(el.dataset.id),
    'waste-rollback': el => Waste.rollback(el.dataset.id)
});
