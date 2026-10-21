/**
 * VM Self-Service Platform - Table-to-cards labels (E13-T06)
 *
 * On phones a `table.table-cards` shows each row as a card whose cells name their column from
 * `data-label` (see css/components/tables.css). Rather than repeat every header in every row's
 * markup, this fills in missing data-labels from the table's own <thead>, for tables rendered now
 * and for rows re-rendered later. Cells that already carry a data-label keep it.
 *
 * U16-T05: on a phone any other table in the page that would scroll sideways becomes cards too
 * (class table-cards-auto, its first cell as the card's title). A table opts out with data-cards="off".
 */
const TableCards = (function() {
    'use strict';

    /** Header texts by column index, honouring colspan. */
    function headerLabels(table) {
        const labels = [];
        const row = table.tHead && table.tHead.rows[0];
        if (!row) return labels;
        Array.from(row.cells).forEach(th => {
            const text = (th.getAttribute('data-label') || th.textContent || '').replace(/\s+/g, ' ').trim();
            for (let i = 0; i < (th.colSpan || 1); i++) labels.push(text);
        });
        return labels;
    }

    function labelTable(table) {
        const labels = headerLabels(table);
        if (!labels.length) return;
        Array.from(table.tBodies).forEach(body => {
            Array.from(body.rows).forEach(tr => {
                let col = 0;
                Array.from(tr.cells).forEach(td => {
                    if (!td.hasAttribute('data-label') && td.colSpan === 1 && labels[col]) {
                        td.setAttribute('data-label', labels[col]);
                    }
                    col += td.colSpan || 1;
                });
            });
        });
    }

    const PHONE_QUERY = '(max-width: 767.98px)';

    /** On a phone: page tables wider than their box (and shown) become cards. */
    function autoCards() {
        if (!window.matchMedia || !window.matchMedia(PHONE_QUERY).matches) return;
        const area = document.getElementById('content-area');
        if (!area) return;
        area.querySelectorAll('table:not(.table-cards):not([data-cards="off"])').forEach(table => {
            if (!table.tHead || !table.tBodies.length || !table.parentElement) return;
            if (table.getBoundingClientRect().width === 0) return;   // in a hidden tab: checked when it shows
            if (table.scrollWidth > table.parentElement.clientWidth + 1) table.classList.add('table-cards', 'table-cards-auto');
        });
    }

    /** Label every card table inside `root` (default: the whole document). */
    function label(root) {
        autoCards();
        (root || document).querySelectorAll('table.table-cards').forEach(labelTable);
    }

    let scheduled = false;
    function schedule() {
        if (scheduled) return;
        scheduled = true;
        requestAnimationFrame(() => {
            scheduled = false;
            label(document);
        });
    }

    function init() {
        label(document);
        // `hidden` too: a tab panel shown later may hold a wide table.
        new MutationObserver(schedule).observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['hidden'] });
        if (window.matchMedia) {
            const media = window.matchMedia(PHONE_QUERY);
            if (media.addEventListener) media.addEventListener('change', schedule);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    return { label };
})();

window.TableCards = TableCards;
