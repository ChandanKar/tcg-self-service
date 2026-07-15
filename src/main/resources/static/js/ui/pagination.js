/**
 * Shared Pagination UI
 *
 * Replaces eight independent reimplementations of the same "Showing X-Y of Z, First/Prev/
 * [page numbers]/Next/Last" bar (VM Registry, User Management, Access Management, Environments,
 * Access Requests, Activity Logs, All Logs, Audit Logs) that had each drifted to a different bar
 * height, button size, and page-number windowing tweak. One definition here, one look everywhere.
 */
const Pagination = (function() {
    'use strict';

    const WINDOW = 2; // page numbers shown on each side of the current page
    const BTN_CLASS = 'pg-btn';

    /**
     * Renders a "Showing X-Y of Z <items>" bar with First/Prev/[page window]/Next/Last buttons
     * into containerSelector, and (re)binds one delegated click handler.
     *
     * Safe to call on every render, including when containerSelector's element itself gets
     * destroyed and recreated by a parent full-view re-render — pass bindAncestor in that case
     * (a selector for a stable ancestor, e.g. '#content-area') so the click delegation survives.
     *
     * @param {string} containerSelector - element whose innerHTML becomes the pagination bar
     * @param {object} opts
     * @param {number} opts.page - current page, indexed per opts.zeroIndexed
     * @param {number} opts.totalItems
     * @param {number} opts.pageSize
     * @param {string} [opts.itemLabel='items'] - plural noun shown after the count, e.g. "users"
     * @param {boolean} [opts.zeroIndexed=false] - true when opts.page is a 0-indexed server page
     * @param {string} [opts.bindAncestor=containerSelector] - delegation root for the click handler
     * @param {function} opts.onPageChange - (page) => void, called in the same indexing as opts.page
     */
    function renderNumbered(containerSelector, opts) {
        const {
            page, totalItems, pageSize, itemLabel = 'items',
            zeroIndexed = false, bindAncestor = containerSelector, onPageChange
        } = opts;

        const $container = $(containerSelector);
        const totalPages = pageSize > 0 ? Math.ceil(totalItems / pageSize) : 0;
        const first = zeroIndexed ? 0 : 1;
        const last = zeroIndexed ? Math.max(0, totalPages - 1) : Math.max(1, totalPages);

        if (totalPages <= 1) {
            $container.html(`<span class="pagination-info">${totalItems} ${itemLabel}</span>`);
            return;
        }

        const displayPage = zeroIndexed ? page + 1 : page;
        const start = (displayPage - 1) * pageSize + 1;
        const end = Math.min(displayPage * pageSize, totalItems);
        const rangeStart = Math.max(first, page - WINDOW);
        const rangeEnd = Math.min(last, page + WINDOW);

        let pageButtons = '';
        for (let i = rangeStart; i <= rangeEnd; i++) {
            const label = zeroIndexed ? i + 1 : i;
            pageButtons += `<button class="${BTN_CLASS} ${i === page ? 'active' : ''}" data-page="${i}">${label}</button>`;
        }

        const atFirst = page <= first;
        const atLast = page >= last;

        $container.html(`
            <span class="pagination-info">Showing ${start}–${end} of ${totalItems} ${itemLabel}</span>
            <div class="pagination-controls">
                <button class="${BTN_CLASS}" data-page="${first}" ${atFirst ? 'disabled' : ''} title="First page"><i class="fas fa-angle-double-left"></i></button>
                <button class="${BTN_CLASS}" data-page="${page - 1}" ${atFirst ? 'disabled' : ''} title="Previous page"><i class="fas fa-chevron-left"></i></button>
                ${pageButtons}
                <button class="${BTN_CLASS}" data-page="${page + 1}" ${atLast ? 'disabled' : ''} title="Next page"><i class="fas fa-chevron-right"></i></button>
                <button class="${BTN_CLASS}" data-page="${last}" ${atLast ? 'disabled' : ''} title="Last page"><i class="fas fa-angle-double-right"></i></button>
            </div>
        `);

        $(bindAncestor).off('click.pagination', `.${BTN_CLASS}`).on('click.pagination', `.${BTN_CLASS}`, function() {
            if ($(this).prop('disabled')) return;
            const target = parseInt($(this).data('page'), 10);
            if (isNaN(target) || target < first || target > last || target === page) return;
            onPageChange(target);
        });
    }

    /**
     * Pure markup (no DOM injection, no binding) for a compact Prev/Next-only bar — "Page N of
     * M", no page-number list. Used by Cost Management, where up to three of these bars can be
     * embedded in one screen and each already wires its own onclick handler inline (avoids
     * needing a post-insertion bind pass for markup that's assembled into a larger HTML string).
     *
     * @param {object} opts
     * @param {number} opts.page - 0-indexed current page
     * @param {number} opts.totalPages
     * @param {number} opts.totalItems
     * @param {string} [opts.itemLabel='rows']
     * @param {string} opts.prevOnClick - raw JS expression for the Prev button's onclick attribute
     * @param {string} opts.nextOnClick - raw JS expression for the Next button's onclick attribute
     */
    function buildSimpleMarkup(opts) {
        const { page, totalPages, totalItems, itemLabel = 'rows', prevOnClick, nextOnClick } = opts;

        if (!totalPages || totalPages <= 1) {
            return `<span class="pagination-info">${totalItems || 0} ${itemLabel}</span>`;
        }

        return `
            <span class="pagination-info">Page ${page + 1} of ${totalPages} (${totalItems} ${itemLabel})</span>
            <div class="pagination-controls">
                <button class="${BTN_CLASS}" ${page === 0 ? 'disabled' : ''} onclick="${prevOnClick}">
                    <i class="fas fa-chevron-left"></i> Prev
                </button>
                <button class="${BTN_CLASS} pg-btn-wide" ${page >= totalPages - 1 ? 'disabled' : ''} onclick="${nextOnClick}">
                    Next <i class="fas fa-chevron-right"></i>
                </button>
            </div>
        `;
    }

    return { renderNumbered, buildSimpleMarkup };
})();
