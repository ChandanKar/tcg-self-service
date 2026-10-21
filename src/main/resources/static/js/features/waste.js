/**
 * Waste page (E22-T03, U15-T07 Waste.dc.html): what the daily sweep found (unattached volumes, unused
 * public IPs, gp2 volumes, VMs stopped over 30 days, old snapshots) with estimated monthly cost; type
 * cards that filter the list, a fix per row (proposed, then approved by another admin on Waste ›
 * Fixes), dismiss and reopen, "Check again now" and an Excel export. Admins only.
 */
const Waste = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    const PAGE_SIZE = 10;
    /** The board's names for each type, in its order. */
    const TYPES = {
        UNATTACHED_VOLUME: { label: 'Unattached volumes' },
        UNASSOCIATED_IP: { label: 'Unused public IPs' },
        GP2_VOLUME: { label: 'gp2 → gp3' },
        LONG_STOPPED_VM: { label: 'Stopped over 30 days' },
        OLD_SNAPSHOT: { label: 'Old snapshots' }
    };

    /** The fix each finding type may get (E22-T05), named for what it does; long-stopped VMs get none. */
    const FIXES = {
        UNATTACHED_VOLUME: { type: 'DELETE_VOLUME', label: 'Snapshot and delete…' },
        GP2_VOLUME: { type: 'MIGRATE_GP3', label: 'Move to gp3…' },
        OLD_SNAPSHOT: { type: 'ARCHIVE_SNAPSHOT', label: 'Archive…' },
        UNASSOCIATED_IP: { type: 'RELEASE_IP', label: 'Release…' }
    };
    const FIX_LABEL = { DELETE_VOLUME: 'Delete the volume (snapshot first)', MIGRATE_GP3: 'Change it to gp3',
        ARCHIVE_SNAPSHOT: 'Move the snapshot to the archive tier', RELEASE_IP: 'Release the IP' };

    function parseDetails(f) {
        if (!f || !f.details) return {};
        try { return JSON.parse(f.details) || {}; } catch (e) { return {}; }
    }

    /** "Unattached volume, 500 GiB gp3", "Unused public IP 13.0.0.1", "Stopped VM, m5.2xlarge", "Snapshot from 2025". */
    function kindText(f) {
        const d = parseDetails(f);
        const gib = d.sizeGib != null ? `${d.sizeGib} GiB` : '';
        switch (f.findingType) {
            case 'UNATTACHED_VOLUME': return ['Unattached volume', [gib, d.volumeType].filter(Boolean).join(' ')].filter(Boolean).join(', ');
            case 'UNASSOCIATED_IP': return d.publicIp ? `Unused public IP ${d.publicIp}` : 'Unused public IP';
            case 'GP2_VOLUME': return ['gp2 volume', gib].filter(Boolean).join(', ');
            case 'LONG_STOPPED_VM': return ['Stopped VM', d.instanceType].filter(Boolean).join(', ');
            case 'OLD_SNAPSHOT': {
                const year = String(d.startTime || d.createdAt || '').slice(0, 4);
                return /^\d{4}$/.test(year) ? `Snapshot from ${year}` : ['Snapshot', gib].filter(Boolean).join(', ');
            }
            default: return f.findingType;
        }
    }

    /** "Checked daily at 04:00 UTC; last check today." from the sweep's schedule and last run. */
    function scheduleText(summary, now) {
        const s = summary || {};
        const checked = s.checked ? (/^Daily \d\d:\d\d$/.test(s.checked) ? `Checked daily at ${s.checked.slice(6)} UTC`
            : `Checked ${s.checked.charAt(0).toLowerCase()}${s.checked.slice(1)}`) : '';
        let last = '';
        if (s.lastSweepAt) {
            const days = Math.floor(((now || new Date()) - new Date(s.lastSweepAt)) / 86_400_000);
            last = days < 1 ? 'last check today' : days === 1 ? 'last check yesterday' : `last check ${days} days ago`;
        } else {
            last = 'not checked yet';
        }
        return [checked, last].filter(Boolean).join('; ') + '.';
    }

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
        state = { filters: { type: '', status: 'OPEN' }, page: 0, summary: null, findings: null, errors: {} };
        $('#content-area').html(`
            <div class="content-view waste-page" id="waste-view">
                ${PageHeader.markup({ title: 'Waste',
                    summaryHtml: '<span id="waste-intro">Things in AWS that cost money and do nothing.</span>',
                    actionsHtml: `<div class="waste-toolbar">
                        <button type="button" class="btn btn-outline-secondary" data-action="waste-export">Export, Excel</button>
                        <button type="button" class="btn btn-outline-primary" data-action="waste-sweep" id="waste-sweep-btn">Check again now</button></div>`,
                    helpPage: typeof HelpLink !== 'undefined' ? 'waste' : null, helpLabel: 'Waste' })}
                <div id="waste-summary"></div>
                <div id="waste-filters"></div>
                <div id="waste-table"></div>
                <div id="waste-actions"></div>
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
        if (f.status) q.set('status', f.status);
        return `${Config.API.waste.findings}?${q.toString()}`;
    }

    function error(label) {
        return Utils.html`<div class="cost-panel-error" role="alert"><i class="fas fa-triangle-exclamation" aria-hidden="true"></i>
            <span>Couldn't load ${label}.</span><button type="button" class="btn btn-sm btn-ghost" data-action="waste-refresh">Retry</button></div>`;
    }

    function money2(v) {
        return v === null || v === undefined ? '—' : '$' + Number(v).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    }

    function money0(v) {
        return '$' + Math.round(Number(v || 0)).toLocaleString('en-US');
    }

    function draw() {
        const s = state.summary;
        if (state.errors.summary) {
            $('#waste-summary').html(error('the waste totals'));
        } else if (s) {
            $('#waste-intro').text(`Things in AWS that cost money and do nothing. ${scheduleText(s)}`);
            const off = s.sweepEnabled ? '' : `<p class="cost-note waste-off" role="status">Waste sweeps are turned off: set
                <code>waste.sweep.enabled</code> (and grant the read-only EC2 permissions) to find waste daily.</p>`;
            $('#waste-summary').html(`${off}<p class="waste-total"><span class="cost-mono waste-total-value">${Utils.escapeHtml(money0(s.monthlyTotal))}</span>
                a month across <span class="cost-mono">${s.openCount}</span> open finding${s.openCount === 1 ? '' : 's'}</p>`);
            $('#waste-sweep-btn').prop('disabled', !s.sweepEnabled)
                .attr('title', s.sweepEnabled ? 'Run the sweep now' : 'Waste sweeps are turned off: set waste.sweep.enabled');
        }
        drawFilters();
        drawTable();
        drawFooter();
    }

    /** Everything plus one pressable card per type, with its monthly cost and open count. */
    function drawFilters() {
        const s = state.summary;
        if (!s) { $('#waste-filters').html(''); return; }
        const byType = Object.fromEntries((s.byType || []).map(t => [t.type, t]));
        const card = (type, label, monthly, count) => Utils.html`<button type="button" class="waste-card" data-action="waste-type"
                data-type="${type}" aria-pressed="${state.filters.type === type ? 'true' : 'false'}">
                <span class="cost-mono waste-card-cost">${money0(monthly)}</span><span class="waste-card-name">${label}</span>
                <span class="cost-note">${String(count)} open</span></button>`;
        const cards = [card('', 'Everything', s.monthlyTotal, s.openCount)].concat(Object.entries(TYPES).map(([type, meta]) => {
            const t = byType[type] || { count: 0, monthly: 0 };
            return card(type, meta.label, t.monthly, t.count);
        })).join('');
        $('#waste-filters').html(`<div class="waste-cards" role="group" aria-label="Show">${cards}</div>
            <label class="waste-dismissed"><input type="checkbox" class="form-check-input" id="waste-show-dismissed"
                ${state.filters.status === 'DISMISSED' ? 'checked' : ''}> Show dismissed</label>`);
        $('#waste-show-dismissed').on('change', function() {
            state.filters.status = this.checked ? 'DISMISSED' : 'OPEN';
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
        const rows = (page.content || []).length ? page.content.map(f => {
            const fix = FIXES[f.findingType];
            const actions = f.status === 'OPEN'
                ? Utils.html`${Utils.raw(state.actionsEnabled && fix
                    ? Utils.html`<button type="button" class="btn btn-link btn-sm waste-fix" data-action="waste-propose" data-id="${f.findingId}"
                        data-fix="${fix.type}" aria-label="${fix.label.replace('…', '')}: ${f.resourceId}">${fix.label}</button>` : '')}${Utils.raw(
                    f.findingType === 'LONG_STOPPED_VM' && f.environmentId
                    ? Utils.html`<a class="btn btn-link btn-sm waste-fix" href="#/environments/${f.environmentId}">Open its environment</a>` : '')}<button
                        type="button" class="btn btn-link btn-sm waste-dismiss" data-action="waste-dismiss" data-id="${f.findingId}"
                        aria-label="Dismiss ${f.resourceId}">Dismiss</button>`
                : f.status === 'DISMISSED' ? Utils.html`<button type="button" class="btn btn-link btn-sm" data-action="waste-reopen"
                    data-id="${f.findingId}" aria-label="Reopen ${f.resourceId}">Reopen</button>` : '';
            return Utils.html`
            <tr data-finding="${f.findingId}">
                <td><div class="cost-mono waste-id">${f.resourceId}</div><div class="rs-sub">${kindText(f)}${Utils.raw(f.dismissedReason
                    ? Utils.html` · dismissed: ${f.dismissedReason}` : '')}</div></td>
                <td class="cost-mono waste-region">${f.region}</td>
                <td>${f.environmentId ? Utils.raw(Utils.html`<a href="#/environments/${f.environmentId}">${f.environmentName || f.environmentId}</a>`) : '—'}</td>
                <td>${age(f.eligibleSince || f.firstSeenAt)}</td>
                <td class="cost-num cost-mono">${money2(f.estMonthlyCost)}</td>
                <td class="waste-actions-cell">${Utils.raw(actions)}</td>
            </tr>`;
        }).join('') : `<tr><td colspan="6" class="text-muted text-center py-3">${state.filters.status === 'DISMISSED'
            ? 'Nothing dismissed.' : 'No waste found.'}</td></tr>`;
        const meta = page.page || { number: 0, totalPages: 1, totalElements: 0 };
        const pager = meta.totalElements > PAGE_SIZE ? Pagination.buildPager({ page: meta.number + 1, totalItems: meta.totalElements,
            pageSize: PAGE_SIZE, action: 'waste-page' }) : '';
        $('#waste-table').html(`<section class="cost-card rs-table-card waste-table-card">
            <div class="cost-scroll"><table class="cost-table waste-table" id="waste-findings">
                <thead><tr><th scope="col">Resource</th><th scope="col">Region</th><th scope="col">Environment</th><th scope="col">Idle for</th>
                    <th scope="col" class="cost-num">A month</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr></thead>
                <tbody>${rows}</tbody></table></div>${pager}</section>`);
    }

    /** How fixes work, and the fixes waiting for approval (Waste › Fixes); hidden while fixes are off. */
    function drawFooter() {
        if (!state.actionsEnabled) {
            $('#waste-actions').html('');
            return;
        }
        const waiting = (state.actions || []).filter(a => a.status === 'PROPOSED').length;
        $('#waste-actions').html(`<p class="cost-note waste-foot">Fixes go through an approval and keep a snapshot first, so they can be undone.
            <a href="#/waste/fixes" id="waste-fixes-link">Fixes · ${waiting} waiting</a></p>`);
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
        Modals.confirm('Propose a fix', `${FIX_LABEL[fixType]}: ${f.resourceId}? Another admin approves it before anything changes in AWS.`, () => {
            call('POST', Config.API.waste.propose(findingId), { type: fixType })
                .then(() => { Notifications.show('Fix proposed; another admin must approve it.', 'success'); refresh(); })
                .catch(e => Notifications.showError(serverMessage(e, 'Could not propose the fix.')));
        }, { confirmText: 'Propose' });
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
        filterType: type => { state.filters.type = type || ''; state.page = 0; refresh(); },
        page: p => { state.page = Math.max(0, p); refresh(); },
        model: { kindText, scheduleText }
    };
})();

if (typeof window !== 'undefined') window.Waste = Waste;

if (typeof Actions !== 'undefined') Actions.registerAll({
    'waste-refresh': () => Waste.refresh(),
    'waste-dismiss': el => Waste.dismiss(el.dataset.id),
    'waste-reopen': el => Waste.reopen(el.dataset.id),
    'waste-sweep': () => Waste.sweep(),
    'waste-export': () => Waste.exportFindings(),
    'waste-type': el => Waste.filterType(el.dataset.type),
    'waste-page': el => Waste.page(Number(el.dataset.page) - 1),
    'waste-propose': el => Waste.propose(el.dataset.id, el.dataset.fix)
});
