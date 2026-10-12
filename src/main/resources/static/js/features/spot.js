/**
 * Spot panel on the environment page (E31-T08): the environment's Spot policy, its VMs and node
 * groups with their Spot state (Spot, standby, excluded, fallback), interruptions in the last 30
 * days and what Spot saved. Administrators can allow Spot (with the interruption acknowledgement),
 * exclude a VM, convert a node group to a Spot twin or go back to on demand. Nothing is shown
 * while Spot is off (the summary answers 404).
 */
const SpotPanel = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    let current = null;
    let renderToken = 0;

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data || null, QUIET).done(resolve).fail(reject);
        });
    }

    function serverMessage(e, fallback) {
        return (e && e.responseJSON && e.responseJSON.message) || fallback;
    }

    function render(env) {
        const $panel = $('#env-spot-panel');
        if (!$panel.length || !env) return;
        const token = ++renderToken;
        call('GET', Config.API.costManagement.spotSummary(env.environmentId)).then(summary => {
            if (token !== renderToken || !$('#env-spot-panel').length) return;
            current = { env, summary };
            draw();
        }).catch(() => {
            if (token === renderToken) $('#env-spot-panel').empty();
        });
    }

    function reload() {
        if (current) render(current.env);
    }

    function badges(v) {
        const out = [];
        if (v.purchaseOption === 'SPOT') out.push('<span class="badge text-bg-info">Spot</span>');
        if (v.standby) out.push('<span class="badge text-bg-secondary">Standby</span>');
        if (v.excluded) out.push('<span class="badge text-bg-light border">Excluded</span>');
        if (v.fallbackActive) out.push('<span class="badge text-bg-warning spot-fallback-badge">On-demand fallback</span>');
        return out.join(' ');
    }

    function actions(v, s) {
        if (!s.canAdminister) return '';
        const out = [];
        if (v.convertible) {
            out.push(Utils.html`<button type="button" class="btn btn-sm btn-tonal btn-primary" data-action="spot-convert" data-id="${v.vmId}">Convert to Spot</button>`);
        }
        if (v.revertible) {
            out.push(Utils.html`<button type="button" class="btn btn-sm btn-ghost" data-action="spot-revert" data-id="${v.vmId}">Back to on demand</button>`);
        }
        if (v.purchaseOption === 'ON_DEMAND' && !v.standby) {
            out.push(Utils.html`<button type="button" class="btn btn-sm btn-ghost" data-action="spot-exclude" data-id="${v.vmId}"
                data-excluded="${v.excluded ? 'false' : 'true'}">${v.excluded ? 'Allow Spot' : 'Exclude'}</button>`);
        }
        return out.join(' ');
    }

    function draw() {
        const s = current.summary;
        const allowed = s.policy === 'ALLOWED';
        let policyControl = '';
        if (s.canAdminister) {
            policyControl = allowed
                ? '<button type="button" class="btn btn-sm btn-ghost" data-action="spot-policy" data-policy="NONE">Stop allowing Spot</button>'
                : s.production
                    ? '<span class="small text-muted">Production environments cannot use Spot.</span>'
                    : '<button type="button" class="btn btn-sm btn-tonal btn-primary" data-action="spot-policy" data-policy="ALLOWED">Allow Spot</button>';
        }
        const rows = (s.vms || []).map(v => Utils.html`<li class="spot-vm d-flex justify-content-between align-items-center flex-wrap gap-2" data-spot-vm="${v.vmId}">
                <div class="min-w-0"><span class="fw-semibold">${v.name}</span>
                    <span class="small text-muted">${v.kind === 'EKS_NODE_GROUP' ? 'node group' : 'VM'}</span>
                    ${Utils.raw(badges(v))}
                    ${Utils.raw(v.partnerName ? Utils.html`<div class="small text-muted">${v.standby ? 'Standby for' : 'Spot twin of'} ${v.partnerName}</div>` : '')}</div>
                <div class="d-flex gap-1 flex-wrap">${Utils.raw(actions(v, s))}</div></li>`).join('');
        $('#env-spot-panel').html(`<section class="card mb-3 spot-card" aria-labelledby="spot-title"><div class="card-body">
            <div class="d-flex justify-content-between align-items-center flex-wrap gap-2 mb-2">
                <h2 class="h6 mb-0" id="spot-title"><i class="fas fa-bolt me-1" aria-hidden="true"></i>Spot
                    <span class="badge ${allowed ? 'text-bg-success' : 'text-bg-light border'} ms-1 spot-policy">${allowed ? 'Allowed' : 'Not allowed'}</span></h2>
                ${policyControl}
            </div>
            <div class="small text-muted mb-2 spot-stats">${Number(s.spotVmCount || 0)} on Spot · ${Number(s.standbyCount || 0)} standby ·
                ${Number(s.interruptions30d || 0)} interruption${Number(s.interruptions30d) === 1 ? '' : 's'} in 30 days ·
                saved ${Utils.escapeHtml(Utils.formatCurrency(Number(s.spotSavingsMonth || 0)))}
                ${s.fallbackActive ? ' · <span class="text-warning fw-semibold">on-demand fallback running</span>' : ''}</div>
            <ul class="list-unstyled mb-0 spot-vm-list">${rows || '<li class="small text-muted">No AWS VMs or node groups.</li>'}</ul>
        </div></section>`);
    }

    function setPolicy(el) {
        if (!current) return;
        const policy = el.dataset.policy;
        const envId = current.env.environmentId;
        const send = (ack) => call('PUT', Config.API.costManagement.spotPolicy(envId), { policy, acknowledgeInterruptible: ack })
            .then(() => { Notifications.success(policy === 'ALLOWED' ? 'Spot is allowed in this environment.' : 'Spot is no longer allowed.'); reload(); })
            .catch(e => Notifications.showError(serverMessage(e, 'Could not change the Spot policy.')));
        if (policy !== 'ALLOWED') {
            send(false);
            return;
        }
        Modals.confirm('Allow Spot',
            'Spot capacity is cheaper but AWS can take it back at short notice. Allow it only for an environment that can be interrupted. Allow Spot here?',
            () => send(true), { confirmText: 'Allow Spot', confirmClass: 'btn-warning' });
    }

    function exclude(el) {
        if (!current) return;
        call('PUT', Config.API.costManagement.spotExcluded(current.env.environmentId, el.dataset.id), { excluded: el.dataset.excluded === 'true' })
            .then(reload)
            .catch(e => Notifications.showError(serverMessage(e, 'Could not change the exclusion.')));
    }

    function convert(el) {
        if (!current) return;
        const envId = current.env.environmentId;
        Modals.confirm('Convert to Spot',
            'A Spot copy of this node group is created; once it runs, this node group is scaled to 0 and kept as standby. This takes several minutes. Continue?',
            () => call('POST', Config.API.costManagement.spotConvert(envId, el.dataset.id))
                .then(() => { Notifications.success('Conversion started. You will be notified when it finishes.'); reload(); })
                .catch(e => Notifications.showError(serverMessage(e, 'Could not start the conversion.'))),
            { confirmText: 'Convert', confirmClass: 'btn-primary' });
    }

    function revert(el) {
        if (!current) return;
        const envId = current.env.environmentId;
        Modals.confirm('Back to on demand',
            'The on-demand node group is started again, then the Spot node group is stopped and deleted. Continue?',
            () => call('POST', Config.API.costManagement.spotRevert(envId, el.dataset.id))
                .then(() => { Notifications.success('Going back to on demand. You will be notified when it finishes.'); reload(); })
                .catch(e => Notifications.showError(serverMessage(e, 'Could not go back to on demand.'))),
            { confirmText: 'Go back', confirmClass: 'btn-primary' });
    }

    return { render, setPolicy, exclude, convert, revert };
})();

window.SpotPanel = SpotPanel;

Actions.registerAll({
    'spot-policy': el => SpotPanel.setPolicy(el),
    'spot-exclude': el => SpotPanel.exclude(el),
    'spot-convert': el => SpotPanel.convert(el),
    'spot-revert': el => SpotPanel.revert(el)
});
