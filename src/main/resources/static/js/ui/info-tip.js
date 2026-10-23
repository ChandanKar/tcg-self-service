/**
 * VM Self-Service Platform - Info tip (U20-T08)
 * A small "i" button beside a label that says what a term means. The text shows while the button
 * is hovered or focused (and stays while the pointer is on the text), Escape hides it, and screen
 * readers read it as the button's description (WCAG 1.4.13: dismissible, hoverable, persistent).
 * Not for table headings: the text would become part of the column's name.
 *
 *   InfoTip.markup('team-cc-tip', 'Cost centre', InfoTip.TERMS.costCentre)
 */
const InfoTip = (function() {
    'use strict';

    function esc(value) {
        return String(value == null ? '' : value).replace(/[&<>"']/g,
            c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    }

    /** The button and its text; id names the text (aria-describedby), term names the button. */
    function markup(id, term, text) {
        return `<span class="info-tip"><button type="button" class="info-tip-btn" aria-label="What ${esc(term)} means"`
            + ` aria-describedby="${esc(id)}"><span aria-hidden="true">i</span></button>`
            + `<span class="info-tip-text" role="tooltip" id="${esc(id)}">${esc(text)}</span></span>`;
    }

    if (typeof document !== 'undefined') {
        // Escape hides an open tip until the pointer or focus leaves it. It is taken first (capture), so
        // inside a dialog the first Escape closes the tip and only the next one the dialog.
        document.addEventListener('keydown', e => {
            if (e.key !== 'Escape') return;
            const open = [...document.querySelectorAll('.info-tip:hover, .info-tip:focus-within')]
                .filter(tip => !tip.classList.contains('info-tip-closed'));
            if (!open.length) return;
            open.forEach(tip => tip.classList.add('info-tip-closed'));
            e.preventDefault();
            e.stopPropagation();
        }, true);
        const reopen = e => {
            const tip = e.target && e.target.closest ? e.target.closest('.info-tip') : null;
            if (tip) tip.classList.remove('info-tip-closed');
        };
        document.addEventListener('mouseout', reopen);
        document.addEventListener('focusout', reopen);
    }

    /** Words said the same wherever the term appears. */
    const TERMS = {
        costCentre: "The finance code this team's cloud spend is charged to. It heads the team's line on Chargeback, goes into the FOCUS export, and is written to the team's environments in AWS as the cost-center tag."
    };

    return { markup, TERMS };
})();

if (typeof window !== 'undefined') window.InfoTip = InfoTip;
