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
    /** The idle VMs card (U18-T04: moved here from Cost management's Overview). */
    const IDLE = 'IDLE_VMS';

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

    /**
     * "Checked daily at 04:00 UTC; last check today." from the sweep's schedule and last run; nothing
     * while the checks are switched off (U23-T07: they do not run, so no schedule to promise).
     */
    function scheduleText(summary, now) {
        const s = summary || {};
        if (s.sweepEnabled === false) return '';
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

    /**
     * The Everything card (U23-T07): the open findings and the idle VMs card's amount and count, so it
     * is everything the cards beside it show.
     */
    function everything(summary, idleTotals) {
        const s = summary || {};
        const idle = idleTotals || { count: 0, monthly: 0 };
        return { monthly: Number(s.monthlyTotal || 0) + Number(idle.monthly || 0), count: (s.openCount || 0) + (idle.count || 0) };
    }

    /** The findings table with nothing in it: why, and where the idle VMs are (U23-T07). */
    function emptyText(summary, filters, idleTotals) {
        if (filters.status === 'DISMISSED') return 'Nothing dismissed.';
        const n = !filters.type && idleTotals ? idleTotals.count || 0 : 0;
        const idle = n ? ` ${n} idle ${n === 1 ? 'VM is' : 'VMs are'} under Idle VMs.` : '';
        const checked = !summary || summary.sweepEnabled !== false;
        return (checked ? 'No waste found.' : 'Disks, addresses and snapshots are not checked while waste checks are off.') + idle;
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
        state = { filters: { type: '', status: 'OPEN' }, page: 0, summary: null, findings: null, errors: {}, idleTotals: null, idle: null,
            fit: null, fitted: false };
        $('#content-area').html(`
            <div class="content-view page page-frame waste-page" id="waste-view">
                ${PageHeader.markup({ title: 'Waste', sectionNav: PageHeader.costNav('waste'),
                    summaryHtml: '<span id="waste-intro">Things in AWS that cost money and do nothing.</span>',
                    actionsHtml: `<div class="waste-toolbar">
                        <button type="button" class="btn btn-outline-secondary" data-action="waste-export">Export, Excel</button>
                        <button type="button" class="btn btn-outline-primary" data-action="waste-sweep" id="waste-sweep-btn">Check again now</button></div>`,
                    helpPage: typeof HelpLink !== 'undefined' ? 'waste' : null, helpLabel: 'Waste' })}
                <div id="waste-summary"></div>
                <div id="waste-filters"></div>
                <div id="waste-table" class="page-fill waste-table-area" data-scroll-label="Waste"></div>
                <div id="waste-actions"></div>
            </div>`);
        refresh();
    }

    function refresh() {
        const token = pageToken;
        const idle = state.filters.type === IDLE;
        Promise.allSettled([call('GET', Config.API.waste.summary), idle ? Promise.resolve(null) : call('GET', findingsUrl()),
            call('GET', Config.API.waste.actionsConfig), call('GET', Config.API.waste.actions),
            call('GET', Config.API.costManagement.summary),
            idle ? call('GET', Config.API.costManagement.idleWaste(state.page, pageSize())) : Promise.resolve(null)]).then(([summary, findings, config, actions, costs, idlePage]) => {
            if (!ContentRouter.isCurrent(token)) return;
            state.errors = {};
            state.idleTotals = costs.status === 'fulfilled' && costs.value
                ? { count: costs.value.idleVmCount || 0, monthly: costs.value.idleWasteMonthlyCost || 0 } : null;
            if (idle) {
                if (idlePage.status === 'fulfilled') state.idle = idlePage.value; else state.errors.idle = idlePage.reason || true;
            }
            state.actionsEnabled = config.status === 'fulfilled' && config.value && config.value.enabled;
            state.actions = actions.status === 'fulfilled' ? (actions.value || []) : [];
            if (summary.status === 'fulfilled') state.summary = summary.value; else state.errors.summary = summary.reason || true;
            if (findings.status === 'fulfilled') state.findings = findings.value; else state.errors.findings = findings.reason || true;
            draw();
        });
    }

    function findingsUrl() {
        const f = state.filters;
        const q = new URLSearchParams({ page: state.page, size: pageSize() });
        if (f.type) q.set('type', f.type);
        if (f.status) q.set('status', f.status);
        return `${Config.API.waste.findings}?${q.toString()}`;
    }

    function error(label, xhr) {
        return UiStates.failed({ what: label, xhr, retryAction: 'waste-refresh', className: 'cost-panel-error' });
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
            $('#waste-summary').html(error('the waste totals', state.errors.summary));
        } else if (s) {
            const schedule = scheduleText(s);
            $('#waste-intro').text(`Things in AWS that cost money and do nothing.${schedule ? ' ' + schedule : ''}`);
            // U22-T07: switched off reads as off, not as "$0 a month across 0 open findings".
            const off = s.sweepEnabled ? '' : `<p class="cost-note waste-off" id="waste-off-note" role="status">Waste checks are switched off, so
                unused disks, addresses and snapshots are not looked for. An administrator can switch it on in the server settings.</p>`;
            const total = s.sweepEnabled || s.openCount > 0
                ? `<span class="cost-mono waste-total-value">${Utils.escapeHtml(money0(s.monthlyTotal))}</span>
                a month across <span class="cost-mono">${s.openCount}</span> open finding${s.openCount === 1 ? '' : 's'}`
                : 'Not checked';
            $('#waste-summary').html(`${off}<div class="waste-total-row"><p class="waste-total">${total}</p>
                <span id="waste-dismissed-slot"></span></div>`);
            $('#waste-sweep-btn').prop('disabled', !s.sweepEnabled)
                .attr('title', s.sweepEnabled ? 'Run the sweep now' : 'Waste checks are switched off');
            // U23-T07: the reason is read out with the disabled button too.
            if (s.sweepEnabled) $('#waste-sweep-btn').removeAttr('aria-describedby');
            else $('#waste-sweep-btn').attr('aria-describedby', 'waste-off-note');
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
        const idle = state.idleTotals;
        const all = everything(s, idle);
        const cards = [card('', 'Everything', all.monthly, all.count)].concat(Object.entries(TYPES).map(([type, meta]) => {
            const t = byType[type] || { count: 0, monthly: 0 };
            return card(type, meta.label, t.monthly, t.count);
        })).concat(idle ? [Utils.html`<button type="button" class="waste-card waste-card-idle" data-action="waste-type"
                data-type="${IDLE}" aria-pressed="${state.filters.type === IDLE ? 'true' : 'false'}">
                <span class="cost-mono waste-card-cost">${money0(idle.monthly)}</span><span class="waste-card-name">Idle VMs</span>
                <span class="cost-note">${String(idle.count)} barely used</span></button>`] : []).join('');
        $('#waste-filters').html(`<div class="waste-cards" role="group" aria-label="Show">${cards}</div>`);
        // U20-T01: on the total's line, so the findings keep the height.
        $('#waste-dismissed-slot').html(state.filters.type === IDLE ? '' : `<label class="waste-dismissed"><input type="checkbox" class="form-check-input" id="waste-show-dismissed"
                ${state.filters.status === 'DISMISSED' ? 'checked' : ''}> Show dismissed</label>`);
        $('#waste-show-dismissed').on('change', function() {
            state.filters.status = this.checked ? 'DISMISSED' : 'OPEN';
            state.page = 0;
            refresh();
        });
    }

    /** Idle VMs (U18-T04): running VMs that barely work, by idle cost, with the EKS and Jira cells of Cost management. */
    function drawIdle() {
        if (state.errors.idle) {
            $('#waste-table').html(error('the idle VMs', state.errors.idle));
            return;
        }
        const page = state.idle;
        if (!page) return;
        const meta = page.page || { number: page.number || 0, totalElements: page.totalElements || 0 };
        const rows = typeof CostManagement !== 'undefined' && CostManagement.idleRowsHtml
            ? CostManagement.idleRowsHtml(page.content || []) : '';
        $('#waste-table').html(`<section class="list-panel rs-table-card waste-table-card">
            <div class="list-panel-head waste-idle-head"><h2>Idle VMs, by idle cost</h2>
                <a href="${Utils.escapeHtml(Config.API.costManagement.idleWasteExport())}" download="idle-vms.xlsx">Excel</a></div>
            <div class="cost-scroll list-panel-body"><table class="cost-table waste-table" id="cost-idle-table">
                <thead><tr><th scope="col">VM</th><th scope="col">Group</th><th scope="col" class="cost-num">A month</th>
                    <th scope="col" class="cost-num">Latest CPU</th><th scope="col">Idle since</th><th scope="col" class="cost-num">Idle, a month</th></tr></thead>
                <tbody id="cost-idle-table-body">${rows}</tbody></table></div>${pagerFoot(meta)}</section>`);
        attachFit(meta.totalElements);
    }

    /** The pager in the panel's foot, when there is more than one page. */
    function pagerFoot(meta) {
        return meta.totalElements > pageSize() ? `<div class="list-panel-foot">${Pagination.buildPager({ page: meta.number + 1,
            totalItems: meta.totalElements, pageSize: pageSize(), action: 'waste-page' })}</div>` : '';
    }

    function pageSize() {
        return state.fit || PAGE_SIZE;
    }

    /**
     * Rows a page follow the window (U20-T01): measured once a visit and when it is resized; the server
     * pages, so a new count fetches the page holding the first row shown (not when every row is already shown).
     */
    function attachFit(total) {
        const panel = document.querySelector('#waste-table .waste-table-card');
        if (!panel || typeof ListPanel === 'undefined' || !panel.querySelector('tbody tr')) return;
        const apply = count => {
            if (!count || count === pageSize()) return;
            const first = state.page * pageSize();
            const shownAll = state.page === 0 && total <= Math.min(count, pageSize());
            state.fit = count;
            if (shownAll) return;
            state.page = ListPanel.pageKeepingFirst(first, count) - 1;
            refresh();
        };
        const handle = ListPanel.attach(panel, { rowSelector: 'tbody tr', rowHeight: 56, min: 3, max: 15, onFit: apply });
        if (!state.fitted) {
            state.fitted = true;
            apply(handle.fitCount());
        }
    }

    function drawTable() {
        if (state.filters.type === IDLE) {
            drawIdle();
            return;
        }
        if (state.errors.findings) {
            $('#waste-table').html(error('the findings', state.errors.findings));
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
        }).join('') : `<tr><td colspan="6" class="text-muted text-center py-3">${Utils.escapeHtml(
            emptyText(state.summary, state.filters, state.idleTotals))}</td></tr>`;
        const meta = page.page || { number: 0, totalPages: 1, totalElements: 0 };
        $('#waste-table').html(`<section class="list-panel rs-table-card waste-table-card" aria-label="Findings">
            <div class="cost-scroll list-panel-body"><table class="cost-table waste-table" id="waste-findings">
                <thead><tr><th scope="col">Resource</th><th scope="col">Region</th><th scope="col">Environment</th><th scope="col">Idle for</th>
                    <th scope="col" class="cost-num">A month</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr></thead>
                <tbody>${rows}</tbody></table></div>${pagerFoot(meta)}</section>`);
        attachFit(meta.totalElements);
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

    /** Opens "Propose a fix" (U15-T08) for a row: the finding in one line, the fix and a reason. */
    function propose(findingId) {
        const f = ((state.findings && state.findings.content) || []).find(x => x.findingId === findingId);
        if (!f || typeof WasteFixes === 'undefined') return;
        const line = [f.resourceId, kindText(f), f.environmentName, f.eligibleSince || f.firstSeenAt ? `unused ${age(f.eligibleSince || f.firstSeenAt)}` : '',
            `${money2(f.estMonthlyCost)} a month`].filter(Boolean).join(' · ');
        WasteFixes.openPropose(f, line, refresh);
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
        model: { kindText, scheduleText, everything, emptyText }
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
    'waste-propose': el => Waste.propose(el.dataset.id)
});
