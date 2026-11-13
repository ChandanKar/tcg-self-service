/**
 * Webhooks (E34-T07, U14-T05 ApiTabs.dc.html). One module, two places:
 *   renderEnvPanel(env)   - the environment settings' Webhooks section: that environment's webhooks;
 *                           those who administer it add, edit, disable, delete, test and see deliveries.
 *   mountAll(container)   - API access › Webhooks, admins only: every webhook with its scope ("All
 *                           environments" or "Payments Dev only"), and for the chosen one its events,
 *                           Send test, Edit and the recent deliveries ("Delivered", "Failed, retrying"
 *                           with the next attempt, "Waiting", "Gave up"), then the signing and retry rule.
 * The signing secret is shown once, when the webhook is created; URLs come back as scheme://host only.
 * Renders nothing (env) or says so (admin) while webhooks are off, without asking the server.
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
    const STATUS = { DELIVERED: ['Delivered', 'ok'], FAILED: ['Failed, retrying', 'bad'], PENDING: ['Waiting', 'wait'], GAVE_UP: ['Gave up', 'off'] };
    const POLICY = 'Each delivery is signed (X-VmControl-Signature). Failures retry after 1 min, 5 min, 30 min, 2 h and 12 h; '
        + 'after 50 failed deliveries in a row the webhook switches itself off.';

    let current = null; // { mode: 'env'|'all', env, container, hooks, results, selected, deliveries, envs }
    let renderToken = 0;

    // ---- pure model (node tests: src/test/js/webhooks.test.mjs) ----

    function hhmm(d) {
        return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
    }

    /** "17:45" today, "yesterday", "Fri" this week, else "12 Oct". */
    function when(ts, now = new Date()) {
        const d = new Date(ts);
        const days = Math.round((new Date(now.getFullYear(), now.getMonth(), now.getDate())
            - new Date(d.getFullYear(), d.getMonth(), d.getDate())) / 86400000);
        if (days <= 0) return hhmm(d);
        if (days === 1) return 'yesterday';
        if (days < 7) return d.toLocaleDateString('en-GB', { weekday: 'short' });
        return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
    }

    /** {label, kind} for a delivery status. */
    function statusOf(status) {
        const s = STATUS[status] || [String(status || '').toLowerCase().replace('_', ' '), 'off'];
        return { label: s[0], kind: s[1] };
    }

    /** "200 · attempt 1", "503 · attempt 3, next at 09:11", "timed out · 6 attempts". */
    function detail(d) {
        const answer = d.responseCode ? String(d.responseCode) : (d.error || 'no answer');
        const n = Number(d.attempt) || 0;
        if (d.status === 'GAVE_UP') return `${answer} · ${n} attempt${n === 1 ? '' : 's'}`;
        if (d.status === 'FAILED' || d.status === 'PENDING') {
            const next = d.nextAttemptAt ? `, next at ${hhmm(new Date(d.nextAttemptAt))}` : '';
            return d.status === 'PENDING' && !d.responseCode && !d.error ? `attempt ${n + 1}${next}` : `${answer} · attempt ${n}${next}`;
        }
        return `${answer} · attempt ${n}`;
    }

    /** "All environments" or "Payments Dev only". */
    function scope(h, envNames) {
        if (!h.environmentId) return 'All environments';
        return `${(envNames && envNames[h.environmentId]) || 'One environment'} only`;
    }

    // ---- shared ----

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data || null, QUIET).done(resolve).fail(reject);
        });
    }

    function message(e, fallback) {
        return (e && e.responseJSON && e.responseJSON.message) || fallback;
    }

    function enabled() {
        return typeof Capabilities === 'undefined' || Capabilities.has('webhooks');
    }

    function $box() {
        return current ? $(current.container) : $();
    }

    function reload() {
        if (!current) return;
        if (current.mode === 'env') renderEnvPanel(current.env);
        else loadAll(current.container, current.selected);
    }

    function find(id) {
        return current && current.hooks.find(h => h.id === id);
    }

    // ---- the environment's panel ----

    function renderEnvPanel(env) {
        const $panel = $('#env-webhook-panel');
        if (!$panel.length || !env) return;
        const token = ++renderToken;
        call('GET', Config.API.webhooks.list(env.environmentId)).then(hooks => {
            if (token !== renderToken || !$('#env-webhook-panel').length) return;
            current = { mode: 'env', env, container: '#env-webhook-panel', hooks: hooks || [], results: {} };
            drawEnv();
        }).catch(() => { if (token === renderToken) $('#env-webhook-panel').empty(); });
    }

    function lastDelivery(h) {
        const d = h.lastDelivery;
        if (!d) return '<span class="text-muted">No deliveries yet</span>';
        const st = statusOf(d.status);
        return Utils.html`<span class="wh-tag wh-tag-${st.kind}">${st.label}</span>
            <span class="text-muted">${new Date(d.at).toLocaleString()}${d.responseCode ? ' · HTTP ' + d.responseCode : ''}</span>`;
    }

    function testResult(h) {
        const result = current.results[h.id];
        return !result ? '' : result.delivered
            ? '<span class="wh-tag wh-tag-ok">Ping delivered</span>'
            : Utils.html`<span class="text-danger small">Ping not delivered: ${result.error || ('HTTP ' + result.status)}</span>`;
    }

    function drawEnv() {
        const c = current;
        const rows = c.hooks.length ? c.hooks.map(h => Utils.html`<li class="webhook d-flex justify-content-between align-items-start gap-2 flex-wrap" data-webhook="${h.id}">
                <div class="min-w-0">
                    <div class="fw-semibold">${h.label}
                        ${Utils.raw(h.active ? '' : '<span class="badge text-bg-secondary ms-1">Disabled</span>')}</div>
                    <div class="small text-muted">${h.urlHost} · ${h.eventTypes.join(', ')}</div>
                    ${Utils.raw(h.disabledReason ? Utils.html`<div class="small text-danger">${h.disabledReason}</div>` : '')}
                    <div class="small webhook-last">${Utils.raw(lastDelivery(h))}</div>
                    <div class="webhook-test-result" aria-live="polite">${Utils.raw(testResult(h))}</div>
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
                </div></li>`).join('')
            : '<li class="text-muted small">No webhooks yet. Add one to send this environment\'s events to your own systems.</li>';
        $('#env-webhook-panel').html(`<section class="card mb-3 webhooks-card" aria-labelledby="webhooks-title"><div class="card-body">
            <div class="d-flex justify-content-between align-items-center flex-wrap gap-2 mb-2">
                <h2 class="h6 mb-0" id="webhooks-title"><i class="fas fa-satellite-dish me-1" aria-hidden="true"></i>Webhooks</h2>
                <button type="button" class="btn btn-sm btn-tonal btn-primary" data-action="webhook-add"><i class="fas fa-plus" aria-hidden="true"></i> Add webhook</button>
            </div>
            <ul class="list-unstyled mb-0 webhook-list">${rows}</ul></div></section>`);
    }

    // ---- API access › Webhooks: every webhook ----

    function mountAll(container) {
        if (!enabled()) {
            $(container).html('<p class="wh-note" id="wh-off">Webhooks are switched off. An administrator can switch them on in the server configuration (WEBHOOKS_ENABLED, with CHAT_SECRET_KEY set to keep their secrets; needs a restart).</p>');
            return;
        }
        loadAll(container, null);
    }

    function loadAll(container, selected) {
        const token = ++renderToken;
        if (!$(container).find('.wh-all').length) $(container).html('<p class="wh-note">Loading webhooks…</p>');
        Promise.all([call('GET', Config.API.webhooks.list(null)), call('GET', Config.API.environments.list).catch(() => [])]).then(([hooks, envs]) => {
            if (token !== renderToken || !$(container).length) return;
            const envNames = {};
            (Array.isArray(envs) ? envs : []).forEach(e => { envNames[e.environmentId] = e.displayName || e.name; });
            const list = hooks || [];
            current = { mode: 'all', container, hooks: list, results: {}, envs: envNames, deliveries: null,
                selected: list.some(h => h.id === selected) ? selected : (list[0] ? list[0].id : null) };
            drawAll();
            if (current.selected) loadDeliveries(current.selected);
        }).catch(e => {
            if (token === renderToken) $(container).html(Utils.html`<p class="wh-note" role="alert">${message(e, "Couldn't load the webhooks.")}</p>`);
        });
    }

    function drawAll() {
        const c = current;
        const list = c.hooks.map(h => Utils.html`<button type="button" class="wh-item${Utils.raw(h.id === c.selected ? ' wh-item-on' : '')}"
                data-wh-select="${h.id}" aria-pressed="${h.id === c.selected}">
                <span class="wh-item-name">${h.label}</span>
                <span class="wh-item-sub"><span class="wh-mono">${h.urlHost}</span> · ${scope(h, c.envs)}</span>
                <span class="wh-item-state">${h.active ? 'Active' : 'Switched off'}</span></button>`).join('');
        const h = find(c.selected);
        const card = h ? Utils.html`<section class="wh-card" id="wh-detail" aria-labelledby="wh-detail-title" data-webhook="${h.id}">
                <div class="wh-card-head"><div><h2 id="wh-detail-title">Webhook: ${h.label}</h2>
                    <div class="wh-sub"><span class="wh-mono">${h.urlHost}</span> · ${scope(h, c.envs)}${h.active ? '' : ' · switched off'}</div>
                    ${Utils.raw(h.disabledReason ? Utils.html`<div class="wh-sub wh-bad-text">${h.disabledReason}</div>` : '')}</div>
                    <span class="wh-actions">
                        <button type="button" class="wh-link" data-action="webhook-test" data-id="${h.id}">Send test</button>
                        <button type="button" class="wh-link" data-action="webhook-edit" data-id="${h.id}">Edit</button>
                        <button type="button" class="wh-link" data-action="webhook-toggle" data-id="${h.id}">${h.active ? 'Switch off' : 'Switch on'}</button>
                        <button type="button" class="wh-link wh-danger" data-action="webhook-delete" data-id="${h.id}" aria-label="Delete ${h.label}">Delete</button></span></div>
                <div class="webhook-test-result" aria-live="polite">${Utils.raw(testResult(h))}</div>
                <div class="wh-chips">${Utils.raw(h.eventTypes.map(e => Utils.html`<span class="wh-chip wh-mono">${e}</span>`).join(''))}</div>
                <div class="wh-label">Recent deliveries</div>
                <div id="wh-deliveries">${Utils.raw(deliveriesHtml())}</div>
                <p class="wh-note">${POLICY}</p>
            </section>` : '';
        $box().html(`<div class="wh-all">
                <div class="wh-all-head"><p class="wh-note">Webhooks tell other systems what happened. Those on one environment are also set in its settings.</p>
                    <button type="button" class="btn btn-secondary btn-sm" data-action="webhook-add">Add webhook</button></div>
                ${c.hooks.length ? `<div class="wh-layout"><div class="wh-list" role="group" aria-label="Webhooks">${list}</div>${card}</div>`
                    : '<p class="wh-note">No webhooks yet.</p>'}
            </div>`);
        $box().off('.wh').on('click.wh', '[data-wh-select]', function() {
            current.selected = this.dataset.whSelect;
            current.deliveries = null;
            drawAll();
            loadDeliveries(current.selected);
        });
    }

    function deliveriesHtml() {
        const c = current;
        if (c.deliveries === null) return '<p class="wh-note">Loading…</p>';
        if (c.deliveries.error) return Utils.html`<p class="wh-note" role="alert">${c.deliveries.error}</p>`;
        if (!c.deliveries.items.length) return '<p class="wh-note">No deliveries yet. Send test to try the receiver.</p>';
        const now = new Date();
        return `<div class="wh-scroll"><table class="wh-table" id="wh-delivery-table"><tbody>${c.deliveries.items.map(d => {
            const st = statusOf(d.status);
            return Utils.html`<tr><td class="wh-mono">${d.eventType}</td><td>${when(d.createdAt, now)}</td>
                <td><span class="wh-tag wh-tag-${st.kind}">${st.label}</span></td><td class="wh-sub">${detail(d)}</td></tr>`;
        }).join('')}</tbody></table></div>`;
    }

    function loadDeliveries(id) {
        const token = renderToken;
        call('GET', Config.API.webhooks.deliveries(id)).then(page => {
            if (token !== renderToken || !current || current.selected !== id) return;
            current.deliveries = { items: ((page && page.content) || []).slice(0, 8) };
            $('#wh-deliveries').html(deliveriesHtml());
        }).catch(e => {
            if (token !== renderToken || !current || current.selected !== id) return;
            current.deliveries = { error: message(e, "Couldn't load the deliveries.") };
            $('#wh-deliveries').html(deliveriesHtml());
        });
    }

    // ---- shared actions ----

    function openForm(id) {
        const h = id ? find(id) : null;
        const chosen = h ? h.eventTypes : ['operation.failed', 'environment.stopped'];
        const all = current && current.mode === 'all';
        const envOptions = all && !h ? `<div class="mb-2"><label class="form-label small mb-1" for="webhook-env">Environments</label>
                <select class="form-select form-select-sm" id="webhook-env"><option value="">All environments</option>${Object.entries(current.envs || {})
                    .sort((a, b) => a[1].localeCompare(b[1])).map(([envId, name]) => Utils.html`<option value="${envId}">${name} only</option>`).join('')}</select></div>` : '';
        Modals.show({
            id: 'webhookModal',
            title: h ? 'Edit webhook' : 'Add webhook',
            body: `<form id="webhook-form" novalidate>
                <div class="mb-2"><label class="form-label small mb-1" for="webhook-label">Label</label>
                    <input class="form-control form-control-sm" id="webhook-label" maxlength="100" value="${Utils.escapeHtml(h ? h.label : '')}"></div>
                <div class="mb-2"><label class="form-label small mb-1" for="webhook-url">URL (https)</label>
                    <input class="form-control form-control-sm" id="webhook-url" type="url" placeholder="${h ? 'Leave empty to keep ' + Utils.escapeHtml(h.urlHost) : 'https://'}">
                    <div class="form-text">Must be https and reachable on the internet; private addresses are refused.</div></div>
                ${envOptions}
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
        const environmentId = current.mode === 'env' ? current.env.environmentId : ($('#webhook-env').val() || null);
        const request = h
            ? call('PUT', Config.API.webhooks.item(h.id), { label: body.label, url: body.url || null, eventTypes: body.eventTypes })
            : call('POST', Config.API.webhooks.create, Object.assign(body, { environmentId }));
        request.then(result => {
            Modals.hideWhenReady(document.getElementById('webhookModal'));
            if (!h && result && result.secret) revealSecret(result.secret);
            if (!h && result && result.webhook && current.mode === 'all') current.selected = result.webhook.id;
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
            if (!current) return;
            current.results[id] = r;
            if (current.mode === 'env') {
                drawEnv();
            } else {
                $('#wh-detail .webhook-test-result').html(testResult(find(id) || { id }));
                Notifications.show(r.delivered ? 'Test delivered.' : `Test not delivered: ${r.error || ('HTTP ' + r.status)}`, r.delivered ? 'success' : 'error');
                loadDeliveries(id);
            }
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

    /** The environment panel's Deliveries dialog. */
    function deliveries(id) {
        const h = find(id);
        if (!h) return;
        call('GET', Config.API.webhooks.deliveries(id)).then(page => {
            const items = (page && page.content) || [];
            const rows = items.map(d => {
                const st = statusOf(d.status);
                return Utils.html`<tr>
                    <td class="small">${new Date(d.createdAt).toLocaleString()}</td>
                    <td class="small"><code>${d.eventType}</code></td>
                    <td class="small"><span class="wh-tag wh-tag-${st.kind}">${st.label}</span></td>
                    <td class="small">${detail(d)}</td>
                </tr>`;
            }).join('');
            Modals.show({
                id: 'webhookDeliveriesModal',
                size: 'lg',
                title: 'Deliveries: ' + h.label,
                body: rows ? `<div class="table-responsive"><table class="table table-sm" id="webhook-deliveries">
                    <thead><tr><th>Queued</th><th>Event</th><th>Status</th><th>Detail</th></tr></thead>
                    <tbody>${rows}</tbody></table></div><p class="small text-muted mb-0">${POLICY}</p>` : '<p class="text-muted small mb-0">No deliveries yet.</p>',
                buttons: [{ text: 'Close', class: 'btn-secondary', dismiss: true }]
            });
        }).catch(e => Notifications.showError(message(e, "Couldn't load the deliveries.")));
    }

    return { renderEnvPanel, mountAll, openForm, test, toggle, remove, deliveries, model: { when, statusOf, detail, scope } };
})();

if (typeof window !== 'undefined') window.WebhooksPanel = WebhooksPanel;

if (typeof Actions !== 'undefined') Actions.registerAll({
    'webhook-add': () => WebhooksPanel.openForm(null),
    'webhook-edit': el => WebhooksPanel.openForm(el.dataset.id),
    'webhook-test': el => WebhooksPanel.test(el.dataset.id, el),
    'webhook-toggle': el => WebhooksPanel.toggle(el.dataset.id),
    'webhook-delete': el => WebhooksPanel.remove(el.dataset.id),
    'webhook-deliveries': el => WebhooksPanel.deliveries(el.dataset.id)
});
