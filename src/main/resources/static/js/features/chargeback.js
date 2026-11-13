/**
 * Chargeback (E23-T08, U15-T09 Chargeback.dc.html, ChargebackRules.dc.html): what each team owes for
 * a month with shared costs split by rules, the share from AWS billing, environments with no team,
 * "Finalise and send to finance"; the shared cost rules as a list and an inline editor; and the
 * environments and VMs missing the tags chargeback needs. Admin only.
 */
const Chargeback = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    /** Rule methods as the editor offers them. */
    const METHODS = [
        ['EVEN', 'Evenly', 'Same amount for each team'],
        ['FIXED_PERCENT', 'Fixed percentages', 'You set each share; must total 100%'],
        ['PROPORTIONAL_DIRECT', 'By own cost', 'In line with what each team spends directly']
    ];
    const METHOD_NAME = Object.fromEntries(METHODS.map(([k, name]) => [k, name]));
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

    function money0(v) {
        return '$' + Math.round(Number(v || 0)).toLocaleString('en-US');
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

    function monthName(value) {
        const [y, m] = value.split('-').map(Number);
        return new Date(Date.UTC(y, m - 1, 1)).toLocaleDateString([], { month: 'long', timeZone: 'UTC' });
    }

    function monthOptions(selected) {
        const out = [];
        for (let i = 0; i < 13; i++) {
            const value = monthValue(i);
            out.push(`<option value="${value}" ${value === selected ? 'selected' : ''}>${Utils.escapeHtml(monthLabel(value))}${i === 0 ? ' (so far)' : ''}</option>`);
        }
        return out.join('');
    }

    function capable(feature) {
        return typeof Capabilities !== 'undefined' && Capabilities.has(feature);
    }

    // ---------------------------------------------------------------- model (node-tested)

    /** The Unassigned row's environments and amount, for the banner and the asides. */
    function unassigned(statement) {
        const row = statement && (statement.rows || []).find(r => !r.teamId);
        if (!row) return { environments: [], amount: 0 };
        return { environments: row.environments || [], amount: Number(row.total || 0) };
    }

    /** Each environment's whole cost in a statement: its own row plus every share of a split one. */
    function environmentCosts(statement) {
        const costs = {};
        ((statement && statement.rows) || []).forEach(r => (r.environments || []).forEach(e => {
            costs[e.environmentId] = (costs[e.environmentId] || 0) + Number(e.amount || 0);
        }));
        return costs;
    }

    /** "100% ✓", or "95%, needs 100%"; ok only at exactly 100 with every share above 0. */
    function percentCheck(percents) {
        const cents = percents.reduce((acc, p) => acc + Math.round((Number(p) || 0) * 100), 0);
        const missing = percents.some(p => !(Number(p) > 0));
        const total = cents % 100 === 0 ? String(cents / 100) : (cents / 100).toFixed(2);
        if (missing) return { ok: false, text: 'Give every team a share above 0%' };
        return cents === 10000 ? { ok: true, text: '100% ✓' } : { ok: false, text: `${total}%, needs 100%` };
    }

    /** "12 Nov" for "2026-11-12". */
    function dayText(value) {
        const t = String(value || '').slice(0, 10);
        if (!/^\d{4}-\d{2}-\d{2}$/.test(t)) return '';
        const [y, m, d] = t.split('-').map(Number);
        return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', timeZone: 'UTC' });
    }

    /**
     * Which days the statement covers, beside its total (U23-T02). Staging's reviewer read "$4,764" as
     * November's charge: the page opens on last month (October), and the month list reads "November 2026
     * (so far)". The line names the month and its days; for the current month it gives Cost management's
     * month to date (fleet), which also counts today so far, so the two can be compared honestly.
     */
    function periodText(statement, current, fleet) {
        if (!statement || !statement.dataThrough) return '';
        const month = monthLabel(statement.month);
        const from = dayText(statement.dataFrom).split(' ')[0];
        const days = `${month}, ${from} to ${dayText(statement.dataThrough)}, from the daily cost snapshots.`;
        if (!current) return days;
        if (!fleet || fleet.monthToDate === null || fleet.monthToDate === undefined) return days;
        return `${days} Cost management's month to date, ${money0(fleet.monthToDate)}, adds today so far.`;
    }

    /** What each team would have paid last month under the rule (null when it depends on their own cost). */
    function shareAmounts(method, cost, percents) {
        if (cost == null) return percents.map(() => null);
        if (method === 'EVEN') return percents.map(() => percents.length ? cost / percents.length : 0);
        if (method === 'FIXED_PERCENT') return percents.map(p => cost * (Number(p) || 0) / 100);
        return percents.map(() => null);
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
            tab: ['rules', 'tags'].includes(q.tab) ? q.tab : 'statement',
            month: /^\d{4}-\d{2}$/.test(q.month || '') ? q.month : monthValue(1),
            statement: null, statementError: false, expanded: new Set(),
            rules: null, rulesError: false, selected: null, draft: null,
            teams: [], environments: [], lastMonth: null, tags: null, fleet: null
        };
        render();
        loadStatement();
        loadTags();
        if (state.tab === 'rules') loadRules();
    }

    function tagCount() {
        const envs = unassigned(state.statement).environments.length;
        const vms = state.tags ? Number(state.tags.untagged || 0) : 0;
        return envs + vms;
    }

    function render() {
        const tabs = [{ key: 'statement', label: 'Statement' }, { key: 'rules', label: 'Shared cost rules' },
            { key: 'tags', label: `Missing tags · ${tagCount()}` }];
        $('#content-area').html(`
            <div class="content-view chargeback-page" id="chargeback-view">
                ${PageHeader.markup({ title: 'Chargeback', sectionNav: PageHeader.costNav('chargeback'),
                    summaryHtml: 'What each team owes for the month, with shared costs split by your rules.',
                    actionsHtml: `<div class="chargeback-toolbar" id="chargeback-toolbar"></div>`,
                    helpPage: typeof HelpLink !== 'undefined' ? 'chargeback' : null, helpLabel: 'Chargeback' })}
                ${Tabs.markup({ id: 'chargeback-tabs', label: 'Chargeback', active: state.tab, tabs })}
                ${tabs.map(t => Tabs.panel({ id: 'chargeback-tabs', key: t.key, active: state.tab }, '')).join('')}
            </div>`);
        Tabs.bind(document.getElementById('chargeback-tabs'), key => showTab(key));
        drawToolbar();
        drawTab();
    }

    function showTab(tab) {
        state.tab = tab;
        if (window.history && window.history.replaceState) {
            const q = new URLSearchParams();
            if (state.month !== monthValue(1)) q.set('month', state.month);
            if (tab !== 'statement') q.set('tab', tab);
            window.history.replaceState(null, '', '#/chargeback' + (q.toString() ? '?' + q : ''));
        }
        if (tab === 'rules' && state.rules === null) loadRules();
        drawToolbar();
        drawTab();
    }

    function drawTab() {
        if (state.tab === 'rules') drawRules();
        else if (state.tab === 'tags') drawTags();
        else drawStatement();
    }

    function panel(key) {
        return $(`#chargeback-tabs-panel-${key}`);
    }

    /** Month, Download and Finalise belong to the statement; the other tabs keep the header quiet. */
    function drawToolbar() {
        if (state.tab !== 'statement') {
            $('#chargeback-toolbar').html('');
            return;
        }
        const s = state.statement;
        $('#chargeback-toolbar').html(Utils.html`
            <label class="visually-hidden" for="chargeback-month">Month</label>
            <select id="chargeback-month" class="form-select chargeback-month">${Utils.raw(monthOptions(state.month))}</select>
            <button type="button" class="btn btn-outline-secondary" data-action="chargeback-download" data-format="xlsx" ${s ? '' : 'disabled'}>Download, Excel</button>
            ${Utils.raw(canFinalize() ? `<button type="button" class="btn btn-primary" data-action="chargeback-finalize" id="chargeback-finalize">Finalise and send to finance</button>` : '')}`);
        $('#chargeback-month').on('change', function() {
            state.month = this.value;
            state.expanded.clear();
            showTab(state.tab);
            loadStatement();
        });
    }

    function updateTagTabLabel() {
        $('#chargeback-tabs [data-tab="tags"]').text(`Missing tags · ${tagCount()}`);
    }

    // ---------------------------------------------------------------- statement

    function loadStatement() {
        const token = pageToken;
        const month = state.month;
        state.statement = null;
        state.statementError = false;
        drawTab();
        call('GET', Config.API.costManagement.chargeback(month)).then(statement => {
            if (!ContentRouter.isCurrent(token) || state.month !== month) return;
            state.statement = statement;
            if (month === monthValue(1)) state.lastMonth = statement;
            if (month === monthValue(0)) loadFleetMonth(token);
            drawToolbar();
            updateTagTabLabel();
            drawTab();
        }).catch(xhr => {
            if (!ContentRouter.isCurrent(token) || state.month !== month) return;
            state.statementError = xhr || true;
            drawTab();
        });
    }

    /** Cost management's month to date for the current month's statement (U23-T01's figure). */
    function loadFleetMonth(token) {
        call('GET', Config.API.costManagement.month).then(fleet => {
            if (!ContentRouter.isCurrent(token) || state.month !== monthValue(0)) return;
            state.fleet = fleet || null;
            drawStatement();
        }).catch(() => {});
    }

    function canFinalize() {
        const s = state.statement;
        // U22-T07: with chargeback switched off nothing is finalised or sent (staging offered the button).
        return capable('chargeback') && !!s && s.status === 'DRAFT' && state.month < monthValue(0);
    }

    /** The line under the statement: what draft or final means for this month. */
    function footText(s) {
        if (s.status === 'FINAL') return "Final. The numbers don't change.";
        if (state.month >= monthValue(0)) return 'So far this month; the numbers change until the month ends.';
        if (!capable('chargeback')) return 'Draft. Chargeback is switched off, so statements are not finalised or sent to finance. An administrator can switch it on in the server settings.'; 
        const next = monthValue(0) === state.month ? monthValue(-1) : nextMonth(state.month);
        return `Draft. It is finalised early in ${monthName(next)} and emailed to finance; after that the numbers don't change.`;
    }

    function nextMonth(value) {
        const [y, m] = value.split('-').map(Number);
        const d = new Date(Date.UTC(y, m, 1));
        return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
    }

    function drawStatement() {
        if (state.tab !== 'statement') return;
        const s = state.statement;
        if (state.statementError) {
            panel('statement').html(UiStates.failed({ what: 'the statement', xhr: state.statementError, retryAction: 'chargeback-reload', className: 'cost-panel-error' }));
            return;
        }
        if (!s) {
            panel('statement').html('<p class="cost-note">Loading the statement…</p>');
            return;
        }
        const gap = unassigned(s);
        const banner = gap.environments.length ? Utils.html`<div class="chargeback-banner" role="status" id="chargeback-banner">
                <span>${String(gap.environments.length)} environment${gap.environments.length === 1 ? ' has' : 's have'} no team, so
                    <span class="cost-mono">${money0(gap.amount)}</span> sits in Unassigned.</span>
                <a href="#/teams">Assign teams</a></div>` : '';
        const period = periodText(s, state.month === monthValue(0), state.fleet);
        panel('statement').html(Utils.html`${Utils.raw(banner)}${Utils.raw(statementTable(s))}
            ${Utils.raw(period ? Utils.html`<p class="cost-note" id="chargeback-period">${period}</p>` : '')}
            <p class="cost-note chargeback-foot" id="chargeback-foot">${footText(s)}</p><span id="chargeback-status" hidden>${s.status}</span>`);
    }

    function statementTable(s) {
        if (!s.rows.length) {
            return '<section class="cost-card"><p class="text-muted text-center py-4 mb-0">No cost data for this month.</p></section>';
        }
        const rows = s.rows.map(r => {
            const key = r.teamId || 'unassigned';
            const open = state.expanded.has(key);
            const detail = open ? Utils.html`<tr class="chargeback-detail"><td colspan="6" data-label="Environments">
                <ul class="list-unstyled mb-0">${Utils.raw(r.environments.map(e => Utils.html`<li class="d-flex justify-content-between gap-2">
                    <span>${e.environmentName}${Utils.raw(e.shared ? ' <span class="chargeback-shared">shared</span>' : '')}</span>
                    <span class="cost-mono">${money(e.amount)}</span></li>`).join(''))}</ul></td></tr>` : '';
            return Utils.html`<tr class="${r.teamId ? '' : 'chargeback-unassigned'}">
                <td data-label="Team"><button type="button" class="chargeback-toggle" data-action="chargeback-expand"
                    data-key="${key}" aria-expanded="${String(open)}">${r.teamName}</button></td>
                <td data-label="Cost centre" class="cost-mono chargeback-cc">${r.costCenter || '—'}</td>
                <td data-label="Own environments" class="cost-num cost-mono">${money0(r.directCost)}</td>
                <td data-label="Share of shared" class="cost-num cost-mono">${r.teamId ? money0(r.sharedAllocated) : '—'}</td>
                <td data-label="Total" class="cost-num cost-mono chargeback-total">${money0(r.total)}</td>
                <td data-label="From AWS billing" class="cost-num cost-mono" title="${s.dataSource}">${Math.round(Number(r.actualCoveragePct || 0))}%</td>
            </tr>${Utils.raw(detail)}`;
        }).join('');
        const t = s.totals;
        return Utils.html`<section class="cost-card rs-table-card chargeback-card"><div class="cost-scroll table-cards-wrap">
            <table class="table table-cards chargeback-table" id="chargeback-table">
                <thead><tr><th scope="col">Team</th><th scope="col">Cost centre</th><th scope="col" class="cost-num">Own environments</th>
                    <th scope="col" class="cost-num">Share of shared</th><th scope="col" class="cost-num">Total</th>
                    <th scope="col" class="cost-num">From AWS billing</th></tr></thead>
                <tbody>${Utils.raw(rows)}
                <tr class="chargeback-totals">
                    <th scope="row" data-label="">Total</th><td data-label="Cost centre"></td>
                    <td data-label="Own environments" class="cost-num cost-mono">${money0(t.directCost)}</td>
                    <td data-label="Share of shared" class="cost-num cost-mono">${money0(t.sharedAllocated)}</td>
                    <td data-label="Total" class="cost-num cost-mono" id="chargeback-grand-total">${money0(t.total)}</td>
                    <td data-label="From AWS billing" class="cost-num cost-mono">${Math.round(Number(t.actualCoveragePct || 0))}%</td>
                </tr></tbody></table></div></section>`;
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
            title: 'Finalise and send to finance',
            message: `This freezes the ${monthLabel(month)} statement and emails it to finance. Later billing data will not change it.`,
            impact: ['The statement is stored as final', 'Downloads and the page show the frozen figures', 'It cannot be reopened from the app'],
            confirmText: month,
            actionText: 'Finalise and send',
            onConfirm: () => call('POST', Config.API.costManagement.chargebackFinalize(month) + '?send=true')
                .then(statement => {
                    if (!isActive() || state.month !== month) return;
                    state.statement = statement;
                    drawToolbar();
                    drawStatement();
                    Notifications.success(statement && statement.emailed
                        ? `${monthName(month)} finalised and sent to finance.`
                        : `${monthName(month)} finalised. No finance address is set, so nothing was sent.`);
                })
                .catch(e => Notifications.showError(serverMessage(e, 'Could not finalise the statement.')))
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

    // ---------------------------------------------------------------- missing tags

    function loadTags() {
        const token = pageToken;
        call('GET', Config.API.costManagement.tagsStatus).then(tags => {
            if (!ContentRouter.isCurrent(token)) return;
            state.tags = tags || null;
            updateTagTabLabel();
            if (state.tab !== 'statement') drawTab();
        }).catch(() => { /* the tab says it could not tell */ });
    }

    function tagsCard(compact) {
        const t = state.tags;
        if (!t) return '<p class="cost-note">Couldn\'t read the cost tag status.</p>';
        const missing = Number(t.untagged || 0);
        const button = capable('costTagging') && missing > 0
            ? `<button type="button" class="btn btn-outline-primary btn-sm" data-action="chargeback-tag-now" id="chargeback-tag-now">Tag the ${missing} now</button>`
            : (capable('costTagging') ? '' : '<p class="cost-note mb-0">Cost tagging is switched off, so the platform does not add these tags. An administrator can switch it on in Settings › Cost.</p>');
        return `<div class="chargeback-tag-counts"><span><b class="cost-mono">${Number(t.tagged || 0)}</b> tagged</span>
                <span class="${missing ? 'chargeback-missing' : ''}"><b class="cost-mono">${missing}</b> missing</span></div>
            ${compact ? '' : ''}<p class="cost-note">The platform writes environment and team tags (starting <span class="cost-mono">tcg:</span>) to each
                VM every night, so AWS billing can be split the same way.</p>${button}`;
    }

    function drawTags() {
        if (state.tab !== 'tags') return;
        const gap = unassigned(state.statement);
        const envs = gap.environments.length ? gap.environments.map(e => Utils.html`<li><a href="#/environments/${e.environmentId}/settings">${e.environmentName}</a>
                <span class="cost-mono">${money(e.amount)}</span></li>`).join('')
            : '<li class="cost-note">Every environment with cost this month has a team.</li>';
        panel('tags').html(`<div class="chargeback-tags">
                <section class="cost-card"><h2 class="chargeback-h2">Environments with no team</h2>
                    <p class="cost-note">Their cost shows as Unassigned on the ${Utils.escapeHtml(monthLabel(state.month))} statement. Set the team in each environment's settings.</p>
                    <ul class="chargeback-envs" id="chargeback-untagged-envs">${envs}</ul></section>
                <section class="cost-card" id="chargeback-tags-card"><h2 class="chargeback-h2">Cost tags on VMs</h2>${tagsCard()}</section>
            </div>`);
    }

    function tagNow() {
        const $btn = $('[data-action="chargeback-tag-now"]').prop('disabled', true).text('Tagging…');
        call('POST', Config.API.costManagement.tagsReconcile)
            .then(result => {
                Notifications.success(`Cost tags written: ${result.tagged} tagged, ${result.failed} failed.`);
                loadTags();
            })
            .catch(e => {
                Notifications.showError(serverMessage(e, 'Could not write the cost tags.'));
                $btn.prop('disabled', false);
            });
    }

    // ---------------------------------------------------------------- shared cost rules

    function loadRules() {
        const token = pageToken;
        Promise.all([
            call('GET', Config.API.costManagement.splitRules),
            call('GET', Config.API.teams.list(false)).catch(() => []),
            call('GET', Config.API.environments.list).catch(() => []),
            state.lastMonth ? Promise.resolve(state.lastMonth) : call('GET', Config.API.costManagement.chargeback(monthValue(1))).catch(() => null)
        ]).then(([rules, teams, envs, lastMonth]) => {
            if (!ContentRouter.isCurrent(token)) return;
            state.rules = rules || [];
            state.teams = teams || [];
            state.environments = envs || [];
            state.lastMonth = lastMonth;
            state.rulesError = false;
            if (!state.selected && state.rules.length) pick(state.rules[0].ruleId, true);
            else drawRules();
        }).catch(xhr => {
            if (!ContentRouter.isCurrent(token)) return;
            state.rulesError = xhr || true;
            drawRules();
        });
    }

    function envName(id) {
        const e = state.environments.find(x => x.environmentId === id);
        const rule = (state.rules || []).find(r => r.environmentId === id);
        return e ? (e.displayName || e.name) : (rule && rule.environmentName) || id;
    }

    function teamName(id) {
        const t = state.teams.find(x => x.teamId === id);
        return t ? t.displayName : id;
    }

    /** Selects a rule (or 'new') and copies it into the editor's draft. */
    function pick(ruleId, quiet) {
        const rule = (state.rules || []).find(r => r.ruleId === ruleId);
        state.selected = rule ? rule.ruleId : 'new';
        state.draft = rule
            ? { ruleId: rule.ruleId, environmentId: rule.environmentId, method: rule.method, effectiveFrom: String(rule.effectiveFrom).slice(0, 7),
                shares: rule.shares.map(s => ({ teamId: s.teamId, percent: s.percent == null ? '' : String(Number(s.percent)) })) }
            : { ruleId: null, environmentId: '', method: 'EVEN', effectiveFrom: monthValue(-1), shares: [{ teamId: '', percent: '' }, { teamId: '', percent: '' }] };
        drawRules();
        if (!quiet) $(rule ? '#csr-editor input[name="csr-method"]:checked' : '#csr-environment').trigger('focus');
    }

    function drawRules() {
        if (state.tab !== 'rules') return;
        if (state.rulesError) {
            panel('rules').html(UiStates.failed({ what: 'the shared cost rules', xhr: state.rulesError, retryAction: 'chargeback-rules-reload', className: 'cost-panel-error' }));
            return;
        }
        if (!state.rules) {
            panel('rules').html('<p class="cost-note">Loading the rules…</p>');
            return;
        }
        const costs = environmentCosts(state.lastMonth);
        const list = state.rules.map(r => Utils.html`<button type="button" class="csr-rule" data-action="chargeback-rule-pick" data-id="${r.ruleId}"
                aria-current="${state.selected === r.ruleId ? 'true' : 'false'}"><b>${r.environmentName || envName(r.environmentId)}</b>
                <span class="csr-rule-meta"><span>${METHOD_NAME[r.method] || r.method} · ${String(r.shares.length)} team${r.shares.length === 1 ? '' : 's'}</span>
                <span class="cost-mono">${costs[r.environmentId] != null ? money0(costs[r.environmentId]) : '—'}</span></span></button>`).join('');
        const gap = unassigned(state.lastMonth);
        panel('rules').html(`
            <p class="cost-intro mb-3">An environment several teams use can pass its cost on to them instead of to its own team.</p>
            <div class="csr-layout">
                <nav class="cost-card csr-list" aria-label="Rules">
                    <div class="csr-list-title">One rule per environment</div>
                    ${list || '<p class="cost-note px-3">No rules yet.</p>'}
                    <button type="button" class="btn btn-link csr-new" data-action="chargeback-rule-new">+ New rule</button>
                </nav>
                <section class="cost-card csr-editor" id="csr-editor" aria-labelledby="csr-title">${editorHtml(costs)}</section>
                <aside class="csr-aside">
                    <div class="cost-card"><h2 class="chargeback-h2">Cost with no team</h2>
                        <div class="cost-mono csr-big">${money0(gap.amount)}</div>
                        <p class="cost-note">Last month, from ${gap.environments.length} environment${gap.environments.length === 1 ? '' : 's'} not linked to a team.
                            It shows as "Unassigned" on the statement.</p>
                        <a href="#/teams">Link environments to teams</a></div>
                    <div class="cost-card" id="chargeback-tags-aside"><h2 class="chargeback-h2">Cost tags on VMs</h2>${tagsCard(true)}</div>
                </aside>
            </div>`);
        validateRule();
    }

    function editorHtml(costs) {
        const d = state.draft;
        if (!d) return '<p class="cost-note p-3 mb-0">Choose a rule, or add one with + New rule.</p>';
        const cost = d.environmentId ? costs[d.environmentId] : null;
        const taken = new Set((state.rules || []).filter(r => r.ruleId !== d.ruleId).map(r => r.environmentId));
        const envChoice = d.ruleId ? '' : Utils.html`<label class="csr-field" for="csr-environment">Environment
            <select class="form-select" id="csr-environment"><option value="">Choose an environment</option>${Utils.raw(state.environments
                .filter(e => !taken.has(e.environmentId))
                .map(e => Utils.html`<option value="${e.environmentId}" ${e.environmentId === d.environmentId ? 'selected' : ''}>${e.displayName || e.name}</option>`).join(''))}</select></label>`;
        const methods = METHODS.map(([k, name, desc]) => Utils.html`<label class="csr-method${d.method === k ? ' on' : ''}">
                <span><input type="radio" class="form-check-input" name="csr-method" value="${k}" ${d.method === k ? 'checked' : ''}> ${name}</span>
                <span class="cost-note">${desc}</span></label>`).join('');
        const fixed = d.method === 'FIXED_PERCENT';
        const amounts = shareAmounts(d.method, cost, d.shares.map(s => s.percent));
        const teamOptions = sel => state.teams.map(t => Utils.html`<option value="${t.teamId}" ${t.teamId === sel ? 'selected' : ''}>${t.displayName}</option>`).join('');
        const rows = d.shares.map((s, i) => Utils.html`<div class="csr-share" data-index="${String(i)}">
                <select class="form-select form-select-sm csr-team" aria-label="Team"><option value="">Choose a team</option>${Utils.raw(teamOptions(s.teamId))}</select>
                <span class="csr-pct">${Utils.raw(fixed ? Utils.html`<input type="number" class="form-control form-control-sm cost-mono csr-percent" min="0.01" max="100" step="0.01"
                    value="${s.percent}" aria-label="${s.teamId ? teamName(s.teamId) : 'Team'} percent">%` : '—')}</span>
                <span class="cost-mono csr-amount">${amounts[i] == null ? '—' : money0(amounts[i])}</span>
                <button type="button" class="btn btn-link btn-sm csr-remove" data-action="chargeback-share-remove" data-index="${String(i)}"
                    aria-label="Remove ${s.teamId ? teamName(s.teamId) : 'this team'}">×</button></div>`).join('');
        const from = [monthValue(0), monthValue(-1), monthValue(-2), monthValue(-3)];
        if (d.effectiveFrom && !from.includes(d.effectiveFrom)) from.unshift(d.effectiveFrom);
        return Utils.html`
            <div class="csr-head"><h2 id="csr-title">${d.ruleId ? (envName(d.environmentId)) : 'New rule'}</h2>
                <span class="cost-note">${cost == null ? '' : `${money0(cost)} last month`}</span></div>
            <div class="csr-body">
                ${Utils.raw(envChoice)}
                <fieldset class="csr-methods"><legend>Split its cost</legend><div class="csr-method-grid">${Utils.raw(methods)}</div></fieldset>
                <div class="csr-shares">
                    <div class="csr-share csr-share-head"><span>Team</span><span>Share</span><span>Last month</span><span></span></div>
                    <div id="csr-shares">${Utils.raw(rows)}</div>
                    <div class="csr-share csr-share-foot"><button type="button" class="btn btn-link btn-sm p-0" data-action="chargeback-share-add">+ Add team</button>
                        <span class="cost-mono" id="csr-sum" aria-live="polite"></span><span class="cost-mono">${cost == null ? '' : money0(cost)}</span><span></span></div>
                </div>
                <label class="csr-field csr-from" for="csr-from">From
                    <select class="form-select" id="csr-from">${Utils.raw(from.map(m => `<option value="${m}" ${m === d.effectiveFrom ? 'selected' : ''}>${Utils.escapeHtml(monthLabel(m))}</option>`).join(''))}</select>
                    <span class="cost-note">Finalised months don't change</span></label>
            </div>
            <div class="csr-actions">
                ${Utils.raw(d.ruleId ? '<button type="button" class="btn btn-outline-danger" data-action="chargeback-rule-delete" id="csr-delete">Delete rule</button>' : '<span></span>')}
                <button type="button" class="btn btn-primary" id="csr-save" data-action="chargeback-rule-save" disabled>Save rule</button>
            </div>`;
    }

    /** Copies the editor's fields into the draft. */
    function readEditor() {
        const d = state.draft;
        if (!d) return;
        if (!d.ruleId) d.environmentId = $('#csr-environment').val() || '';
        d.method = $('#csr-editor input[name="csr-method"]:checked').val() || d.method;
        d.effectiveFrom = $('#csr-from').val() || d.effectiveFrom;
        $('#csr-shares .csr-share').each(function() {
            const i = Number(this.dataset.index);
            if (!d.shares[i]) return;
            d.shares[i].teamId = $(this).find('.csr-team').val() || '';
            const pct = $(this).find('.csr-percent');
            if (pct.length) d.shares[i].percent = pct.val();
        });
    }

    /** The rule as the API takes it, and why it cannot be saved yet. */
    function readRule() {
        const d = state.draft;
        const shares = d.shares.filter(s => s.teamId);
        const body = {
            environmentId: d.environmentId,
            method: d.method,
            effectiveFrom: d.effectiveFrom + '-01',
            shares: shares.map(s => ({ teamId: s.teamId, percent: d.method === 'FIXED_PERCENT' && s.percent !== '' ? Number(s.percent) : null }))
        };
        let error = null;
        if (!body.environmentId) error = 'Choose the environment.';
        else if (!shares.length) error = 'Add at least one team.';
        else if (new Set(shares.map(s => s.teamId)).size !== shares.length) error = 'A team is listed twice.';
        return { body, error };
    }

    function validateRule() {
        if (!state.draft || !$('#csr-editor').length) return;
        readEditor();
        const { body, error } = readRule();
        let ok = !error;
        let text = error || '';
        if (!error && body.method === 'FIXED_PERCENT') {
            const check = percentCheck(body.shares.map(s => s.percent));
            ok = check.ok;
            text = check.text;
        } else if (!error) {
            text = body.method === 'EVEN' ? `Evenly between ${body.shares.length}` : 'By each team\'s own cost';
        }
        $('#csr-sum').text(text).toggleClass('csr-bad', !ok).toggleClass('csr-ok', ok);
        $('#csr-save').prop('disabled', !ok);
    }

    /** Redraws the editor after a change that alters its rows (method, add, remove, environment). */
    function redrawEditor() {
        readEditor();
        $('#csr-editor').html(editorHtml(environmentCosts(state.lastMonth)));
        validateRule();
    }

    function addShare() {
        readEditor();
        state.draft.shares.push({ teamId: '', percent: '' });
        redrawEditor();
        $('#csr-shares .csr-team').last().trigger('focus');
    }

    function removeShare(index) {
        readEditor();
        state.draft.shares.splice(Number(index), 1);
        redrawEditor();
    }

    function saveRule() {
        readEditor();
        const { body, error } = readRule();
        if (error) return;
        const d = state.draft;
        const $btn = $('#csr-save').prop('disabled', true);
        (d.ruleId ? call('PUT', Config.API.costManagement.splitRule(d.ruleId), body) : call('POST', Config.API.costManagement.splitRules, body))
            .then(saved => {
                Notifications.success(d.ruleId ? 'Rule saved.' : 'Rule added.');
                state.selected = saved && saved.ruleId ? saved.ruleId : null;
                state.rules = null;
                call('GET', Config.API.costManagement.splitRules).then(rules => {
                    state.rules = rules || [];
                    pick(state.selected && state.rules.some(r => r.ruleId === state.selected) ? state.selected : (state.rules[0] || {}).ruleId, true);
                });
            })
            .catch(e => { Notifications.showError(serverMessage(e, 'Could not save the rule.')); $btn.prop('disabled', false); });
    }

    function deleteRule() {
        const d = state.draft;
        if (!d || !d.ruleId) return;
        Modals.confirm('Delete rule', `Delete the rule for ${envName(d.environmentId)}? Its cost goes back to its own team from the next statement.`, () => {
            call('DELETE', Config.API.costManagement.splitRule(d.ruleId))
                .then(() => {
                    Notifications.success('Rule deleted.');
                    state.selected = null;
                    state.draft = null;
                    loadRules();
                })
                .catch(e => Notifications.showError(serverMessage(e, 'Could not delete the rule.')));
        }, { confirmText: 'Delete', confirmClass: 'btn-danger' });
    }

    if (typeof $ !== 'undefined') {
        $(document).on('change', '#csr-editor input[name="csr-method"], #csr-environment', () => redrawEditor());
        $(document).on('input change', '#csr-editor .csr-team, #csr-editor .csr-percent, #csr-from', () => validateRule());
    }

    return {
        load, showTab, toggle, finalize, download, loadStatement, loadRules, pick, addShare, removeShare, saveRule, deleteRule, tagNow,
        model: { unassigned, environmentCosts, percentCheck, shareAmounts, periodText }
    };
})();

if (typeof window !== 'undefined') window.Chargeback = Chargeback;

if (typeof Actions !== 'undefined') Actions.registerAll({
    'chargeback-expand': el => Chargeback.toggle(el.dataset.key),
    'chargeback-finalize': () => Chargeback.finalize(),
    'chargeback-download': el => Chargeback.download(el.dataset.format),
    'chargeback-reload': () => Chargeback.loadStatement(),
    'chargeback-rules-reload': () => Chargeback.loadRules(),
    'chargeback-rule-pick': el => Chargeback.pick(el.dataset.id),
    'chargeback-rule-new': () => Chargeback.pick(null),
    'chargeback-rule-save': () => Chargeback.saveRule(),
    'chargeback-rule-delete': () => Chargeback.deleteRule(),
    'chargeback-share-add': () => Chargeback.addShare(),
    'chargeback-share-remove': el => Chargeback.removeShare(el.dataset.index),
    'chargeback-tag-now': () => Chargeback.tagNow()
});
