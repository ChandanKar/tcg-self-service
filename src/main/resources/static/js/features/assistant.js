/**
 * The Ask panel (E36-T05, U09-T06 AskPanel.dc.html): a slide-out where a signed-in user asks
 * questions about their environments and costs. The server answers with a model that uses
 * read-only tools as the user; answers are shown as text (escaped; lines starting "- " become a
 * list), never as HTML, with the tools used in a disclosure. Questions sit in navy bubbles on the
 * right, answers as plain text on the left; environments the user can see that an answer names
 * get "Open <environment>" links (at most 3). Suggestion chips send a question. The conversation
 * lives only in this page ("Start over" clears it); the server keeps the last 10 messages.
 * Hidden while assistant.chat.enabled is off. Opened from the account menu and the help menu.
 */
const AssistantPanel = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    const MAX_QUESTION = 2000;
    const MAX_ENV_LINKS = 3;
    const CHIPS = ['Cost trend for 3 months', 'Spend by service', 'Savings I could make', 'VMs that could be smaller'];
    let enabled = false;
    let busy = false;
    let thread = [];      // [{ role: 'user'|'assistant', text, toolCalls?, failed? }]
    let environments = null;  // [{ id, name }] the user can see, loaded on first open

    // ---- pure model (node tests: src/test/js/assistant.test.mjs) ----

    function escapeRegExp(s) {
        return String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    }

    /**
     * Environments an answer names (case-insensitive, whole words), in the order they first
     * appear, longest name first when two overlap; at most `max`.
     */
    function envMentions(text, envs, max) {
        const source = String(text || '');
        const found = [];
        (envs || []).filter(e => e && e.name && e.name.trim().length >= 2)
            .slice().sort((a, b) => b.name.length - a.name.length)
            .forEach(e => {
                if (found.some(f => f.env.id === e.id)) return;
                const re = new RegExp(`(^|[^\\w])${escapeRegExp(e.name.trim())}(?=$|[^\\w])`, 'gi');
                let m;
                while ((m = re.exec(source)) !== null) {
                    const at = m.index + m[1].length;
                    // A mention inside a longer name already found (Payments in Payments Dev) does not count.
                    if (!found.some(f => at >= f.at && at < f.at + f.env.name.length)) {
                        found.push({ env: e, at });
                        return;
                    }
                }
            });
        return found.sort((a, b) => a.at - b.at).slice(0, max || MAX_ENV_LINKS).map(f => f.env);
    }

    /** Chips show while nothing is asked yet or after an answer, never while waiting. */
    function showChips(list, isBusy) {
        if (isBusy) return false;
        return !list.length || list[list.length - 1].role === 'assistant';
    }

    // ---- panel ----

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data || null, QUIET).done(resolve).fail(reject);
        });
    }

    function init() {
        $(document).on('click', '#ask-menu-btn', function(e) {
            e.preventDefault();
            $('.user-profile-trigger').trigger('focus');
            open();
        });
        $(document).on('submit', '#assistant-form', function(e) {
            e.preventDefault();
            send();
        });
        $(document).on('click', '#assistant-clear', function() {
            thread = [];
            draw();
            $('#assistant-question').trigger('focus');
        });
        $(document).on('click', '[data-ask-chip]', function() {
            $('#assistant-question').val(this.getAttribute('data-ask-chip'));
            send();
        });
        $(document).on('click', '[data-ask-env]', function() {
            const id = this.getAttribute('data-ask-env');
            Slideout.close();
            ContentRouter.navigate('environment-detail', { environmentId: id });
        });
        // Another panel in the same slot must not keep this one's header line.
        $(document).on('slideout:opened', (e, panel) => {
            if (panel && panel.getAttribute('data-variant') !== 'ask') $(panel).find('.ask-head-extra').remove();
        });
        call('GET', Config.API.assistant.status).then(s => {
            enabled = !!(s && s.enabled);
            $('#ask-menu-btn').toggle(enabled);
        }).catch(() => { enabled = false; });
    }

    function isEnabled() {
        return enabled;
    }

    /** Model text as safe HTML: escaped, paragraphs, and "- " / "* " lines as a list. */
    function format(text) {
        const blocks = [];
        let list = null;
        let para = [];
        const flush = () => {
            if (para.length) blocks.push('<p>' + para.map(Utils.escapeHtml).join('<br>') + '</p>');
            para = [];
        };
        String(text || '').split(/\r?\n/).forEach(line => {
            const item = line.match(/^\s*[-*•]\s+(.*)$/);
            if (item) {
                flush();
                if (!list) { list = []; }
                list.push('<li>' + Utils.escapeHtml(item[1]) + '</li>');
                return;
            }
            if (list) { blocks.push('<ul>' + list.join('') + '</ul>'); list = null; }
            if (line.trim() === '') flush(); else para.push(line);
        });
        if (list) blocks.push('<ul>' + list.join('') + '</ul>');
        flush();
        return blocks.join('');
    }

    function toolsUsed(calls) {
        if (!calls || !calls.length) return '';
        const items = calls.map(c => {
            const args = c.arguments && Object.keys(c.arguments).length ? ' ' + JSON.stringify(c.arguments) : '';
            return `<li><code>${Utils.escapeHtml(c.name)}</code>${Utils.escapeHtml(args)}${c.error ? ' <span class="assistant-refused">(refused)</span>' : ''}</li>`;
        }).join('');
        return `<details class="assistant-tools"><summary>Tools used (${calls.length})</summary><ul>${items}</ul></details>`;
    }

    function envLinks(m) {
        if (m.failed || !environments) return '';
        const envs = envMentions(m.text, environments, MAX_ENV_LINKS);
        if (!envs.length) return '';
        return `<div class="assistant-env-links">${envs.map(e => Utils.html`<button type="button" class="assistant-env-link"
                data-ask-env="${e.id}">Open ${e.name}</button>`).join('')}</div>`;
    }

    function messageHtml(m) {
        if (m.role === 'user') {
            return `<div class="assistant-msg assistant-msg-user"><div class="assistant-bubble">${format(m.text)}</div></div>`;
        }
        return `<div class="assistant-msg assistant-msg-answer${m.failed ? ' assistant-msg-failed' : ''}">
                    <div class="assistant-bubble">${format(m.text)}</div>${envLinks(m)}${toolsUsed(m.toolCalls)}</div>`;
    }

    function draw() {
        const $thread = $('#assistant-thread');
        if (!$thread.length) return;
        $thread.html(thread.length ? thread.map(messageHtml).join('')
            : '<p class="assistant-intro">Ask about cost, idle VMs, savings or sizes in the environments you can see.</p>');
        if (busy) {
            $thread.append(`<div class="assistant-busy" id="assistant-thinking" role="status">
                <span class="assistant-dots" aria-hidden="true"><span></span><span></span><span></span></span>Looking it up…</div>`);
        }
        $thread.scrollTop($thread[0].scrollHeight);
        $('#assistant-chips').html(showChips(thread, busy)
            ? CHIPS.map(c => Utils.html`<button type="button" class="assistant-chip" data-ask-chip="${c}">${c}</button>`).join('')
            : '');
        $('#assistant-send').prop('disabled', busy);
        $('#assistant-clear').prop('disabled', busy || !thread.length);
    }

    /** The environments the user can see, for the "Open <environment>" links (loaded once). */
    function loadEnvironments() {
        if (environments) return;
        environments = [];
        call('GET', Config.API.access.myEnvironments).then(list => {
            const byId = new Map();
            (Array.isArray(list) ? list : []).forEach(a => {
                if (a.environmentId && a.environmentName) byId.set(a.environmentId, a.environmentName);
            });
            environments = Array.from(byId, ([id, name]) => ({ id, name }));
            draw();
        }).catch(() => { environments = null; });
    }

    function open() {
        Slideout.open('Ask', `
            <div class="assistant-panel">
                <div id="assistant-thread" class="assistant-thread" aria-live="polite"></div>
                <div id="assistant-chips" class="assistant-chips" aria-label="Suggested questions"></div>
                <form id="assistant-form" class="assistant-form">
                    <label for="assistant-question" class="visually-hidden">Your question</label>
                    <input type="text" id="assistant-question" class="form-control" maxlength="${MAX_QUESTION}"
                           placeholder="Ask about cost, idle VMs, savings…" autocomplete="off">
                    <button type="submit" class="btn btn-primary" id="assistant-send">Ask</button>
                </form>
                <div id="assistant-error" class="assistant-error" role="alert"></div>
                <p class="assistant-foot">Remembers the last 10 messages of this conversation.
                    <button type="button" class="assistant-start-over" id="assistant-clear">Start over</button></p>
            </div>`, { variant: 'ask', width: 440, docked: true });
        const $header = $('#dynamicSlideoutPanel .slideout-panel-header');
        $header.find('.ask-head-extra').remove();
        $header.find('#dynamicSlideoutTitle').after(`<div class="ask-head-sub ask-head-extra">About environments and cost you can see.
            It can't start, stop or change anything.</div>`);
        draw();
        loadEnvironments();
        setTimeout(() => $('#assistant-question').trigger('focus'), 0);
    }

    function send() {
        if (busy) return;
        const question = String($('#assistant-question').val() || '').trim();
        $('#assistant-error').text('');
        if (!question) return;
        const history = thread.filter(m => !m.failed).map(m => ({ role: m.role, text: m.text }));
        thread.push({ role: 'user', text: question });
        $('#assistant-question').val('');
        busy = true;
        draw();
        call('POST', Config.API.assistant.ask, { question, history }).then(r => {
            thread.push({ role: 'assistant', text: r.answer, toolCalls: r.toolCalls || [] });
        }).catch(xhr => {
            const msg = (xhr && xhr.responseJSON && xhr.responseJSON.message) || 'The assistant could not answer. Try again.';
            thread.push({ role: 'assistant', text: msg, failed: true });
        }).finally(() => {
            busy = false;
            draw();
            $('#assistant-question').trigger('focus');
        });
    }

    return { init, isEnabled, open, format, model: { envMentions, showChips } };
})();
