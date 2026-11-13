/**
 * Cost on the environment page (E18-T03, G7), since U07-T06 part of "About this environment"
 * (EnvDetail.dc.html): the cost block (#env-cost-card) with month to date and the change against
 * last month, the last 14 days as bars (weekends lighter, today gold), actuals, a run-rate forecast
 * and savings; and the Schedule fact (#env-fact-schedule) from the same response. Loads after the
 * page renders and never blocks it; a late response for an environment no longer shown is
 * dropped. With carbon data on (E37-T03), last month's attributed emissions follow in one line.
 */
const EnvCost = (function() {
    'use strict';

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

    const pad = n => String(n).padStart(2, '0');
    const schedules = {};   // envId -> scheduleStatus, for the start dialog's "Schedule stop" chip (U08-T02)

    /** "Mon 20:00", 24-hour like the rest of the app. */
    function when(ts) {
        const d = new Date(ts);
        return `${d.toLocaleDateString([], { weekday: 'short' })} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
    }

    /** Load and render the cost block (#env-cost-card) and the Schedule fact (#env-fact-schedule). */
    function render(env) {
        const $card = $('#env-cost-card');
        if (!$card.length || !env) return;
        const token = ContentRouter.token();
        $card.html(UiStates.loading('lines', { count: 2 }));
        ApiClient.get(Config.API.environments.cost(env.environmentId), { suppressGlobalError: true })
            .done(function(cost) {
                if (!ContentRouter.isCurrent(token)) return; // navigated away meanwhile
                schedules[env.environmentId] = cost.scheduleStatus || null;
                $card.html(buildCard(cost, env));
                $('#env-fact-schedule').html(scheduleFact(cost));
                mountNamespaces(env, cost);
                if (cost.scope !== 'GROUPS') addCarbon($card, env, token);
            })
            .fail(function(xhr) {
                if (!ContentRouter.isCurrent(token)) return;
                $card.html(UiStates.failed({ what: 'the cost', xhr, retryAction: 'env-cost-retry', className: 'env-cost-error' }));
                $card.data('env', env);
            });
        $card.data('env', env);
    }

    /** Last full month's carbon in one line (E37-T03); nothing while carbon is off or has no data. */
    function addCarbon($card, env, token) {
        if (typeof Capabilities === 'undefined' || !Capabilities.has('carbon')) return; // U02-T03
        ApiClient.get(Config.API.environments.carbon(env.environmentId), { suppressGlobalError: true })
            .done(function(carbon) {
                if (!ContentRouter.isCurrent(token) || !carbon || !carbon.latestMonth) return;
                const kg = carbon.latestMbmKg !== null && carbon.latestMbmKg !== undefined ? carbon.latestMbmKg : carbon.latestLbmKg;
                const basis = carbon.latestMbmKg !== null && carbon.latestMbmKg !== undefined ? 'market-based' : 'location-based';
                const label = new Date(carbon.latestMonth + '-01T00:00:00Z').toLocaleDateString([], { month: 'short', year: 'numeric', timeZone: 'UTC' });
                $card.find('.env-cost-block').append(Utils.html`
                    <div class="env-carbon env-fact-note" id="env-carbon" title="${carbon.coverageNote}">
                        Carbon (${label}): <strong id="env-carbon-kg">${formatKg(kg)} kg CO₂e</strong> (${basis})
                    </div>`);
            });
    }

    function formatKg(kg) {
        const n = Number(kg);
        return n >= 100 ? Math.round(n).toLocaleString() : n.toLocaleString(undefined, { maximumFractionDigits: 1 });
    }

    function isEks(env) {
        return String(env && env.serviceType || '').toUpperCase() === 'EKS';
    }

    /**
     * EKS environments seen whole show their cost by namespace on the page (E26-T05, U07-T08,
     * EksEnv.dc.html); group-only views do not (the cost scope says which).
     */
    function mountNamespaces(env, cost) {
        const $section = $('#env-eks-namespaces-section');
        if (!$section.length || !isEks(env) || cost.scope === 'GROUPS' || typeof EksWorkloads === 'undefined') return;
        $section.prop('hidden', false);
        EksWorkloads.mount($('#env-eks-namespaces'), d => Config.API.environments.eksWorkloadCost(env.environmentId, d));
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

    /**
     * The Schedule fact: "None" and "Started and stopped by hand" (with Set one up for environment
     * admins), else the next stop and start and how many schedule rules apply.
     */
    function scheduleFact(cost) {
        const s = cost.scheduleStatus;
        if (!s || s.ruleCount === 0) {
            const setUp = Auth.isEnvAdmin && Auth.isEnvAdmin()
                ? '<a class="env-fact-action" href="#/automation-rules">Set one up</a>' : '';
            return `<div class="env-fact schedule-fact">
                    <div class="env-fact-head"><span>Schedule</span><span class="env-fact-actions">${setUp}</span></div>
                    <div class="env-fact-value" id="env-cost-schedule">None</div>
                    <div class="env-fact-note">Started and stopped by hand</div>
                </div>`;
        }
        const value = scheduleValue(s);
        const stopNote = s.nextStop ? ' · stops ' + relative(s.nextStop)
            : s.partialStop && s.partialStop.at ? ` · ${s.partialStop.scope} stops ${relative(s.partialStop.at)}` : '';
        return Utils.html`<div class="env-fact schedule-fact">
                <div class="env-fact-head"><span>Schedule</span></div>
                <div class="env-fact-value" id="env-cost-schedule">${value}</div>
                <div class="env-fact-note">${s.ruleCount} schedule rule${s.ruleCount === 1 ? '' : 's'}${stopNote}</div>
            </div>`;
    }

    /**
     * The Schedule fact's value (U23-T04): the next stop and start, the sooner first ("Next start Mon
     * 08:00 · next stop Mon 20:00"); a rule on part of the environment names what it acts on ("Group-2
     * stops Fri 12:30"). Staging's ise said "Next stop Fri 12:30" while its only rule stopped Group-2.
     */
    function scheduleValue(s, whenFn = when) {
        const items = [];
        if (s.nextStop) items.push({ at: s.nextStop, text: `next stop ${whenFn(s.nextStop)}` });
        if (s.partialStop && s.partialStop.at) items.push({ at: s.partialStop.at, text: `${s.partialStop.scope} stops ${whenFn(s.partialStop.at)}` });
        if (s.nextStart) items.push({ at: s.nextStart, text: `next start ${whenFn(s.nextStart)}` });
        if (s.partialStart && s.partialStart.at) items.push({ at: s.partialStart.at, text: `${s.partialStart.scope} starts ${whenFn(s.partialStart.at)}` });
        if (!items.length) return 'Nothing planned';
        const value = items.sort((a, b) => new Date(a.at) - new Date(b.at)).map(i => i.text).join(' · ');
        return value.startsWith('next ') ? 'N' + value.slice(1) : value;
    }

    /** The last 14 days as bars: weekends lighter, today gold; a summary for screen readers. */
    function dailyBars(daily) {
        const days = (daily || []).slice(-14);
        if (!days.length) return '';
        const values = days.map(d => Number(d.estimated) || 0);
        const max = Math.max(...values, 0.01);
        const today = new Date().toISOString().slice(0, 10);
        const w = 20, h = 54;
        const bars = days.map((d, i) => {
            const v = values[i];
            const bh = Math.max(2, Math.round(v / max * (h - 4)));
            const date = String(d.date).slice(0, 10);
            const dow = new Date(date + 'T00:00:00Z').getUTCDay();
            const kind = date === today ? 'today' : (dow === 0 || dow === 6) ? 'weekend' : 'weekday';
            return `<rect class="env-cost-bar ${kind}" x="${i * w}" y="${h - bh}" width="${w - 5}" height="${bh}" rx="2"><title>${Utils.escapeHtml(date)}: ${Utils.escapeHtml(money(v))}</title></rect>`;
        }).join('');
        const label = `Daily cost for the last ${days.length} days, from ${money(Math.min(...values))} to ${money(Math.max(...values))} a day`;
        return `<svg class="env-cost-bars" id="env-cost-trend" viewBox="0 0 ${days.length * w} ${h}" preserveAspectRatio="none"
                    role="img" aria-label="${Utils.escapeHtml(label)}">${bars}</svg>`;
    }

    function buildCard(cost, env) {
        const groups = cost.scope === 'GROUPS';
        const notes = [];
        if (!groups && cost.actualDays > 0) {
            notes.push(Utils.html`Actual (${cost.actualDays} day${cost.actualDays === 1 ? '' : 's'}): <strong>${money(cost.monthToDateActual)}</strong>`);
        }
        if (!groups && cost.forecastMonthEnd !== null && cost.forecastMonthEnd !== undefined) {
            notes.push(Utils.html`<span title="${cost.forecastBasis || ''}">If the current rate continues: <strong>${money(cost.forecastMonthEnd)}</strong> this month</span>`);
        }
        const saved = cost.savingsMtd && Number(cost.savingsMtd) > 0
            ? Utils.html`<div class="env-fact-note env-cost-saved">Saved ${money(cost.savingsMtd)} this month${cost.savingsSource ? ' (' + cost.savingsSource + ')' : ''}</div>`
            : '';
        return Utils.html`
            <div class="env-cost-block">
                <div class="env-fact-head"><span>Cost this month${groups ? ' — your groups only' : ''}</span></div>
                <div class="env-cost-mtd mono" id="env-cost-mtd">${money(cost.monthToDateEstimated)} ${Utils.raw(changeBadge(cost.changePercent))}</div>
                ${groups ? '' : Utils.raw(dailyBars(cost.daily))}
                ${groups || !(cost.daily || []).length ? '' : Utils.raw('<div class="env-fact-note">Last 14 days</div>')}
                ${Utils.raw(notes.map(n => `<div class="env-fact-note">${n}</div>`).join(''))}
                ${Utils.raw(saved)}
                ${isEks(env) && !groups ? Utils.raw('<div class="env-fact-note env-eks-fee">Includes the EKS cluster fee, which runs even when nodes are at 0</div>') : ''}
            </div>`;
    }

    /**
     * The schedule last read for an environment ({ruleCount, nextStop, nextStart, partialStop, partialStart,
     * vmStops}), or null.
     */
    function scheduleFor(envId) {
        return schedules[envId] || null;
    }

    return { render, retry, scheduleFor, model: { scheduleValue } };
})();

window.EnvCost = EnvCost;

Actions.register('env-cost-retry', () => EnvCost.retry());

