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
     * isCurrent (optional) drops a late answer after the dialog moved on. Resolves with the
     * preview (null on failure).
     */
    function render($el, envId, scope, hours, extension, isCurrent) {
        if (!$el || !$el.length) return Promise.resolve(null);
        $el.text('');
        return load(envId, scope, hours, extension)
            .then(preview => {
                if (isCurrent && !isCurrent()) return null;
                const line = text(preview, extension);
                $el.text(line || '').prop('hidden', !line);
                return preview;
            })
            .catch(() => {
                if (!isCurrent || isCurrent()) $el.text('').prop('hidden', true);
                return null;
            });
    }

    // ============= Team quotas (E25-T08) =============

    function amount(q, value) {
        const n = Number(value || 0);
        return q.metric === 'COST' ? money(n) : `${Math.round(n * 10) / 10} h`;
    }

    /** A labelled usage bar for one quota. */
    function quotaBar(q) {
        const pct = Math.min(100, Number(q.percent || 0));
        const tone = Number(q.percent) >= 100 ? 'bg-danger' : Number(q.percent) >= q.warnPercent ? 'bg-warning' : 'bg-success';
        const label = `${q.metric === 'COST' ? 'Spend' : 'VM-hours'}: ${amount(q, q.used)} of ${amount(q, q.limit)}`;
        return Utils.html`<div class="quota-bar mb-2" data-metric="${q.metric}">
            <div class="d-flex justify-content-between small"><span>${label}</span><span>${Number(q.percent)}%</span></div>
            <div class="progress" role="progressbar" aria-label="${label}" aria-valuenow="${pct}" aria-valuemin="0" aria-valuemax="100">
                <div class="progress-bar ${tone}" style="width: ${pct}%"></div></div></div>`;
    }

    /**
     * The start dialog's quota line: this month's usage plus what the start adds, in warning
     * style past the warning level and error style when a BLOCK quota would be exceeded, which
     * also disables $confirm with the reason. Nothing when the user cannot see the team's usage.
     */
    function renderQuota($el, teamId, preview, $confirm) {
        if (!$el || !$el.length || !teamId || !preview) return Promise.resolve();
        return new Promise(resolve => {
            ApiClient.get(Config.API.teams.quotaUsage(teamId), { suppressGlobalError: true })
                .done(quotas => {
                    const active = (quotas || []).filter(q => q.active);
                    if (!active.length || !$el.closest('body').length) { resolve(); return; }
                    let worst = 'ok';
                    const lines = active.map(q => {
                        const adds = q.metric === 'COST' ? Number(preview.estimatedTotal || 0) : preview.vms.length * preview.hours;
                        const projected = Number(q.used) + adds;
                        const over = projected > Number(q.limit);
                        const near = projected * 100 >= Number(q.limit) * q.warnPercent;
                        if (over && q.enforcement === 'BLOCK') worst = 'block';
                        else if ((over || near) && worst !== 'block') worst = 'warn';
                        return `Team quota: ${amount(q, q.used)} of ${amount(q, q.limit)} used; this start adds ~${amount(q, adds)}`
                            + (over ? (q.enforcement === 'BLOCK' ? ' (over the limit: blocked)'
                                : q.enforcement === 'REQUIRE_APPROVAL' ? ' (over the limit: needs approval)' : ' (over the limit)') : '');
                    });
                    $el.removeClass('text-warning text-danger').addClass(worst === 'block' ? 'text-danger' : worst === 'warn' ? 'text-warning' : '')
                        .html(lines.map(l => Utils.html`<div>${l}</div>`).join('')).prop('hidden', false)
                        .attr('data-quota', worst);
                    if (worst === 'block' && $confirm && $confirm.length) {
                        $confirm.prop('disabled', true).attr('title', 'The team quota would be exceeded');
                    }
                    resolve();
                })
                .fail(() => resolve());
        });
    }

    return { text, render, money, quotaBar, renderQuota };
})();

window.CostPreview = CostPreview;
