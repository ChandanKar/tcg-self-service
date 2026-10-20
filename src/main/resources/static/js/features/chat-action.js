/**
 * Confirm page for one-click Teams buttons (E24-T04, U09-T08 ChatAction.dc.html):
 * #/chat-action/<token>. A bare page (no side menu, no top-bar tools) with one card: where the
 * link came from, the question ("Extend the lease on Payments Dev?"), what happens now, the
 * choices as large radio chips, who is signed in and when the link ends, "Open <environment>
 * instead" and the action. The outcome replaces the card: Done, already used, expired, not
 * allowed (naming an admin to ask), each with a way to the environment. The link grants nothing
 * by itself: the server checks the user's own permissions, and a link works once.
 */
const ChatActionPage = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    let pageToken = null;

    // ---- pure model (node tests: src/test/js/chat-action.test.mjs) ----

    function hm(d) {
        return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
    }

    function dayStart(d) {
        return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
    }

    /** "18:00 today", "10:12 tomorrow", "Fri 18:00" within the week, "17 Nov 18:00" after that. */
    function when(ts, now) {
        const d = new Date(ts);
        const n = now || new Date();
        const days = Math.round((dayStart(d) - dayStart(n)) / 86400000);
        if (days === 0) return `${hm(d)} today`;
        if (days === 1) return `${hm(d)} tomorrow`;
        if (days > 1 && days < 7) return `${d.toLocaleDateString('en-GB', { weekday: 'short' })} ${hm(d)}`;
        return `${d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })} ${hm(d)}`;
    }

    /** "2 hours" / "30 minutes" / "1 hour" for a choice (E24-T05). */
    function choiceLabel(value, unit) {
        if (unit === 'minutes' && value % 60 === 0) {
            const h = value / 60;
            return h + (h === 1 ? ' hour' : ' hours');
        }
        return value + ' ' + (value === 1 ? unit.replace(/s$/, '') : unit);
    }

    /** "It stops at 18:00 today; 2 hours more would be 20:00." in the reader's own time. */
    function extendSentence(currentEnd, minutes, now) {
        const end = new Date(currentEnd);
        const n = now || new Date();
        const base = end > n ? end : n;
        const next = new Date(base.getTime() + minutes * 60000);
        const nextText = dayStart(next) === dayStart(end) ? hm(next) : when(next, n);
        return `It stops at ${when(end, n)}; ${choiceLabel(minutes, 'minutes')} more would be ${nextText}.`;
    }

    /** What the page says when a link cannot be used: tone (dot), title, body. */
    function outcome(code, message) {
        switch (code) {
            case 'LINK_USED': return { tone: 'gold', title: 'This link was already used', body: 'Each link works once.' };
            case 'LINK_EXPIRED': return { tone: 'grey', title: 'This link has expired',
                body: 'Links work for 24 hours. Open the environment to do it there.' };
            case 'LINK_INVALID': return { tone: 'grey', title: "This link can't be used",
                body: 'It is incomplete or was changed. Use the button in Teams again.' };
            case 'NOT_ALLOWED': return { tone: 'red', title: "You can't do this", body: message || '' };
            case 'STATE': return { tone: 'gold', title: 'Nothing to do now', body: message || '' };
            case 'NOT_FOUND': return { tone: 'grey', title: 'Not found', body: message || '' };
            case 'REFUSED': return { tone: 'red', title: "That didn't work", body: message || '' };
            default: return { tone: 'red', title: 'Something went wrong', body: message || 'Try the button in Teams again.' };
        }
    }

    /** The Done sentence, with the new end in the reader's time when the server sent it. */
    function doneText(result, envName, now) {
        if (result && result.endsAt && result.type === 'EXTEND_LEASE') return `${envName} now stops at ${when(result.endsAt, now)}.`;
        if (result && result.endsAt && result.type === 'SNOOZE_IDLE_STOP') {
            return `Idle auto-stop is paused for ${envName} until ${when(result.endsAt, now)}.`;
        }
        return (result && result.message) || 'Done.';
    }

    // ---- page ----

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data || null, QUIET).done(resolve).fail(reject);
        });
    }

    function frame(inner) {
        $('#content-area').html(`<div class="chat-action-page" id="chat-action-view">${inner}</div>`);
    }

    function envLink(id, name, suffix) {
        if (!id) return Utils.html`<a class="ca-link" href="#/dashboard">Go to Home</a>`;
        return Utils.html`<a class="ca-link" href="#/environments/${id}">Open ${name || 'the environment'}${suffix || ''}</a>`;
    }

    function load(params) {
        pageToken = ContentRouter.token();
        document.body.classList.add('bare-page');
        ContentRouter.onLeave(() => document.body.classList.remove('bare-page'));
        const token = params && params.token;
        frame('<p class="ca-checking" role="status">Checking the link…</p>');
        if (!token) {
            showOutcome({ code: 'LINK_INVALID' });
            return;
        }
        const nav = pageToken;
        call('GET', Config.API.chatActions.item(token))
            .then(preview => { if (ContentRouter.isCurrent(nav)) showPreview(token, preview); })
            .catch(e => { if (ContentRouter.isCurrent(nav)) showOutcome(errorOf(e)); });
    }

    function errorOf(e) {
        const body = (e && e.responseJSON) || {};
        return { code: body.code || (e && e.status === 409 ? 'LINK_USED' : null), message: body.message,
            environmentId: body.environmentId, environmentName: body.environmentName };
    }

    function showPreview(token, p) {
        const choices = p.choices && p.choices.length ? p.choices : null;
        const describe = p.type === 'EXTEND_LEASE' && p.currentEnd
            ? extendSentence(p.currentEnd, p.defaultChoice || 120) : p.description;
        const signedIn = typeof Auth !== 'undefined' && Auth.getUserDisplayName ? Auth.getUserDisplayName() : '';
        const chips = choices ? Utils.html`<fieldset class="ca-choices">
                <legend>${p.type === 'EXTEND_LEASE' ? 'Add' : 'Snooze for'}</legend>
                <div class="ca-chip-row">${Utils.raw(choices.map(c => Utils.html`<label class="ca-chip">
                    <input type="radio" name="ca-choice" value="${c}" ${c === p.defaultChoice ? 'checked' : ''}>${choiceLabel(c, p.choiceUnit)}</label>`).join(''))}</div>
            </fieldset>` : '';
        frame(Utils.html`
            <section class="ca-card" aria-labelledby="ca-title">
                <div class="ca-head">
                    <div class="ca-source">${p.source || ''}</div>
                    <h1 id="ca-title">${p.title || p.environmentName}</h1>
                    <p class="ca-description" id="chat-action-description">${describe}</p>
                </div>
                ${Utils.raw(chips)}
                <p class="ca-signed-in">${signedIn ? `Signed in as ${signedIn}. ` : ''}The link works once and ends at ${when(p.expiresAt)}.</p>
                <div class="ca-foot">
                    ${Utils.raw(envLink(p.environmentId, p.environmentName, ' instead'))}
                    <button type="button" class="btn ${p.type === 'STOP_ENVIRONMENT' ? 'btn-danger' : 'btn-primary'} ca-confirm"
                        id="chat-action-confirm" data-action="chat-action-confirm">${p.confirmLabel}</button>
                </div>
            </section>`);
        $('#chat-action-confirm').data('token', token).data('preview', p).trigger('focus');
        $('#chat-action-view').on('change', 'input[name="ca-choice"]', function() {
            if (p.type === 'EXTEND_LEASE' && p.currentEnd) {
                $('#chat-action-description').text(extendSentence(p.currentEnd, Number(this.value)));
            }
        });
    }

    /** One outcome card: Done (role=status) or a problem (role=alert), with a way to the environment. */
    function showOutcome(o, done) {
        const view = done ? { tone: 'green', title: 'Done', body: o.message } : outcome(o.code, o.message);
        frame(Utils.html`
            <section class="ca-card ca-outcome" data-tone="${view.tone}" role="${done ? 'status' : 'alert'}"
                     id="${done ? 'chat-action-done' : 'chat-action-error'}" aria-labelledby="ca-title">
                <div class="ca-outcome-body">
                    <span class="ca-dot" aria-hidden="true"></span>
                    <div>
                        <h1 id="ca-title">${view.title}</h1>
                        <p>${view.body}</p>
                    </div>
                </div>
                <div class="ca-foot">${Utils.raw(envLink(o.environmentId, o.environmentName))}</div>
            </section>`);
    }

    function confirm(el) {
        const token = $(el).data('token');
        const preview = $(el).data('preview') || {};
        const nav = pageToken;
        $(el).prop('disabled', true);
        const $choice = $('input[name="ca-choice"]:checked');
        call('POST', Config.API.chatActions.item(token), $choice.length ? { choice: Number($choice.val()) } : null)
            .then(result => {
                if (!ContentRouter.isCurrent(nav)) return;
                showOutcome({ message: doneText(result, preview.environmentName),
                    environmentId: result.environmentId, environmentName: preview.environmentName }, true);
            })
            .catch(e => {
                if (!ContentRouter.isCurrent(nav)) return;
                const err = errorOf(e);
                showOutcome({ ...err, environmentId: err.environmentId || preview.environmentId,
                    environmentName: err.environmentName || preview.environmentName });
            });
    }

    return { load, confirm, model: { when, choiceLabel, extendSentence, outcome, doneText } };
})();

if (typeof window !== 'undefined') window.ChatActionPage = ChatActionPage;

if (typeof Actions !== 'undefined') {
    Actions.registerAll({
        'chat-action-confirm': el => ChatActionPage.confirm(el)
    });
}
