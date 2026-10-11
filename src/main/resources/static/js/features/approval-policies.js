/**
 * Approval policies page (E25-T06), #/approval-policies: admins see every policy, environment
 * admins their environments' ones. Editor for the conditions (instance-type globs, vCPU, hours,
 * estimated cost), approvers and ANY/ALL, and a dry run that shows what a start would need.
 */
const ApprovalPolicies = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    let pageToken = null;
    let state = null;

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data || null, QUIET).done(resolve).fail(reject);
        });
    }

    function serverMessage(e, fallback) {
        return (e && e.responseJSON && e.responseJSON.message) || fallback;
    }

    function load() {
        pageToken = ContentRouter.token();
        if (!Auth.isEnvAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3">Access denied. Admin or Env Admin only.</div>');
            return;
        }
        state = { envFilter: Auth.isAdmin() ? '' : null, environments: [], teams: [], users: [], policies: null, error: false };
        $('#content-area').html(`
            <div class="content-view approval-policies-page" id="approval-policies-view">
                <div class="content-header d-flex justify-content-between align-items-center flex-wrap gap-2">
                    <div>
                        <h1><i class="fas fa-shield-alt me-2" aria-hidden="true"></i>Approval policies</h1>
                        <p class="text-muted mb-0">Starts and extensions that match a policy wait for its approvers</p>
                    </div>
                    <button type="button" class="btn btn-primary" data-action="policy-new"><i class="fas fa-plus" aria-hidden="true"></i> New policy</button>
                </div>
                <div class="d-flex gap-2 align-items-center mb-3 flex-wrap">
                    <label class="small text-muted" for="policy-env-filter">Environment</label>
                    <select id="policy-env-filter" class="form-select form-select-sm w-auto"></select>
                </div>
                <div id="policy-list"></div>
                <section class="card mt-3" aria-labelledby="policy-dry-title"><div class="card-body">
                    <h2 class="h6" id="policy-dry-title">Try it: would a start need approval?</h2>
                    <div class="d-flex gap-2 flex-wrap align-items-end">
                        <div><label class="form-label small mb-1" for="dry-env">Environment</label>
                            <select id="dry-env" class="form-select form-select-sm"></select></div>
                        <div><label class="form-label small mb-1" for="dry-hours">Hours</label>
                            <input id="dry-hours" type="number" min="1" max="720" value="8" class="form-control form-control-sm" style="width:6rem"></div>
                        <button type="button" class="btn btn-sm btn-tonal btn-primary" data-action="policy-dry-run">Check</button>
                    </div>
                    <div id="dry-result" class="mt-2 small" aria-live="polite"></div>
                </div></section>
            </div>`);
        const token = pageToken;
        Promise.allSettled([call('GET', Config.API.environments.list), call('GET', Config.API.teams.list(false)),
            Auth.isAdmin() ? call('GET', Config.API.users.list) : Promise.resolve([])]).then(([envs, teams, users]) => {
            if (!ContentRouter.isCurrent(token)) return;
            state.environments = envs.status === 'fulfilled' ? (envs.value || []) : [];
            state.teams = teams.status === 'fulfilled' ? (teams.value || []) : [];
            state.users = users.status === 'fulfilled' ? (users.value || []) : [];
            const options = state.environments.map(e => Utils.html`<option value="${e.environmentId}">${e.displayName || e.name}</option>`).join('');
            if (state.envFilter === null) state.envFilter = state.environments.length ? state.environments[0].environmentId : '';
            $('#policy-env-filter').html((Auth.isAdmin() ? '<option value="">All policies</option>' : '') + options).val(state.envFilter)
                .on('change', function() { state.envFilter = this.value; refresh(); });
            $('#dry-env').html(options);
            refresh();
        });
    }

    function envName(id) {
        const e = state.environments.find(x => x.environmentId === id);
        return e ? (e.displayName || e.name) : id;
    }

    function refresh() {
        const token = pageToken;
        call('GET', Config.API.approvalPolicies.list(state.envFilter)).then(list => {
            if (!ContentRouter.isCurrent(token)) return;
            state.policies = list || [];
            draw();
        }).catch(e => {
            if (!ContentRouter.isCurrent(token)) return;
            $('#policy-list').html(Utils.html`<div class="cost-panel-error" role="alert"><span>${serverMessage(e, "Couldn't load policies.")}</span></div>`);
        });
    }

    function conditions(p) {
        const parts = [];
        if (p.instanceTypePatterns.length) parts.push('types ' + p.instanceTypePatterns.join(', '));
        if (p.minVcpu) parts.push(`${p.minVcpu}+ vCPU`);
        if (p.maxRuntimeHours) parts.push(`over ${p.maxRuntimeHours} h`);
        if (p.maxEstimatedCost !== null && p.maxEstimatedCost !== undefined) parts.push(`over ${Utils.formatCurrency(Number(p.maxEstimatedCost))}`);
        return parts.join(' · ');
    }

    function draw() {
        const rows = state.policies.length ? state.policies.map(p => Utils.html`<tr>
            <td data-label="Policy" class="fw-semibold">${p.name}${Utils.raw(p.active ? '' : ' <span class="badge text-bg-secondary">Off</span>')}</td>
            <td data-label="Applies to">${p.environmentName || (p.teamName ? 'Team ' + p.teamName : 'All environments')}</td>
            <td data-label="Needs approval when">${conditions(p)}</td>
            <td data-label="Approvers">${p.approvers.map(a => a.label).join(', ') || 'Admins'} (${p.approverMode === 'ALL' ? 'all' : 'any one'})</td>
            <td data-label="" class="text-end text-nowrap">
                <button type="button" class="btn btn-sm btn-ghost" data-action="policy-edit" data-id="${p.policyId}" aria-label="Edit ${p.name}"><i class="fas fa-pen" aria-hidden="true"></i></button>
                <button type="button" class="btn btn-sm btn-ghost" data-action="policy-delete" data-id="${p.policyId}" aria-label="Delete ${p.name}"><i class="fas fa-trash" aria-hidden="true"></i></button>
            </td></tr>`).join('')
            : '<tr><td colspan="5" class="text-muted text-center py-3">No policies: nothing needs approval.</td></tr>';
        $('#policy-list').html(`<section class="card"><div class="card-body"><div class="table-responsive">
            <table class="table table-sm table-cards mb-0" id="policy-table"><thead><tr><th>Policy</th><th>Applies to</th>
            <th>Needs approval when</th><th>Approvers</th><th><span class="visually-hidden">Actions</span></th></tr></thead>
            <tbody>${rows}</tbody></table></div></div></section>`);
    }

    function approverOptions(selected) {
        const opts = ['<option value="ENV_ADMINS:">Environment admins</option>'];
        state.teams.forEach(t => opts.push(Utils.html`<option value="TEAM:${t.teamId}">Team: ${t.displayName}</option>`));
        state.users.filter(u => u.isActive !== false).forEach(u =>
            opts.push(Utils.html`<option value="USER:${u.userId}">${u.displayName || u.email}</option>`));
        return opts.map(o => {
            const value = o.match(/value="([^"]*)"/)[1];
            return selected.includes(value) ? o.replace('<option ', '<option selected ') : o;
        }).join('');
    }

    function openForm(policyId) {
        const p = policyId ? state.policies.find(x => x.policyId === policyId) : null;
        const scopeOptions = (Auth.isAdmin() ? '<option value="">All environments</option>' : '') + state.environments.map(e =>
            Utils.html`<option value="ENV:${e.environmentId}">${e.displayName || e.name}</option>`).join('')
            + (Auth.isAdmin() ? state.teams.map(t => Utils.html`<option value="TEAM:${t.teamId}">Team: ${t.displayName}</option>`).join('') : '');
        const currentScope = p ? (p.environmentId ? 'ENV:' + p.environmentId : p.teamId ? 'TEAM:' + p.teamId : '')
            : (state.envFilter ? 'ENV:' + state.envFilter : '');
        const selectedApprovers = p ? p.approvers.map(a => `${a.type}:${a.approverId || ''}`) : ['ENV_ADMINS:'];
        Modals.show({
            id: 'policyModal',
            title: p ? `Edit policy: ${p.name}` : 'New approval policy',
            size: 'lg',
            body: Utils.html`
                <div class="row g-2">
                    <div class="col-sm-7"><label class="form-label" for="pol-name">Name</label>
                        <input class="form-control" id="pol-name" maxlength="100" value="${p ? p.name : ''}" required></div>
                    <div class="col-sm-5"><label class="form-label" for="pol-scope">Applies to</label>
                        <select class="form-select" id="pol-scope" ${p ? 'disabled' : ''}>${Utils.raw(scopeOptions)}</select></div>
                </div>
                <fieldset class="mt-3"><legend class="form-label fs-6">Needs approval when any of these match</legend>
                    <div class="row g-2">
                        <div class="col-sm-6"><label class="form-label small" for="pol-patterns">Instance types (comma-separated, * and ? allowed)</label>
                            <input class="form-control" id="pol-patterns" placeholder="*.8xlarge, p*" value="${p ? p.instanceTypePatterns.join(', ') : ''}"></div>
                        <div class="col-6 col-sm-2"><label class="form-label small" for="pol-vcpu">vCPU or more</label>
                            <input class="form-control" id="pol-vcpu" type="number" min="1" value="${p && p.minVcpu ? p.minVcpu : ''}"></div>
                        <div class="col-6 col-sm-2"><label class="form-label small" for="pol-hours">Over hours</label>
                            <input class="form-control" id="pol-hours" type="number" min="1" value="${p && p.maxRuntimeHours ? p.maxRuntimeHours : ''}"></div>
                        <div class="col-6 col-sm-2"><label class="form-label small" for="pol-cost">Over $</label>
                            <input class="form-control" id="pol-cost" type="number" min="0" step="0.01" value="${p && p.maxEstimatedCost !== null ? p.maxEstimatedCost : ''}"></div>
                    </div></fieldset>
                <div class="row g-2 mt-2">
                    <div class="col-sm-8"><label class="form-label" for="pol-approvers">Approvers</label>
                        <select class="form-select" id="pol-approvers" multiple size="5">${Utils.raw(approverOptions(selectedApprovers))}</select>
                        <div class="form-text">The requester never approves their own request. With no approver left, admins decide.</div></div>
                    <div class="col-sm-4"><label class="form-label" for="pol-mode">Who must agree</label>
                        <select class="form-select" id="pol-mode"><option value="ANY">Any one approver</option>
                            <option value="ALL" ${p && p.approverMode === 'ALL' ? 'selected' : ''}>All approvers</option></select>
                        <div class="form-check form-switch mt-3"><input class="form-check-input" type="checkbox" id="pol-active" ${!p || p.active ? 'checked' : ''}>
                            <label class="form-check-label" for="pol-active">Active</label></div></div>
                </div>`,
            buttons: [{ text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: p ? 'Save' : 'Create policy', class: 'btn-primary', id: 'pol-save' }],
            onShow: () => $('#pol-name').trigger('focus')
        });
        $('#pol-scope').val(currentScope);
        $('#pol-save').on('click', function() {
            const scope = $('#pol-scope').val() || '';
            const num = sel => { const v = $(sel).val(); return v === '' ? null : Number(v); };
            const body = {
                name: $('#pol-name').val().trim(),
                environmentId: scope.startsWith('ENV:') ? scope.slice(4) : null,
                teamId: scope.startsWith('TEAM:') ? scope.slice(5) : null,
                instanceTypePatterns: $('#pol-patterns').val().split(',').map(s => s.trim()).filter(Boolean),
                minVcpu: num('#pol-vcpu'), maxRuntimeHours: num('#pol-hours'), maxEstimatedCost: num('#pol-cost'),
                approverMode: $('#pol-mode').val(),
                approvers: ($('#pol-approvers').val() || []).map(v => { const i = v.indexOf(':'); return { type: v.slice(0, i), approverId: v.slice(i + 1) || null }; }),
                active: $('#pol-active').is(':checked')
            };
            const $btn = $(this).prop('disabled', true);
            (p ? call('PUT', Config.API.approvalPolicies.item(p.policyId), body) : call('POST', Config.API.approvalPolicies.create, body))
                .then(() => {
                    bootstrap.Modal.getInstance(document.getElementById('policyModal')).hide();
                    Notifications.success(p ? 'Policy saved.' : 'Policy created.');
                    refresh();
                })
                .catch(e => { Notifications.showError(serverMessage(e, 'Could not save the policy.')); $btn.prop('disabled', false); });
        });
    }

    function remove(policyId) {
        const p = state.policies.find(x => x.policyId === policyId);
        if (!p) return;
        Modals.confirm('Delete policy', `Delete "${p.name}"? Starts it matched no longer need approval.`, () => {
            call('DELETE', Config.API.approvalPolicies.item(policyId))
                .then(() => { Notifications.success('Policy deleted.'); refresh(); })
                .catch(e => Notifications.showError(serverMessage(e, 'Could not delete the policy.')));
        }, { confirmText: 'Delete', confirmClass: 'btn-danger' });
    }

    function dryRun() {
        const environmentId = $('#dry-env').val();
        if (!environmentId) return;
        call('POST', Config.API.approvalPolicies.evaluate, { environmentId, hours: Number($('#dry-hours').val()) || null })
            .then(d => {
                $('#dry-result').html(d.requiresApproval
                    ? Utils.html`<div class="alert alert-warning mb-0" id="dry-outcome"><strong>Needs approval</strong> in ${envName(environmentId)}:
                        <ul class="mb-0">${Utils.raw(d.matches.map(m => Utils.html`<li>${m.name}: ${m.reasons.join('; ')}</li>`).join(''))}</ul></div>`
                    : Utils.html`<div class="alert alert-success mb-0" id="dry-outcome"><strong>No approval needed</strong> in ${envName(environmentId)}.</div>`);
            })
            .catch(e => $('#dry-result').text(serverMessage(e, 'Could not check.')));
    }

    return { load, openForm, remove, dryRun };
})();

window.ApprovalPolicies = ApprovalPolicies;

Actions.registerAll({
    'policy-new': () => ApprovalPolicies.openForm(null),
    'policy-edit': el => ApprovalPolicies.openForm(el.dataset.id),
    'policy-delete': el => ApprovalPolicies.remove(el.dataset.id),
    'policy-dry-run': () => ApprovalPolicies.dryRun()
});
