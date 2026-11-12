/**
 * VM Self-Service Platform - Groups and VMs table (U07-T03, EnvDetail.dc.html)
 * One table for the environment page: a row per group ("Database · Start order 1 · after …" with
 * Start group / Stop group) followed by its VMs (name, IP address, size, state, CPU over 24 h,
 * Details and Start / Stop / Retry). Large environments (U07-T04, EnvDetailLarge.dc.html) get
 * renderLarge instead: one strip of all VMs with the counts, a group list, and the chosen group's
 * VMs with All / Running / Stopped / Problems and 10 per page. The words and rules are pure
 * functions (EnvVmTable.model) so src/test/js/env-vm-table.test.mjs checks them on their own. No
 * instance ids for anyone here.
 */
const EnvVmTable = (function() {
    'use strict';

    const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    const pad = n => String(n).padStart(2, '0');

    function toDate(value) {
        if (!value) return null;
        const d = value instanceof Date ? value : new Date(value);
        return Number.isNaN(d.getTime()) ? null : d;
    }

    const TRANSITIONAL = ['STARTING', 'STOPPING'];
    const isTransitional = status => TRANSITIONAL.includes(String(status || '').toUpperCase());

    /**
     * The state cell: {text, kind} with kind running | waiting | failed | stopped | unknown.
     * "Running since 08:02" today, "Running since 16 Oct" before; "Failed to start" for ERROR.
     */
    function state(vm, now = new Date()) {
        switch (vm.status) {
            case 'RUNNING': {
                const since = toDate(vm.stateSince);
                if (!since) return { text: 'Running', kind: 'running' };
                const today = since.toDateString() === now.toDateString();
                return { text: `Running since ${today ? `${pad(since.getHours())}:${pad(since.getMinutes())}`
                    : `${since.getDate()} ${MONTHS[since.getMonth()]}`}`, kind: 'running' };
            }
            case 'STARTING': return { text: 'Starting', kind: 'waiting' };
            case 'STOPPING': return { text: 'Stopping', kind: 'waiting' };
            case 'ERROR': return { text: 'Failed to start', kind: 'failed' };
            case 'NOT_FOUND': return { text: 'Not found in the cloud', kind: 'failed' };
            case 'TERMINATED': return { text: 'Terminated', kind: 'failed' };
            case 'STOPPED': return { text: 'Stopped', kind: 'stopped' };
            default: return { text: 'Unknown', kind: 'unknown' };
        }
    }

    /** "23%" from the 24 h average; "—" without samples. */
    function cpu(vm) {
        const value = vm.cpuAvg24h;
        if (value === null || value === undefined || value === '') return '—';
        const n = Number(value);
        return Number.isFinite(n) ? `${Math.round(n)}%` : '—';
    }

    /** The VM's own action: stop | start | retry | busy, or null for people who cannot operate. */
    function vmAction(vm, canOperate) {
        if (!canOperate) return null;
        if (isTransitional(vm.status)) return 'busy';
        if (vm.status === 'RUNNING') return 'stop';
        if (vm.status === 'ERROR') return 'retry';
        return 'start';
    }

    /** The group's actions: ['stop'], ['start'], ['start', 'stop'] (partly running), ['busy'] or []. */
    function groupActions(vms, canOperate) {
        if (!canOperate || !vms.length) return [];
        if (vms.some(v => isTransitional(v.status))) return ['busy'];
        const running = vms.filter(v => v.status === 'RUNNING').length;
        if (running === 0) return ['start'];
        if (running === vms.length) return ['stop'];
        return ['start', 'stop'];
    }

    function dependsOn(group) {
        if (Array.isArray(group.dependsOnGroupIds)) return group.dependsOnGroupIds;
        if (typeof group.dependsOnGroupIds === 'string' && group.dependsOnGroupIds) {
            try {
                const parsed = JSON.parse(group.dependsOnGroupIds);
                return Array.isArray(parsed) ? parsed : [];
            } catch (e) { return []; }
        }
        return [];
    }

    /** "Start order 2 · after Database"; a dependency no longer present reads "removed group". */
    function orderText(group, groupNames) {
        const after = dependsOn(group);
        return `Start order ${group.sequencePosition ?? '-'}`
            + (after.length ? ` · after ${after.map(id => groupNames[id] || 'removed group').join(', ')}` : '');
    }

    // ---------------------------------------------------------------- markup

    function rdsNote(vm, note) {
        return typeof note === 'function' ? note(vm) : '';
    }

    /**
     * A name that may wrap after "_" and "-" (U21-T15): names like
     * mcube_dev_poc_master_100.112.1.135 have no spaces, so without break points they pushed the
     * table past its card and hid the Start buttons. Not after ".", so an IP inside a name stays in
     * one piece. Escaped first; only <wbr> is added.
     */
    function breakable(name) {
        return Utils.escapeHtml(String(name || '')).replace(/([_\-])/g, '$1<wbr>');
    }

    function vmRow(vm, canOperate, opts) {
        const s = state(vm, opts.now);
        const name = vm.displayName || vm.name;
        const badges = (vm.provider === 'AWS_RDS'
            ? Utils.html` <span class="vm-badge vm-rds-badge">${vm.rdsKind === 'CLUSTER' ? 'Aurora cluster' : 'DB instance'}</span>` : '')
            + (vm.purchaseOption === 'SPOT'
                ? ' <span class="vm-badge vm-spot-badge" title="Runs on Spot capacity: AWS can interrupt it">Spot</span>' : '');
        const action = vmAction(vm, canOperate);
        const button = action === null ? ''
            : action === 'busy'
                ? Utils.html`<button type="button" class="btn btn-sm btn-secondary" disabled aria-label="${name}: operation in progress">Working…</button>`
                : action === 'stop'
                    ? Utils.html`<button type="button" class="btn btn-sm btn-danger env-vm-op" data-vm-id="${vm.vmId}" data-action="stop-vm" aria-label="Stop ${name}">Stop</button>`
                    : Utils.html`<button type="button" class="btn btn-sm btn-outline-success env-vm-op" data-vm-id="${vm.vmId}" data-action="start-vm" aria-label="${action === 'retry' ? 'Retry' : 'Start'} ${name}">${action === 'retry' ? 'Retry' : 'Start'}</button>`;
        return Utils.html`
            <tr class="env-vm-row" data-vm-id="${vm.vmId}">
                <td class="td-primary env-vm-name" data-label="VM"><div class="env-vm-name-line"><span class="env-vm-name-text" title="${name}">` + breakable(name) + '</span>' + badges + Utils.html`</div></td>
                <td class="env-vm-ip" data-label="IP address"><span class="mono">${vm.privateIp || '—'}</span></td>
                <td class="env-vm-size" data-label="Size"><span class="mono">${vm.instanceType || '—'}</span></td>
                <td class="env-vm-state" data-label="State"><span class="env-vm-state-text state-${s.kind}"><span class="env-vm-dot" aria-hidden="true"></span>${s.text}</span>`
            + rdsNote(vm, opts.rdsNote) + Utils.html`</td>
` + (opts.noCpu ? '' : Utils.html`
                <td class="env-vm-cpu" data-label="CPU 24 h"><span class="mono">${cpu(vm)}</span></td>`) + Utils.html`
                <td class="td-actions env-vm-actions">
                    <button type="button" class="btn btn-sm btn-link env-vm-details" data-vm-id="${vm.vmId}" data-action="vm-details" aria-label="Details of ${name}">Details</button>`
            + button + '</td></tr>';
    }

    function groupRow(group, vms, canOperate, groupNames) {
        const name = group.displayName || group.name;
        const buttons = groupActions(vms, canOperate).map(a => a === 'busy'
            ? Utils.html`<button type="button" class="btn btn-sm btn-secondary" disabled aria-label="${name}: operation in progress">In progress</button>`
            : a === 'stop'
                ? Utils.html`<button type="button" class="btn btn-sm btn-danger" data-group-id="${group.groupId}" data-action="group-action" data-action-type="stop" aria-label="Stop group ${name}">Stop group</button>`
                : Utils.html`<button type="button" class="btn btn-sm btn-outline-success" data-group-id="${group.groupId}" data-action="group-action" data-action-type="start" aria-label="Start group ${name}">Start group</button>`
        ).join('');
        return Utils.html`
            <tr class="env-group-row" data-group-id="${group.groupId}">
                <th scope="rowgroup" colspan="4">${name} <span class="env-group-order">${orderText(group, groupNames)}</span></th>
                <td class="env-vm-cpu env-col-spacer" aria-hidden="true"></td>
                <td class="td-actions env-group-actions">` + buttons + '</td></tr>';
    }

    /**
     * The table for an environment's groups ([{group, vms, viewerCanOperate}]). canOperate is the
     * viewer's right on the environment; a group's own viewerCanOperate narrows it (U02-T02).
     * opts: {now, rdsNote(vm)}.
     */
    function render(env, opts = {}) {
        const groups = (env.groups || []).slice().sort((a, b) =>
            ((a.group || a).sequencePosition ?? 0) - ((b.group || b).sequencePosition ?? 0));
        if (!groups.length) {
            return '<div class="panel env-vms-empty">No groups configured for this environment.</div>';
        }
        const groupNames = {};
        groups.forEach(gd => { const g = gd.group || gd; if (g && g.groupId) groupNames[g.groupId] = g.displayName || g.name; });
        const o = { now: opts.now || new Date(), rdsNote: opts.rdsNote };
        const body = groups.map(gd => {
            const group = gd.group || gd;
            const vms = gd.vms || [];
            const canOperate = !!opts.canOperate && (typeof gd.viewerCanOperate === 'boolean' ? gd.viewerCanOperate : true);
            const rows = vms.length ? vms.map(vm => vmRow(vm, canOperate, o)).join('')
                : '<tr class="env-vm-row env-vm-none"><td colspan="5">No VMs in this group</td><td class="env-vm-cpu env-col-spacer" aria-hidden="true"></td></tr>';
            return `<tbody class="env-group">${groupRow(group, vms, canOperate, groupNames)}${rows}</tbody>`;
        }).join('');
        return `
            <div class="panel env-vms-panel table-cards-wrap">
                <table class="table mb-0 env-vm-table table-cards">
                    <thead>
                        <tr>
                            <th scope="col">VM</th>
                            <th scope="col">IP address</th>
                            <th scope="col">Size</th>
                            <th scope="col">State</th>
                            <th scope="col" class="env-vm-cpu">CPU 24 h</th>
                            <th scope="col"><span class="visually-hidden">Actions</span></th>
                        </tr>
                    </thead>
                    ${body}
                </table>
            </div>`;
    }

    // ---------------------------------------------------------------- large environments (U07-T04)

    const PROBLEM = ['ERROR', 'NOT_FOUND', 'TERMINATED'];
    const isProblem = vm => PROBLEM.includes(vm.status) || vm.stateDriftDetected === true;
    const PAGE_SIZE = 10;
    const FILTERS = [
        { key: 'all', label: 'All' },
        { key: 'running', label: 'Running' },
        { key: 'stopped', label: 'Stopped' },
        { key: 'problems', label: 'Problems' },
    ];

    /** {running, stopped, failed, total}; failed counts VMs that failed to start (ERROR). */
    function countByState(vms) {
        const list = Array.isArray(vms) ? vms : [];
        return {
            running: list.filter(v => v.status === 'RUNNING').length,
            stopped: list.filter(v => v.status === 'STOPPED').length,
            failed: list.filter(v => v.status === 'ERROR').length,
            total: list.length,
        };
    }

    /** The VMs a filter keeps: all, running, stopped, or problems (failed, gone, drifted). */
    function filterVms(vms, filter) {
        const list = Array.isArray(vms) ? vms : [];
        switch (filter) {
            case 'running': return list.filter(v => v.status === 'RUNNING');
            case 'stopped': return list.filter(v => v.status === 'STOPPED');
            case 'problems': return list.filter(isProblem);
            default: return list.slice();
        }
    }

    /** One page (0-based, clamped): {items, page, pages, from, to, total}; from/to are 1-based. */
    function pageOf(list, page, size = PAGE_SIZE) {
        const total = list.length;
        const pages = Math.max(1, Math.ceil(total / size));
        const p = Math.min(Math.max(0, Number(page) || 0), pages - 1);
        const items = list.slice(p * size, p * size + size);
        return { items, page: p, pages, from: total ? p * size + 1 : 0, to: p * size + items.length, total };
    }

    /** "1 failed · 2 stopped", "4 stopped" or "All running" for the group list. */
    function groupNote(vms) {
        const c = countByState(vms);
        if (c.failed) return `${c.failed} failed` + (c.stopped ? ` · ${c.stopped} stopped` : '');
        if (c.stopped) return `${c.stopped} stopped`;
        return c.total ? (c.running === c.total ? 'All running' : `${c.total - c.running} not running`) : 'No VMs';
    }

    const ORDINAL = n => n + (n % 100 >= 11 && n % 100 <= 13 ? 'th' : ({ 1: 'st', 2: 'nd', 3: 'rd' }[n % 10] || 'th'));

    function sortedGroups(env) {
        return (env.groups || []).slice().sort((a, b) =>
            ((a.group || a).sequencePosition ?? 0) - ((b.group || b).sequencePosition ?? 0));
    }

    /** The group chosen first: the first with a problem, else the first in start order. */
    function defaultGroupId(env) {
        const groups = sortedGroups(env);
        const withProblem = groups.find(gd => (gd.vms || []).some(isProblem));
        const chosen = withProblem || groups[0];
        return chosen ? (chosen.group || chosen).groupId : null;
    }

    /**
     * The large layout. state = {groupId, filter, page}; an unknown group falls back to
     * defaultGroupId. opts as for render, plus canOperate.
     */
    function renderLarge(env, state = {}, opts = {}) {
        const groups = sortedGroups(env);
        if (!groups.length) {
            return '<div class="panel env-vms-empty">No groups configured for this environment.</div>';
        }
        const all = groups.flatMap(gd => gd.vms || []);
        const c = countByState(all);
        const names = groups.map(gd => (gd.group || gd).displayName || (gd.group || gd).name);
        const strip = PowerStrip.render(PowerStrip.fromVmGroups(groups), { large: true, showCount: false });
        const summary = `
            <section class="panel env-large-summary" aria-label="All ${c.total} VMs in start order">
                ${strip}
                <div class="env-large-counts">
                    <span><b class="mono">${c.running}</b> running</span>
                    <span><b class="mono">${c.stopped}</b> stopped</span>
                    ${c.failed ? `<span class="state-failed"><b class="mono">${c.failed}</b> failed to start</span>` : ''}
                    <span>${Utils.escapeHtml('Start order: ' + names.join(' → '))}</span>
                </div>
            </section>`;

        const groupId = groups.some(gd => (gd.group || gd).groupId === state.groupId) ? state.groupId : defaultGroupId(env);
        const filter = FILTERS.some(f => f.key === state.filter) ? state.filter : 'all';
        const list = groups.map((gd, i) => {
            const g = gd.group || gd;
            const vms = gd.vms || [];
            const counts = countByState(vms);
            const current = g.groupId === groupId;
            return Utils.html`<button type="button" class="env-group-pick${current ? ' is-current' : ''}" data-action="env-large-group"
                    data-group-id="${g.groupId}" aria-current="${current ? 'true' : 'false'}">
                    <span class="env-group-pick-row"><b>${ORDINAL(i + 1)} · ${names[i]}</b><span class="mono">${counts.running}/${counts.total}</span></span>
                    <span class="env-group-pick-note${counts.failed ? ' state-failed' : ''}">${groupNote(vms)}</span>
                </button>`;
        }).join('');

        const selected = groups.find(gd => (gd.group || gd).groupId === groupId);
        const group = selected.group || selected;
        const vms = selected.vms || [];
        const canOperate = !!opts.canOperate && (typeof selected.viewerCanOperate === 'boolean' ? selected.viewerCanOperate : true);
        const shown = filterVms(vms, filter);
        const pg = pageOf(shown, state.page);
        const o = { now: opts.now || new Date(), rdsNote: opts.rdsNote, noCpu: true };
        const rows = pg.items.length ? pg.items.map(vm => vmRow(vm, canOperate, o)).join('')
            : '<tr class="env-vm-row env-vm-none"><td colspan="5">Nothing matches this filter</td></tr>';
        const groupButtons = groupActions(vms, canOperate).map(a => a === 'busy'
            ? '<button type="button" class="btn btn-sm btn-secondary" disabled>In progress</button>'
            : a === 'stop'
                ? Utils.html`<button type="button" class="btn btn-sm btn-danger" data-group-id="${group.groupId}" data-action="group-action" data-action-type="stop" aria-label="Stop group ${group.displayName || group.name}">Stop group</button>`
                : Utils.html`<button type="button" class="btn btn-sm btn-outline-success" data-group-id="${group.groupId}" data-action="group-action" data-action-type="start" aria-label="Start group ${group.displayName || group.name}">Start group</button>`
        ).join('');
        const pager = pg.total
            ? Pagination.buildPager({ totalItems: pg.total, page: pg.page + 1, pageSize: PAGE_SIZE, action: 'env-large-page', key: 'env-vms' })
            : '<div class="pager"><span class="pager-range">Nothing matches this filter</span></div>';

        return summary + `
            <div class="env-large-body">
                <nav class="panel env-group-list" aria-label="Groups">
                    <div class="env-group-list-head">${groups.length} group${groups.length === 1 ? '' : 's'}</div>
                    ${list}
                </nav>
                <section class="panel env-group-panel" aria-labelledby="env-group-panel-title">
                    <div class="env-group-panel-head">
                        <h2 id="env-group-panel-title">${Utils.escapeHtml(group.displayName || group.name)} · <span>${vms.length} VM${vms.length === 1 ? '' : 's'}</span></h2>
                        ${FilterGroup.markup({ label: 'Show', options: FILTERS, active: filter, action: 'env-large-filter' })}
                        <span class="env-group-actions">${groupButtons}</span>
                    </div>
                    <div class="table-cards-wrap">
                        <table class="table mb-0 env-vm-table env-vm-table-large table-cards">
                            <thead>
                                <tr>
                                    <th scope="col">VM</th>
                                    <th scope="col">IP address</th>
                                    <th scope="col">Size</th>
                                    <th scope="col">State</th>
                                    <th scope="col"><span class="visually-hidden">Actions</span></th>
                                </tr>
                            </thead>
                            <tbody>${rows}</tbody>
                        </table>
                    </div>
                    <div class="panel-foot">${pager}</div>
                </section>
            </div>`;
    }

    // ---------------------------------------------------------------- EKS (U07-T08, EksEnv.dc.html)

    /** "On-demand" or "Spot, falls back to on-demand". */
    function capacityText(capacityType) {
        return capacityType === 'SPOT' ? 'Spot, falls back to on-demand' : 'On-demand';
    }

    /** The node groups in start order, each with its node-group VM (one per group in this model). */
    function eksRows(env) {
        return sortedGroups(env).flatMap(gd => (gd.vms || []).map(vm => ({ gd, vm })));
    }

    /** {running, total} nodes across node groups: total is what they run when started. */
    function nodeTotals(env) {
        return eksRows(env).reduce((t, { vm }) => {
            const ng = vm.eksNodeGroup || {};
            return { running: t.running + (ng.currentNodes || 0), total: t.total + (ng.desiredWhenRunning || 0) };
        }, { running: 0, total: 0 });
    }

    /**
     * Node groups instead of VMs: name, a light per node, node size, "3 / 3" running of when
     * started, capacity, and Scale to 0 / Start for the group; viewers read "Running" or
     * "Scaled to 0" instead of a button.
     */
    function renderEks(env, opts = {}) {
        const rows = eksRows(env);
        if (!rows.length) {
            return '<div class="panel env-vms-empty">No node groups registered for this cluster yet.</div>';
        }
        const body = rows.map(({ gd, vm }) => {
            const group = gd.group || gd;
            const ng = vm.eksNodeGroup || {};
            const name = vm.displayName || vm.name;
            const total = ng.desiredWhenRunning || 0;
            const running = ng.currentNodes || 0;
            const lights = Array.from({ length: Math.max(total, running) }, (_, i) =>
                isTransitional(vm.status) ? 'B' : (i < running ? 'R' : 'S'));
            const strip = lights.length ? PowerStrip.render([{ name, lights }], { showCount: false }) : '<span class="text-muted">—</span>';
            const canOperate = !!opts.canOperate && (typeof gd.viewerCanOperate === 'boolean' ? gd.viewerCanOperate : true);
            let action;
            if (isTransitional(vm.status)) {
                action = Utils.html`<span class="env-eks-state">${vm.status === 'STARTING' ? 'Scaling up…' : 'Scaling to 0…'}</span>`;
            } else if (!canOperate) {
                action = Utils.html`<span class="env-eks-state">${vm.status === 'RUNNING' ? 'Running' : 'Scaled to 0'}</span>`;
            } else if (vm.status === 'RUNNING') {
                action = Utils.html`<button type="button" class="btn btn-sm btn-danger" data-group-id="${group.groupId}" data-action="group-action" data-action-type="stop" aria-label="Scale ${name} to 0">Scale to 0</button>`;
            } else {
                action = Utils.html`<button type="button" class="btn btn-sm btn-outline-success" data-group-id="${group.groupId}" data-action="group-action" data-action-type="start" aria-label="Start ${name}">Start</button>`;
            }
            return Utils.html`
                <tr class="env-vm-row env-eks-row" data-vm-id="${vm.vmId}">
                    <td class="td-primary env-vm-name" data-label="Node group"><div class="env-vm-name-line"><span class="env-vm-name-text" title="${name}">` + breakable(name) + Utils.html`</span></div></td>
                    <td class="env-eks-nodes" data-label="Nodes">` + strip + Utils.html`</td>
                    <td class="env-vm-size" data-label="Size"><span class="mono">${ng.instanceType || '—'}</span></td>
                    <td class="env-eks-count" data-label="Running / when started"><span class="mono">${running} / ${total}</span></td>
                    <td class="env-eks-capacity" data-label="Capacity">${capacityText(ng.capacityType)}</td>
                    <td class="td-actions env-vm-actions">` + action + '</td></tr>';
        }).join('');
        return `
            <div class="panel env-vms-panel table-cards-wrap">
                <h2 class="env-eks-title">Node groups <span>· start top to bottom</span></h2>
                <table class="table mb-0 env-vm-table env-eks-table table-cards">
                    <thead>
                        <tr>
                            <th scope="col">Node group</th>
                            <th scope="col">Nodes</th>
                            <th scope="col">Size</th>
                            <th scope="col">Running / when started</th>
                            <th scope="col">Capacity</th>
                            <th scope="col"><span class="visually-hidden">Action</span></th>
                        </tr>
                    </thead>
                    <tbody>${body}</tbody>
                </table>
            </div>`;
    }

    return {
        render, renderLarge, renderEks, nodeTotals, defaultGroupId, capacityText,
        model: { state, cpu, vmAction, groupActions, orderText, countByState, filterVms, pageOf, groupNote, defaultGroupId,
            capacityText, nodeTotals },
    };
})();

if (typeof window !== 'undefined') window.EnvVmTable = EnvVmTable;
