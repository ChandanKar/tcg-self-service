/**
 * EKS namespace and workload cost from Split Cost Allocation Data (E26-T05): namespace rows that
 * expand to their workloads, and the unused (idle) node capacity highlighted. Mounted on Cost
 * Management for admins and on an EKS environment's cost card for whole-environment viewers.
 * A late response for a container that has been reloaded or left is dropped.
 */
const EksWorkloads = (function() {
    'use strict';

    const DAYS = [7, 30, 90];

    function money(v) {
        return Utils.formatCurrency(Number(v || 0));
    }

    /** Render the days picker and load into $container; urlFor(days) gives the endpoint. */
    function mount($container, urlFor, days) {
        if (!$container || !$container.length) return;
        const selected = DAYS.includes(Number(days)) ? Number(days) : 7;
        $container.data('eksUrlFor', urlFor);
        $container.html(Utils.html`
            <div class="d-flex justify-content-between align-items-center flex-wrap gap-2 mb-2">
                <span class="small text-muted">Pod requests by namespace, from AWS Split Cost Allocation Data</span>
                <label class="small d-flex align-items-center gap-1 mb-0">Last
                    <select class="form-select form-select-sm w-auto eks-workloads-days" data-action-change="eks-workloads-days" aria-label="Days">
                        ${Utils.raw(DAYS.map(d => `<option value="${d}"${d === selected ? ' selected' : ''}>${d} days</option>`).join(''))}
                    </select>
                </label>
            </div>
            <div class="eks-workloads-body"><div class="text-muted small"><i class="fas fa-spinner fa-spin"></i> Loading workload cost…</div></div>`);
        load($container, selected);
    }

    function load($container, days) {
        const urlFor = $container.data('eksUrlFor');
        if (!urlFor) return;
        const stamp = Date.now() + Math.random();
        $container.data('eksStamp', stamp);
        const token = ContentRouter.token();
        const $body = $container.find('.eks-workloads-body');
        ApiClient.get(urlFor(days), { suppressGlobalError: true })
            .done(function(data) {
                if (!ContentRouter.isCurrent(token) || $container.data('eksStamp') !== stamp) return;
                $body.html(buildTable(data));
            })
            .fail(function(xhr) {
                if (!ContentRouter.isCurrent(token) || $container.data('eksStamp') !== stamp) return;
                const denied = xhr && xhr.status === 403;
                $body.html(denied
                    ? '<div class="small text-muted">Workload cost needs access to the whole environment.</div>'
                    : '<div class="small text-danger" role="alert">Couldn\'t load the workload cost.</div>');
            });
    }

    function buildTable(data) {
        const namespaces = (data && data.namespaces) || [];
        if (namespaces.length === 0 && !Number(data && data.unusedCost)) {
            return `<div class="eks-workloads-empty small text-muted">
                No workload cost yet. Turn on <strong>Split Cost Allocation Data</strong> for EKS in AWS Data Exports (CUR 2.0),
                create the export's Athena table, then set <code>EKS_SCAD_ENABLED=true</code> with the
                <code>EKS_SCAD_ATHENA_*</code> settings. Costs appear after the next daily run.</div>`;
        }
        const rows = namespaces.map((ns, i) => Utils.html`
            <tr class="eks-namespace-row">
                <td>
                    <button type="button" class="btn btn-link btn-sm p-0 eks-ns-toggle" data-action="eks-workloads-toggle"
                            data-ns-index="${i}" aria-expanded="false">
                        <i class="fas fa-caret-right"></i> ${ns.namespace}
                    </button>
                    <span class="small text-muted">${ns.workloads.length} workload${ns.workloads.length === 1 ? '' : 's'}</span>
                </td>
                <td class="cost-num">${money(ns.splitCost)}</td>
                <td class="cost-num">${money(ns.unusedCost)}</td>
                <td class="cost-num">${ns.share}%</td>
            </tr>
            ${Utils.raw(ns.workloads.map(w => Utils.html`
            <tr class="eks-workload-row" data-ns-parent="${i}" hidden>
                <td class="ps-4">${w.workload}</td>
                <td class="cost-num">${money(w.splitCost)}</td>
                <td class="cost-num">${money(w.unusedCost)}</td>
                <td class="cost-num">${w.share}%</td>
            </tr>`).join(''))}`).join('');
        return Utils.html`
            <div class="table-responsive">
                <table class="table table-baseline table-sm cost-table eks-workloads-table">
                    <thead><tr><th>Namespace</th><th class="cost-num">Split cost</th><th class="cost-num">Unused</th><th class="cost-num">Share</th></tr></thead>
                    <tbody>
                        ${Utils.raw(rows)}
                        <tr class="eks-unused-row table-warning" title="Node capacity paid for but not requested by any pod">
                            <td><i class="fas fa-moon"></i> Unused capacity</td>
                            <td class="cost-num"></td>
                            <td class="cost-num"><strong>${money(data.unusedCost)}</strong></td>
                            <td class="cost-num">${data.unusedShare}%</td>
                        </tr>
                    </tbody>
                </table>
            </div>
            <div class="small text-muted">${data.from} to ${data.to}: ${money(data.totalSplitCost)} requested, ${money(data.unusedCost)} unused.</div>`;
    }

    function toggle(el) {
        const $table = $(el).closest('table');
        const open = el.getAttribute('aria-expanded') !== 'true';
        el.setAttribute('aria-expanded', String(open));
        $(el).find('i').toggleClass('fa-caret-right', !open).toggleClass('fa-caret-down', open);
        $table.find(`tr[data-ns-parent="${Number(el.dataset.nsIndex)}"]`).prop('hidden', !open);
    }

    function changeDays(el) {
        const $container = $(el).closest('[data-eks-workloads]');
        load($container, Number(el.value));
    }

    return { mount, toggle, changeDays };
})();

window.EksWorkloads = EksWorkloads;

Actions.register('eks-workloads-toggle', el => EksWorkloads.toggle(el));
Actions.register('eks-workloads-days', el => EksWorkloads.changeDays(el), 'change');
