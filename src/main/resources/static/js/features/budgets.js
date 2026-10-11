/**
 * Budgets (E20-T05): the Budgets page (budgets you can see, their spend against the amount,
 * pending budget actions to approve) and the budget panel on the environment page (the bar, the
 * start-freeze banner with "Lift freeze", and "Set budget" for owners). Who may do what is decided
 * by the server; the page offers only what /budgets/config says the caller may budget.
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

    function actionLabel(a) {
        return { FREEZE_STARTS: 'Freeze starts', STOP_ENVIRONMENT: 'Stop environment' }[a] || 'Alerts only';
    }

    /** Spend against the amount, coloured at 75 / 90 / 100 %. */
    function bar(b) {
        const pct = Number(b.percentUsed || 0);
        const tone = pct >= 100 ? 'over' : pct >= 90 ? 'danger' : pct >= 75 ? 'warn' : 'ok';
        return Utils.html`<div class="budget-bar budget-${tone}" role="progressbar" aria-valuemin="0" aria-valuemax="100"
                aria-valuenow="${Math.min(100, Math.round(pct))}" aria-label="${pct}% of the budget used">
            <span style="width:${Math.min(100, pct)}%"></span></div>`;
    }

    function summary(b) {
        const forecast = b.forecastMonthEnd === null || b.forecastMonthEnd === undefined ? '' : ` · forecast ${money(b.forecastMonthEnd)}`;
        return `${money(b.spendToDate)} of ${money(b.monthlyAmount)}${forecast}`;
    }

    // ============= Budgets page =============

    function load() {
        pageToken = ContentRouter.token();
        page = { data: {}, errors: {} };
        $('#content-area').html(`
            <div class="content-view budgets-page" id="budgets-view">
                <div class="content-header d-flex justify-content-between align-items-center flex-wrap gap-2">
                    <div>
                        <h1><i class="fas fa-wallet me-2" aria-hidden="true"></i>Budgets</h1>
                        <p class="text-muted mb-0">Monthly budgets per environment or team, with alerts and approval-gated actions</p>
                    </div>
                    <button type="button" class="btn btn-primary" data-action="budget-new" id="budget-new-btn" hidden>
                        <i class="fas fa-plus" aria-hidden="true"></i> New budget</button>
                </div>
                <div id="budget-actions-panel"></div>
                <div id="budget-list-panel"><div class="text-muted small p-2"><i class="fas fa-spinner fa-spin me-1" aria-hidden="true"></i>Loading budgets…</div></div>
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
        return Utils.html`<div class="cost-panel-error" role="alert"><i class="fas fa-triangle-exclamation" aria-hidden="true"></i>
            <span>Couldn't load ${label}.</span>
            <button type="button" class="btn btn-sm btn-ghost" data-action="budget-retry" data-key="${key}">Retry</button></div>`;
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
        const rows = budgets.length ? budgets.map(b => Utils.html`
            <tr>
                <td data-label="Scope">${b.scopeType === 'TEAM'
                    ? Utils.raw(Utils.html`<span class="badge text-bg-light border me-1">Team</span>${b.scopeName}`)
                    : Utils.raw(Utils.html`<a href="#/environments/${b.scopeId}">${b.scopeName}</a>`)}
                    ${Utils.raw(b.enabled ? '' : '<span class="badge text-bg-light border ms-1">Off</span>')}</td>
                <td data-label="Budget">${money(b.monthlyAmount)}</td>
                <td data-label="This month" class="budget-progress-cell">${Utils.raw(bar(b))}
                    <div class="small">${money(b.spendToDate)} · ${b.percentUsed === null ? '—' : b.percentUsed + '%'}</div></td>
                <td data-label="Forecast">${money(b.forecastMonthEnd)}</td>
                <td data-label="Action">${actionLabel(b.actionType)}${Utils.raw(b.startFrozenUntil
                    ? Utils.html`<div class="small text-danger"><i class="fas fa-snowflake" aria-hidden="true"></i> Frozen until ${day(b.startFrozenUntil)}</div>` : '')}</td>
                <td data-label="" class="text-end text-nowrap">${Utils.raw(canEdit(b) ? Utils.html`
                    <button type="button" class="btn btn-sm btn-ghost" data-action="budget-edit" data-id="${b.budgetId}" aria-label="Edit budget of ${b.scopeName}">
                        <i class="fas fa-pen" aria-hidden="true"></i></button>
                    <button type="button" class="btn btn-sm btn-ghost text-danger" data-action="budget-delete" data-id="${b.budgetId}" aria-label="Delete budget of ${b.scopeName}">
                        <i class="fas fa-trash" aria-hidden="true"></i></button>` : '')}</td>
            </tr>`).join('')
            : '<tr><td colspan="6" class="text-muted text-center py-3">No budgets yet</td></tr>';
        $('#budget-list-panel').html(`
            <section class="card"><div class="card-body">
                <div class="table-responsive"><table class="table table-sm table-cards mb-0" id="budgets-table">
                    <thead><tr><th>Scope</th><th>Budget</th><th>This month</th><th>Forecast</th><th>Action</th><th><span class="visually-hidden">Actions</span></th></tr></thead>
                    <tbody>${rows}</tbody></table></div>
                ${budgets.length ? `<p class="small text-muted mb-0 mt-2">Spend uses AWS billing where available, otherwise the estimate, up to yesterday.</p>` : ''}
            </div></section>`);
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

    function showForm(budget, preset, config) {
        const scopeType = budget ? budget.scopeType : (preset.scopeType || 'ENVIRONMENT');
        const scopeId = budget ? budget.scopeId : (preset.scopeId || '');
        const thresholds = budget ? budget.thresholds : [50, 75, 90, 100];
        const envOptions = (config.environments || []).map(e =>
            Utils.html`<option value="${e.environmentId}" ${e.environmentId === scopeId ? 'selected' : ''}>${e.name}</option>`).join('');
        const teamOptions = (config.teams || []).map(t =>
            Utils.html`<option value="${t}" ${t === scopeId ? 'selected' : ''}>${t}</option>`).join('');
        const action = budget ? budget.actionType : 'NONE';
        const body = `
            <form id="budget-form" novalidate>
                ${(config.teams || []).length ? Utils.html`<div class="mb-3">
                    <label class="form-label" for="budget-scope-type">Budget for</label>
                    <select class="form-select" id="budget-scope-type" ${budget ? 'disabled' : ''}>
                        <option value="ENVIRONMENT" ${scopeType === 'ENVIRONMENT' ? 'selected' : ''}>An environment</option>
                        <option value="TEAM" ${scopeType === 'TEAM' ? 'selected' : ''}>A team (all its environments)</option>
                    </select></div>` : '<input type="hidden" id="budget-scope-type" value="ENVIRONMENT">'}
                <div class="mb-3" id="budget-env-field" ${scopeType === 'TEAM' ? 'hidden' : ''}>
                    <label class="form-label" for="budget-env">Environment</label>
                    <select class="form-select" id="budget-env" ${budget || preset.scopeId ? 'disabled' : ''}>${envOptions}</select>
                </div>
                <div class="mb-3" id="budget-team-field" ${scopeType === 'TEAM' ? '' : 'hidden'}>
                    <label class="form-label" for="budget-team">Team</label>
                    <select class="form-select" id="budget-team" ${budget ? 'disabled' : ''}>${teamOptions}</select>
                </div>
                <div class="mb-3">
                    <label class="form-label" for="budget-amount">Monthly budget (USD)</label>
                    <input type="number" class="form-control" id="budget-amount" min="1" max="10000000" step="1" required
                           value="${budget ? Number(budget.monthlyAmount) : ''}">
                </div>
                <fieldset class="mb-3">
                    <legend class="form-label fs-6">Alert at</legend>
                    <div class="d-flex flex-wrap gap-2">${[50, 75, 90, 100].map(t => `
                        <label class="budget-chip"><input type="checkbox" class="form-check-input budget-threshold" value="${t}"
                            ${thresholds.includes(t) ? 'checked' : ''}> ${t}%</label>`).join('')}</div>
                </fieldset>
                <div class="form-check form-switch mb-3">
                    <input class="form-check-input" type="checkbox" role="switch" id="budget-forecast" ${!budget || budget.forecastAlert ? 'checked' : ''}>
                    <label class="form-check-label" for="budget-forecast">Also alert when the month-end forecast is over budget</label>
                </div>
                <div class="mb-3" id="budget-action-field" ${scopeType === 'TEAM' ? 'hidden' : ''}>
                    <label class="form-label" for="budget-action">When the budget is used up</label>
                    <select class="form-select" id="budget-action" ${config.actionsEnabled ? '' : 'disabled'} aria-describedby="budget-action-note">
                        <option value="NONE" ${action === 'NONE' ? 'selected' : ''}>Alert only</option>
                        <option value="FREEZE_STARTS" ${action === 'FREEZE_STARTS' ? 'selected' : ''}>Ask an owner to freeze starts</option>
                        <option value="STOP_ENVIRONMENT" ${action === 'STOP_ENVIRONMENT' ? 'selected' : ''}>Ask an owner to stop the environment</option>
                    </select>
                    <div class="form-text" id="budget-action-note">${config.actionsEnabled
                        ? 'Nothing happens until an owner other than you (or an admin) approves it.'
                        : 'Budget actions are turned off on this server; budgets only send alerts.'}</div>
                </div>
                <div class="form-check form-switch mb-0">
                    <input class="form-check-input" type="checkbox" role="switch" id="budget-enabled" ${!budget || budget.enabled ? 'checked' : ''}>
                    <label class="form-check-label" for="budget-enabled">Enabled</label>
                </div>
            </form>`;
        Modals.show({
            id: 'budgetModal',
            title: budget ? `Edit budget: ${budget.scopeName}` : 'New budget',
            body,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: budget ? 'Save' : 'Create budget', class: 'btn-primary', id: 'budget-save-btn' }
            ],
            onShow: () => $('#budget-amount').trigger('focus')
        });
        $('#budget-scope-type').on('change', function() {
            const team = $(this).val() === 'TEAM';
            $('#budget-env-field').prop('hidden', team);
            $('#budget-team-field').prop('hidden', !team);
            $('#budget-action-field').prop('hidden', team);
        });
        $('#budget-save-btn').on('click', function() {
            const type = $('#budget-scope-type').val();
            const dto = {
                scopeType: type,
                scopeId: type === 'TEAM' ? $('#budget-team').val() : $('#budget-env').val(),
                monthlyAmount: Number($('#budget-amount').val()),
                thresholds: $('.budget-threshold:checked').map(function() { return Number(this.value); }).get(),
                forecastAlert: $('#budget-forecast').is(':checked'),
                actionType: type === 'TEAM' ? 'NONE' : $('#budget-action').val(),
                enabled: $('#budget-enabled').is(':checked')
            };
            if (!dto.scopeId) { Notifications.showError('Choose what the budget is for.'); return; }
            if (!(dto.monthlyAmount > 0)) { Notifications.showError('Enter a monthly budget above zero.'); return; }
            if (!dto.thresholds.length) { Notifications.showError('Choose at least one alert threshold.'); return; }
            const $btn = $(this).prop('disabled', true);
            (budget ? call('PUT', Config.API.budgets.update(budget.budgetId), dto) : call('POST', Config.API.budgets.create, dto))
                .then(() => {
                    bootstrap.Modal.getInstance(document.getElementById('budgetModal')).hide();
                    Notifications.show(budget ? 'Budget saved.' : 'Budget created.', 'success');
                    refreshAfterDecision();
                })
                .catch(e => { Notifications.showError(serverMessage(e, 'Could not save the budget.')); $btn.prop('disabled', false); });
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
        });
    }

    function envPanelHtml(env, budget, owner, actions) {
        if (!budget) {
            return owner ? `<div class="budget-env-empty small text-muted mb-3"><i class="fas fa-wallet me-1" aria-hidden="true"></i>No budget.
                <button type="button" class="btn btn-link btn-sm p-0 align-baseline" data-action="budget-set-env">Set budget</button></div>` : '';
        }
        const frozen = budget.startFrozenUntil ? Utils.html`
            <div class="alert alert-warning d-flex justify-content-between align-items-center flex-wrap gap-2 mb-2 budget-freeze-banner" role="status">
                <span><i class="fas fa-snowflake me-1" aria-hidden="true"></i>Starts are frozen until ${day(budget.startFrozenUntil)} (budget exhausted).
                ${Auth.isAdmin() ? '' : 'Stops still work.'}</span>
                ${Utils.raw(owner ? '<button type="button" class="btn btn-sm btn-ghost" data-action="budget-lift-freeze">Lift freeze</button>' : '')}
            </div>` : '';
        return Utils.html`
            <section class="card mb-3 budget-env-card" aria-label="Budget">
                <div class="card-body py-2">
                    ${Utils.raw(frozen)}
                    <div class="d-flex justify-content-between align-items-center gap-2">
                        <span class="small text-muted text-uppercase">Budget this month</span>
                        ${Utils.raw(owner ? Utils.html`<button type="button" class="btn btn-link btn-sm p-0" data-action="budget-edit" data-id="${budget.budgetId}">Edit</button>` : '')}
                    </div>
                    ${Utils.raw(bar(budget))}
                    <div class="small" id="env-budget-summary">${summary(budget)}</div>
                    ${Utils.raw(owner && actions.length ? actions.map(actionRow).join('') : '')}
                </div>
            </section>`;
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
        setForEnvironment: () => envPanel && openForm(null, { scopeType: 'ENVIRONMENT', scopeId: envPanel.env.environmentId })
    };
})();

window.Budgets = Budgets;

Actions.registerAll({
    'budget-new': () => Budgets.openForm(null),
    'budget-edit': el => Budgets.edit(el.dataset.id),
    'budget-delete': el => Budgets.remove(el.dataset.id),
    'budget-approve': el => Budgets.approve(el.dataset.id),
    'budget-reject': el => Budgets.reject(el.dataset.id),
    'budget-retry': el => Budgets.retry(el.dataset.key),
    'budget-set-env': () => Budgets.setForEnvironment(),
    'budget-lift-freeze': () => Budgets.liftFreeze()
});
