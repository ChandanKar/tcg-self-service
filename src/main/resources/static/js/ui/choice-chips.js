/**
 * VM Self-Service Platform - Choice chips (U07-T05, LockLease.dc.html)
 * A small set of mutually exclusive choices shown as buttons ("1 hour", "2 hours", "End of day"):
 * a fieldset with a legend and one button per option carrying aria-pressed. Clicking one selects
 * it and fires a "choice-change" event on the fieldset with the value in event.detail.
 *
 *   ChoiceChips.markup({ id, legend, options: [{ value, label }], selected })
 *   ChoiceChips.value(id)   // the selected value as a string ('' for none)
 */
const ChoiceChips = (function() {
    'use strict';

    function markup({ id, legend, options = [], selected }) {
        const chosen = options.some(o => String(o.value) === String(selected)) ? String(selected)
            : (options[0] ? String(options[0].value) : '');
        const buttons = options.map(o => Utils.html`<button type="button" class="choice-chip" data-value="${String(o.value)}"
                aria-pressed="${String(String(o.value) === chosen)}">${o.label}</button>`).join('');
        return Utils.html`<fieldset class="choice-chips" id="${id}"><legend>${legend}</legend>`
            + `<div class="choice-chips-row">${buttons}</div></fieldset>`;
    }

    function value(id) {
        const pressed = document.querySelector(`#${CSS.escape(id)} .choice-chip[aria-pressed="true"]`);
        return pressed ? pressed.getAttribute('data-value') : '';
    }

    function select(button) {
        const set = button.closest('.choice-chips');
        if (!set || button.disabled) return;
        set.querySelectorAll('.choice-chip').forEach(b => b.setAttribute('aria-pressed', String(b === button)));
        set.dispatchEvent(new CustomEvent('choice-change', { detail: button.getAttribute('data-value'), bubbles: true }));
    }

    if (typeof document !== 'undefined') {
        document.addEventListener('click', e => {
            const button = e.target.closest && e.target.closest('.choice-chip');
            if (button) select(button);
        });
    }

    return { markup, value };
})();

if (typeof window !== 'undefined') window.ChoiceChips = ChoiceChips;
