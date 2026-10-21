/**
 * Cost anomalies (E21-T04): the list under the environment's cost card (last 90 days) and the
 * table the Cost Management page uses. Owners say whether an anomaly was real; the verdict is
 * stored and, when enabled on the server, sent to AWS.
 */
const CostAnomalies = (function() {
    'use strict';

    const VERDICTS = [
        { value: 'YES', label: 'Real', title: 'An unexpected cost worth looking into' },
        { value: 'NO', label: 'Not an anomaly', title: 'Normal spend; AWS was wrong' },
        { value: 'PLANNED_ACTIVITY', label: 'Planned', title: 'Expected: planned work' }
    ];

    function money(v) {
        return v === null || v === undefined ? '—' : Utils.formatCurrency(Number(v));
    }

    function verdictLabel(value) {
        const v = VERDICTS.find(x => x.value === value);
        return v ? v.label : '';
    }

    function dates(a) {
        if (!a.anomalyStart) return '';
        return a.anomalyEnd && a.anomalyEnd !== a.anomalyStart ? `${a.anomalyStart} – ${a.anomalyEnd}` : `since ${a.anomalyStart}`;
    }

    function feedbackCell(a) {
        const current = a.feedback ? Utils.html`<span class="badge text-bg-light border anomaly-verdict">${verdictLabel(a.feedback)}</span>` : '';
        if (!a.canGiveFeedback) return current || '<span class="text-muted">—</span>';
        const buttons = VERDICTS.map(v => Utils.html`<button type="button" class="btn btn-sm ${a.feedback === v.value ? 'btn-primary' : 'btn-ghost'}"
                data-action="anomaly-feedback" data-id="${a.anomalyId}" data-value="${v.value}" title="${v.title}"
                aria-pressed="${a.feedback === v.value ? 'true' : 'false'}">${v.label}</button>`).join('');
        return `<div class="d-flex flex-wrap gap-1 anomaly-feedback" role="group" aria-label="Was this a real anomaly?">${buttons}</div>`;
    }

    /** Table rows for a list of anomalies; showEnvironment adds the environment column. */
    function tableHtml(anomalies, showEnvironment, emptyText) {
        const rows = anomalies.length ? anomalies.map(a => Utils.html`
            <tr data-anomaly="${a.anomalyId}">
                ${Utils.raw(showEnvironment ? Utils.html`<td data-label="Environment">${a.environmentId
                    ? Utils.raw(Utils.html`<a href="#/environments/${a.environmentId}">${a.environmentName || a.environmentId}</a>`)
                    : Utils.raw('<span class="text-muted">Unlinked monitor</span>')}</td>` : '')}
                <td data-label="Impact"><strong>${money(a.totalImpact)}</strong>${Utils.raw(a.totalExpected !== null && a.totalExpected !== undefined
                    ? Utils.html`<div class="small text-muted">expected ${money(a.totalExpected)}</div>` : '')}</td>
                <td data-label="When">${dates(a)}</td>
                <td data-label="Top cause">${a.topCause || '—'}</td>
                <td data-label="Verdict">${Utils.raw(feedbackCell(a))}${Utils.raw(typeof JiraTickets !== 'undefined' && a.environmentId
                    ? '<div class="mt-1">' + JiraTickets.cell('ANOMALY', a.anomalyId) + '</div>' : '')}</td>
            </tr>`).join('')
            : Utils.html`<tr><td colspan="${showEnvironment ? 5 : 4}" class="text-muted text-center py-3">${emptyText}</td></tr>`;
        return `<div class="table-responsive"><table class="table table-sm table-cards mb-0 anomaly-table">
            <thead><tr>${showEnvironment ? '<th>Environment</th>' : ''}<th>Impact</th><th>When</th><th>Top cause</th><th>Verdict</th></tr></thead>
            <tbody>${rows}</tbody></table></div>`;
    }

    // ============= Environment page =============

    let shown = [];

    /**
     * The newest anomaly of the last 2 days as the gold Cost alert (U07-T07): "Spend was 3× usual:"
     * with the amounts and the top cause; Review (Cost management) for administrators. The 90-day
     * table moves to Insights (U08-T08).
     */
    function renderEnvPanel(env) {
        if (!env || typeof EnvAlerts === 'undefined') return;
        const token = ContentRouter.token();
        ApiClient.get(Config.API.costAnomalies.forEnvironment(env.environmentId, 2), { suppressGlobalError: true })
            .done(list => {
                if (!ContentRouter.isCurrent(token)) return;
                shown = list || [];
                EnvAlerts.set('cost', alertFor(shown), env.environmentId);
            })
            .fail(() => { if (ContentRouter.isCurrent(token)) EnvAlerts.set('cost', null, env.environmentId); });
    }

    function alertFor(list) {
        const newest = (list || []).slice().sort((a, b) => String(b.anomalyStart || '').localeCompare(String(a.anomalyStart || '')))[0];
        if (!newest) return null;
        const expected = newest.totalExpected === null || newest.totalExpected === undefined ? null : Number(newest.totalExpected);
        const impact = Number(newest.totalImpact || 0);
        const cause = newest.topCause ? ` Top cause: ${newest.topCause}.` : '';
        const action = Auth.isAdmin && Auth.isAdmin() ? { label: 'Review', href: '#/cost-management?tab=anomalies' } : null;
        if (expected && expected > 0) {
            const actual = expected + impact;
            const ratio = Math.round(actual / expected * 10) / 10;
            return { tone: 'gold', title: `Spend was ${ratio}× usual:`,
                detail: `${money(actual)} against about ${money(expected)}.${cause}`, action };
        }
        return { tone: 'gold', title: 'Spend was higher than usual:', detail: `${money(impact)} more than expected.${cause}`, action };
    }

    function giveFeedback(el) {
        const id = el.dataset.id;
        const value = el.dataset.value;
        ApiClient.post(Config.API.costAnomalies.feedback(id), { value }, { suppressGlobalError: true })
            .done(() => {
                Notifications.show(`Marked as "${verdictLabel(value)}".`, 'success');
                $(`[data-action="anomaly-feedback"][data-id="${CSS.escape(id)}"]`).each(function() {
                    const on = this.dataset.value === value;
                    $(this).toggleClass('btn-primary', on).toggleClass('btn-ghost', !on).attr('aria-pressed', on ? 'true' : 'false');
                });
            })
            .fail(xhr => Notifications.showError((xhr.responseJSON && xhr.responseJSON.message) || 'Could not save the verdict.'));
    }

    return { tableHtml, renderEnvPanel, giveFeedback };
})();

window.CostAnomalies = CostAnomalies;

Actions.register('anomaly-feedback', el => CostAnomalies.giveFeedback(el));
