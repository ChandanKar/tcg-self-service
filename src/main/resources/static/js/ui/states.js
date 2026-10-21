/**
 * VM Self-Service Platform - Empty, filtered, loading and couldn't-load states (U17-T01, States.dc.html)
 *
 * Every list and page uses these four, so each message says what happened and what to do next:
 *   UiStates.empty({title, text, action})            nothing yet: a title, one sentence, one action
 *   UiStates.filtered({title, text, clearLabel, onClear | clearAction})
 *                                                     filters hide everything: names the filter, offers to show all
 *   UiStates.loading(shape, {count})                 outlines shaped like the content ('rows', 'cards', 'lines');
 *                                                     they appear only after 300 ms (CSS), so a quick answer never flashes
 *   UiStates.failed({what, xhr, onRetry | retryAction})
 *                                                     "VMs didn't load. The server didn't answer. The rest of the page
 *                                                     still works." + Try again + "ref 7f3a92c1" from a 500's body
 * Each returns HTML (all text escaped); `titleId` puts an id on the title. An action is {label, href} or {label, action, data}; onClear / onRetry
 * callbacks are wired through Actions (no inline handlers). UiStates.mountLoading / mount set aria-busy.
 */
const UiStates = (function() {
    'use strict';

    const html = (strings, ...values) => Utils.html(strings, ...values);
    const raw = v => Utils.raw(v);

    /** Callbacks for Try again / Show all, by id; each runs once. */
    const callbacks = new Map();
    let nextId = 1;

    function remember(fn) {
        const id = String(nextId++);
        callbacks.set(id, fn);
        return id;
    }

    function run(el) {
        const id = el.getAttribute('data-ui-cb');
        const fn = callbacks.get(id);
        callbacks.delete(id);
        if (fn) fn(el);
    }

    if (typeof Actions !== 'undefined' && Actions.register) Actions.register('ui-state-callback', run);

    const GLYPH = '<span class="ui-state-glyph" aria-hidden="true"><span></span><span></span><span></span></span>';

    function dataAttrs(data) {
        return Object.keys(data || {}).map(k => html` data-${raw(String(k).replace(/[^a-z0-9-]/gi, ''))}="${data[k]}"`).join('');
    }

    /** One action as a link or a button; `primary` for the main one. */
    function actionHtml(a, primary) {
        if (!a || !a.label) return '';
        const cls = primary ? 'btn btn-primary btn-sm' : 'btn btn-outline-secondary btn-sm';
        if (a.href) return html`<a class="${cls}" href="${a.href}">${a.label}</a>`;
        if (typeof a.onClick === 'function') {
            return html`<button type="button" class="${cls}" data-action="ui-state-callback" data-ui-cb="${remember(a.onClick)}">${a.label}</button>`;
        }
        return html`<button type="button" class="${cls}" data-action="${a.action || ''}"${raw(dataAttrs(a.data))}>${a.label}</button>`;
    }

    function block(kind, role, title, text, actions, titleId) {
        const id = titleId ? html` id="${titleId}"` : '';
        return html`<div class="ui-state ui-state-${kind}" role="${role}">${raw(kind === 'failed' ? '' : GLYPH)}
                <p class="ui-state-title"${raw(id)}>${title}</p>${raw(text ? html`<p class="ui-state-text">${text}</p>` : '')}${raw(actions ? `<div class="ui-state-actions">${actions}</div>` : '')}
            </div>`;
    }

    /** Nothing yet: "No environments yet" · "Ask for access to one and it appears here once approved." · Request access. */
    function empty(opts) {
        const o = opts || {};
        return block('empty', 'status', o.title || 'Nothing here yet', o.text, actionHtml(o.action, true), o.titleId);
    }

    /** Filters hide everything: 'No VMs match "Running" in Web' · why · Show all VMs. */
    function filtered(opts) {
        const o = opts || {};
        const clear = o.onClear ? { label: o.clearLabel || 'Show all', onClick: o.onClear }
            : { label: o.clearLabel || 'Show all', action: o.clearAction, data: o.clearData };
        return block('filtered', 'status', o.title || 'Nothing matches these filters', o.text, actionHtml(clear, false), o.titleId);
    }

    /** Why it failed, from the response status. */
    function reason(status) {
        const s = Number(status) || 0;
        if (s === 0) return 'The server didn\'t answer.';
        if (s === 401) return 'Your session ended. Sign in again.';
        if (s === 403) return 'You can\'t see this.';
        if (s === 404) return 'It isn\'t there any more.';
        if (s >= 500) return 'Something failed on the server.';
        return 'The server turned the request down.';
    }

    /** The 8-character reference a 500 carries (GlobalExceptionHandler), or ''. */
    function refOf(xhr) {
        const body = xhr && (xhr.responseJSON || null);
        const ref = body && typeof body.ref === 'string' ? body.ref : '';
        return /^[0-9a-f]{8}$/i.test(ref) ? ref : '';
    }

    /** Couldn't load: "VMs didn't load" · reason + "The rest of the page still works." · Try again · ref. */
    function failed(opts) {
        const o = opts || {};
        const xhr = o.xhr || null;
        const status = o.status !== undefined ? o.status : (xhr ? xhr.status : 0);
        const ref = o.ref || refOf(xhr);
        const retry = o.onRetry ? actionHtml({ label: 'Try again', onClick: o.onRetry }, false)
            : o.retryAction ? actionHtml({ label: 'Try again', action: o.retryAction, data: o.retryData }, false) : '';
        const refHtml = ref ? html`<span class="ui-state-ref">ref ${ref}</span>` : '';
        return block('failed', 'alert', `${o.what || 'This'} didn't load`,
            `${reason(status)} The rest of the page still works.`, retry + refHtml);
    }

    /** Outlines shaped like the content. Shown after 300 ms by CSS (.ui-loading), so fast answers never flash. */
    function loading(shape, opts) {
        const n = Math.max(1, Math.min(12, (opts && opts.count) || (shape === 'cards' ? 3 : 4)));
        const label = '<span class="visually-hidden">Loading…</span>';
        if (shape === 'cards') {
            const card = '<div class="ui-outline-card"><span class="ui-outline ui-outline-title"></span><span class="ui-outline"></span><span class="ui-outline ui-outline-short"></span></div>';
            return `<div class="ui-loading ui-loading-cards">${label}${card.repeat(n)}</div>`;
        }
        if (shape === 'lines') {
            return `<div class="ui-loading ui-loading-lines">${label}${'<span class="ui-outline"></span>'.repeat(n)}</div>`;
        }
        const row = '<div class="ui-outline-row"><span class="ui-outline ui-outline-dot"></span><span class="ui-outline"></span><span class="ui-outline ui-outline-short"></span></div>';
        return `<div class="ui-loading ui-loading-rows">${label}${row.repeat(n)}</div>`;
    }

    /** Puts outlines in `container` and marks it busy until mount() fills it. */
    function mountLoading(container, shape, opts) {
        const $c = $(container);
        $c.attr('aria-busy', 'true').html(loading(shape, opts));
    }

    /** Fills `container` and clears aria-busy. */
    function mount(container, markup) {
        $(container).attr('aria-busy', 'false').html(markup);
    }

    return { empty, filtered, failed, loading, mountLoading, mount, model: { reason, refOf } };
})();

window.UiStates = UiStates;
