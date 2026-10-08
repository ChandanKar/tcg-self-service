/**
 * Cost card on the environment detail page (E18-T03, G7): month to date with the change against
 * last month, actuals, a run-rate forecast, a 30-day trend, the top VMs, the next scheduled
 * stop/start and savings. Loads after the page renders and never blocks it; a late response for
 * an environment no longer shown is dropped.
 */
const EnvCost = (function() {
    'use strict';

    let chart = null;

    function money(amount) {
        return amount === null || amount === undefined ? '—' : Utils.formatCurrency(Number(amount));
    }

    function relative(ts) {
        if (!ts) return '';
        const minutes = Math.round((new Date(ts).getTime() - Date.now()) / 60000);
        if (minutes < 60) return `in ${Math.max(minutes, 1)} min`;
        const hours = Math.round(minutes / 60);
        if (hours < 48) return `in ${hours} h`;
        return `in ${Math.round(hours / 24)} days`;
    }

    function when(ts) {
        return new Date(ts).toLocaleString([], { weekday: 'short', hour: '2-digit', minute: '2-digit' });
    }

    function disposeChart() {
        if (chart) {
            chart.dispose();
            chart = null;
        }
    }

    /** Load and render into #env-cost-card. */
    function render(env) {
        const $card = $('#env-cost-card');
        if (!$card.length || !env) return;
        const token = ContentRouter.token();
        disposeChart();
        $card.html('<div class="env-cost-loading text-muted small"><i class="fas fa-spinner fa-spin"></i> Loading cost…</div>');
        ApiClient.get(Config.API.environments.cost(env.environmentId), { suppressGlobalError: true })
            .done(function(cost) {
                if (!ContentRouter.isCurrent(token)) return; // navigated away meanwhile
                $card.html(buildCard(cost));
                drawTrend(cost);
                ContentRouter.onLeave(disposeChart);
            })
            .fail(function() {
                if (!ContentRouter.isCurrent(token)) return;
                $card.html(`<div class="env-cost-error small" role="alert">Couldn't load the cost.
                    <button type="button" class="btn btn-link btn-sm p-0 align-baseline" data-action="env-cost-retry">Retry</button></div>`);
                $card.data('env', env);
            });
        $card.data('env', env);
    }

    function retry() {
        const env = $('#env-cost-card').data('env');
        if (env) render(env);
    }

    function changeBadge(change) {
        if (change === null || change === undefined) return '';
        const up = Number(change) > 0;
        return Utils.html`<span class="env-cost-change ${up ? 'up' : 'down'}"
            title="Against the same days last month">${up ? '▲' : '▼'} ${Math.abs(Number(change))}%</span>`;
    }

    function scheduleLine(cost) {
        const s = cost.scheduleStatus;
        if (!s || s.ruleCount === 0) {
            return Auth.isEnvAdmin && Auth.isEnvAdmin()
                ? '<div class="small">No schedule — <a href="#/automation-rules">set one up</a></div>'
                : '<div class="small text-muted">No schedule</div>';
        }
        const parts = [];
        if (s.nextStop) parts.push(Utils.html`next stop ${when(s.nextStop)} (${relative(s.nextStop)})`);
        if (s.nextStart) parts.push(Utils.html`next start ${when(s.nextStart)}`);
        return Utils.html`<div class="small" id="env-cost-schedule"><i class="fas fa-clock"></i>
            ${s.ruleCount} schedule${s.ruleCount === 1 ? '' : 's'}${parts.length ? ' · ' : ''}${Utils.raw(parts.join(' · '))}</div>`;
    }

    function buildCard(cost) {
        const groups = cost.scope === 'GROUPS';
        const lines = [];
        if (!groups && cost.actualDays > 0) {
            lines.push(Utils.html`<div class="small">Actual (${cost.actualDays} day${cost.actualDays === 1 ? '' : 's'}): <strong>${money(cost.monthToDateActual)}</strong></div>`);
        }
        if (!groups && cost.forecastMonthEnd !== null && cost.forecastMonthEnd !== undefined) {
            lines.push(Utils.html`<div class="small" title="${cost.forecastBasis || ''}">If the current rate continues: <strong>${money(cost.forecastMonthEnd)}</strong> this month</div>`);
        }
        lines.push(scheduleLine(cost));
        if (cost.leaseStatus && cost.leaseStatus.endsAt) {
            lines.push(Utils.html`<div class="small"><i class="fas fa-hourglass-half"></i> Lease ends ${relative(cost.leaseStatus.endsAt)}</div>`);
        }
        if (cost.savingsMtd && Number(cost.savingsMtd) > 0) {
            lines.push(Utils.html`<div class="small text-success">Saved ${money(cost.savingsMtd)} this month${cost.savingsSource ? ' (' + cost.savingsSource + ')' : ''}</div>`);
        }
        const top = (cost.topVms || []).filter(v => Number(v.mtdCost) > 0);
        const topHtml = top.length === 0 ? '' : Utils.html`
            <div class="env-cost-top">
                <div class="small text-muted text-uppercase mb-1">Top VMs this month</div>
                ${Utils.raw(top.map(v => Utils.html`<div class="d-flex justify-content-between small">
                    <span class="text-truncate me-2">${v.name}</span><span>${money(v.mtdCost)}</span></div>`).join(''))}
            </div>`;

        return Utils.html`
            <div class="card mb-3 env-cost-card">
                <div class="card-body py-2">
                    <div class="env-cost-grid">
                        <div>
                            <div class="small text-muted text-uppercase">Cost this month${groups ? ' — your groups only' : ''}</div>
                            <div class="env-cost-mtd" id="env-cost-mtd">${money(cost.monthToDateEstimated)} ${Utils.raw(changeBadge(cost.changePercent))}</div>
                            ${Utils.raw(lines.join(''))}
                        </div>
                        ${groups ? '' : Utils.raw('<div class="env-cost-trend" id="env-cost-trend" aria-label="Daily cost, last 30 days"></div>')}
                        ${Utils.raw(topHtml)}
                    </div>
                </div>
            </div>`;
    }

    function drawTrend(cost) {
        const el = document.getElementById('env-cost-trend');
        if (!el || !window.echarts || !(cost.daily || []).length) return;
        chart = echarts.init(el, null, { renderer: 'svg' });
        chart.setOption({
            grid: { left: 4, right: 4, top: 6, bottom: 4 },
            xAxis: { type: 'category', show: false, data: cost.daily.map(d => d.date) },
            yAxis: { type: 'value', show: false },
            tooltip: { trigger: 'axis', valueFormatter: v => money(v) },
            series: [{ type: 'line', data: cost.daily.map(d => Number(d.estimated)), smooth: true, symbol: 'none',
                areaStyle: { opacity: 0.15 } }]
        });
    }

    return { render, retry };
})();

window.EnvCost = EnvCost;

Actions.register('env-cost-retry', () => EnvCost.retry());
