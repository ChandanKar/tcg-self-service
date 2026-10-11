/**
 * VM Self-Service Platform - Table-to-cards labels (E13-T06)
 *
 * On phones a `table.table-cards` shows each row as a card whose cells name their column from
 * `data-label` (see css/components/tables.css). Rather than repeat every header in every row's
 * markup, this fills in missing data-labels from the table's own <thead>, for tables rendered now
 * and for rows re-rendered later. Cells that already carry a data-label keep it.
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

    /** Label every card table inside `root` (default: the whole document). */
    function label(root) {
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
        new MutationObserver(schedule).observe(document.body, { childList: true, subtree: true });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    return { label };
})();

window.TableCards = TableCards;
