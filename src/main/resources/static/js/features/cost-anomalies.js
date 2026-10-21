/**
 * Cost anomalies (E21-T04): the list in the environment's insights (last 90 days) and the Unusual
 * spend tab of Cost management (U15-T05, Anomalies.dc.html). Owners say whether the spend was
 * expected; the verdict is stored and, when enabled on the server, sent to AWS.
 */
const CostAnomalies = (function() {
    'use strict';

    /** Shared by both lists (U15-T05): was this spend expected? */
    const VERDICTS = [
        { value: 'YES', label: 'Unexpected', title: 'An unexpected cost worth looking into' },
        { value: 'PLANNED_ACTIVITY', label: 'Planned', title: 'Expected: planned work' },
        { value: 'NO', label: 'Not unusual', title: 'Normal spend; AWS was wrong' }
    ];
    const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

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

    // ============= Unusual spend tab (U15-T05) =============

    function money0(v) {
        return v === null || v === undefined ? '—' : '$' + Math.round(Number(v)).toLocaleString('en-US');
    }

    /** "18 Oct · 1 day", "15–17 Oct · 3 days", "30 Sep – 2 Oct · 3 days"; "since 18 Oct" while open. */
    function whenText(a) {
        if (!a.anomalyStart) return '';
        const parse = iso => { const [y, m, d] = String(iso).split('-').map(Number); return { y, m, d, t: Date.UTC(y, m - 1, d) }; };
        const from = parse(a.anomalyStart);
        const label = p => `${p.d} ${MONTHS[p.m - 1]}`;
        if (!a.anomalyEnd) return `since ${label(from)}`;
        const to = parse(a.anomalyEnd);
        const days = Math.round((to.t - from.t) / 86400000) + 1;
        const range = days <= 1 ? label(from) : from.m === to.m && from.y === to.y ? `${from.d}–${label(to)}` : `${label(from)} – ${label(to)}`;
        return `${range} · ${days} day${days === 1 ? '' : 's'}`;
    }

    /** Unexpected / Planned / Not unusual as one radio group; the chosen one is filled. */
    function verdictRadios(a, name) {
        if (!a.canGiveFeedback) return a.feedback ? Utils.html`<span class="anomaly-verdict">${verdictLabel(a.feedback)}</span>` : '<span class="text-muted">—</span>';
        const chosen = VERDICTS.some(v => v.value === a.feedback) ? a.feedback : null;
        const radios = VERDICTS.map((v, i) => {
            const on = v.value === chosen;
            const focusable = on || (!chosen && i === 0);
            return Utils.html`<button type="button" role="radio" class="anomaly-seg" aria-checked="${on ? 'true' : 'false'}"
                tabindex="${focusable ? '0' : '-1'}" title="${v.title}" data-action="anomaly-feedback" data-id="${a.anomalyId}"
                data-value="${v.value}">${v.label}</button>`;
        }).join('');
        return Utils.html`<span class="anomaly-verdicts" role="radiogroup" aria-label="${name}: was this expected?">${Utils.raw(radios)}</span>`;
    }

    /** The fleet table: environment and when, spent with the extra, usual, top cause, verdict, ticket. */
    function fleetTableHtml(anomalies, emptyText) {
        const rows = anomalies.length ? anomalies.map(a => {
            const name = a.environmentName || a.environmentId || 'Unlinked monitor';
            const expected = a.totalExpected === null || a.totalExpected === undefined ? null : Number(a.totalExpected);
            const impact = Number(a.totalImpact || 0);
            const spent = expected === null ? null : expected + impact;
            return Utils.html`
            <tr data-anomaly="${a.anomalyId}">
                <td>${Utils.raw(a.environmentId
                    ? Utils.html`<a class="rs-name" href="#/environments/${a.environmentId}">${name}</a>`
                    : '<span class="text-muted">Unlinked monitor</span>')}<span class="rs-sub">${whenText(a)}</span></td>
                <td class="cost-num cost-mono">${spent === null ? '—' : money0(spent)}<span class="anomaly-extra">+${money0(impact)}</span></td>
                <td class="cost-num cost-mono anomaly-usual">${money0(expected)}</td>
                <td class="anomaly-cause">${a.topCause || '—'}</td>
                <td>${Utils.raw(verdictRadios(a, name))}</td>
                <td class="anomaly-ticket">${Utils.raw(typeof JiraTickets !== 'undefined' && a.environmentId ? JiraTickets.cell('ANOMALY', a.anomalyId) : '')}</td>
            </tr>`;
        }).join('') : Utils.html`<tr><td colspan="6" class="text-muted text-center py-3">${emptyText}</td></tr>`;
        return `<div class="cost-scroll"><table class="cost-table anomaly-fleet-table" id="cost-anomalies-table">
            <thead><tr><th scope="col">Environment · when</th><th scope="col" class="cost-num">Spent</th><th scope="col" class="cost-num">Usual</th>
                <th scope="col">Top cause</th><th scope="col">Was this expected?</th><th scope="col"><span class="visually-hidden">Ticket</span></th></tr></thead>
            <tbody>${rows}</tbody></table></div>`;
    }

    /** Arrow keys move between a verdict group's radios (Space or Enter chooses). */
    function moveFocus(event) {
        const keys = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 };
        if (!(event.key in keys)) return;
        const group = event.target.closest('.anomaly-verdicts');
        if (!group) return;
        const radios = Array.from(group.querySelectorAll('[role="radio"]'));
        const next = radios[(radios.indexOf(event.target) + keys[event.key] + radios.length) % radios.length];
        event.preventDefault();
        radios.forEach(r => { r.tabIndex = r === next ? 0 : -1; });
        next.focus();
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
                    if (this.getAttribute('role') === 'radio') {
                        this.setAttribute('aria-checked', on ? 'true' : 'false');
                        this.tabIndex = on ? 0 : -1;
                    } else {
                        $(this).toggleClass('btn-primary', on).toggleClass('btn-ghost', !on).attr('aria-pressed', on ? 'true' : 'false');
                    }
                });
            })
            .fail(xhr => Notifications.showError((xhr.responseJSON && xhr.responseJSON.message) || 'Could not save the verdict.'));
    }

    return { tableHtml, fleetTableHtml, renderEnvPanel, giveFeedback, moveFocus, model: { whenText, VERDICTS } };
})();

if (typeof window !== 'undefined') window.CostAnomalies = CostAnomalies;

if (typeof Actions !== 'undefined') Actions.register('anomaly-feedback', el => CostAnomalies.giveFeedback(el));
if (typeof document !== 'undefined') document.addEventListener('keydown', e => CostAnomalies.moveFocus(e));
