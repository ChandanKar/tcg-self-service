/**
 * Webhooks on the environment detail page (E34-T07): those who administer the environment add,
 * edit, disable and delete webhooks, choose their events, send a test ping and see recent
 * deliveries. The signing secret is shown once, when the webhook is created; URLs come back as
 * scheme://host only. Renders nothing while webhooks are off or for others.
 */
const WebhooksPanel = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    const EVENTS = [
        ['operation.completed', 'Operation completed'], ['operation.failed', 'Operation failed'],
        ['environment.started', 'Environment started'], ['environment.stopped', 'Environment stopped'],
        ['lease.expiring', 'Lease expiring'], ['lease.expired', 'Lease expired'],
        ['access.granted', 'Access granted'], ['access.revoked', 'Access revoked'],
        ['idle.stopped', 'Stopped as idle'], ['budget.threshold', 'Budget threshold'],
        ['provisioning.ready', 'Provisioned environment ready'], ['provisioning.destroyed', 'Provisioned environment destroyed']
    ];
    let current = null; // { env, hooks, results }
    let renderToken = 0;

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data || null, QUIET).done(resolve).fail(reject);
        });
    }

    function message(e, fallback) {
        return (e && e.responseJSON && e.responseJSON.message) || fallback;
    }

    function renderEnvPanel(env) {
        const $panel = $('#env-webhook-panel');
        if (!$panel.length || !env) return;
        const token = ++renderToken;
        call('GET', Config.API.webhooks.list(env.environmentId)).then(hooks => {
            if (token !== renderToken || !$('#env-webhook-panel').length) return;
            current = { env, hooks: hooks || [], results: {} };
            draw();
        }).catch(() => { if (token === renderToken) $('#env-webhook-panel').empty(); });
    }

    function reload() {
        if (current) renderEnvPanel(current.env);
    }

    function lastDelivery(h) {
        const d = h.lastDelivery;
        if (!d) return '<span class="text-muted">No deliveries yet</span>';
        const cls = d.status === 'DELIVERED' ? 'text-bg-success' : d.status === 'PENDING' ? 'text-bg-warning' : 'text-bg-danger';
        return Utils.html`<span class="badge ${cls}">${d.status.toLowerCase().replace('_', ' ')}</span>
            <span class="text-muted">${new Date(d.at).toLocaleString()}${d.responseCode ? ' · HTTP ' + d.responseCode : ''}</span>`;
    }

    function draw() {
        const c = current;
        const rows = c.hooks.length ? c.hooks.map(h => {
            const result = c.results[h.id];
            const resultHtml = !result ? '' : result.delivered
                ? '<span class="badge text-bg-success">Ping delivered</span>'
                : Utils.html`<span class="text-danger small">Ping not delivered: ${result.error || ('HTTP ' + result.status)}</span>`;
            return Utils.html`<li class="webhook d-flex justify-content-between align-items-start gap-2 flex-wrap" data-webhook="${h.id}">
                <div class="min-w-0">
                    <div class="fw-semibold">${h.label}
                        ${Utils.raw(h.active ? '' : '<span class="badge text-bg-secondary ms-1">Disabled</span>')}</div>
                    <div class="small text-muted">${h.urlHost} · ${h.eventTypes.join(', ')}</div>
                    ${Utils.raw(h.disabledReason ? Utils.html`<div class="small text-danger">${h.disabledReason}</div>` : '')}
                    <div class="small webhook-last">${Utils.raw(lastDelivery(h))}</div>
                    <div class="webhook-test-result" aria-live="polite">${Utils.raw(resultHtml)}</div>
                </div>
                <div class="d-flex gap-1 flex-wrap">
                    <button type="button" class="btn btn-sm btn-ghost" data-action="webhook-test" data-id="${h.id}">
                        <i class="fas fa-paper-plane" aria-hidden="true"></i> Test</button>
                    <button type="button" class="btn btn-sm btn-ghost" data-action="webhook-deliveries" data-id="${h.id}">Deliveries</button>
                    <button type="button" class="btn btn-sm btn-ghost" data-action="webhook-toggle" data-id="${h.id}">${h.active ? 'Disable' : 'Enable'}</button>
                    <button type="button" class="btn btn-sm btn-ghost" data-action="webhook-edit" data-id="${h.id}"
                        aria-label="Edit ${h.label}"><i class="fas fa-pen" aria-hidden="true"></i></button>
                    <button type="button" class="btn btn-sm btn-ghost" data-action="webhook-delete" data-id="${h.id}"
                        aria-label="Delete ${h.label}"><i class="fas fa-trash" aria-hidden="true"></i></button>
                </div></li>`;
        }).join('') : '<li class="text-muted small">No webhooks yet. Add one to send this environment\'s events to your own systems.</li>';
        $('#env-webhook-panel').html(`<section class="card mb-3 webhooks-card" aria-labelledby="webhooks-title"><div class="card-body">
            <div class="d-flex justify-content-between align-items-center flex-wrap gap-2 mb-2">
                <h2 class="h6 mb-0" id="webhooks-title"><i class="fas fa-satellite-dish me-1" aria-hidden="true"></i>Webhooks</h2>
                <button type="button" class="btn btn-sm btn-tonal btn-primary" data-action="webhook-add"><i class="fas fa-plus" aria-hidden="true"></i> Add webhook</button>
            </div>
            <ul class="list-unstyled mb-0 webhook-list">${rows}</ul></div></section>`);
    }

    function find(id) {
        return current && current.hooks.find(h => h.id === id);
    }

    function openForm(id) {
        const h = id ? find(id) : null;
        const chosen = h ? h.eventTypes : ['operation.failed', 'environment.stopped'];
        Modals.show({
            id: 'webhookModal',
            title: h ? 'Edit webhook' : 'Add webhook',
            body: `<form id="webhook-form" novalidate>
                <div class="mb-2"><label class="form-label small mb-1" for="webhook-label">Label</label>
                    <input class="form-control form-control-sm" id="webhook-label" maxlength="100" value="${Utils.escapeHtml(h ? h.label : '')}"></div>
                <div class="mb-2"><label class="form-label small mb-1" for="webhook-url">URL (https)</label>
                    <input class="form-control form-control-sm" id="webhook-url" type="url" placeholder="${h ? 'Leave empty to keep ' + Utils.escapeHtml(h.urlHost) : 'https://'}">
                    <div class="form-text">Must be https and reachable on the internet; private addresses are refused.</div></div>
                <fieldset class="mb-2"><legend class="form-label small mb-1">Events</legend>
                    ${EVENTS.map(([value, label]) => Utils.html`<div class="form-check form-check-inline">
                        <input class="form-check-input webhook-event" type="checkbox" id="wh-ev-${value.replace('.', '-')}" value="${value}" ${chosen.includes(value) ? 'checked' : ''}>
                        <label class="form-check-label small" for="wh-ev-${value.replace('.', '-')}">${label}</label></div>`).join('')}
                </fieldset>
                <div class="small text-danger" id="webhook-error" role="alert"></div>
            </form>`,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: h ? 'Save' : 'Add', class: 'btn-primary', id: 'webhook-save' }
            ]
        });
        $('#webhook-save').on('click', () => save(h));
    }

    function save(h) {
        const body = {
            label: String($('#webhook-label').val() || '').trim(),
            url: String($('#webhook-url').val() || '').trim(),
            eventTypes: $('#webhook-form .webhook-event:checked').map(function() { return this.value; }).get()
        };
        if (!body.label) { $('#webhook-error').text('Give the webhook a label.'); return; }
        if (!h && !body.url) { $('#webhook-error').text('Enter the URL.'); return; }
        if (!body.eventTypes.length) { $('#webhook-error').text('Choose at least one event.'); return; }
        $('#webhook-save').prop('disabled', true);
        const request = h
            ? call('PUT', Config.API.webhooks.item(h.id), { label: body.label, url: body.url || null, eventTypes: body.eventTypes })
            : call('POST', Config.API.webhooks.create, Object.assign(body, { environmentId: current.env.environmentId }));
        request.then(result => {
            Modals.hideWhenReady(document.getElementById('webhookModal'));
            if (!h && result && result.secret) revealSecret(result.secret);
            reload();
        }).catch(e => {
            $('#webhook-save').prop('disabled', false);
            $('#webhook-error').text(message(e, 'The webhook could not be saved.'));
        });
    }

    /** The signing secret, once; the dialog and the secret leave the page when it closes. */
    function revealSecret(secret) {
        Modals.show({
            id: 'webhookSecretModal',
            title: 'Webhook signing secret',
            body: `<div class="alert alert-warning small" role="alert">Store it with the receiver now. You will not see this again.
                    Each request carries <code>X-VmControl-Signature: t=&lt;time&gt;,v1=&lt;HMAC-SHA256&gt;</code>.</div>
                <input class="form-control form-control-sm font-monospace" id="webhook-secret-value" readonly aria-label="Signing secret">`,
            buttons: [{ text: 'Done', class: 'btn-primary', dismiss: true }]
        });
        $('#webhook-secret-value').val(secret);
        const modal = document.getElementById('webhookSecretModal');
        if (modal) modal.addEventListener('hide.bs.modal', () => $('#webhook-secret-value').val(''), { once: true });
    }

    function test(id, el) {
        $(el).prop('disabled', true);
        call('POST', Config.API.webhooks.test(id)).then(r => {
            if (current) current.results[id] = r;
            draw();
        }).catch(e => Notifications.showError(message(e, 'The ping could not be sent.')))
            .finally(() => $(el).prop('disabled', false));
    }

    function toggle(id) {
        const h = find(id);
        if (!h) return;
        call('PUT', Config.API.webhooks.item(id), { active: !h.active }).then(() => reload())
            .catch(e => Notifications.showError(message(e, 'Could not change the webhook.')));
    }

    function remove(id) {
        const h = find(id);
        if (!h) return;
        Modals.confirm('Delete webhook', `Delete '${h.label}'? Its queued deliveries are dropped.`, () => {
            call('DELETE', Config.API.webhooks.item(id)).then(() => reload())
                .catch(e => Notifications.showError(message(e, 'Could not delete the webhook.')));
        }, { confirmText: 'Delete', confirmClass: 'btn-danger' });
    }

    function deliveries(id) {
        const h = find(id);
        if (!h) return;
        call('GET', Config.API.webhooks.deliveries(id)).then(page => {
            const items = (page && page.content) || [];
            const rows = items.map(d => Utils.html`<tr>
                    <td class="small">${new Date(d.createdAt).toLocaleString()}</td>
                    <td class="small"><code>${d.eventType}</code></td>
                    <td class="small">${d.status.toLowerCase().replace('_', ' ')}</td>
                    <td class="small">${d.attempt}</td>
                    <td class="small">${d.responseCode || ''} ${d.error || ''}</td>
                </tr>`).join('');
            Modals.show({
                id: 'webhookDeliveriesModal',
                size: 'lg',
                title: 'Deliveries: ' + h.label,
                body: rows ? `<div class="table-responsive"><table class="table table-sm" id="webhook-deliveries">
                    <thead><tr><th>Queued</th><th>Event</th><th>Status</th><th>Attempts</th><th>Response</th></tr></thead>
                    <tbody>${rows}</tbody></table></div>` : '<p class="text-muted small mb-0">No deliveries yet.</p>',
                buttons: [{ text: 'Close', class: 'btn-secondary', dismiss: true }]
            });
        }).catch(e => Notifications.showError(message(e, "Couldn't load the deliveries.")));
    }

    return { renderEnvPanel, openForm, test, toggle, remove, deliveries };
})();

window.WebhooksPanel = WebhooksPanel;

Actions.registerAll({
    'webhook-add': () => WebhooksPanel.openForm(null),
    'webhook-edit': el => WebhooksPanel.openForm(el.dataset.id),
    'webhook-test': el => WebhooksPanel.test(el.dataset.id, el),
    'webhook-toggle': el => WebhooksPanel.toggle(el.dataset.id),
    'webhook-delete': el => WebhooksPanel.remove(el.dataset.id),
    'webhook-deliveries': el => WebhooksPanel.deliveries(el.dataset.id)
});
