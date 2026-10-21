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

/**
 * The Spot page (U11-T08, SpotSettings.dc.html): Settings › Spot for EKS environments. The policy
 * (with the acknowledgement), each node group's size, where it runs, its standby, what it saves a
 * month and what can be done with it, how the standby takes over, and the interruptions of the last
 * 30 days with how long the standby ran.
 */
const SpotPage = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    let s = null; // { env, $root, summary, converting:Set }

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data || null, QUIET).done(resolve).fail(reject);
        });
    }

    function message(e, fallback) {
        return (e && e.responseJSON && e.responseJSON.message) || fallback;
    }

    /** Whole dollars, as monthly amounts read best ("$61", "about $96"). */
    function money(n) {
        return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 }).format(Number(n || 0));
    }

    // ---- model

    /** One node group's row: {name, size, runsOn, standby, saves, actions:[{kind,label,danger,excluded}]} */
    function rowModel(v, summary, converting) {
        const size = v.instanceType ? (v.nodeCount ? `${v.nodeCount} × ${v.instanceType}` : v.instanceType) : '';
        const onSpot = v.purchaseOption === 'SPOT';
        const runsOn = onSpot ? 'Spot' : v.excluded ? 'On-demand · kept off Spot' : 'On-demand';
        const standby = onSpot && v.partnerName ? (v.fallbackActive ? 'Running: Spot is short' : 'Ready at 0 nodes') : '—';
        const saves = v.monthlySavings === null || v.monthlySavings === undefined ? '—'
            : (v.savingsEstimated ? `about ${money(v.monthlySavings)}` : money(v.monthlySavings));
        const actions = [];
        if (summary.canAdminister) {
            if (converting && converting.has(v.vmId)) {
                actions.push({ kind: 'busy', label: 'Moving to Spot…' });
            } else if (v.revertible) {
                actions.push({ kind: 'revert', label: 'Back to on-demand', danger: true });
            } else if (v.excluded) {
                actions.push({ kind: 'exclude', label: 'Allow Spot', excluded: false });
            } else if (!onSpot && !v.standby) {
                if (v.convertible) actions.push({ kind: 'convert', label: 'Move to Spot' });
                actions.push({ kind: 'exclude', label: 'Keep off Spot', excluded: true });
            }
        }
        return { name: v.name, size, runsOn, onSpot, standby, saves, actions };
    }

    /** The node groups to list: not the standbys (they show as their Spot copy's standby). */
    function rows(summary) {
        return (summary.vms || []).filter(v => v.kind === 'EKS_NODE_GROUP' && !v.standby);
    }

    /** "app-nodes · Fri 14:06", "standby ran 22 min" / "standby still running". */
    function interruptionText(i, now) {
        const d = new Date(i.at);
        const n = now || new Date();
        const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
        const sameDay = d.toDateString() === n.toDateString();
        const day = sameDay ? 'today' : d.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' });
        return { what: `${i.vmName} · ${day} ${hm}`,
            standby: i.standbyMinutes === null || i.standbyMinutes === undefined ? 'standby still running' : `standby ran ${i.standbyMinutes} min` };
    }

    // ---- page

    function render(env, $root) {
        s = { env, $root, summary: null, converting: new Set() };
        $root.html(`<h2>Spot</h2>
            <p class="es-text">Run EKS node groups on spare AWS capacity for up to 70% less. AWS can take the nodes back at short notice. For EKS environments only.</p>
            <div id="spot-page-body"><div class="vr-empty">Loading…</div></div>`);
        if ((env.serviceType || 'EC2') !== 'EKS') {
            $('#spot-page-body').html('<p class="es-text" id="spot-not-eks">Spot is for EKS environments only.</p>');
            return;
        }
        bind();
        load();
    }

    function load() {
        const mine = s;
        call('GET', Config.API.costManagement.spotSummary(s.env.environmentId))
            .then(summary => {
                if (s !== mine) return;
                s.summary = summary;
                draw();
            })
            .catch(e => {
                if (s !== mine) return;
                $('#spot-page-body').html(e && e.status === 404
                    ? '<p class="es-text" id="spot-off">Spot is switched off for the platform. An administrator can switch it on in Settings.</p>'
                    : '<p class="es-text">The Spot details could not be loaded.</p>');
            });
    }

    function draw() {
        const sum = s.summary;
        const allowed = sum.policy === 'ALLOWED';
        const admin = sum.canAdminister;
        const policy = Utils.html`
            <fieldset class="spot-policy-choice"${Utils.raw(admin ? '' : ' disabled')}>
                <legend>This environment</legend>
                <label class="spot-radio"><input type="radio" name="spot-policy-choice" value="NONE"${Utils.raw(allowed ? '' : ' checked')}>
                    <span><b>Not allowed</b><span>Every node group stays on on-demand capacity</span></span></label>
                <label class="spot-radio"><input type="radio" name="spot-policy-choice" value="ALLOWED"${Utils.raw(allowed ? ' checked' : '')}${Utils.raw(sum.production ? ' disabled' : '')}>
                    <span><b>Allowed for the node groups you choose</b><span>${sum.production ? 'Not offered for production environments' : 'Only for environments that can be interrupted'}</span></span></label>
                <label class="spot-ack"><input type="checkbox" id="spot-ack"${Utils.raw(allowed ? ' checked' : '')}> I understand nodes on Spot can be interrupted</label>
            </fieldset>`;
        const table = rows(sum).map(v => {
            const r = rowModel(v, sum, s.converting);
            const acts = r.actions.map(a => a.kind === 'busy' ? Utils.html`<span class="spot-busy">${a.label}</span>`
                : Utils.html`<button type="button" class="vr-act${Utils.raw(a.danger ? ' vr-danger' : '')}" data-spot-act="${a.kind}"
                    data-id="${v.vmId}"${Utils.raw(a.kind === 'exclude' ? ` data-excluded="${a.excluded}"` : '')}>${a.label}</button>`).join('');
            return Utils.html`<tr data-spot-vm="${v.vmId}">
                    <td class="spot-name">${r.name}<span>${r.size}</span></td>
                    <td><span class="spot-chip${Utils.raw(r.onSpot ? ' spot-chip-on' : '')}">${r.runsOn}</span></td>
                    <td class="spot-standby">${r.standby}</td>
                    <td class="spot-saves">${r.saves}</td>
                    <td class="vr-actions">${Utils.raw(acts)}</td>
                </tr>`;
        }).join('');
        const interruptions = (sum.recentInterruptions || []).map(i => {
            const t = interruptionText(i);
            return Utils.html`<div class="spot-int"><span>${t.what}</span><span>${t.standby}</span></div>`;
        }).join('') || '<p class="es-text">No interruptions in the last 30 days.</p>';
        const grace = Number(sum.fallbackGraceMinutes || 10);
        $('#spot-page-body').html(`
            <div class="spot-grid">
                <section class="spot-main">
                    ${policy}
                    ${admin ? '<div class="spot-save"><span class="acd-error" id="spot-error" role="alert"></span><button type="button" class="btn btn-primary" id="spot-save" disabled>Save</button></div>' : ''}
                    <div class="spot-table-wrap"><table class="spot-table">
                        <thead><tr><th scope="col">Node group</th><th scope="col">Runs on</th><th scope="col">On-demand standby</th>
                            <th scope="col">Saves a month</th><th scope="col"><span class="visually-hidden">Action</span></th></tr></thead>
                        <tbody>${table || '<tr><td colspan="5" class="vr-muted">No node groups yet.</td></tr>'}</tbody>
                    </table></div>
                    <p class="es-text spot-how">Moving to Spot creates a Spot copy of the node group and keeps the original at 0 nodes as a standby. Going back starts the standby and removes the copy.</p>
                </section>
                <aside class="spot-aside">
                    <div class="spot-box">
                        <h3>If AWS takes nodes back</h3>
                        <p>When a Spot group stays short of nodes for ${grace} minutes, its on-demand standby starts and this environment&#39;s admins are told. Once Spot is healthy again, the standby goes back to 0.</p>
                        <p class="spot-muted">${sum.conversionEnabled ? 'Moving node groups to Spot is switched on for the platform by an administrator.'
                            : 'Moving node groups to Spot is switched off for the platform; an administrator can switch it on.'}</p>
                    </div>
                    <div class="spot-box" id="spot-interruptions">
                        <h3>Interruptions, 30 days</h3>
                        ${interruptions}
                        <div class="spot-saved">Saved <b>${Utils.escapeHtml(money(sum.spotSavingsMonth))}</b> on Spot this month.</div>
                    </div>
                </aside>
            </div>`);
        updateSave();
    }

    function chosen() {
        return $('input[name="spot-policy-choice"]:checked').val() || 'NONE';
    }

    function updateSave() {
        if (!s || !s.summary) return;
        const policy = chosen();
        $('.spot-ack').prop('hidden', policy !== 'ALLOWED');
        const changed = policy !== (s.summary.policy === 'ALLOWED' ? 'ALLOWED' : 'NONE');
        const ackOk = policy !== 'ALLOWED' || $('#spot-ack').is(':checked');
        $('#spot-save').prop('disabled', !changed || !ackOk);
    }

    function save() {
        const policy = chosen();
        $('#spot-error').text('');
        $('#spot-save').prop('disabled', true).text('Saving…');
        call('PUT', Config.API.costManagement.spotPolicy(s.env.environmentId),
            { policy, acknowledgeInterruptible: policy === 'ALLOWED' && $('#spot-ack').is(':checked') })
            .then(() => {
                Notifications.success(policy === 'ALLOWED' ? 'Spot is allowed in this environment.' : 'Spot is no longer allowed.');
                load();
            })
            .catch(e => {
                $('#spot-error').text(message(e, 'The Spot setting was not saved.'));
                $('#spot-save').text('Save');
                updateSave();
            });
    }

    function act(el) {
        const envId = s.env.environmentId;
        const id = el.dataset.id;
        const kind = el.dataset.spotAct;
        if (kind === 'exclude') {
            call('PUT', Config.API.costManagement.spotExcluded(envId, id), { excluded: el.dataset.excluded === 'true' })
                .then(load)
                .catch(e => Notifications.showError(message(e, 'Could not change it.')));
            return;
        }
        if (kind === 'convert') {
            Modals.confirm('Move to Spot',
                'A Spot copy of this node group is created; once it runs, this node group is scaled to 0 and kept as its standby. This takes several minutes.',
                () => call('POST', Config.API.costManagement.spotConvert(envId, id))
                    .then(() => {
                        s.converting.add(id);
                        Notifications.success('Moving to Spot. You will be notified when it finishes.');
                        draw();
                    })
                    .catch(e => Notifications.showError(message(e, 'Could not start moving it to Spot.'))),
                { confirmText: 'Move to Spot', confirmClass: 'btn-primary' });
            return;
        }
        if (kind === 'revert') {
            Modals.confirm('Back to on-demand',
                'The on-demand standby is started again, then the Spot copy is stopped and removed.',
                () => call('POST', Config.API.costManagement.spotRevert(envId, id))
                    .then(() => { Notifications.success('Going back to on-demand. You will be notified when it finishes.'); load(); })
                    .catch(e => Notifications.showError(message(e, 'Could not go back to on-demand.'))),
                { confirmText: 'Back to on-demand', confirmClass: 'btn-danger' });
        }
    }

    function bind() {
        const NS = '.spotPage';
        s.$root.off(NS)
            .on('change' + NS, 'input[name="spot-policy-choice"], #spot-ack', () => updateSave())
            .on('click' + NS, '#spot-save', () => save())
            .on('click' + NS, '[data-spot-act]', function() { act(this); });
    }

    return { render, model: { rowModel, rows, interruptionText } };
})();

if (typeof window !== 'undefined') window.SpotPage = SpotPage;
