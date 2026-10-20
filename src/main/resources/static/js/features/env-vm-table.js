/**
 * VM Self-Service Platform - Groups and VMs table (U07-T03, EnvDetail.dc.html)
 * One table for the environment page: a row per group ("Database · Start order 1 · after …" with
 * Start group / Stop group) followed by its VMs (name, IP address, size, state, CPU over 24 h,
 * Details and Start / Stop / Retry). The words and rules are pure functions (EnvVmTable.model) so
 * src/test/js/env-vm-table.test.mjs checks them on their own. No instance ids for anyone here.
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
                <td class="td-primary env-vm-name" data-label="VM">${name}` + badges + Utils.html`</td>
                <td class="env-vm-ip" data-label="IP address"><span class="mono">${vm.privateIp || '—'}</span></td>
                <td class="env-vm-size" data-label="Size"><span class="mono">${vm.instanceType || '—'}</span></td>
                <td class="env-vm-state" data-label="State"><span class="env-vm-state-text state-${s.kind}"><span class="env-vm-dot" aria-hidden="true"></span>${s.text}</span>`
            + rdsNote(vm, opts.rdsNote) + Utils.html`</td>
                <td class="env-vm-cpu" data-label="CPU 24 h"><span class="mono">${cpu(vm)}</span></td>
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
                <th scope="rowgroup" colspan="5">${name} <span class="env-group-order">${orderText(group, groupNames)}</span></th>
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
                : '<tr class="env-vm-row env-vm-none"><td colspan="6">No VMs in this group</td></tr>';
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
                            <th scope="col">CPU 24 h</th>
                            <th scope="col"><span class="visually-hidden">Actions</span></th>
                        </tr>
                    </thead>
                    ${body}
                </table>
            </div>`;
    }

    return { render, model: { state, cpu, vmAction, groupActions, orderText } };
})();

if (typeof window !== 'undefined') window.EnvVmTable = EnvVmTable;
