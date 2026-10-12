/**
 * New environments from templates (E32-T07): the catalogue of templates the user may request, a
 * request form generated from each template's parameter schema, and "My requests" with status,
 * lifetime and actions (cancel while waiting; extend and destroy once ready). The page and its
 * sidebar item exist only while provisioning is on (the API answers 404 otherwise).
 */
const Templates = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    const POLL_KEY = 'provisioningRequests';
    const POLL_MS = 15000;
    const BUSY = ['APPROVED', 'PROVISIONING', 'DESTROYING'];
    const STATUS = {
        PENDING_APPROVAL: ['text-bg-warning', 'Waiting for approval'],
        APPROVED: ['text-bg-info', 'Approved'],
        REJECTED: ['text-bg-danger', 'Rejected'],
        PROVISIONING: ['text-bg-info', 'Building'],
        READY: ['text-bg-success', 'Ready'],
        FAILED: ['text-bg-danger', 'Failed'],
        DESTROYING: ['text-bg-secondary', 'Destroying'],
        DESTROYED: ['text-bg-light border', 'Destroyed'],
        CANCELLED: ['text-bg-light border', 'Cancelled']
    };
    let pageToken = null;
    let tab = 'catalogue';
    let templates = [];
    let requests = [];

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data || null, QUIET).done(resolve).fail(reject);
        });
    }

    function serverMessage(e, fallback) {
        return (e && e.responseJSON && e.responseJSON.message) || fallback;
    }

    /** Shows the sidebar item only while the catalogue answers (provisioning on). */
    function probeNav() {
        call('GET', Config.API.templates.list()).then(() => $('#templates-link').show()).catch(() => $('#templates-link').hide());
    }

    function load(params) {
        pageToken = ContentRouter.token();
        if (params && params.tab) tab = params.tab;
        $('#content-area').html(`
            <div class="content-view templates-page" id="templates-view">
                <div class="content-header">
                    <h1><i class="fas fa-layer-group me-2" aria-hidden="true"></i>New environment</h1>
                    <p class="text-muted mb-0">Request an environment built from an approved template; it is removed when its lifetime ends</p>
                </div>
                <ul class="nav nav-tabs mb-3" role="tablist" id="templates-tabs"></ul>
                <div id="templates-body"><div class="text-muted small"><i class="fas fa-spinner fa-spin"></i> Loading…</div></div>
            </div>`);
        refresh();
    }

    function tabs() {
        const list = [['catalogue', 'Templates'], ['mine', 'My requests']];
        if (typeof TemplatesAdmin !== 'undefined') list.push(...TemplatesAdmin.tabs());
        return list;
    }

    function drawTabs() {
        $('#templates-tabs').html(tabs().map(([id, label]) => `<li class="nav-item" role="presentation">
            <button type="button" class="nav-link ${tab === id ? 'active' : ''}" role="tab" aria-selected="${tab === id}"
                    data-action="templates-tab" data-tab="${id}">${Utils.escapeHtml(label)}</button></li>`).join(''));
    }

    function refresh() {
        const token = pageToken;
        drawTabs();
        if (typeof TemplatesAdmin !== 'undefined' && TemplatesAdmin.handles(tab)) {
            RealTime.stopPolling(POLL_KEY);
            TemplatesAdmin.render(tab, $('#templates-body'), token);
            return;
        }
        const url = tab === 'mine' ? Config.API.provisioning.mine() : Config.API.templates.list();
        call('GET', url).then(data => {
            if (!ContentRouter.isCurrent(token)) return;
            if (tab === 'mine') {
                requests = (data && data.content) || [];
                drawRequests();
                followBusy();
            } else {
                templates = data || [];
                drawCatalogue();
                RealTime.stopPolling(POLL_KEY);
            }
        }).catch(e => {
            if (!ContentRouter.isCurrent(token)) return;
            const off = e && e.status === 404;
            $('#templates-body').html(`<div class="alert ${off ? 'alert-secondary' : 'alert-danger'}" role="alert">${Utils.escapeHtml(off
                ? 'New environments from templates are not available here.' : serverMessage(e, "Couldn't load this page."))}</div>`);
        });
    }

    function setTab(id) {
        tab = id;
        refresh();
    }

    function money(v) {
        return v == null ? '' : Utils.escapeHtml(Utils.formatCurrency(Number(v)));
    }

    function drawCatalogue() {
        if (!templates.length) {
            $('#templates-body').html('<p class="text-muted">No templates are available to you yet.</p>');
            return;
        }
        $('#templates-body').html(`<div class="row g-3 templates-grid">${templates.map(t => Utils.html`
            <div class="col-12 col-md-6 col-xl-4"><section class="card h-100 template-card" data-template="${t.templateId}">
                <div class="card-body d-flex flex-column">
                    <h2 class="h6 mb-1">${t.name}</h2>
                    <div class="small text-muted mb-2">${t.region || 'Default region'} · ${t.engine === 'TERRAFORM' ? 'Terraform' : 'AWS Service Catalog'}</div>
                    <p class="small flex-grow-1 mb-2">${t.description || ''}</p>
                    <div class="small mb-2">
                        ${Utils.raw(t.estimatedMonthlyCost != null ? `<span class="me-2">~${money(t.estimatedMonthlyCost)}/month</span>` : '')}
                        <span class="me-2">${t.defaultTtlHours} h by default, up to ${t.maxTtlHours} h</span>
                        ${Utils.raw(t.requiresApproval ? '<span class="badge text-bg-warning">Needs approval</span>' : '<span class="badge text-bg-success">No approval needed</span>')}
                    </div>
                    <button type="button" class="btn btn-sm btn-primary align-self-start" data-action="templates-request" data-id="${t.templateId}">Request</button>
                </div></section></div>`).join('')}</div>`);
    }

    function field(p) {
        const id = 'tpl-p-' + p.key;
        const label = Utils.escapeHtml(p.label || p.key) + (p.required ? ' <span class="text-danger" aria-hidden="true">*</span>' : '');
        const def = p.default == null ? '' : String(p.default);
        let control;
        if (p.type === 'enum') {
            control = `<select class="form-select form-select-sm" id="${id}" data-param="${Utils.escapeHtml(p.key)}" ${p.required ? 'required' : ''}>
                ${p.required || def ? '' : '<option value="">(none)</option>'}
                ${(p.options || []).map(o => `<option value="${Utils.escapeHtml(o)}" ${String(o) === def ? 'selected' : ''}>${Utils.escapeHtml(o)}</option>`).join('')}</select>`;
        } else {
            control = `<input class="form-control form-control-sm" id="${id}" data-param="${Utils.escapeHtml(p.key)}"
                type="${p.type === 'number' ? 'number' : 'text'}" ${p.type === 'number' ? 'step="any"' : ''}
                value="${Utils.escapeHtml(def)}" ${p.required ? 'required' : ''}
                ${p.pattern ? `pattern="${Utils.escapeHtml(p.pattern)}" title="Must match ${Utils.escapeHtml(p.pattern)}"` : ''}>`;
        }
        return `<div class="mb-2"><label class="form-label small mb-1" for="${id}">${label}</label>${control}
            <div class="invalid-feedback" data-error="${Utils.escapeHtml(p.key)}"></div></div>`;
    }

    function openRequest(templateId) {
        const t = templates.find(x => x.templateId === templateId);
        if (!t) return;
        const params = Array.isArray(t.parameterSchema) ? t.parameterSchema : [];
        Modals.show({
            id: 'templateRequestModal',
            title: 'Request: ' + t.name,
            body: `<form id="template-request-form" novalidate>
                <div class="mb-2"><label class="form-label small mb-1" for="tpl-name">Environment name <span class="text-danger" aria-hidden="true">*</span></label>
                    <input class="form-control form-control-sm" id="tpl-name" required maxlength="63" pattern="[A-Za-z0-9][A-Za-z0-9 ._-]{1,62}"
                        title="2 to 63 letters, digits, spaces, '.', '_' or '-'">
                    <div class="invalid-feedback" data-error="environmentName"></div></div>
                <div class="mb-2"><label class="form-label small mb-1" for="tpl-ttl">Lifetime (hours, at most ${Number(t.maxTtlHours)})</label>
                    <input class="form-control form-control-sm" id="tpl-ttl" type="number" min="1" max="${Number(t.maxTtlHours)}" value="${Number(t.defaultTtlHours)}">
                    <div class="invalid-feedback" data-error="ttlHours"></div></div>
                ${params.map(field).join('')}
                <div class="mb-2"><label class="form-label small mb-1" for="tpl-why">Why do you need it?</label>
                    <textarea class="form-control form-control-sm" id="tpl-why" rows="2" maxlength="2000"></textarea></div>
                <div class="small text-danger" id="tpl-error" role="alert"></div>
            </form>`,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: t.requiresApproval ? 'Send for approval' : 'Request', class: 'btn-primary', id: 'tpl-submit' }
            ]
        });
        $('#tpl-submit').on('click', () => submit(t, params));
    }

    function showError(key, message) {
        const $f = key ? $(`#template-request-form [data-error="${CSS.escape(key)}"]`) : $();
        if ($f.length) {
            $f.text(message).prev().addClass('is-invalid');
            $f.closest('.mb-2').find('input,select').addClass('is-invalid');
        } else {
            $('#tpl-error').text(message);
        }
    }

    /** Client checks mirror the server's; the server's message wins and is shown at its field. */
    function submit(t, params) {
        $('#template-request-form .is-invalid').removeClass('is-invalid');
        $('#template-request-form .invalid-feedback').text('');
        $('#tpl-error').text('');
        const name = String($('#tpl-name').val() || '').trim();
        let ok = true;
        if (!/^[A-Za-z0-9][A-Za-z0-9 ._-]{1,62}$/.test(name)) {
            showError('environmentName', "2 to 63 letters, digits, spaces, '.', '_' or '-', starting with a letter or digit.");
            ok = false;
        }
        const ttl = Number($('#tpl-ttl').val());
        if (!Number.isInteger(ttl) || ttl < 1 || ttl > t.maxTtlHours) {
            showError('ttlHours', `Between 1 and ${t.maxTtlHours} hours.`);
            ok = false;
        }
        const values = {};
        params.forEach(p => {
            const raw = String($(`#template-request-form [data-param="${CSS.escape(p.key)}"]`).val() || '').trim();
            if (!raw) {
                if (p.required) { showError(p.key, 'Required.'); ok = false; }
                return;
            }
            if (p.type === 'number' && Number.isNaN(Number(raw))) { showError(p.key, 'Must be a number.'); ok = false; return; }
            if (p.pattern) {
                let re = null;
                try { re = new RegExp('^(?:' + p.pattern + ')$'); } catch (e) { re = null; }
                if (re && !re.test(raw)) { showError(p.key, 'Must match ' + p.pattern + '.'); ok = false; return; }
            }
            values[p.key] = p.type === 'number' ? Number(raw) : raw;
        });
        if (!ok) return;
        $('#tpl-submit').prop('disabled', true);
        call('POST', Config.API.provisioning.create(), {
            templateId: t.templateId, environmentName: name, ttlHours: ttl, parameters: values,
            justification: String($('#tpl-why').val() || '').trim() || null
        }).then(r => {
            Modals.hideWhenReady(document.getElementById('templateRequestModal'));
            Notifications.success(r.status === 'APPROVED' ? `'${name}' is approved and will be built shortly.`
                : `Request for '${name}' sent for approval.`);
            setTab('mine');
        }).catch(e => {
            $('#tpl-submit').prop('disabled', false);
            const msg = serverMessage(e, 'The request could not be sent.');
            const named = /Parameter '([^']+)'/.exec(msg);
            showError(named ? named[1] : (/name/i.test(msg) ? 'environmentName' : /ttlHours|lifetime/i.test(msg) ? 'ttlHours' : null), msg);
        });
    }

    function when(ts) {
        return ts ? new Date(ts).toLocaleString([], { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : '';
    }

    function countdown(ts) {
        if (!ts) return '';
        const ms = new Date(ts).getTime() - Date.now();
        if (ms <= 0) return 'due now';
        const h = Math.floor(ms / 3600000);
        return h >= 48 ? `in ${Math.floor(h / 24)} days` : h >= 1 ? `in ${h} h` : `in ${Math.max(1, Math.floor(ms / 60000))} min`;
    }

    function actions(r) {
        const out = [];
        if (r.status === 'READY' && r.environmentId) {
            out.push(Utils.html`<a class="btn btn-sm btn-ghost" href="#/environments/${r.environmentId}">Open</a>`);
            out.push(Utils.html`<button type="button" class="btn btn-sm btn-ghost" data-action="templates-extend" data-id="${r.requestId}">Extend</button>`);
            out.push(Utils.html`<button type="button" class="btn btn-sm btn-ghost text-danger" data-action="templates-destroy" data-id="${r.requestId}">Destroy</button>`);
        }
        if (r.canCancel) {
            out.push(Utils.html`<button type="button" class="btn btn-sm btn-ghost" data-action="templates-cancel" data-id="${r.requestId}">Cancel</button>`);
        }
        return out.join(' ');
    }

    function drawRequests() {
        if (!requests.length) {
            $('#templates-body').html('<p class="text-muted">You have not requested an environment yet.</p>');
            return;
        }
        $('#templates-body').html(`<div class="table-responsive"><table class="table table-baseline table-cards" id="my-requests-table">
            <thead><tr><th>Environment</th><th>Template</th><th>Status</th><th>Requested</th><th>Reviewed by</th><th>Destroyed</th><th></th></tr></thead>
            <tbody>${requests.map(r => {
                const s = STATUS[r.status] || ['text-bg-light border', r.status];
                return Utils.html`<tr data-request="${r.requestId}">
                    <td data-label="Environment"><strong>${r.environmentName}</strong></td>
                    <td data-label="Template">${r.templateName || ''}</td>
                    <td data-label="Status"><span class="badge ${s[0]} request-status">${s[1]}</span>
                        ${Utils.raw(r.errorMessage ? Utils.html`<div class="small text-danger request-error">${r.errorMessage}</div>` : '')}
                        ${Utils.raw(r.status === 'REJECTED' && r.reviewNotes ? Utils.html`<div class="small text-muted">${r.reviewNotes}</div>` : '')}</td>
                    <td data-label="Requested">${when(r.createdAt)}</td>
                    <td data-label="Reviewed by">${r.reviewedByName || ''}</td>
                    <td data-label="Destroyed">${r.destroyAt ? when(r.destroyAt) + ' (' + countdown(r.destroyAt) + ')' : ''}</td>
                    <td>${Utils.raw(actions(r))}</td></tr>`;
            }).join('')}</tbody></table></div>`);
    }

    /** While something is being approved, built or destroyed, refresh every 15 s; stops when the user leaves. */
    function followBusy() {
        if (requests.some(r => BUSY.includes(r.status))) {
            RealTime.startPolling(POLL_KEY, () => {
                if (tab !== 'mine') return;
                call('GET', Config.API.provisioning.mine()).then(d => {
                    requests = (d && d.content) || [];
                    drawRequests();
                    if (!requests.some(r => BUSY.includes(r.status))) RealTime.stopPolling(POLL_KEY);
                }).catch(() => {});
            }, POLL_MS, { pageScoped: true, immediate: false });
        } else {
            RealTime.stopPolling(POLL_KEY);
        }
    }

    function cancel(el) {
        call('POST', Config.API.provisioning.cancel(el.dataset.id)).then(() => refresh())
            .catch(e => Notifications.showError(serverMessage(e, 'Could not cancel the request.')));
    }

    function extend(el) {
        Modals.prompt('Extend the lifetime', 'Extra hours', hours => {
            call('POST', Config.API.provisioning.extend(el.dataset.id), { hours: Number(hours) })
                .then(() => { Notifications.success('Lifetime extended.'); refresh(); })
                .catch(e => Notifications.showError(serverMessage(e, 'Could not extend it.')));
        }, { inputType: 'number', submitText: 'Extend', required: true });
    }

    /** Destroy needs the environment's name typed exactly. */
    function destroy(el) {
        const r = requests.find(x => x.requestId === el.dataset.id);
        if (!r) return;
        Modals.show({
            id: 'templateDestroyModal',
            title: 'Destroy ' + r.environmentName,
            body: `<p>Everything in <strong>${Utils.escapeHtml(r.environmentName)}</strong> is deleted. This cannot be undone.</p>
                <label class="form-label small" for="tpl-destroy-name">Type the environment name to confirm</label>
                <input class="form-control form-control-sm" id="tpl-destroy-name" autocomplete="off">`,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Destroy', class: 'btn-danger', id: 'tpl-destroy-confirm', disabled: true }
            ]
        });
        $('#tpl-destroy-name').on('input', function() {
            $('#tpl-destroy-confirm').prop('disabled', $(this).val() !== r.environmentName);
        });
        $('#tpl-destroy-confirm').on('click', () => {
            if ($('#tpl-destroy-name').val() !== r.environmentName) return;
            call('POST', Config.API.provisioning.destroy(r.requestId), { confirm: true }).then(() => {
                Modals.hideWhenReady(document.getElementById('templateDestroyModal'));
                Notifications.success(`'${r.environmentName}' is being destroyed.`);
                refresh();
            }).catch(e => Notifications.showError(serverMessage(e, 'Could not destroy it.')));
        });
    }

    return { load, probeNav, setTab, openRequest, cancel, extend, destroy, refresh };
})();

window.Templates = Templates;

Actions.registerAll({
    'templates-tab': el => Templates.setTab(el.dataset.tab),
    'templates-request': el => Templates.openRequest(el.dataset.id),
    'templates-cancel': el => Templates.cancel(el),
    'templates-extend': el => Templates.extend(el),
    'templates-destroy': el => Templates.destroy(el)
});
