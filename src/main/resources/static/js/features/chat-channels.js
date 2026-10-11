/**
 * Microsoft Teams channels on the environment detail page (E24-T02): those who administer the
 * environment add, edit and remove channels, choose which events each receives, and send a test
 * message. Webhook URLs go to the server once and never come back (only a masked host).
 */
const ChatChannels = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    const DEFAULT_EVENTS = ['LEASE_EXPIRING', 'IDLE_AUTO_STOPPED', 'BUDGET_THRESHOLD', 'COST_ANOMALY',
        'ACCESS_REQUESTED', 'OPERATION_FAILED'];
    let current = null; // { env, channels, options, results }
    let renderToken = 0;

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data, QUIET).done(resolve).fail(reject);
        });
    }

    function serverMessage(error, fallback) {
        return (error && error.responseJSON && error.responseJSON.message) || fallback;
    }

    function eventLabel(name) {
        const s = String(name).toLowerCase().replace(/_/g, ' ');
        return s.charAt(0).toUpperCase() + s.slice(1);
    }

    /** Renders into #env-chat-panel; nothing for those who cannot administer the environment. */
    function renderEnvPanel(env) {
        const $panel = $('#env-chat-panel');
        if (!$panel.length || !env) return;
        const token = ++renderToken;
        Promise.all([call('GET', Config.API.chatChannels.list(env.environmentId)), call('GET', Config.API.chatChannels.options)])
            .then(([channels, options]) => {
                if (token !== renderToken || !$('#env-chat-panel').length) return;
                current = { env, channels: channels || [], options: options || { eventTypes: [] }, results: {} };
                draw();
            })
            .catch(() => { if (token === renderToken) $('#env-chat-panel').empty(); });
    }

    function reload() {
        if (current) renderEnvPanel(current.env);
    }

    function draw() {
        const c = current;
        const rows = c.channels.length ? c.channels.map(ch => {
            const result = c.results[ch.channelId];
            const resultHtml = !result ? '' : result.delivered
                ? '<span class="badge text-bg-success">Delivered</span>'
                : Utils.html`<span class="text-danger small">Not delivered: ${result.error || 'unknown error'}</span>`;
            return Utils.html`<li class="chat-channel d-flex justify-content-between align-items-start gap-2 flex-wrap" data-channel="${ch.channelId}">
                <div class="min-w-0">
                    <div class="fw-semibold">${ch.label}
                        ${Utils.raw(ch.active ? '' : '<span class="badge text-bg-secondary ms-1">Off</span>')}</div>
                    <div class="small text-muted">${ch.maskedHost} · ${ch.eventTypes.length} event${ch.eventTypes.length === 1 ? '' : 's'}:
                        ${ch.eventTypes.map(eventLabel).join(', ')}</div>
                    <div class="chat-test-result" aria-live="polite">${Utils.raw(resultHtml)}</div>
                </div>
                <div class="d-flex gap-1">
                    <button type="button" class="btn btn-sm btn-ghost" data-action="chat-test" data-id="${ch.channelId}">
                        <i class="fas fa-paper-plane" aria-hidden="true"></i> Send test</button>
                    <button type="button" class="btn btn-sm btn-ghost" data-action="chat-edit" data-id="${ch.channelId}"
                        aria-label="Edit ${ch.label}"><i class="fas fa-pen" aria-hidden="true"></i></button>
                    <button type="button" class="btn btn-sm btn-ghost" data-action="chat-remove" data-id="${ch.channelId}"
                        aria-label="Remove ${ch.label}"><i class="fas fa-trash" aria-hidden="true"></i></button>
                </div></li>`;
        }).join('') : '<li class="text-muted small">No channels yet. Add a Teams channel to get this environment\'s alerts in chat.</li>';
        const off = c.options.enabled ? '' : `<div class="small text-muted mb-2"><i class="fas fa-info-circle me-1" aria-hidden="true"></i>
            Chat notifications are turned off on the server (CHAT_ENABLED). Channels are kept and start receiving messages once it is on.</div>`;
        $('#env-chat-panel').html(`<section class="card mb-3 chat-channels-card" aria-labelledby="chat-channels-title"><div class="card-body">
            <div class="d-flex justify-content-between align-items-center flex-wrap gap-2 mb-2">
                <h2 class="h6 mb-0" id="chat-channels-title"><i class="fas fa-comments me-1" aria-hidden="true"></i>Teams channels</h2>
                <button type="button" class="btn btn-sm btn-tonal btn-primary" data-action="chat-add"><i class="fas fa-plus" aria-hidden="true"></i> Add channel</button>
            </div>${off}
            <ul class="list-unstyled mb-0 chat-channel-list">${rows}</ul></div></section>`);
    }

    function openForm(channelId) {
        if (!current) return;
        const ch = channelId ? current.channels.find(x => x.channelId === channelId) : null;
        const chosen = new Set(ch ? ch.eventTypes : DEFAULT_EVENTS);
        const boxes = (current.options.eventTypes || []).map((e, i) => Utils.html`<div class="form-check">
            <input class="form-check-input chat-event" type="checkbox" id="chat-ev-${i}" value="${e}" ${chosen.has(e) ? 'checked' : ''}>
            <label class="form-check-label" for="chat-ev-${i}">${eventLabel(e)}</label></div>`).join('');
        Modals.show({
            id: 'chatChannelModal',
            title: ch ? `Edit channel: ${ch.label}` : 'Add Teams channel',
            body: Utils.html`
                <div class="mb-3"><label class="form-label" for="chat-label">Name</label>
                    <input type="text" class="form-control" id="chat-label" maxlength="100" value="${ch ? ch.label : ''}" placeholder="e.g. Platform team" required></div>
                <div class="mb-3"><label class="form-label" for="chat-url">Webhook URL</label>
                    <input type="url" class="form-control" id="chat-url" autocomplete="off" spellcheck="false"
                        placeholder="${ch ? 'Leave empty to keep the current webhook' : 'https://…logic.azure.com/workflows/…'}" ${ch ? '' : 'required'}>
                    <div class="form-text">From a Teams Workflows "post to a channel when a webhook request is received" flow. Stored encrypted; it is not shown again.</div></div>
                ${Utils.raw(ch ? Utils.html`<div class="form-check form-switch mb-3"><input class="form-check-input" type="checkbox" id="chat-active" ${ch.active ? 'checked' : ''}>
                    <label class="form-check-label" for="chat-active">Send messages to this channel</label></div>` : '')}
                <fieldset><legend class="form-label fs-6">Events</legend>
                    <div class="chat-event-grid">${Utils.raw(boxes)}</div></fieldset>`,
            size: 'lg',
            buttons: [{ text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: ch ? 'Save' : 'Add channel', class: 'btn-primary', id: 'chat-save-btn' }],
            onShow: () => $('#chat-label').trigger('focus')
        });
        $('#chat-save-btn').on('click', function() {
            const body = {
                environmentId: current.env.environmentId,
                provider: 'TEAMS',
                label: $('#chat-label').val().trim(),
                webhookUrl: $('#chat-url').val().trim() || null,
                eventTypes: $('#chatChannelModal .chat-event:checked').map(function() { return this.value; }).get()
            };
            if (ch) body.active = $('#chat-active').is(':checked');
            if (!body.label) { Notifications.showError('A name is required.'); return; }
            if (!ch && !body.webhookUrl) { Notifications.showError('The webhook URL is required.'); return; }
            if (!body.eventTypes.length) { Notifications.showError('Choose at least one event.'); return; }
            const $btn = $(this).prop('disabled', true);
            (ch ? call('PUT', Config.API.chatChannels.item(ch.channelId), body) : call('POST', Config.API.chatChannels.create, body))
                .then(() => {
                    bootstrap.Modal.getInstance(document.getElementById('chatChannelModal')).hide();
                    Notifications.success(ch ? 'Channel saved.' : 'Channel added.');
                    reload();
                })
                .catch(e => { Notifications.showError(serverMessage(e, 'Could not save the channel.')); $btn.prop('disabled', false); });
        });
    }

    function test(channelId, el) {
        const $btn = $(el).prop('disabled', true);
        call('POST', Config.API.chatChannels.test(channelId))
            .then(result => {
                if (!current) return;
                current.results[channelId] = result;
                draw();
            })
            .catch(e => {
                Notifications.showError(serverMessage(e, 'Could not send the test message.'));
                $btn.prop('disabled', false);
            });
    }

    function remove(channelId) {
        const ch = current && current.channels.find(x => x.channelId === channelId);
        if (!ch) return;
        Modals.confirm('Remove channel', `Stop sending this environment's alerts to ${ch.label}?`, () => {
            call('DELETE', Config.API.chatChannels.item(channelId))
                .then(() => { Notifications.success('Channel removed.'); reload(); })
                .catch(e => Notifications.showError(serverMessage(e, 'Could not remove the channel.')));
        }, { confirmText: 'Remove', confirmClass: 'btn-danger' });
    }

    return { renderEnvPanel, openForm, test, remove };
})();

window.ChatChannels = ChatChannels;

Actions.registerAll({
    'chat-add': () => ChatChannels.openForm(null),
    'chat-edit': el => ChatChannels.openForm(el.dataset.id),
    'chat-test': el => ChatChannels.test(el.dataset.id, el),
    'chat-remove': el => ChatChannels.remove(el.dataset.id)
});
