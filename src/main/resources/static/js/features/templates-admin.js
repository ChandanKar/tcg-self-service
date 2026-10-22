/**
 * Template administration and the provisioning requests queue (E32-T08, U14-T08 TemplatesAdmin.dc.html),
 * as tabs of the New environments page.
 *   Templates (admins): the templates on the left (name, how it is built, Offered / Not offered), the
 *     chosen one's editor in the middle (Offered to people, description, built with and its fields, AWS
 *     account and region, cost, lasts / longest allowed hours, who can ask, needs approval by, VMs per
 *     group, parameters) saved without leaving it, and "Waiting for you" alongside with Approve / Decline.
 *   Requests · N waiting (admins and environment admins): every request waiting for a decision; a
 *     decline always carries a reason.
 */
const TemplatesAdmin = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    const MAX_HOURS = 2160;
    let all = [];
    let pending = [];
    let pendingCount = null;
    let accounts = [];
    let selected = null; // a templateId, or 'new'
    let $root = null;

    // ---- pure model (node tests: src/test/js/templates-admin.test.mjs) ----

    /** "for 5 days" from 120 hours, "for 36 hours" below two days. */
    function forText(hours) {
        const h = Number(hours) || 0;
        return h >= 48 && h % 24 === 0 ? `for ${h / 24} days` : `for ${h} hour${h === 1 ? '' : 's'}`;
    }

    /** "about $40": the template's monthly estimate for the hours asked; null without an estimate. */
    function aboutCost(monthly, hours) {
        if (monthly === null || monthly === undefined || monthly === '') return null;
        return `about $${Math.round(Number(monthly) * (Number(hours) || 0) / 730).toLocaleString('en-US')}`;
    }

    function requestsLabel(count) {
        return count ? `Requests · ${count} waiting` : 'Requests';
    }

    function engineName(engine) {
        return engine === 'TERRAFORM' ? 'Terraform' : 'Service Catalog';
    }

    // ---- page ----

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data || null, QUIET).done(resolve).fail(reject);
        });
    }

    function serverMessage(e, fallback) {
        return (e && e.responseJSON && e.responseJSON.message) || fallback;
    }

    function totalOf(data) {
        if (data && data.page && data.page.totalElements != null) return data.page.totalElements;
        return ((data && data.content) || []).length;
    }

    /** The page's tabs for this person, built around the catalogue and My requests. */
    function tabs(mine) {
        if (Auth.isAdmin()) {
            return [['manage', 'Templates'], ['approvals', requestsLabel(pendingCount)], ['catalogue', 'Ask for one'], mine[1]];
        }
        return Auth.isEnvAdmin() ? mine.concat([['approvals', requestsLabel(pendingCount)]]) : mine;
    }

    function handles(tab) {
        return tab === 'approvals' || tab === 'manage';
    }

    /** How many requests wait for this person, for the tab's label. */
    function countPending(token, done) {
        if (!Auth.isEnvAdmin()) return;
        call('GET', Config.API.provisioning.pending()).then(d => {
            if (!ContentRouter.isCurrent(token)) return;
            pendingCount = totalOf(d);
            done();
        }).catch(() => {});
    }

    function render(tab, $body, token) {
        $root = $body;
        const loads = [call('GET', Config.API.provisioning.pending())];
        if (tab === 'manage') {
            loads.push(call('GET', Config.API.templates.all()), call('GET', Config.API.awsAccounts.options).catch(() => []));
        }
        Promise.all(loads).then(([waiting, templates, options]) => {
            if (!ContentRouter.isCurrent(token)) return;
            pending = (waiting && waiting.content) || [];
            pendingCount = totalOf(waiting);
            if (tab === 'manage') {
                all = templates || [];
                accounts = Array.isArray(options) ? options : [];
                if (!selected || (selected !== 'new' && !all.some(t => t.templateId === selected))) {
                    selected = all.length ? all[0].templateId : 'new';
                }
                drawManage();
            } else {
                drawApprovals();
            }
        }).catch(e => {
            if (ContentRouter.isCurrent(token)) {
                $body.html(Utils.html`<div class="alert alert-danger" role="alert">${serverMessage(e, "Couldn't load this tab.")}</div>`);
            }
        });
    }

    // ---------------------------------------------------------------- requests

    function params(p) {
        const entries = p && typeof p === 'object' ? Object.entries(p) : [];
        return entries.length ? entries.map(([k, v]) => `${k} = ${typeof v === 'object' ? JSON.stringify(v) : v}`).join(', ') : 'none';
    }

    const LIFECYCLE = 'After approval: Creating → Ready. It\'s removed when its time runs out, unless someone extends it.';

    function requestCard(r, compact) {
        const t = all.find(x => x.templateId === r.templateId);
        const cost = aboutCost(t ? t.estimatedMonthlyCost : null, r.ttlHours);
        const who = [r.requestedByName || r.requestedByUserId, forText(r.ttlHours), cost].filter(Boolean).join(' · ');
        return Utils.html`<div class="ta-request provisioning-approval" data-request="${r.requestId}">
                <div><b>${r.environmentName}</b> <span class="ta-sub">· ${r.templateName || ''}</span></div>
                <div class="ta-sub">${who}</div>
                ${Utils.raw(r.justification ? Utils.html`<div class="ta-why request-why">"${r.justification}"</div>` : '')}
                ${Utils.raw(compact ? '' : Utils.html`<div class="ta-sub request-params">Parameters: ${params(r.parameters)}</div>`)}
                <div class="ta-request-actions">
                    <button type="button" class="btn btn-sm btn-primary" data-action="tpl-approve" data-id="${r.requestId}">Approve</button>
                    <button type="button" class="btn btn-sm btn-secondary" data-action="tpl-reject" data-id="${r.requestId}">Decline</button>
                </div>
            </div>`;
    }

    function drawApprovals() {
        $root.html(pending.length ? `<section class="ta-card ta-queue" aria-labelledby="ta-queue-title">
                <div class="ta-card-head"><h2 id="ta-queue-title">Waiting for a decision</h2></div>
                ${pending.map(r => requestCard(r, false)).join('')}
                <p class="ta-note ta-pad">${Utils.escapeHtml(LIFECYCLE)}</p></section>`
            : '<p class="text-muted">No requests are waiting for you.</p>');
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

    /** A decline needs a reason: the prompt does not send without one. */
    function reject(el) {
        const r = pending.find(x => x.requestId === el.dataset.id);
        if (!r) return;
        Modals.prompt('Decline request', 'Why is it declined? The requester sees this.', notes => {
            if (!notes || !notes.trim()) return;
            call('POST', Config.API.provisioning.reject(r.requestId), { notes: notes.trim() }).then(() => {
                Notifications.success(`Request for '${r.environmentName}' declined.`);
                Templates.refresh();
            }).catch(e => Notifications.showError(serverMessage(e, 'Could not decline the request.')));
        }, { required: true, submitText: 'Decline' });
    }

    // ---------------------------------------------------------------- templates

    function drawManage() {
        const list = all.map(t => Utils.html`<button type="button" class="ta-item${Utils.raw(t.templateId === selected ? ' ta-item-on' : '')}"
                data-template="${t.templateId}" data-action="tpl-select" data-id="${t.templateId}" aria-current="${t.templateId === selected ? 'true' : 'false'}">
                <b>${t.name}</b><span class="ta-item-sub"><span>${engineName(t.engine)}</span>
                <span class="ta-state ta-state-${t.active ? 'on' : 'off'}">${t.active ? 'Offered' : 'Not offered'}</span></span></button>`).join('');
        const aside = pending.length ? pending.map(r => requestCard(r, true)).join('') : '<p class="ta-note ta-pad">Nothing is waiting for you.</p>';
        $root.html(`<div class="ta-layout">
                <nav class="ta-card ta-list" aria-label="Templates" id="templates-admin-list">${list}
                    <div class="ta-list-foot"><button type="button" class="ta-link" data-action="tpl-new">+ New template</button></div></nav>
                <section class="ta-card ta-editor" id="ta-editor" aria-labelledby="ta-editor-title"></section>
                <aside class="ta-card ta-aside" aria-labelledby="ta-waiting-title">
                    <div class="ta-card-head"><h2 id="ta-waiting-title">Waiting for you</h2></div>${aside}
                    <p class="ta-note ta-pad">${Utils.escapeHtml(LIFECYCLE)}</p></aside>
            </div>`);
        drawEditor();
    }

    function select(el) {
        selected = el.dataset.id;
        drawManage();
    }

    function startNew() {
        selected = 'new';
        drawManage();
        $('#tpl-e-name').trigger('focus');
    }

    const SC_FIELDS = [['productId', 'Product', true], ['provisioningArtifactId', 'Version (provisioning artifact)', true],
        ['launchPathId', 'Launch path', false]];
    const TF_FIELDS = [['organization', 'Organization', true], ['workspacePrefix', 'Workspace prefix', true],
        ['vcsRepoIdentifier', 'VCS repository (org/repo)', false], ['oauthTokenId', 'VCS OAuth token id', false],
        ['noCodeModuleId', 'No-code module id', false], ['terraformVersion', 'Terraform version', false], ['agentPoolId', 'Agent pool id', false]];

    function engineFields(engine, ref) {
        const fields = engine === 'TERRAFORM' ? TF_FIELDS : SC_FIELDS;
        return fields.map(([key, label, req]) => `<label class="ta-field" for="tpl-ref-${key}">${Utils.escapeHtml(label)}${req ? ' *' : ''}
            <input class="form-control form-control-sm tpl-ref ta-mono" id="tpl-ref-${key}" data-key="${key}" value="${Utils.escapeHtml((ref && ref[key]) || '')}"></label>`).join('');
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

    function current() {
        return selected === 'new' ? null : all.find(x => x.templateId === selected) || null;
    }

    function accountOptions(t) {
        const chosen = t && t.awsAccountRef ? t.awsAccountRef : '';
        const known = accounts.some(a => a.ref === chosen);
        return `<option value="">The default account</option>${accounts.map(a => Utils.html`<option value="${a.ref}"${Utils.raw(a.ref === chosen ? ' selected' : '')}>${a.name}</option>`).join('')}`
            + (chosen && !known ? Utils.html`<option value="${chosen}" selected>${chosen}</option>` : '');
    }

    function regionList(ref) {
        const a = accounts.find(x => x.ref === ref) || accounts.find(x => x.isDefault);
        return ((a && a.regions) || []).map(r => Utils.html`<option value="${r}"></option>`).join('');
    }

    function drawEditor() {
        const t = current();
        const engine = t ? t.engine : 'SERVICE_CATALOG';
        const roles = t ? t.allowedRoles : ['ENV_ADMIN', 'USER'];
        const role = (r, label) => `<label class="ta-check"><input class="form-check-input tpl-e-role" type="checkbox" id="tpl-e-role-${r}" value="${r}" ${roles.includes(r) ? 'checked' : ''}> ${label}</label>`;
        const schema = (t && t.parameterSchema) || [];
        $('#ta-editor').html(`<form id="tpl-editor" novalidate>
            <div class="ta-editor-head"><h2 id="ta-editor-title">${Utils.escapeHtml(t ? t.name : 'New template')}</h2>
                <label class="ta-offer"><span class="form-check m-0"><input class="form-check-input" type="checkbox" id="tpl-e-active"
                    ${!t || t.active ? 'checked' : ''}></span> Offered to people</label></div>
            <div class="ta-fields">
                <label class="ta-field ta-wide" for="tpl-e-name">Name *<input class="form-control form-control-sm" id="tpl-e-name" maxlength="120" value="${Utils.escapeHtml(t ? t.name : '')}"></label>
                <label class="ta-field ta-wide" for="tpl-e-desc">Description<textarea class="form-control form-control-sm" id="tpl-e-desc" rows="1">${Utils.escapeHtml(t && t.description ? t.description : '')}</textarea></label>
                <label class="ta-field" for="tpl-e-engine">Built with<select class="form-select form-select-sm" id="tpl-e-engine" data-action-change="tpl-engine">
                    <option value="SERVICE_CATALOG" ${engine === 'SERVICE_CATALOG' ? 'selected' : ''}>AWS Service Catalog</option>
                    <option value="TERRAFORM" ${engine === 'TERRAFORM' ? 'selected' : ''}>Terraform (HCP Terraform / TFE)</option></select></label>
                <div class="ta-ref" id="tpl-e-ref">${engineFields(engine, t && t.engineRef)}</div>
                <label class="ta-field" for="tpl-e-account">AWS account<select class="form-select form-select-sm" id="tpl-e-account">${accountOptions(t)}</select></label>
                <label class="ta-field" for="tpl-e-region">Region<input class="form-control form-control-sm ta-mono" id="tpl-e-region" list="tpl-e-regions"
                    placeholder="The account's region" value="${Utils.escapeHtml(t && t.region ? t.region : '')}"><datalist id="tpl-e-regions">${regionList(t && t.awsAccountRef)}</datalist></label>
                <label class="ta-field" for="tpl-e-cost">Estimated cost a month ($)<input class="form-control form-control-sm ta-mono" id="tpl-e-cost" type="number" min="0" step="0.01"
                    value="${t && t.estimatedMonthlyCost != null ? Number(t.estimatedMonthlyCost) : ''}"></label>
                <label class="ta-field" for="tpl-e-layout">VMs per group, in start order<input class="form-control form-control-sm ta-mono" id="tpl-e-layout" placeholder="1|2|1"
                    value="${Utils.escapeHtml(t && t.vmLayout ? t.vmLayout : '')}"></label>
                <label class="ta-field" for="tpl-e-ttl">Lasts, unless extended<span class="ta-unit"><input class="form-control form-control-sm ta-mono" id="tpl-e-ttl" type="number" min="1"
                    max="${MAX_HOURS}" value="${t ? Number(t.defaultTtlHours) : 72}"> hours</span></label>
                <label class="ta-field" for="tpl-e-max">Longest allowed<span class="ta-unit"><input class="form-control form-control-sm ta-mono" id="tpl-e-max" type="number" min="1"
                    max="${MAX_HOURS}" value="${t ? Number(t.maxTtlHours) : 336}"> hours (max ${MAX_HOURS.toLocaleString('en-US')})</span></label>
                <fieldset class="ta-field ta-wide"><legend>Who can ask for one</legend>${role('USER', 'Users')}${role('ENV_ADMIN', 'Environment admins')}${role('VIEWER', 'Viewers')}</fieldset>
                <div class="ta-field ta-wide ta-approval"><label class="ta-check"><input class="form-check-input" type="checkbox" id="tpl-e-approval" ${!t || t.requiresApproval ? 'checked' : ''}> Needs approval by</label>
                    <label class="visually-hidden" for="tpl-e-approver">Approved by</label>
                    <select class="form-select form-select-sm" id="tpl-e-approver"><option value="ADMIN">an administrator</option>
                        <option value="ENV_ADMIN" ${t && t.approverRole === 'ENV_ADMIN' ? 'selected' : ''}>an environment admin</option></select></div>
                <details class="ta-wide ta-params"${Utils.raw(schema.length ? ' open' : '')}><summary>Parameters people fill in (${schema.length})</summary>
                    <div class="table-responsive"><table class="table table-sm mb-1" id="tpl-e-params"><thead><tr><th>Key</th><th>Label</th><th>Type</th><th>Required</th><th>Default</th><th>Options</th><th>Pattern</th><th></th></tr></thead>
                    <tbody>${schema.map(paramRow).join('')}</tbody></table></div>
                    <button type="button" class="btn btn-sm btn-ghost" data-action="tpl-param-add"><i class="fas fa-plus" aria-hidden="true"></i> Add parameter</button></details>
            </div>
            <div class="ta-error" id="tpl-e-error" role="alert"></div>
            <div class="ta-editor-foot"><button type="button" class="btn btn-primary btn-sm" id="tpl-e-save" data-action="tpl-save">${t ? 'Save template' : 'Add template'}</button></div>
        </form>`);
        $('#tpl-e-account').on('change', function() { $('#tpl-e-regions').html(regionList(this.value)); });
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
            const options = String(get('options').val() || '').split(',').map(x => x.trim()).filter(Boolean);
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
            estimatedMonthlyCost: cost === '' ? null : Number(cost),
            vmLayout: String($('#tpl-e-layout').val() || '').trim(),
            active: $('#tpl-e-active').is(':checked')
        };
    }

    function problem(body) {
        if (!body.name) return 'A name is required.';
        if (!(body.defaultTtlHours >= 1) || !(body.maxTtlHours >= 1)) return 'Give both lifetimes in hours.';
        if (body.maxTtlHours > MAX_HOURS) return `The longest allowed is ${MAX_HOURS.toLocaleString('en-US')} hours.`;
        if (body.defaultTtlHours > body.maxTtlHours) return 'It cannot last longer than the longest allowed.';
        if (!body.allowedRoles.length) return 'Choose who can ask for one.';
        return null;
    }

    /** Saves and stays on the template; switching off a template people can ask for asks first. */
    function save() {
        const t = current();
        const body = collect();
        const issue = problem(body);
        if (issue) {
            $('#tpl-e-error').text(issue);
            return;
        }
        $('#tpl-e-error').text('');
        const send = () => {
            $('#tpl-e-save').prop('disabled', true);
            const req = t ? call('PUT', Config.API.templates.item(t.templateId), body) : call('POST', Config.API.templates.create(), body);
            req.then(saved => {
                Notifications.success(t ? 'Template saved.' : 'Template added.');
                if (saved && saved.templateId) selected = saved.templateId;
                Templates.refresh();
            }).catch(e => {
                $('#tpl-e-save').prop('disabled', false);
                $('#tpl-e-error').text(serverMessage(e, 'The template could not be saved.'));
            });
        };
        if (t && t.active && !body.active) {
            Modals.confirm('Stop offering it', `Stop offering '${t.name}'? Environments built from it keep running; nobody can ask for it again until it is offered.`,
                send, { confirmText: 'Stop offering', confirmClass: 'btn-danger' });
            return;
        }
        send();
    }

    return { tabs, handles, render, countPending, approve, reject, select, startNew, save, changeEngine, addParam, removeParam,
        model: { forText, aboutCost, requestsLabel } };
})();

if (typeof window !== 'undefined') window.TemplatesAdmin = TemplatesAdmin;

if (typeof Actions !== 'undefined') {
    Actions.registerAll({
        'tpl-approve': el => TemplatesAdmin.approve(el),
        'tpl-reject': el => TemplatesAdmin.reject(el),
        'tpl-select': el => TemplatesAdmin.select(el),
        'tpl-new': () => TemplatesAdmin.startNew(),
        'tpl-save': () => TemplatesAdmin.save(),
        'tpl-param-add': () => TemplatesAdmin.addParam(),
        'tpl-param-remove': el => TemplatesAdmin.removeParam(el)
    });
    Actions.register('tpl-engine', el => TemplatesAdmin.changeEngine(el), 'change');
}
