/**
 * The Ask panel (E36-T05): a slide-out where a signed-in user asks questions about their
 * environments and costs. The server answers with a model that uses read-only tools as the user;
 * answers are shown as text (escaped; lines starting "- " become a list), never as HTML, with the
 * tools used in a disclosure. The conversation lives only in this page. Hidden while
 * assistant.chat.enabled is off.
 */
const AssistantPanel = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    const MAX_QUESTION = 2000;
    let enabled = false;
    let busy = false;
    let thread = [];      // [{ role: 'user'|'assistant', text, toolCalls?, failed? }]

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
        $(document).on('keydown', '#assistant-question', function(e) {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                send();
            }
        });
        $(document).on('click', '#assistant-clear', function() {
            thread = [];
            draw();
            $('#assistant-question').trigger('focus');
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
            return `<li><code>${Utils.escapeHtml(c.name)}</code>${Utils.escapeHtml(args)}${c.error ? ' <span class="text-danger">(refused)</span>' : ''}</li>`;
        }).join('');
        return `<details class="assistant-tools"><summary>Tools used (${calls.length})</summary><ul>${items}</ul></details>`;
    }

    function messageHtml(m) {
        if (m.role === 'user') {
            return `<div class="assistant-msg assistant-msg-user"><div class="assistant-bubble">${format(m.text)}</div></div>`;
        }
        return `<div class="assistant-msg assistant-msg-answer${m.failed ? ' assistant-msg-failed' : ''}">
                    <div class="assistant-bubble">${format(m.text)}</div>${toolsUsed(m.toolCalls)}</div>`;
    }

    function draw() {
        const $thread = $('#assistant-thread');
        if (!$thread.length) return;
        $thread.html(thread.length ? thread.map(messageHtml).join('')
            : `<p class="text-muted small mb-0">Ask about the environments you can see: costs, savings, idle VMs or rightsizing.
               For example: <em>Which of my environments cost the most this month?</em></p>`);
        if (busy) $thread.append('<div class="assistant-msg assistant-msg-answer" id="assistant-thinking"><div class="assistant-bubble text-muted">Looking it up…</div></div>');
        $thread.scrollTop($thread[0].scrollHeight);
        $('#assistant-send').prop('disabled', busy);
        $('#assistant-clear').prop('disabled', busy || !thread.length);
    }

    function open() {
        Slideout.open('Ask', `
            <div class="assistant-panel">
                <p class="assistant-note small text-muted">Answers come from your data through read-only lookups; nothing is
                    changed. Figures marked estimated are the platform's estimates. Questions and results are sent to the
                    organisation's AI provider.</p>
                <div id="assistant-thread" class="assistant-thread" aria-live="polite"></div>
                <form id="assistant-form" class="assistant-form">
                    <label for="assistant-question" class="visually-hidden">Your question</label>
                    <textarea id="assistant-question" class="form-control" rows="3" maxlength="${MAX_QUESTION}"
                              placeholder="Ask a question (Enter sends, Shift+Enter for a new line)"></textarea>
                    <div id="assistant-error" class="text-danger small" role="alert"></div>
                    <div class="d-flex gap-2 justify-content-end">
                        <button type="button" class="btn btn-outline-secondary btn-sm" id="assistant-clear">Clear</button>
                        <button type="submit" class="btn btn-primary btn-sm" id="assistant-send">Ask</button>
                    </div>
                </form>
            </div>`);
        draw();
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

    return { init, isEnabled, open, format };
})();
