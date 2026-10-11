/**
 * Confirm page for one-click Teams buttons (E24-T04): #/chat-action/<token>. Shows what the link
 * will do for the signed-in user, runs it on Confirm, and shows the result. The link grants
 * nothing by itself: the server checks the user's own permissions, and a link works once.
 */
const ChatActionPage = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    let pageToken = null;

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data || null, QUIET).done(resolve).fail(reject);
        });
    }

    /** "2 hours" / "30 minutes" for the duration picker (E24-T05). */
    function choiceLabel(value, unit) {
        if (unit === 'minutes' && value % 60 === 0) {
            const h = value / 60;
            return h + (h === 1 ? ' hour' : ' hours');
        }
        return value + ' ' + (value === 1 ? unit.replace(/s$/, '') : unit);
    }

    function failure(e) {
        const status = e && e.status;
        const message = (e && e.responseJSON && e.responseJSON.message) || 'Something went wrong.';
        if (status === 409) return { title: 'Link already used', message };
        if (status === 403) return { title: 'Not allowed', message };
        if (status === 404) return { title: 'Not found', message };
        return { title: 'This link cannot be used', message };
    }

    function frame(inner) {
        $('#content-area').html(`<div class="content-view chat-action-page" id="chat-action-view">
            <div class="content-header"><h1><i class="fas fa-bolt me-2" aria-hidden="true"></i>Teams action</h1></div>
            <section class="card chat-action-card"><div class="card-body" id="chat-action-body">${inner}</div></section></div>`);
    }

    function load(params) {
        pageToken = ContentRouter.token();
        const token = params && params.token;
        frame('<div class="text-muted"><i class="fas fa-spinner fa-spin me-1" aria-hidden="true"></i>Checking the link…</div>');
        if (!token) {
            showError({ title: 'This link cannot be used', message: 'The link is incomplete.' });
            return;
        }
        const nav = pageToken;
        call('GET', Config.API.chatActions.item(token))
            .then(preview => {
                if (!ContentRouter.isCurrent(nav)) return;
                $('#chat-action-body').html(Utils.html`
                    <p class="mb-1 text-muted small">${preview.environmentName}</p>
                    <p class="fs-5 mb-3" id="chat-action-description">${preview.description}</p>
                    ${Utils.raw(preview.choices && preview.choices.length ? Utils.html`<div class="mb-3">
                        <label class="form-label" for="chat-action-choice">${preview.type === 'EXTEND_LEASE' ? 'Extend by' : 'Snooze for'}</label>
                        <select class="form-select chat-action-choice" id="chat-action-choice">${Utils.raw(preview.choices.map(c =>
                            `<option value="${c}" ${c === preview.defaultChoice ? 'selected' : ''}>${Utils.escapeHtml(choiceLabel(c, preview.choiceUnit))}</option>`).join(''))}</select>
                    </div>` : '')}
                    <p class="small text-muted">You act as yourself: the same checks as in the app apply. The link works once.</p>
                    <div class="d-flex gap-2 flex-wrap">
                        <button type="button" class="btn ${preview.type === 'STOP_ENVIRONMENT' ? 'btn-danger' : 'btn-primary'}"
                            id="chat-action-confirm" data-action="chat-action-confirm">${preview.confirmLabel}</button>
                        <a class="btn btn-ghost" href="#/dashboard" id="chat-action-cancel">Cancel</a>
                    </div>`);
                $('#chat-action-confirm').data('token', token).trigger('focus');
            })
            .catch(e => { if (ContentRouter.isCurrent(nav)) showError(failure(e)); });
    }

    function showError(f) {
        $('#chat-action-body').html(Utils.html`<div class="alert alert-warning mb-3" role="alert" id="chat-action-error">
            <strong>${f.title}.</strong> ${f.message}</div>
            <a class="btn btn-ghost" href="#/dashboard">Go to the dashboard</a>`);
    }

    function confirm(el) {
        const token = $(el).data('token');
        const nav = pageToken;
        $(el).prop('disabled', true);
        const $choice = $('#chat-action-choice');
        call('POST', Config.API.chatActions.item(token), $choice.length ? { choice: Number($choice.val()) } : null)
            .then(result => {
                if (!ContentRouter.isCurrent(nav)) return;
                $('#chat-action-body').html(Utils.html`<div class="alert alert-success mb-3" role="status" id="chat-action-done">
                    <i class="fas fa-check-circle me-1" aria-hidden="true"></i>${result.message}</div>
                    <a class="btn btn-primary" href="#/environments/${result.environmentId}">Open the environment</a>`);
            })
            .catch(e => { if (ContentRouter.isCurrent(nav)) showError(failure(e)); });
    }

    return { load, confirm };
})();

window.ChatActionPage = ChatActionPage;

Actions.registerAll({
    'chat-action-confirm': el => ChatActionPage.confirm(el)
});
