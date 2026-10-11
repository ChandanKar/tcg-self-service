/**
 * Chargeback page (E23-T08): the monthly per-team statement (direct cost plus shared environments
 * split between teams), finalizing a finished month, CSV/XLSX downloads, and the shared-cost split
 * rules with their editor. Admin only.
 */
const Chargeback = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    const METHODS = {
        EVEN: 'Even split',
        FIXED_PERCENT: 'Fixed percentages',
        PROPORTIONAL_DIRECT: 'Proportional to direct cost'
    };
    let pageToken = null;
    let state = null;

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data, QUIET).done(resolve).fail(reject);
        });
    }

    function serverMessage(error, fallback) {
        return (error && error.responseJSON && error.responseJSON.message) || fallback;
    }

    function isActive() {
        return pageToken !== null && ContentRouter.isCurrent(pageToken);
    }

    function money(v) {
        return Utils.formatCurrency(Number(v || 0));
    }

    function monthValue(offset) {
        const d = new Date();
        const m = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() - offset, 1));
        return `${m.getUTCFullYear()}-${String(m.getUTCMonth() + 1).padStart(2, '0')}`;
    }

    function monthLabel(value) {
        const [y, m] = value.split('-').map(Number);
        return new Date(Date.UTC(y, m - 1, 1)).toLocaleDateString([], { month: 'long', year: 'numeric', timeZone: 'UTC' });
    }

    function monthOptions(selected) {
        const out = [];
        for (let i = 0; i < 13; i++) {
            const value = monthValue(i);
            out.push(`<option value="${value}" ${value === selected ? 'selected' : ''}>${Utils.escapeHtml(monthLabel(value))}</option>`);
        }
        return out.join('');
    }

    // ---------------------------------------------------------------- load

    function load() {
        pageToken = ContentRouter.token();
        if (!Auth.isAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3">Access denied. Admin only.</div>');
            return;
        }
        const q = ContentRouter.query ? ContentRouter.query() : {};
        state = {
            tab: q.tab === 'rules' ? 'rules' : 'statement',
            month: /^\d{4}-\d{2}$/.test(q.month || '') ? q.month : monthValue(1),
            statement: null, statementError: false, expanded: new Set(),
            rules: null, rulesError: false
        };
        $('#content-area').html(`
            <div class="content-view chargeback-page" id="chargeback-view">
                <div class="content-header">
                    <h1><i class="fas fa-file-invoice-dollar me-2" aria-hidden="true"></i>Chargeback</h1>
                    <p class="text-muted mb-0">Monthly cost per team, with shared environments split between the teams that use them</p>
                </div>
                <ul class="nav nav-tabs mb-3" role="tablist">
                    <li class="nav-item" role="presentation"><button type="button" class="nav-link" role="tab" id="chargeback-tab-statement" aria-controls="chargeback-body"
                        data-action="chargeback-tab" data-tab="statement">Statement</button></li>
                    <li class="nav-item" role="presentation"><button type="button" class="nav-link" role="tab" id="chargeback-tab-rules" aria-controls="chargeback-body"
                        data-action="chargeback-tab" data-tab="rules">Split rules</button></li>
                </ul>
                <div id="chargeback-body" role="tabpanel"></div>
            </div>`);
        showTab(state.tab);
    }

    function showTab(tab) {
        state.tab = tab;
        ['statement', 'rules'].forEach(t => {
            $(`#chargeback-tab-${t}`).toggleClass('active', t === tab).attr('aria-selected', String(t === tab));
        });
        $('#chargeback-body').attr('aria-labelledby', `chargeback-tab-${tab}`);
        if (tab === 'rules') {
            drawRules();
            loadRules();
        } else {
            loadStatement();
        }
    }

    // ---------------------------------------------------------------- statement

    function loadStatement() {
        const token = pageToken;
        const month = state.month;
        state.statement = null;
        state.statementError = false;
        drawStatement();
        call('GET', Config.API.costManagement.chargeback(month)).then(statement => {
            if (!ContentRouter.isCurrent(token) || state.month !== month) return;
            state.statement = statement;
            drawStatement();
        }).catch(() => {
            if (!ContentRouter.isCurrent(token) || state.month !== month) return;
            state.statementError = true;
            drawStatement();
        });
    }

    function canFinalize() {
        const s = state.statement;
        return !!s && s.status === 'DRAFT' && state.month < monthValue(0);
    }

    function drawStatement() {
        if (state.tab !== 'statement') return;
        const s = state.statement;
        const badge = s ? Utils.html`<span class="badge ${s.status === 'FINAL' ? 'text-bg-success' : 'text-bg-secondary'}" id="chargeback-status">${s.status}</span>` : '';
        let body;
        if (state.statementError) {
            body = `<div class="cost-panel-error" role="alert"><span>Couldn't load the statement.</span>
                <button type="button" class="btn btn-sm btn-ghost" data-action="chargeback-reload">Retry</button></div>`;
        } else if (!s) {
            body = '<div class="text-muted small p-2"><i class="fas fa-spinner fa-spin me-1" aria-hidden="true"></i>Loading statement…</div>';
        } else {
            body = statementTable(s);
        }
        $('#chargeback-body').html(Utils.html`
            <div class="d-flex justify-content-between align-items-center flex-wrap gap-2 mb-3">
                <div class="d-flex gap-2 align-items-center flex-wrap">
                    <label class="visually-hidden" for="chargeback-month">Month</label>
                    <select id="chargeback-month" class="form-select form-select-sm chargeback-month">${Utils.raw(monthOptions(state.month))}</select>
                    ${Utils.raw(badge)}
                </div>
                <div class="d-flex gap-2 flex-wrap">
                    ${Utils.raw(canFinalize() ? `<button type="button" class="btn btn-sm btn-primary" data-action="chargeback-finalize" id="chargeback-finalize">
                        <i class="fas fa-lock" aria-hidden="true"></i> Finalize</button>` : '')}
                    <button type="button" class="btn btn-sm btn-tonal btn-primary" data-action="chargeback-download" data-format="xlsx" ${s ? '' : 'disabled'}>
                        <i class="fas fa-file-excel" aria-hidden="true"></i> XLSX</button>
                    <button type="button" class="btn btn-sm btn-ghost" data-action="chargeback-download" data-format="csv" ${s ? '' : 'disabled'}>
                        <i class="fas fa-file-csv" aria-hidden="true"></i> CSV</button>
                </div>
            </div>
            ${Utils.raw(s ? Utils.html`<p class="text-muted small mb-2" id="chargeback-source"><i class="fas fa-info-circle me-1" aria-hidden="true"></i>${s.dataSource}</p>` : '')}
            ${Utils.raw(body)}`);
        $('#chargeback-month').on('change', function() {
            state.month = this.value;
            state.expanded.clear();
            if (window.history && window.history.replaceState) {
                window.history.replaceState(null, '', `#/chargeback?month=${state.month}`);
            }
            loadStatement();
        });
    }

    function statementTable(s) {
        if (!s.rows.length) {
            return '<section class="card"><div class="card-body text-muted text-center py-4">No cost data for this month.</div></section>';
        }
        const rows = s.rows.map(r => {
            const key = r.teamId || 'unassigned';
            const open = state.expanded.has(key);
            const detail = open ? Utils.html`<tr class="chargeback-detail"><td colspan="6" data-label="Environments">
                <ul class="list-unstyled mb-0">${Utils.raw(r.environments.map(e => Utils.html`<li class="d-flex justify-content-between gap-2">
                    <span>${e.environmentName}${Utils.raw(e.shared ? ' <span class="badge text-bg-light border">shared</span>' : '')}</span>
                    <span>${money(e.amount)}</span></li>`).join(''))}</ul></td></tr>` : '';
            return Utils.html`<tr class="${r.teamId ? '' : 'chargeback-unassigned'}">
                <td data-label="Team"><button type="button" class="btn btn-link p-0 fw-semibold chargeback-toggle" data-action="chargeback-expand"
                    data-key="${key}" aria-expanded="${String(open)}">
                    <i class="fas fa-chevron-${open ? 'down' : 'right'} me-1" aria-hidden="true"></i>${r.teamName}</button></td>
                <td data-label="Cost center">${r.costCenter || '—'}</td>
                <td data-label="Direct" class="text-end">${money(r.directCost)}</td>
                <td data-label="Shared allocated" class="text-end">${money(r.sharedAllocated)}</td>
                <td data-label="Total" class="text-end fw-semibold">${money(r.total)}</td>
                <td data-label="Actual coverage" class="text-end">${r.actualCoveragePct}%</td>
            </tr>${Utils.raw(detail)}`;
        }).join('');
        const t = s.totals;
        return Utils.html`<section class="card"><div class="card-body">
            <div class="table-responsive"><table class="table table-sm table-cards mb-0" id="chargeback-table">
                <thead><tr><th scope="col">Team</th><th scope="col">Cost center</th><th scope="col" class="text-end">Direct</th>
                    <th scope="col" class="text-end">Shared allocated</th><th scope="col" class="text-end">Total</th>
                    <th scope="col" class="text-end">Actual coverage</th></tr></thead>
                <tbody>${Utils.raw(rows)}</tbody>
                <tfoot><tr class="chargeback-totals">
                    <th scope="row" data-label="">Total</th><td data-label="Cost center"></td>
                    <td data-label="Direct" class="text-end">${money(t.directCost)}</td>
                    <td data-label="Shared allocated" class="text-end">${money(t.sharedAllocated)}</td>
                    <td data-label="Total" class="text-end fw-semibold" id="chargeback-grand-total">${money(t.total)}</td>
                    <td data-label="Actual coverage" class="text-end">${t.actualCoveragePct}%</td>
                </tr></tfoot></table></div></div></section>`;
    }

    function toggle(key) {
        if (state.expanded.has(key)) state.expanded.delete(key); else state.expanded.add(key);
        drawStatement();
        $(`.chargeback-toggle[data-key="${key}"]`).trigger('focus');
    }

    function finalize() {
        if (!canFinalize()) return;
        const month = state.month;
        DestructiveConfirm.show({
            title: 'Finalize chargeback',
            message: `This freezes the ${monthLabel(month)} statement. Later billing data will not change it.`,
            impact: ['The statement is stored as FINAL', 'Downloads and the page show the frozen figures', 'It cannot be reopened from the app'],
            confirmText: month,
            actionText: 'Finalize',
            onConfirm: () => call('POST', Config.API.costManagement.chargebackFinalize(month))
                .then(statement => {
                    if (!isActive() || state.month !== month) return;
                    state.statement = statement;
                    drawStatement();
                    Notifications.success(`${monthLabel(month)} chargeback finalized.`);
                })
                .catch(e => Notifications.showError(serverMessage(e, 'Could not finalize the statement.')))
        });
    }

    function download(format) {
        const month = state.month;
        fetch(Config.API.costManagement.chargebackExport(month, format), { credentials: 'same-origin' })
            .then(r => {
                if (!r.ok) throw new Error(String(r.status));
                return r.blob();
            })
            .then(blob => Utils.downloadBlob(blob, `chargeback-${month}.${format}`))
            .catch(() => Notifications.error('Could not download the statement.'));
    }

    // ---------------------------------------------------------------- split rules

    function loadRules() {
        const token = pageToken;
        call('GET', Config.API.costManagement.splitRules).then(rules => {
            if (!ContentRouter.isCurrent(token)) return;
            state.rules = rules || [];
            state.rulesError = false;
            drawRules();
        }).catch(() => {
            if (!ContentRouter.isCurrent(token)) return;
            state.rulesError = true;
            drawRules();
        });
    }

    function shareText(rule) {
        return rule.shares.map(s => (s.teamName || s.teamId) + (rule.method === 'FIXED_PERCENT' ? ` ${Number(s.percent)}%` : '')).join(', ');
    }

    function drawRules() {
        if (state.tab !== 'rules') return;
        let body;
        if (state.rulesError) {
            body = `<div class="cost-panel-error" role="alert"><span>Couldn't load split rules.</span>
                <button type="button" class="btn btn-sm btn-ghost" data-action="chargeback-rules-reload">Retry</button></div>`;
        } else if (!state.rules) {
            body = '<div class="text-muted small p-2"><i class="fas fa-spinner fa-spin me-1" aria-hidden="true"></i>Loading split rules…</div>';
        } else {
            const rows = state.rules.length ? state.rules.map(r => Utils.html`<tr>
                <td data-label="Shared environment" class="fw-semibold">${r.environmentName || r.environmentId}</td>
                <td data-label="Method">${METHODS[r.method] || r.method}</td>
                <td data-label="Teams">${shareText(r)}</td>
                <td data-label="From">${monthLabel(String(r.effectiveFrom).slice(0, 7))}</td>
                <td data-label="" class="text-end text-nowrap">
                    <button type="button" class="btn btn-sm btn-ghost" data-action="chargeback-rule-edit" data-id="${r.ruleId}"
                        aria-label="Edit the rule for ${r.environmentName || r.environmentId}"><i class="fas fa-pen" aria-hidden="true"></i></button>
                    <button type="button" class="btn btn-sm btn-ghost" data-action="chargeback-rule-delete" data-id="${r.ruleId}"
                        aria-label="Delete the rule for ${r.environmentName || r.environmentId}"><i class="fas fa-trash" aria-hidden="true"></i></button>
                </td></tr>`).join('')
                : '<tr><td colspan="5" class="text-muted text-center py-3">No split rules. Shared environments are charged to their own team.</td></tr>';
            body = `<section class="card"><div class="card-body"><div class="table-responsive">
                <table class="table table-sm table-cards mb-0" id="split-rules-table">
                <thead><tr><th scope="col">Shared environment</th><th scope="col">Method</th><th scope="col">Teams</th><th scope="col">From</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr></thead>
                <tbody>${rows}</tbody></table></div></div></section>`;
        }
        $('#chargeback-body').html(`
            <div class="d-flex justify-content-between align-items-center flex-wrap gap-2 mb-3">
                <p class="text-muted small mb-0">A split rule charges a shared environment's cost to several teams.</p>
                <button type="button" class="btn btn-sm btn-primary" data-action="chargeback-rule-new"><i class="fas fa-plus" aria-hidden="true"></i> New rule</button>
            </div>${body}`);
    }

    function shareRow(teams, share) {
        const options = teams.map(t => `<option value="${Utils.escapeHtml(t.teamId)}" ${share && share.teamId === t.teamId ? 'selected' : ''}>${Utils.escapeHtml(t.displayName)}</option>`).join('');
        return Utils.html`<div class="csr-share d-flex gap-2 align-items-center mb-2">
            <select class="form-select form-select-sm csr-team" aria-label="Team"><option value="">Choose a team</option>${Utils.raw(options)}</select>
            <div class="input-group input-group-sm csr-percent-group">
                <input type="number" class="form-control csr-percent" min="0.01" max="100" step="0.01" aria-label="Percent"
                    value="${share && share.percent != null ? Number(share.percent) : ''}">
                <span class="input-group-text">%</span></div>
            <button type="button" class="btn btn-sm btn-ghost" data-action="chargeback-share-remove" aria-label="Remove team"><i class="fas fa-times" aria-hidden="true"></i></button>
        </div>`;
    }

    function openRuleForm(ruleId) {
        const rule = ruleId ? (state.rules || []).find(r => r.ruleId === ruleId) : null;
        Promise.all([call('GET', Config.API.environments.list), call('GET', Config.API.teams.list(false))]).then(([envs, teams]) => {
            if (!isActive()) return;
            const taken = new Set((state.rules || []).map(r => r.environmentId));
            const envOptions = (envs || []).filter(e => rule ? e.environmentId === rule.environmentId : !taken.has(e.environmentId))
                .map(e => `<option value="${Utils.escapeHtml(e.environmentId)}">${Utils.escapeHtml(e.displayName || e.name)}</option>`).join('');
            const methodOptions = Object.keys(METHODS).map(m => `<option value="${m}" ${(rule ? rule.method : 'EVEN') === m ? 'selected' : ''}>${METHODS[m]}</option>`).join('');
            state.formTeams = teams || [];
            const shares = rule ? rule.shares : [null, null];
            Modals.show({
                id: 'splitRuleModal',
                title: rule ? `Edit split rule: ${rule.environmentName || rule.environmentId}` : 'New split rule',
                body: Utils.html`
                    <div class="mb-3"><label class="form-label" for="csr-environment">Shared environment</label>
                        <select class="form-select" id="csr-environment" ${rule ? 'disabled' : ''}>${Utils.raw(rule ? '' : '<option value="">Choose an environment</option>')}${Utils.raw(envOptions)}</select></div>
                    <div class="row g-2 mb-3">
                        <div class="col-sm-7"><label class="form-label" for="csr-method">Method</label>
                            <select class="form-select" id="csr-method">${Utils.raw(methodOptions)}</select></div>
                        <div class="col-sm-5"><label class="form-label" for="csr-from">From month</label>
                            <input type="month" class="form-control" id="csr-from" value="${rule ? String(rule.effectiveFrom).slice(0, 7) : monthValue(0)}"></div>
                    </div>
                    <fieldset><legend class="form-label fs-6">Teams</legend>
                        <div id="csr-shares">${Utils.raw(shares.map(s => shareRow(state.formTeams, s)).join(''))}</div>
                        <button type="button" class="btn btn-sm btn-ghost" data-action="chargeback-share-add"><i class="fas fa-plus" aria-hidden="true"></i> Add team</button>
                    </fieldset>
                    <div id="csr-sum" class="small mt-2" aria-live="polite"></div>`,
                buttons: [{ text: 'Cancel', class: 'btn-secondary', dismiss: true },
                    { text: rule ? 'Save' : 'Create rule', class: 'btn-primary', id: 'csr-save', disabled: true }],
                onShow: () => $(rule ? '#csr-method' : '#csr-environment').trigger('focus')
            });
            $('#splitRuleModal').on('input change', 'select, input', validateRule);
            $('#csr-save').on('click', function() { saveRule(rule); });
            validateRule();
        }).catch(() => Notifications.showError('Could not load environments and teams.'));
    }

    function addShare() {
        $('#csr-shares').append(shareRow(state.formTeams || [], null));
        $('#csr-shares .csr-team').last().trigger('focus');
        validateRule();
    }

    function removeShare(el) {
        $(el).closest('.csr-share').remove();
        validateRule();
    }

    /** Reads the form; returns { body, error }. */
    function readRule() {
        const method = $('#csr-method').val();
        const shares = $('#csr-shares .csr-share').map(function() {
            return { teamId: $(this).find('.csr-team').val(), percent: $(this).find('.csr-percent').val() };
        }).get().filter(s => s.teamId);
        const body = {
            environmentId: $('#csr-environment').val(),
            method,
            effectiveFrom: ($('#csr-from').val() || monthValue(0)) + '-01',
            shares: shares.map(s => ({ teamId: s.teamId, percent: method === 'FIXED_PERCENT' && s.percent !== '' ? Number(s.percent) : null }))
        };
        let error = null;
        if (!body.environmentId) error = 'Choose the shared environment.';
        else if (!shares.length) error = 'Add at least one team.';
        else if (new Set(shares.map(s => s.teamId)).size !== shares.length) error = 'A team is listed twice.';
        return { body, error };
    }

    function validateRule() {
        const fixed = $('#csr-method').val() === 'FIXED_PERCENT';
        $('#splitRuleModal .csr-percent-group').toggleClass('d-none', !fixed);
        const { body, error } = readRule();
        let message = error;
        let sumOk = true;
        if (!error && fixed) {
            const cents = body.shares.reduce((acc, s) => acc + Math.round((s.percent || 0) * 100), 0);
            const missing = body.shares.some(s => !(s.percent > 0));
            sumOk = !missing && cents === 10000;
            message = missing ? 'Give every team a percentage above 0.'
                : `Sum = ${(cents / 100).toFixed(2)}%${sumOk ? '' : ' (must be 100%)'}`;
        }
        $('#csr-sum').text(message || '').toggleClass('text-danger', !!error || !sumOk).toggleClass('text-success', !error && fixed && sumOk);
        $('#csr-save').prop('disabled', !!error || !sumOk);
    }

    function saveRule(rule) {
        const { body, error } = readRule();
        if (error) return;
        const $btn = $('#csr-save').prop('disabled', true);
        (rule ? call('PUT', Config.API.costManagement.splitRule(rule.ruleId), body) : call('POST', Config.API.costManagement.splitRules, body))
            .then(() => {
                bootstrap.Modal.getInstance(document.getElementById('splitRuleModal')).hide();
                Notifications.success(rule ? 'Split rule saved.' : 'Split rule created.');
                loadRules();
            })
            .catch(e => { Notifications.showError(serverMessage(e, 'Could not save the split rule.')); $btn.prop('disabled', false); });
    }

    function deleteRule(ruleId) {
        const rule = (state.rules || []).find(r => r.ruleId === ruleId);
        if (!rule) return;
        Modals.confirm('Delete split rule', `Delete the split rule for ${rule.environmentName || rule.environmentId}? Its cost goes back to its own team from the next statement.`, () => {
            call('DELETE', Config.API.costManagement.splitRule(ruleId))
                .then(() => { Notifications.success('Split rule deleted.'); loadRules(); })
                .catch(e => Notifications.showError(serverMessage(e, 'Could not delete the split rule.')));
        }, { confirmText: 'Delete', confirmClass: 'btn-danger' });
    }

    return {
        load, showTab, toggle, finalize, download, loadStatement, loadRules,
        openRuleForm, addShare, removeShare, deleteRule
    };
})();

window.Chargeback = Chargeback;

Actions.registerAll({
    'chargeback-tab': el => Chargeback.showTab(el.dataset.tab),
    'chargeback-reload': () => Chargeback.loadStatement(),
    'chargeback-expand': el => Chargeback.toggle(el.dataset.key),
    'chargeback-finalize': () => Chargeback.finalize(),
    'chargeback-download': el => Chargeback.download(el.dataset.format),
    'chargeback-rules-reload': () => Chargeback.loadRules(),
    'chargeback-rule-new': () => Chargeback.openRuleForm(null),
    'chargeback-rule-edit': el => Chargeback.openRuleForm(el.dataset.id),
    'chargeback-rule-delete': el => Chargeback.deleteRule(el.dataset.id),
    'chargeback-share-add': () => Chargeback.addShare(),
    'chargeback-share-remove': el => Chargeback.removeShare(el)
});
