/**
 * Cost preview lines for the start and lease-extension dialogs (E25-T02): "About $1.54 for 8 h
 * ($0.19/h)" for a start, "Extra ~ $0.38 for 2 h" for an extension, with notes for unknown or
 * approximate prices. A failed request shows nothing; the dialog works without it.
 */
const CostPreview = (function() {
    'use strict';

    const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });

    function money(v) {
        return usd.format(Number(v || 0));
    }

    function load(envId, scope, hours, extension) {
        return new Promise((resolve, reject) => {
            ApiClient.get(Config.API.operations.costPreview(envId, scope, hours, extension), { suppressGlobalError: true })
                .done(resolve).fail(reject);
        });
    }

    /** The line for a preview; null when there is nothing to price. */
    function text(preview, extension) {
        if (!preview || !preview.vms || !preview.vms.length) {
            return null;
        }
        const notes = [];
        if (preview.unknownPriceCount > 0) {
            notes.push(`price unknown for ${preview.unknownPriceCount} VM${preview.unknownPriceCount === 1 ? '' : 's'}`);
        }
        if (preview.approximate) notes.push('approximate');
        const main = extension
            ? `Extra ~ ${money(preview.estimatedTotal)} for ${preview.hours} h`
            : `About ${money(preview.estimatedTotal)} for ${preview.hours} h (${money(preview.hourlyTotal)}/h)`;
        return notes.length ? `${main} · ${notes.join(', ')}` : main;
    }

    /**
     * Fills the element with the preview line; empties it on failure or when nothing is priced.
     * isCurrent (optional) drops a late answer after the dialog moved on.
     */
    function render($el, envId, scope, hours, extension, isCurrent) {
        if (!$el || !$el.length) return Promise.resolve();
        $el.text('');
        return load(envId, scope, hours, extension)
            .then(preview => {
                if (isCurrent && !isCurrent()) return;
                const line = text(preview, extension);
                $el.text(line || '').prop('hidden', !line);
            })
            .catch(() => { if (!isCurrent || isCurrent()) $el.text('').prop('hidden', true); });
    }

    return { text, render, money };
})();

window.CostPreview = CostPreview;
