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
                <div id="waste-filters"></div>
                <div id="waste-table"></div>
            </div>`);
        refresh();
    }

    function refresh() {
        const token = pageToken;
        Promise.allSettled([call('GET', Config.API.waste.summary), call('GET', findingsUrl())]).then(([summary, findings]) => {
            if (!ContentRouter.isCurrent(token)) return;
            state.errors = {};
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
        drawFilters();
        drawTable();
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
                <td data-label="" class="text-end">${Utils.raw(f.status === 'OPEN'
                    ? Utils.html`<button type="button" class="btn btn-sm btn-ghost" data-action="waste-dismiss" data-id="${f.findingId}"
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
    'waste-page': el => Waste.page(Number(el.dataset.page))
});
