/**
 * Template administration and the provisioning approvals queue (E32-T08), as extra tabs of the
 * New Environment page: admins list, add, change and retire templates (with a parameter-schema
 * builder); approvers (admins and environment admins) approve or reject waiting requests, a
 * rejection always with a reason.
 */
const TemplatesAdmin = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    let all = [];
    let pending = [];

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data || null, QUIET).done(resolve).fail(reject);
        });
    }

    function serverMessage(e, fallback) {
        return (e && e.responseJSON && e.responseJSON.message) || fallback;
    }

    function tabs() {
        const list = [];
        if (Auth.isEnvAdmin()) list.push(['approvals', 'Approvals']);
        if (Auth.isAdmin()) list.push(['manage', 'Manage templates']);
        return list;
    }

    function handles(tab) {
        return tab === 'approvals' || tab === 'manage';
    }

    function render(tab, $body, token) {
        const url = tab === 'manage' ? Config.API.templates.all() : Config.API.provisioning.pending();
        call('GET', url).then(data => {
            if (!ContentRouter.isCurrent(token)) return;
            if (tab === 'manage') {
                all = data || [];
                drawManage($body);
            } else {
                pending = (data && data.content) || [];
                drawApprovals($body);
            }
        }).catch(e => {
            if (ContentRouter.isCurrent(token)) {
                $body.html(Utils.html`<div class="alert alert-danger" role="alert">${serverMessage(e, "Couldn't load this tab.")}</div>`);
            }
        });
    }

    // ---------------------------------------------------------------- approvals

    function params(p) {
        const entries = p && typeof p === 'object' ? Object.entries(p) : [];
        return entries.length ? entries.map(([k, v]) => `${k} = ${typeof v === 'object' ? JSON.stringify(v) : v}`).join(', ') : 'none';
    }

    function drawApprovals($body) {
        if (!pending.length) {
            $body.html('<p class="text-muted">No requests are waiting for you.</p>');
            return;
        }
        $body.html(`<div class="d-flex flex-column gap-2">${pending.map(r => Utils.html`
            <section class="card provisioning-approval" data-request="${r.requestId}"><div class="card-body">
                <div class="d-flex justify-content-between flex-wrap gap-2">
                    <div class="min-w-0">
                        <h2 class="h6 mb-1">${r.environmentName} <span class="text-muted small">from ${r.templateName || ''}</span></h2>
                        <div class="small text-muted">Requested by ${r.requestedByName || r.requestedByUserId} · ${r.ttlHours} h</div>
                        <div class="small mt-1 request-params">Parameters: ${params(r.parameters)}</div>
                        ${Utils.raw(r.justification ? Utils.html`<div class="small mt-1 request-why">Why: ${r.justification}</div>` : '')}
                    </div>
                    <div class="d-flex gap-1 align-items-start">
                        <button type="button" class="btn btn-sm btn-success" data-action="tpl-approve" data-id="${r.requestId}">Approve</button>
                        <button type="button" class="btn btn-sm btn-outline-danger" data-action="tpl-reject" data-id="${r.requestId}">Reject</button>
                    </div>
                </div></div></section>`).join('')}</div>`);
    }

    function approve(el) {
        const r = pending.find(x => x.requestId === el.dataset.id);
        if (!r) return;
        Modals.confirm('Approve request', `Build '${r.environmentName}' from ${r.templateName || 'the template'}?`, () => {
            call('POST', Config.API.provisioning.approve(r.requestId), {}).then(() => {
                Notifications.success(`'${r.environmentName}' approved; it will be built shortly.`);
                Templates.refresh();
            }).catch(e => Notifications.showError(serverMessage(e, 'Could not approve the request.')));
        }, { confirmText: 'Approve', confirmClass: 'btn-success' });
    }

    /** A rejection needs a reason: the prompt does not send without one. */
    function reject(el) {
        const r = pending.find(x => x.requestId === el.dataset.id);
        if (!r) return;
        Modals.prompt('Reject request', 'Why is it rejected? The requester sees this.', notes => {
            if (!notes || !notes.trim()) return;
            call('POST', Config.API.provisioning.reject(r.requestId), { notes: notes.trim() }).then(() => {
                Notifications.success(`Request for '${r.environmentName}' rejected.`);
                Templates.refresh();
            }).catch(e => Notifications.showError(serverMessage(e, 'Could not reject the request.')));
        }, { required: true, submitText: 'Reject' });
    }

    // ---------------------------------------------------------------- manage

    function drawManage($body) {
        $body.html(`<div class="d-flex justify-content-end mb-2">
                <button type="button" class="btn btn-sm btn-primary" data-action="tpl-edit"><i class="fas fa-plus" aria-hidden="true"></i> New template</button></div>
            ${all.length ? `<div class="table-responsive"><table class="table table-baseline table-cards" id="templates-admin-table">
                <thead><tr><th>Name</th><th>Engine</th><th>Lifetime</th><th>Approval</th><th>Who may request</th><th>State</th><th></th></tr></thead>
                <tbody>${all.map(t => Utils.html`<tr data-template="${t.templateId}">
                    <td data-label="Name"><strong>${t.name}</strong></td>
                    <td data-label="Engine">${t.engine === 'TERRAFORM' ? 'Terraform' : 'Service Catalog'}</td>
                    <td data-label="Lifetime">${t.defaultTtlHours} h (max ${t.maxTtlHours} h)</td>
                    <td data-label="Approval">${t.requiresApproval ? 'By ' + (t.approverRole === 'ENV_ADMIN' ? 'environment admins' : 'admins') : 'Not needed'}</td>
                    <td data-label="Who may request">${(t.allowedRoles || []).join(', ')}</td>
                    <td data-label="State">${Utils.raw(t.active ? '<span class="badge text-bg-success">Active</span>' : '<span class="badge text-bg-light border">Retired</span>')}</td>
                    <td><button type="button" class="btn btn-sm btn-ghost" data-action="tpl-edit" data-id="${t.templateId}">Edit</button>
                        ${Utils.raw(t.active ? Utils.html`<button type="button" class="btn btn-sm btn-ghost text-danger" data-action="tpl-retire" data-id="${t.templateId}">Retire</button>` : '')}</td>
                </tr>`).join('')}</tbody></table></div>` : '<p class="text-muted">No templates yet.</p>'}`);
    }

    const SC_FIELDS = [['productId', 'Product id', true], ['provisioningArtifactId', 'Provisioning artifact (version) id', true],
        ['launchPathId', 'Launch path id', false]];
    const TF_FIELDS = [['organization', 'Organization', true], ['workspacePrefix', 'Workspace prefix', true],
        ['vcsRepoIdentifier', 'VCS repository (org/repo)', false], ['oauthTokenId', 'VCS OAuth token id', false],
        ['noCodeModuleId', 'No-code module id', false], ['terraformVersion', 'Terraform version', false], ['agentPoolId', 'Agent pool id', false]];

    function engineFields(engine, ref) {
        const fields = engine === 'TERRAFORM' ? TF_FIELDS : SC_FIELDS;
        return fields.map(([key, label, req]) => `<div class="col-12 col-md-6"><label class="form-label small mb-1" for="tpl-ref-${key}">${Utils.escapeHtml(label)}${req ? ' *' : ''}</label>
            <input class="form-control form-control-sm tpl-ref" id="tpl-ref-${key}" data-key="${key}" value="${Utils.escapeHtml((ref && ref[key]) || '')}"></div>`).join('');
    }

    function paramRow(p) {
        p = p || {};
        const type = p.type || 'string';
        return `<tr class="tpl-param">
            <td><input class="form-control form-control-sm" data-f="key" aria-label="Key" value="${Utils.escapeHtml(p.key || '')}"></td>
            <td><input class="form-control form-control-sm" data-f="label" aria-label="Label" value="${Utils.escapeHtml(p.label || '')}"></td>
            <td><select class="form-select form-select-sm" data-f="type" aria-label="Type">${['string', 'number', 'enum'].map(x =>
                `<option ${x === type ? 'selected' : ''}>${x}</option>`).join('')}</select></td>
            <td class="text-center"><input class="form-check-input" type="checkbox" data-f="required" aria-label="Required" ${p.required ? 'checked' : ''}></td>
            <td><input class="form-control form-control-sm" data-f="default" aria-label="Default" value="${Utils.escapeHtml(p.default == null ? '' : String(p.default))}"></td>
            <td><input class="form-control form-control-sm" data-f="options" aria-label="Options (comma-separated)" value="${Utils.escapeHtml((p.options || []).join(', '))}"></td>
            <td><input class="form-control form-control-sm" data-f="pattern" aria-label="Pattern" value="${Utils.escapeHtml(p.pattern || '')}"></td>
            <td><button type="button" class="btn btn-sm btn-ghost" data-action="tpl-param-remove" aria-label="Remove parameter"><i class="fas fa-times" aria-hidden="true"></i></button></td>
        </tr>`;
    }

    function openEditor(el) {
        const t = el && el.dataset.id ? all.find(x => x.templateId === el.dataset.id) : null;
        const engine = t ? t.engine : 'SERVICE_CATALOG';
        const roles = t ? t.allowedRoles : ['ENV_ADMIN', 'USER'];
        Modals.show({
            id: 'templateEditorModal',
            size: 'xl',
            title: t ? 'Edit ' + t.name : 'New template',
            body: `<form id="tpl-editor" novalidate><div class="row g-2">
                <div class="col-12 col-md-6"><label class="form-label small mb-1" for="tpl-e-name">Name *</label>
                    <input class="form-control form-control-sm" id="tpl-e-name" maxlength="120" value="${Utils.escapeHtml(t ? t.name : '')}"></div>
                <div class="col-12 col-md-6"><label class="form-label small mb-1" for="tpl-e-engine">Engine</label>
                    <select class="form-select form-select-sm" id="tpl-e-engine" data-action-change="tpl-engine">
                        <option value="SERVICE_CATALOG" ${engine === 'SERVICE_CATALOG' ? 'selected' : ''}>AWS Service Catalog</option>
                        <option value="TERRAFORM" ${engine === 'TERRAFORM' ? 'selected' : ''}>Terraform (HCP Terraform / TFE)</option></select></div>
                <div class="col-12"><label class="form-label small mb-1" for="tpl-e-desc">Description</label>
                    <textarea class="form-control form-control-sm" id="tpl-e-desc" rows="2">${Utils.escapeHtml(t && t.description ? t.description : '')}</textarea></div>
                <div class="col-12"><div class="row g-2" id="tpl-e-ref">${engineFields(engine, t && t.engineRef)}</div></div>
                <div class="col-6 col-md-3"><label class="form-label small mb-1" for="tpl-e-region">Region</label>
                    <input class="form-control form-control-sm" id="tpl-e-region" value="${Utils.escapeHtml(t && t.region ? t.region : '')}"></div>
                <div class="col-6 col-md-3"><label class="form-label small mb-1" for="tpl-e-account">AWS account ref</label>
                    <input class="form-control form-control-sm" id="tpl-e-account" value="${Utils.escapeHtml(t && t.awsAccountRef ? t.awsAccountRef : '')}"></div>
                <div class="col-6 col-md-2"><label class="form-label small mb-1" for="tpl-e-ttl">Default hours</label>
                    <input class="form-control form-control-sm" id="tpl-e-ttl" type="number" min="1" value="${t ? Number(t.defaultTtlHours) : 72}"></div>
                <div class="col-6 col-md-2"><label class="form-label small mb-1" for="tpl-e-max">Max hours</label>
                    <input class="form-control form-control-sm" id="tpl-e-max" type="number" min="1" value="${t ? Number(t.maxTtlHours) : 336}"></div>
                <div class="col-6 col-md-2"><label class="form-label small mb-1" for="tpl-e-cost">Est. monthly $</label>
                    <input class="form-control form-control-sm" id="tpl-e-cost" type="number" min="0" step="0.01" value="${t && t.estimatedMonthlyCost != null ? Number(t.estimatedMonthlyCost) : ''}"></div>
                <div class="col-12 col-md-4"><div class="form-check mt-md-4"><input class="form-check-input" type="checkbox" id="tpl-e-approval" ${!t || t.requiresApproval ? 'checked' : ''}>
                    <label class="form-check-label small" for="tpl-e-approval">Needs approval</label></div></div>
                <div class="col-6 col-md-4"><label class="form-label small mb-1" for="tpl-e-approver">Approved by</label>
                    <select class="form-select form-select-sm" id="tpl-e-approver"><option value="ADMIN">Admins</option>
                        <option value="ENV_ADMIN" ${t && t.approverRole === 'ENV_ADMIN' ? 'selected' : ''}>Environment admins</option></select></div>
                <div class="col-6 col-md-4"><span class="form-label small d-block mb-1">Who may request</span>
                    ${['ENV_ADMIN', 'USER', 'VIEWER'].map(r => `<div class="form-check form-check-inline"><input class="form-check-input tpl-e-role" type="checkbox" id="tpl-e-role-${r}" value="${r}" ${roles.includes(r) ? 'checked' : ''}>
                        <label class="form-check-label small" for="tpl-e-role-${r}">${r === 'ENV_ADMIN' ? 'Env admins' : r === 'USER' ? 'Users' : 'Viewers'}</label></div>`).join('')}</div>
                <div class="col-12"><span class="form-label small d-block mb-1">Parameters</span>
                    <div class="table-responsive"><table class="table table-sm mb-1" id="tpl-e-params"><thead><tr><th>Key</th><th>Label</th><th>Type</th><th>Required</th><th>Default</th><th>Options</th><th>Pattern</th><th></th></tr></thead>
                    <tbody>${((t && t.parameterSchema) || []).map(paramRow).join('')}</tbody></table></div>
                    <button type="button" class="btn btn-sm btn-ghost" data-action="tpl-param-add"><i class="fas fa-plus" aria-hidden="true"></i> Add parameter</button></div>
                <div class="col-12 small text-danger" id="tpl-e-error" role="alert"></div>
            </div></form>`,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Save', class: 'btn-primary', id: 'tpl-e-save' }
            ]
        });
        $('#tpl-e-save').on('click', () => save(t));
    }

    function changeEngine(el) {
        $('#tpl-e-ref').html(engineFields(el.value, null));
    }

    function addParam() {
        $('#tpl-e-params tbody').append(paramRow(null));
    }

    function removeParam(el) {
        $(el).closest('tr').remove();
    }

    function collect() {
        const ref = {};
        $('#tpl-editor .tpl-ref').each(function() {
            const v = String($(this).val() || '').trim();
            if (v) ref[this.dataset.key] = v;
        });
        const schema = [];
        $('#tpl-e-params tbody tr').each(function() {
            const get = f => $(this).find(`[data-f="${f}"]`);
            const key = String(get('key').val() || '').trim();
            if (!key) return;
            const p = { key, type: get('type').val(), required: get('required').is(':checked') };
            const label = String(get('label').val() || '').trim();
            if (label) p.label = label;
            const def = String(get('default').val() || '').trim();
            if (def) p.default = def;
            const options = String(get('options').val() || '').split(',').map(s => s.trim()).filter(Boolean);
            if (p.type === 'enum') p.options = options;
            const pattern = String(get('pattern').val() || '').trim();
            if (pattern) p.pattern = pattern;
            schema.push(p);
        });
        const cost = String($('#tpl-e-cost').val() || '').trim();
        return {
            name: String($('#tpl-e-name').val() || '').trim(),
            description: String($('#tpl-e-desc').val() || '').trim() || null,
            engine: $('#tpl-e-engine').val(),
            engineRef: ref,
            parameterSchema: schema,
            region: String($('#tpl-e-region').val() || '').trim() || null,
            awsAccountRef: String($('#tpl-e-account').val() || '').trim() || null,
            defaultTtlHours: Number($('#tpl-e-ttl').val()),
            maxTtlHours: Number($('#tpl-e-max').val()),
            requiresApproval: $('#tpl-e-approval').is(':checked'),
            approverRole: $('#tpl-e-approver').val(),
            allowedRoles: $('#tpl-editor .tpl-e-role:checked').map(function() { return this.value; }).get(),
            estimatedMonthlyCost: cost === '' ? null : Number(cost)
        };
    }

    function save(t) {
        const body = collect();
        if (!body.name) {
            $('#tpl-e-error').text('A name is required.');
            return;
        }
        $('#tpl-e-save').prop('disabled', true);
        const req = t ? call('PUT', Config.API.templates.item(t.templateId), body) : call('POST', Config.API.templates.create(), body);
        req.then(() => {
            Modals.hideWhenReady(document.getElementById('templateEditorModal'));
            Notifications.success(t ? 'Template saved.' : 'Template added.');
            Templates.refresh();
        }).catch(e => {
            $('#tpl-e-save').prop('disabled', false);
            $('#tpl-e-error').text(serverMessage(e, 'The template could not be saved.'));
        });
    }

    function retire(el) {
        const t = all.find(x => x.templateId === el.dataset.id);
        if (!t) return;
        Modals.confirm('Retire template', `Retire '${t.name}'? Existing environments keep running; nobody can request it again.`, () => {
            call('DELETE', Config.API.templates.item(t.templateId)).then(() => Templates.refresh())
                .catch(e => Notifications.showError(serverMessage(e, 'Could not retire the template.')));
        }, { confirmText: 'Retire', confirmClass: 'btn-danger' });
    }

    return { tabs, handles, render, approve, reject, openEditor, changeEngine, addParam, removeParam, retire };
})();

window.TemplatesAdmin = TemplatesAdmin;

Actions.registerAll({
    'tpl-approve': el => TemplatesAdmin.approve(el),
    'tpl-reject': el => TemplatesAdmin.reject(el),
    'tpl-edit': el => TemplatesAdmin.openEditor(el),
    'tpl-retire': el => TemplatesAdmin.retire(el),
    'tpl-param-add': () => TemplatesAdmin.addParam(),
    'tpl-param-remove': el => TemplatesAdmin.removeParam(el)
});
Actions.register('tpl-engine', el => TemplatesAdmin.changeEngine(el), 'change');
