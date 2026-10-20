/**
 * VM Self-Service Platform - Filter group (U04-T02)
 * The "Show: All / Running / Stopped / Problems" switch of Environments15.dc.html and
 * EnvDetailLarge.dc.html: a role=group of toggle buttons, the pressed one filled navy.
 *
 *   FilterGroup.markup({label: 'Show', options: [{key, label, count}], active, action})
 *
 * Each button carries data-action and data-filter, so the caller registers one Actions handler and
 * re-renders with the new active key.
 */
const FilterGroup = (function() {
    'use strict';

    function markup(opts) {
        const { label = 'Show', options = [], active, action } = opts;
        const current = options.some(o => o.key === active) ? active : (options[0] && options[0].key);
        const buttons = options.map(o => {
            const count = o.count == null ? '' : Utils.html` <span class="filter-count">${o.count}</span>`;
            return Utils.html`<button type="button" class="filter-option" data-action="${action}" data-filter="${o.key}"
                aria-pressed="${String(o.key === current)}">` + Utils.escapeHtml(o.label) + count + '</button>';
        }).join('');
        return Utils.html`<div class="filter-group" role="group" aria-label="${label}">` + buttons + '</div>';
    }

    return { markup };
})();
