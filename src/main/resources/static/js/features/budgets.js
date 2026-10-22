/**
 * Budgets (E20-T05, U12-T07 Budgets.dc.html + CostDialogs.dc.html): the Budgets page (this month's
 * spent and forecast totals, one row per budget with its bar, the forecast tick, a chip and what
 * happens when it is reached; pending budget actions to approve), the New budget dialog, and the
 * budget panel on the environment page (the bar, the start-freeze banner with "Lift freeze", and
 * "Set budget" for owners). Who may do what is decided by the server; the page offers only what
 * /budgets/config says the caller may budget.
 */
const Budgets = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    let pageToken = null;
    let page = null;

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data, QUIET).done(resolve).fail(reject);
        });
    }

    function serverMessage(error, fallback) {
        return (error && error.responseJSON && error.responseJSON.message) || fallback;
    }

    function money(v) {
        return v === null || v === undefined ? '—' : Utils.formatCurrency(Number(v));
    }

    function day(ts) {
        return new Date(ts).toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' });
    }

    /** Whole dollars for the page: "$186". */
    function dollars(v) {
        return v === null || v === undefined ? '—' : `$${Math.round(Number(v)).toLocaleString('en-US')}`;
    }

    const MAX_THRESHOLDS = 4;

    /** The row's chip: "<p>% reached" from 90 %, "Forecast over" when the forecast passes the amount, else "On track". */
    function chipOf(b) {
        const pct = Number(b.percentUsed || 0);
        if (pct >= 90) return { text: `${Math.round(pct)}% reached`, tone: 'bad' };
        if (Number(b.forecastPercent || 0) > 100) return { text: 'Forecast over', tone: 'warn' };
        return { text: 'On track', tone: 'ok' };
    }

    /** What happens when the budget is reached, in words. */
    function actionText(b, actionsEnabled) {
        if (b.scopeType === 'TEAM') return 'Alerts only (team budgets cannot freeze or stop)';
        const off = actionsEnabled === false ? ' (switched off by an administrator)' : '';
        if (b.actionType === 'FREEZE_STARTS') return `When reached, an owner approves freezing starts${off}`;
        if (b.actionType === 'STOP_ENVIRONMENT') return `When reached, an owner approves stopping it${off}`;
        return 'Alerts only';
    }

    /** "· environment" / "· team · 3 environments" */
    function kindText(b) {
        if (b.scopeType !== 'TEAM') return 'environment';
        const n = b.environmentCount;
        return n === undefined || n === null ? 'team' : `team · ${n} environment${n === 1 ? '' : 's'}`;
    }

    /** Spent and forecast over environment budgets (team budgets cover the same spend). */
    function totalsOf(budgets) {
        const env = (budgets || []).filter(b => b.scopeType !== 'TEAM' && b.enabled !== false);
        const sum = key => env.reduce((t, b) => t + Number(b[key] || 0), 0);
        return { spent: sum('spendToDate'), forecast: sum('forecastMonthEnd'), count: env.length,
            teams: (budgets || []).some(b => b.scopeType === 'TEAM') };
    }

    /** "October · day 19 of 31" */
    function monthDay(now) {
        const d = now || new Date();
        const last = new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate();
        return `${d.toLocaleDateString('en-GB', { month: 'long' })} · day ${d.getDate()} of ${last}`;
    }

    /** Thresholds as the server takes them: 1 to 4 different whole percentages between 1 and 200. */
    function thresholdProblem(list, value) {
        const t = Number(value);
        if (!Number.isInteger(t) || t < 1 || t > 200) return 'A whole percentage between 1 and 200.';
        if (list.includes(t)) return `${t}% is already there.`;
        if (list.length >= MAX_THRESHOLDS) return 'Up to 4.';
        return '';
    }

    function actionLabel(a) {
        return { FREEZE_STARTS: 'Freeze starts', STOP_ENVIRONMENT: 'Stop environment' }[a] || 'Alerts only';
    }

    /** Spend against the amount, coloured at 75 / 90 / 100 %, with the month-end forecast as a gold tick. */
    function bar(b) {
        const pct = Number(b.percentUsed || 0);
        const tone = pct >= 100 ? 'over' : pct >= 90 ? 'danger' : pct >= 75 ? 'warn' : 'ok';
        const hasForecast = b.forecastPercent !== null && b.forecastPercent !== undefined;
        const fpct = hasForecast ? Number(b.forecastPercent) : null;
        const label = `${Math.round(pct)}% spent${hasForecast ? `, forecast ${Math.round(fpct)}%` : ''}`;
        return Utils.html`<div class="budget-bar budget-${tone}" role="img" aria-label="${label}">
            <span class="budget-fill" style="width:${Math.min(100, pct)}%"></span>${Utils.raw(hasForecast
                ? `<span class="budget-tick" style="left:${Math.min(100, Math.max(0, fpct))}%"></span>` : '')}</div>`;
    }

    function summary(b) {
        const forecast = b.forecastMonthEnd === null || b.forecastMonthEnd === undefined ? '' : ` · forecast ${money(b.forecastMonthEnd)}`;
        return `${money(b.spendToDate)} of ${money(b.monthlyAmount)}${forecast}`;
    }

    // ============= Budgets page =============

    // Three budgets fit 1440 x 900 without a page scrollbar, with the pending actions above (U17-T07).
    const BUDGETS_PER_PAGE = 3;
    /** Budgets a page on the page frame: as many as fit, at most BUDGETS_PER_PAGE (U18-T04). */
    let fitSize = null;
    const perPage = () => fitSize || BUDGETS_PER_PAGE;

    function load() {
        pageToken = ContentRouter.token();
        page = { data: {}, errors: {}, listPage: 1, showScope: null };
        fitSize = null;
        $('#content-area').html(`
            <div class="content-view page page-frame budgets-page" id="budgets-view">
                ${PageHeader.markup({ title: 'Budgets',
                    summaryHtml: 'Monthly limits for environments and teams, with alerts at 50, 75, 90 and 100%.',
                    actionsHtml: `<button type="button" class="btn btn-primary" data-action="budget-new" id="budget-new-btn" hidden>
                        <svg width="14" height="14" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M10 4v12M4 10h12"></path></svg>
                        New budget</button>`,
                    helpPage: typeof HelpLink !== 'undefined' ? 'budgets' : null, helpLabel: 'Budgets' })}
                <div id="budget-actions-panel"></div>
                <div id="budget-list-panel" class="list-panel page-fill bd-panel"><div class="ar-empty" role="status">Loading budgets…</div></div>
            </div>`);
        fetchPage();
    }

    const REQUESTS = {
        config: () => call('GET', Config.API.budgets.config),
        list: () => call('GET', Config.API.budgets.list),
        actions: () => call('GET', Config.API.budgets.actions('PENDING'))
    };

    function fetchPage(keys) {
        const wanted = keys || Object.keys(REQUESTS);
        const token = pageToken;
        Promise.allSettled(wanted.map(k => REQUESTS[k]())).then(results => {
            if (!ContentRouter.isCurrent(token)) return;
            results.forEach((r, i) => {
                if (r.status === 'fulfilled') {
                    page.data[wanted[i]] = r.value || [];
                    delete page.errors[wanted[i]];
                } else {
                    page.errors[wanted[i]] = true;
                }
            });
            drawPage();
        });
    }

    function panelError(label, key) {
        return UiStates.failed({ what: label, retryAction: 'budget-retry', retryData: { key }, className: 'cost-panel-error' });
    }

    function drawPage() {
        const config = page.data.config;
        const canCreate = config && ((config.environments || []).length || (config.teams || []).length);
        $('#budget-new-btn').prop('hidden', !canCreate);

        // Pending actions
        let actionsHtml = '';
        if (page.errors.actions) {
            actionsHtml = panelError('pending budget actions', 'actions');
        } else if ((page.data.actions || []).length) {
            actionsHtml = Utils.html`<section class="card mb-3 budget-actions" aria-labelledby="budget-actions-title">
                <div class="card-body">
                    <h2 class="h6" id="budget-actions-title"><i class="fas fa-gavel me-1" aria-hidden="true"></i>Waiting for approval</h2>
                    ${Utils.raw(page.data.actions.map(actionRow).join(''))}
                </div></section>`;
        }
        $('#budget-actions-panel').html(actionsHtml);

        // Budget list
        if (page.errors.list) {
            $('#budget-list-panel').html(panelError('budgets', 'list'));
            return;
        }
        const budgets = page.data.list;
        if (!budgets) return;
        const actionsEnabled = config ? config.actionsEnabled : undefined;
        if (!budgets.length) {
            $('#budget-list-panel').html(`<div class="ar-empty">No budgets yet.${canCreate
                ? ' Add one with New budget to be told before an environment or team spends too much.' : ''}</div>`);
            return;
        }
        const t = totalsOf(budgets);
        const totals = `<div class="bd-totals" id="budget-totals">
                <span><span class="bd-swatch" aria-hidden="true"></span> Spent so far <b class="bd-mono">${dollars(t.spent)}</b></span>
                <span><span class="bd-tick-key" aria-hidden="true"></span> Forecast for the month <b class="bd-mono">${dollars(t.forecast)}</b></span>
                <span>${Utils.escapeHtml(monthDay())}</span>
                ${t.teams ? '<span class="bd-note">Totals add up environment budgets; team budgets cover the same spend.</span>' : ''}
            </div>`;
        // After a save, open the page that holds that budget.
        if (page.showScope) {
            const at = budgets.findIndex(b => b.scopeId === page.showScope);
            if (at >= 0) page.listPage = Math.floor(at / perPage()) + 1;
            page.showScope = null;
        }
        const pages = Math.max(1, Math.ceil(budgets.length / perPage()));
        page.listPage = Math.min(Math.max(1, page.listPage || 1), pages);
        const pager = budgets.length > perPage() ? Pagination.buildPager({ page: page.listPage, pageSize: perPage(),
            totalItems: budgets.length, action: 'budget-page', key: 'budgets' }) : '';
        const rows = Pagination.slice(budgets, page.listPage, perPage()).map(b => {
            const chip = chipOf(b);
            return Utils.html`
            <article class="bd-row${Utils.raw(b.enabled ? '' : ' bd-row-off')}" data-budget-id="${b.budgetId}" data-scope-id="${b.scopeId}">
                <div class="bd-head">
                    <div><h2>${b.scopeType === 'TEAM' ? `${b.scopeName} team` : Utils.raw(Utils.html`<a href="#/environments/${b.scopeId}">${b.scopeName}</a>`)}</h2>
                        <span class="bd-kind">· ${kindText(b)}</span>${Utils.raw(b.enabled ? '' : ' <span class="bd-chip bd-chip-off">Off</span>')}</div>
                    <span class="bd-chip bd-chip-${chip.tone}">${chip.text}</span>
                </div>
                ${Utils.raw(bar(b))}
                <div class="bd-line">
                    <span><span class="bd-mono">${dollars(b.spendToDate)}</span> of <span class="bd-mono">${dollars(b.monthlyAmount)}</span>${Utils.raw(
                        b.forecastMonthEnd === null || b.forecastMonthEnd === undefined ? ''
                            : Utils.html` · forecast <span class="bd-mono">${dollars(b.forecastMonthEnd)}</span>`)}</span>
                    <span class="bd-action">${actionText(b, actionsEnabled)}</span>
                </div>
                ${Utils.raw(b.startFrozenUntil ? Utils.html`<div class="bd-frozen">Starts frozen until ${day(b.startFrozenUntil)}</div>` : '')}
                ${Utils.raw(canEdit(b) ? Utils.html`<div class="bd-links">
                    <button type="button" class="bd-link" data-action="budget-edit" data-id="${b.budgetId}" aria-label="Edit budget of ${b.scopeName}">Edit</button>
                    <button type="button" class="bd-link bd-danger" data-action="budget-delete" data-id="${b.budgetId}" aria-label="Delete budget of ${b.scopeName}">Delete</button>
                </div>` : '')}
            </article>`;
        }).join('');
        $('#budget-list-panel').html(`${totals}<div class="bd-list list-panel-body" id="budgets-list" tabindex="0" role="region" aria-label="Budgets, scrolls">${rows}</div>
            <div class="list-panel-foot bd-foot">${pager}
            <p class="bd-note">Spend uses AWS billing where available, otherwise the estimate, up to yesterday.</p></div>`);
        attachFit();
    }

    /** Budgets a page follow the window: measured when the list first shows and when a resize settles (U18-T04). */
    function attachFit() {
        const panel = document.getElementById('budget-list-panel');
        if (!panel || !panel.querySelector('.bd-row') || typeof ListPanel === 'undefined') return;
        const apply = count => {
            if (!count || count === perPage()) return;
            const first = (page.listPage - 1) * perPage();
            fitSize = count;
            page.listPage = ListPanel.pageKeepingFirst(first, count);
            drawPage();
        };
        const handle = ListPanel.attach(panel, { rowSelector: '.bd-row', rowHeight: 150, min: 1, max: BUDGETS_PER_PAGE, onFit: apply });
        if (fitSize === null) apply(handle.fitCount());
    }

    function canEdit(b) {
        const config = page && page.data.config;
        if (!config) return false;
        return b.scopeType === 'TEAM' ? (config.teams || []).length > 0 || Auth.isAdmin()
            : (config.environments || []).some(e => e.environmentId === b.scopeId);
    }

    function actionRow(r) {
        return Utils.html`<div class="budget-action-row d-flex justify-content-between align-items-center flex-wrap gap-2" data-request="${r.requestId}">
            <div><strong>${r.environmentName}</strong>: ${actionLabel(r.actionType).toLowerCase()} — budget of ${money(r.monthlyAmount)} used up in ${r.month}</div>
            <div class="d-flex gap-2">
                <button type="button" class="btn btn-sm btn-primary" data-action="budget-approve" data-id="${r.requestId}">Approve</button>
                <button type="button" class="btn btn-sm btn-ghost" data-action="budget-reject" data-id="${r.requestId}">Reject</button>
            </div></div>`;
    }

    // ============= Decisions =============

    function findRequest(id) {
        return (page && (page.data.actions || []).find(r => r.requestId === id)) || (envPanel && envPanel.actions.find(r => r.requestId === id));
    }

    function approve(id) {
        const r = findRequest(id);
        if (!r) return;
        const send = (reason) => call('POST', Config.API.budgets.approve(id), { reason: reason || null })
            .then(() => { Notifications.show('Budget action approved.', 'success'); refreshAfterDecision(); })
            .catch(e => Notifications.showError(serverMessage(e, 'Could not approve the budget action.')));
        if (r.actionType === 'STOP_ENVIRONMENT') {
            DestructiveConfirm.show({
                title: 'Approve budget stop',
                message: `This stops every VM in "${r.environmentName}" because its budget for ${r.month} is used up.`,
                impact: ['Running VMs are stopped', 'Members are told before it stops', 'Anyone with access can start it again unless starts are frozen'],
                confirmText: r.environmentName,
                actionText: 'Approve stop',
                onConfirm: () => send(null)
            });
        } else {
            Modals.prompt('Approve start freeze', `Reason for freezing starts in ${r.environmentName} (optional)`, send,
                { submitText: 'Approve freeze' });
        }
    }

    function reject(id) {
        const r = findRequest(id);
        if (!r) return;
        Modals.prompt('Reject budget action', `Why not ${actionLabel(r.actionType).toLowerCase()} in ${r.environmentName}? (optional)`,
            reason => call('POST', Config.API.budgets.reject(id), { reason: reason || null })
                .then(() => { Notifications.show('Budget action rejected.', 'success'); refreshAfterDecision(); })
                .catch(e => Notifications.showError(serverMessage(e, 'Could not reject the budget action.'))),
            { submitText: 'Reject' });
    }

    function refreshAfterDecision() {
        if (pageToken && ContentRouter.isCurrent(pageToken) && $('#budgets-view').length) fetchPage();
        if (envPanel && envPanel.env) renderEnvPanel(envPanel.env);
    }

    // ============= Form =============

    function openForm(budget, preset) {
        const configPromise = page && page.data.config ? Promise.resolve(page.data.config) : call('GET', Config.API.budgets.config);
        configPromise.then(config => showForm(budget, preset || {}, config))
            .catch(e => Notifications.showError(serverMessage(e, 'Could not open the budget form.')));
    }

    /** The three "When the budget is reached" choices; freeze and stop only for environments, while actions are on. */
    function actionCardsHtml(selected, team, actionsEnabled) {
        const why = team ? 'Not offered for team budgets.' : actionsEnabled ? '' : 'Switched off by an administrator.';
        const cards = [
            { value: 'NONE', name: 'Alert only', desc: 'Nothing changes for the people using it' },
            { value: 'FREEZE_STARTS', name: 'Freeze new starts', desc: 'An owner approves first; running VMs keep running. An owner can lift it.' },
            { value: 'STOP_ENVIRONMENT', name: 'Stop the environment', desc: 'An owner approves first.' }
        ];
        const value = why ? 'NONE' : selected;
        return cards.map(c => {
            const off = c.value !== 'NONE' && !!why;
            return Utils.html`<label class="bd-choice${Utils.raw(off ? ' bd-choice-off' : '')}">
                <input type="radio" name="budget-action" value="${c.value}"${Utils.raw(value === c.value ? ' checked' : '')}${Utils.raw(off ? ' disabled' : '')}>
                <span><b>${c.name}</b><span>${off ? `${c.desc} ${why}` : c.desc}</span></span></label>`;
        }).join('');
    }

    function thresholdChipsHtml(list) {
        return list.slice().sort((a, b) => a - b).map(t => `<span class="bd-tchip" data-value="${t}">${t}%<button type="button"
            class="bd-tchip-x" aria-label="Remove ${t}%">×</button></span>`).join('');
    }

    function readThresholds() {
        return $('#budget-thresholds .bd-tchip').map(function() { return Number(this.dataset.value); }).get();
    }

    function drawThresholds(list) {
        $('#budget-thresholds').html(thresholdChipsHtml(list));
        const full = list.length >= MAX_THRESHOLDS;
        $('#budget-threshold-input, #budget-threshold-add').prop('disabled', full);
        $('#budget-threshold-count').text(`${list.length} of ${MAX_THRESHOLDS}`);
    }

    function showForm(budget, preset, config) {
        const scopeType = budget ? budget.scopeType : (preset.scopeType || 'ENVIRONMENT');
        const scopeId = budget ? budget.scopeId : (preset.scopeId || '');
        const thresholds = budget ? budget.thresholds : [50, 75, 90, 100];
        const teams = config.teams || [];
        const envOptions = (config.environments || []).map(e =>
            Utils.html`<option value="${e.environmentId}" ${e.environmentId === scopeId ? 'selected' : ''}>${e.name}</option>`).join('');
        const teamOptions = teams.map(t =>
            Utils.html`<option value="${t}" ${t === scopeId ? 'selected' : ''}>${t}</option>`).join('');
        const action = budget ? budget.actionType : 'NONE';
        const fixed = !!budget || !!preset.scopeId;
        const body = `
            <form id="budget-form" class="bd-form" novalidate>
                <p class="bd-intro">A monthly limit. It starts again on the 1st.</p>
                <div class="bd-grid">
                    ${teams.length ? Utils.html`<label class="ap-field">For
                        <select class="form-select" id="budget-scope-type" ${fixed ? 'disabled' : ''}>
                            <option value="ENVIRONMENT" ${scopeType === 'ENVIRONMENT' ? 'selected' : ''}>An environment</option>
                            <option value="TEAM" ${scopeType === 'TEAM' ? 'selected' : ''}>A team</option>
                        </select></label>` : '<input type="hidden" id="budget-scope-type" value="ENVIRONMENT">'}
                    <label class="ap-field" id="budget-env-field" ${scopeType === 'TEAM' ? 'hidden' : ''}>Environment
                        <select class="form-select" id="budget-env" ${fixed ? 'disabled' : ''}>${envOptions}</select></label>
                    <label class="ap-field" id="budget-team-field" ${scopeType === 'TEAM' ? '' : 'hidden'}>Team
                        <select class="form-select" id="budget-team" ${fixed ? 'disabled' : ''}>${teamOptions}</select></label>
                </div>
                <span class="pol-hint">You can't move a budget to something else later.</span>
                <label class="ap-field">Monthly amount
                    <span class="bd-amount"><span aria-hidden="true">$</span><input type="number" class="form-control bd-mono" id="budget-amount"
                        min="1" max="10000000" step="1" required value="${budget ? Number(budget.monthlyAmount) : ''}" aria-label="Monthly amount in dollars"></span></label>
                <div class="ap-field">
                    <span id="budget-threshold-label">Alert when spend reaches <span class="pol-hint">Up to 4. Owners of the environment are told each time.</span></span>
                    <div class="bd-tchips" role="group" aria-labelledby="budget-threshold-label">
                        <span id="budget-thresholds">${thresholdChipsHtml(thresholds)}</span>
                        <span class="bd-tadd">
                            <input type="number" class="form-control form-control-sm bd-mono" id="budget-threshold-input" min="1" max="200" step="1"
                                   placeholder="%" aria-label="Another alert, percent of the amount">
                            <button type="button" class="btn btn-sm btn-secondary" id="budget-threshold-add">Add</button>
                        </span>
                        <span class="pol-hint" id="budget-threshold-count" aria-live="polite"></span>
                    </div>
                </div>
                <label class="re-check bd-check"><input type="checkbox" id="budget-forecast" ${!budget || budget.forecastAlert ? 'checked' : ''}>
                    Also alert when the forecast says it will go over</label>
                <fieldset class="pol-fieldset" id="budget-actions">
                    <legend>When the budget is reached</legend>
                    <div id="budget-action-cards">${actionCardsHtml(action, scopeType === 'TEAM', config.actionsEnabled)}</div>
                </fieldset>
                <label class="re-check bd-check"><input type="checkbox" id="budget-enabled" ${!budget || budget.enabled ? 'checked' : ''}> On</label>
                <div class="re-error" id="budget-error" role="alert" hidden></div>
            </form>`;
        Modals.show({
            id: 'budgetModal',
            title: budget ? `Edit budget: ${budget.scopeName}` : 'New budget',
            body,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: budget ? 'Save budget' : 'Create budget', class: 'btn-primary', id: 'budget-save-btn' }
            ],
            onShow: () => $(fixed ? '#budget-amount' : (teams.length ? '#budget-scope-type' : '#budget-env')).trigger('focus')
        });
        drawThresholds(thresholds);
        const $error = $('#budget-error');
        const fail = message => $error.text(message).prop('hidden', false);
        $('#budget-scope-type').on('change', function() {
            const team = $(this).val() === 'TEAM';
            $('#budget-env-field').prop('hidden', team);
            $('#budget-team-field').prop('hidden', !team);
            const current = $('input[name="budget-action"]:checked').val() || 'NONE';
            $('#budget-action-cards').html(actionCardsHtml(current, team, config.actionsEnabled));
        });
        $('#budget-thresholds').on('click', '.bd-tchip-x', function() {
            const value = Number($(this).closest('.bd-tchip').data('value'));
            drawThresholds(readThresholds().filter(t => t !== value));
            $('#budget-threshold-input').trigger('focus');
        });
        const addThreshold = () => {
            const list = readThresholds();
            const raw = $('#budget-threshold-input').val();
            const problem = thresholdProblem(list, raw);
            if (problem) { fail(problem); return; }
            $error.prop('hidden', true);
            drawThresholds(list.concat(Number(raw)));
            $('#budget-threshold-input').val('');
            if (list.length + 1 < MAX_THRESHOLDS) $('#budget-threshold-input').trigger('focus');
        };
        $('#budget-threshold-add').on('click', addThreshold);
        $('#budget-threshold-input').on('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); addThreshold(); } });
        $('#budget-save-btn').on('click', function() {
            const type = $('#budget-scope-type').val();
            const dto = {
                scopeType: type,
                scopeId: type === 'TEAM' ? $('#budget-team').val() : $('#budget-env').val(),
                monthlyAmount: Number($('#budget-amount').val()),
                thresholds: readThresholds(),
                forecastAlert: $('#budget-forecast').is(':checked'),
                actionType: type === 'TEAM' ? 'NONE' : ($('input[name="budget-action"]:checked').val() || 'NONE'),
                enabled: $('#budget-enabled').is(':checked')
            };
            if (!dto.scopeId) { fail('Choose what the budget is for.'); return; }
            if (!(dto.monthlyAmount > 0)) { fail('Enter a monthly amount above zero.'); $('#budget-amount').trigger('focus'); return; }
            if (!dto.thresholds.length) { fail('Add at least one alert.'); $('#budget-threshold-input').trigger('focus'); return; }
            const $btn = $(this).prop('disabled', true);
            (budget ? call('PUT', Config.API.budgets.update(budget.budgetId), dto) : call('POST', Config.API.budgets.create, dto))
                .then(() => {
                    bootstrap.Modal.getInstance(document.getElementById('budgetModal')).hide();
                    Notifications.show(budget ? 'Budget saved.' : 'Budget created.', 'success');
                    if (page) page.showScope = dto.scopeId;
                    refreshAfterDecision();
                })
                .catch(e => { fail(serverMessage(e, 'Could not save the budget.')); $btn.prop('disabled', false); });
        });
    }

    function edit(id) {
        const b = (page.data.list || []).find(x => x.budgetId === id) || (envPanel && envPanel.budget);
        if (b) openForm(b);
    }

    function remove(id) {
        const b = (page.data.list || []).find(x => x.budgetId === id);
        if (!b) return;
        Modals.confirm('Delete budget', `Delete the ${money(b.monthlyAmount)} budget of ${b.scopeName}? Its alerts stop.`, () => {
            call('DELETE', Config.API.budgets.delete(id))
                .then(() => { Notifications.show('Budget deleted.', 'success'); fetchPage(); })
                .catch(e => Notifications.showError(serverMessage(e, 'Could not delete the budget.')));
        }, { confirmText: 'Delete', confirmClass: 'btn-danger' });
    }

    // ============= Environment page panel =============

    let envPanel = null;

    /** Budget bar, freeze banner and owner actions on the environment page; nothing for others without a budget. */
    function renderEnvPanel(env) {
        const $panel = $('#env-budget-panel');
        if (!$panel.length || !env) return;
        const token = ContentRouter.token();
        envPanel = { env, budget: null, actions: [] };
        Promise.allSettled([
            call('GET', Config.API.budgets.forEnvironment(env.environmentId)),
            call('GET', Config.API.budgets.config),
            call('GET', Config.API.budgets.actions('PENDING'))
        ]).then(([budgetResult, configResult, actionsResult]) => {
            if (!ContentRouter.isCurrent(token)) return;
            if (budgetResult.status === 'rejected') {
                $panel.html('');
                return;
            }
            const budget = budgetResult.value || null;
            const config = configResult.status === 'fulfilled' ? configResult.value : { environments: [] };
            const owner = (config.environments || []).some(e => e.environmentId === env.environmentId);
            const actions = actionsResult.status === 'fulfilled'
                ? (actionsResult.value || []).filter(r => r.environmentId === env.environmentId) : [];
            envPanel = { env, budget, actions, owner };
            $panel.html(envPanelHtml(env, budget, owner, actions));
            freezeAlert(env, budget, owner);
        });
    }

    /**
     * The budget under the cost in "About this environment" (U07-T06): the meter, "$186 of $400",
     * Edit or Set budget for the owner, and pending budget actions to decide. A start freeze is the
     * red Budget alert at the top of the page (U07-T07).
     */
    function envPanelHtml(env, budget, owner, actions) {
        if (!budget) {
            return owner ? `<div class="budget-env-empty env-fact-note">No budget.
                <button type="button" class="btn btn-link env-fact-action" data-action="budget-set-env">Set budget</button></div>` : '';
        }
        return Utils.html`
            <section class="budget-env-card" aria-label="Budget">
                ${Utils.raw(bar(budget))}
                <div class="budget-env-line">
                    <span class="env-fact-note" id="env-budget-summary">${summary(budget)}</span>
                    ${Utils.raw(owner ? Utils.html`<button type="button" class="btn btn-link env-fact-action" data-action="budget-edit" data-id="${budget.budgetId}">Edit</button>` : '')}
                </div>
                ${Utils.raw(owner && actions.length ? actions.map(actionRow).join('') : '')}
            </section>`;
    }

    const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September',
        'October', 'November', 'December'];

    /**
     * The red "Starts are blocked." alert while the budget froze starts (U07-T07), with Lift freeze
     * for the owner or Ask to lift (the Budgets page) for environment admins; Start is disabled for
     * everyone but administrators, who are not held by the freeze.
     */
    function freezeAlert(env, budget, owner) {
        if (typeof EnvAlerts === 'undefined') return;
        if (!budget || !budget.startFrozenUntil) {
            EnvAlerts.set('budget', null, env.environmentId);
            return;
        }
        const name = env.displayName || env.name;
        const month = /^\d{4}-\d{2}$/.test(budget.month || '') ? MONTHS[Number(budget.month.slice(5)) - 1] : 'monthly';
        const action = owner ? { label: 'Lift freeze', dataAction: 'budget-lift-freeze' }
            : (Auth.isEnvAdmin && Auth.isEnvAdmin() ? { label: 'Ask to lift', href: '#/budgets' } : null);
        EnvAlerts.set('budget', {
            tone: 'red', title: 'Starts are blocked.',
            detail: `${name} passed its ${month} budget of ${money(budget.monthlyAmount)}, so new starts are frozen until `
                + `${day(budget.startFrozenUntil)}.${owner ? '' : ' An owner can lift it.'}${Auth.isAdmin() ? '' : ' Stops still work.'}`,
            action,
        }, env.environmentId);
        if (!Auth.isAdmin()) {
            $('#btn-env-action[data-action-type="start"]').prop('disabled', true).attr('title', 'Starts are blocked by the budget');
        }
    }

    function liftFreeze() {
        if (!envPanel) return;
        const env = envPanel.env;
        Modals.prompt('Lift start freeze', 'Why lift the freeze? (optional)', reason => {
            call('POST', Config.API.budgets.liftFreeze(env.environmentId), { reason: reason || null })
                .then(() => { Notifications.show('Freeze lifted: starts are allowed again.', 'success'); renderEnvPanel(env); })
                .catch(e => Notifications.showError(serverMessage(e, 'Could not lift the freeze.')));
        }, { submitText: 'Lift freeze' });
    }

    return {
        load,
        openForm,
        renderEnvPanel,
        approve,
        reject,
        edit,
        remove,
        liftFreeze,
        retry: key => { delete page.errors[key]; fetchPage([key]); },
        goToPage: n => { if (!page) return; page.listPage = Number(n) || 1; drawPage(); },
        setForEnvironment: () => envPanel && openForm(null, { scopeType: 'ENVIRONMENT', scopeId: envPanel.env.environmentId }),
        model: { dollars, chipOf, actionText, kindText, totalsOf, monthDay, thresholdProblem }
    };
})();

window.Budgets = Budgets;

if (typeof Actions !== 'undefined') Actions.registerAll({
    'budget-new': () => Budgets.openForm(null),
    'budget-edit': el => Budgets.edit(el.dataset.id),
    'budget-delete': el => Budgets.remove(el.dataset.id),
    'budget-approve': el => Budgets.approve(el.dataset.id),
    'budget-reject': el => Budgets.reject(el.dataset.id),
    'budget-retry': el => Budgets.retry(el.dataset.key),
    'budget-page': el => { if (!el.disabled) Budgets.goToPage(el.dataset.page); },
    'budget-set-env': () => Budgets.setForEnvironment(),
    'budget-lift-freeze': () => Budgets.liftFreeze()
});
