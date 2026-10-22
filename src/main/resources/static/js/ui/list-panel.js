/**
 * List panel (U18-T01): a list that never makes the page scroll. A fixed head (title, search,
 * filters), a body that scrolls inside the panel with sticky column headings, and a fixed foot
 * (the pager). The page asks how many rows fit the space and pages by that number, so the inner
 * scroll is a fallback; a shadow on the head or foot shows that rows are hidden above or below.
 *
 *   ListPanel.markup({ id, title, label, toolbarHtml, bodyHtml, footHtml })
 *   const panel = ListPanel.attach(element, { rowSelector: 'tbody tr', onFit: n => ... })
 *   panel.fitCount()  // rows that fit now, or null when the page scrolls instead (small windows, phones)
 *
 * Below about 1280 x 600 (phones, tablets, 200% zoom) the page frame lets the page scroll again and
 * the body shows every row; fitCount() returns null there and the page keeps its usual page size.
 */
const ListPanel = (function() {
    'use strict';

    /** The frame holds the page to the window only above this size (css/layout/content.css). */
    const FRAME_QUERY = '(min-width: 1280px) and (min-height: 600px)';
    const RESIZE_SETTLE_MS = 200;

    // ---- pure helpers (node tests: src/test/js/list-panel.test.mjs) ----

    /** How many rows of rowHeight fit in available px, never fewer than min or more than max. */
    function rowsThatFit(available, rowHeight, opts = {}) {
        const min = opts.min || 3;
        const max = opts.max || 50;
        if (!(available > 0) || !(rowHeight > 0)) return min;
        return Math.max(min, Math.min(max, Math.floor(available / rowHeight)));
    }

    /** The 1-based page that keeps the item at firstIndex (0-based) in view with pages of size. */
    function pageKeepingFirst(firstIndex, size) {
        if (!(size > 0) || !(firstIndex > 0)) return 1;
        return Math.floor(firstIndex / size) + 1;
    }

    // ---- markup ----

    function markup(opts) {
        const o = opts || {};
        const esc = typeof Utils !== 'undefined' ? Utils.escapeHtml : s => String(s == null ? '' : s);
        const id = esc(o.id || 'list-panel');
        const titleId = `${id}-title`;
        const head = (o.title || o.toolbarHtml)
            ? `<div class="list-panel-head">${o.title ? `<h2 id="${titleId}">${esc(o.title)}</h2>` : ''}${o.toolbarHtml || ''}</div>`
            : '';
        const label = esc(o.label || o.title || 'List');
        return `<section class="list-panel" id="${id}"${o.title ? ` aria-labelledby="${titleId}"` : ` aria-label="${label}"`}>
                ${head}
                <div class="list-panel-body" id="${id}-body" tabindex="0" role="region" aria-label="${label}, scrolls">${o.bodyHtml || ''}</div>
                ${o.footHtml !== undefined ? `<div class="list-panel-foot" id="${id}-foot">${o.footHtml}</div>` : ''}
            </section>`;
    }

    // ---- behaviour ----

    function framed() {
        return !!(typeof window !== 'undefined' && window.matchMedia && window.matchMedia(FRAME_QUERY).matches);
    }

    /** Shadows on the head and foot while rows are hidden above or below. */
    function updateEdges(panel, body) {
        const above = body.scrollTop > 1;
        const below = body.scrollHeight - body.clientHeight - body.scrollTop > 1;
        panel.classList.toggle('has-more-above', above);
        panel.classList.toggle('has-more-below', below);
    }

    /**
     * Rows that fit the body now: its height minus everything in it that is not a row (column headings,
     * table margins, notes), divided by the height of a rendered row (opts.rowHeight when none is
     * rendered yet). Null when the page scrolls instead.
     */
    function fitCount(panel, opts = {}) {
        if (!framed() || (panel && panel.isConnected === false)) return null;
        const body = panel.querySelector('.list-panel-body');
        if (!body) return null;
        const rows = opts.rowSelector ? Array.from(body.querySelectorAll(opts.rowSelector)) : [];
        const rowsHeight = rows.reduce((sum, r) => sum + r.getBoundingClientRect().height, 0);
        const rowHeight = rows.length ? rowsHeight / rows.length : (opts.rowHeight || 44);
        // The content's own height (not the body's, which is taller when the list is short).
        const last = body.lastElementChild;
        const contentHeight = last
            ? last.getBoundingClientRect().bottom + (parseFloat(getComputedStyle(last).marginBottom) || 0)
                - body.getBoundingClientRect().top + body.scrollTop
            : 0;
        const other = Math.max(0, contentHeight - rowsHeight);
        const available = body.clientHeight - other - (opts.reserve || 0);
        return rowsThatFit(available, rowHeight, opts);
    }

    /**
     * Wires a rendered panel: edge shadows on scroll, and onFit(count) once the window has stopped
     * resizing and the count changed. Returns { fitCount(), refresh(), destroy() }; destroy runs on leave.
     */
    function attach(panel, opts = {}) {
        const body = panel && panel.querySelector('.list-panel-body');
        if (!body) return { fitCount: () => null, refresh() {}, destroy() {} };
        let last = null;
        let timer = null;
        const onScroll = () => updateEdges(panel, body);
        const settle = () => {
            clearTimeout(timer);
            timer = setTimeout(() => {
                // A list re-rendered in place leaves its old panel behind: it stops watching, silently.
                if (!panel.isConnected) {
                    handle.destroy();
                    return;
                }
                updateEdges(panel, body);
                const n = fitCount(panel, opts);
                if (n !== last) {
                    last = n;
                    if (typeof opts.onFit === 'function') opts.onFit(n);
                }
            }, RESIZE_SETTLE_MS);
        };
        body.addEventListener('scroll', onScroll, { passive: true });
        const observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(settle) : null;
        if (observer) observer.observe(body);
        else window.addEventListener('resize', settle);
        last = fitCount(panel, opts);
        updateEdges(panel, body);
        const handle = {
            fitCount: () => fitCount(panel, opts),
            refresh: () => updateEdges(panel, body),
            destroy() {
                clearTimeout(timer);
                body.removeEventListener('scroll', onScroll);
                if (observer) observer.disconnect();
                else window.removeEventListener('resize', settle);
            }
        };
        if (typeof ContentRouter !== 'undefined' && ContentRouter.onLeave) ContentRouter.onLeave(() => handle.destroy());
        return handle;
    }

    return { markup, attach, fitCount, framed, rowsThatFit, pageKeepingFirst, FRAME_QUERY };
})();
