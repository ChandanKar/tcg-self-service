/**
 * Teams page (E23-T03, U25-T05): a paginated table like Users. Counts under the title ("3 teams ·
 * 1 without a cost centre · 0 with no members · 4 environments without a team"), a search (team
 * name, short name, cost centre) and a filter kept in the address (?q=&show=&page=), then one row
 * per team: Team, Cost centre, Members (and the Entra group they sync from), Environments ("162 ·
 * 23 running", with a power strip when it fits one line), This month (GET month-by-team), Monthly
 * limit (VM hours used / limit while quotas are on) and Open / Edit / Deactivate. Rows fit the window
 * (ListPanel), paged with the shared pager. Admins create and edit teams; a team's own page is
 * team-detail.js. Also the team picker of the environment dialogs.
 */
const Teams = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    const PAGE_SIZE = 10;
    /** Teams a page on the page frame: as many rows as fit, at most PAGE_SIZE (U18-T03). */
    let fitSize = null;
    const pageSize = () => fitSize || PAGE_SIZE;
    const SHOW_FILTERS = [['', 'All teams'], ['no-cost-centre', 'Without a cost centre'], ['no-members', 'With no members'],
        ['entra', 'Linked to Entra'], ['manual', 'Not linked']];
    /** A strip in the Environments cell only while it fits one line of the column. */
    const STRIP_MAX_LIGHTS = 12;
    const STRIP_MAX_GROUPS = 3;
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

    // ---- pure model (node tests: src/test/js/teams-cards.test.mjs, teams-list.test.mjs) ----

    function plural(n, one, many) {
        return `${n} ${n === 1 ? one : many}`;
    }

    /** "6 people · synced from Entra group "Payments Engineering"" */
    function membersText(team) {
        const n = team.memberCount || 0;
        const people = plural(n, 'person', 'people');
        if (!team.entraGroupId) return people;
        return `${people} · ${entraText(team)}`;
    }

    /** "synced from Entra group "Payments Engineering"", or '' when no group is linked. */
    function entraText(team) {
        if (!team.entraGroupId) return '';
        return `synced from Entra group "${team.entraGroupName || 'linked group'}"`;
    }

    /** The VM-hours quota as {used, limit, percent, text, label, over}, or null without one. */
    function vmHours(team) {
        const q = (team.quotas || []).find(x => x.metric === 'VM_HOURS' && x.active !== false);
        if (!q) return null;
        const used = Math.round(Number(q.used || 0));
        const limit = Math.round(Number(q.limit || 0));
        const percent = limit > 0 ? Math.round((used / limit) * 100) : 0;
        return { used, limit, percent, over: percent > 90,
            text: `${used.toLocaleString('en-US')} / ${limit.toLocaleString('en-US')}`,
            label: `${percent}% of the monthly VM hours used` };
    }

    /** The Monthly limit cell: "1,840 / 4,000 h" while quotas are on and the team has a VM-hours limit, else "No limit". */
    function limitCell(team, quotasOn) {
        const hours = quotasOn ? vmHours(team) : null;
        if (!hours) return { text: 'No limit', over: false, label: '', none: true };
        return { text: `${hours.text} h`, over: hours.over, label: hours.label, none: false };
    }

    /** "$1,234.56", or '—' without a figure. */
    function money(value) {
        if (value === null || value === undefined || value === '') return '—';
        const amount = Number(value);
        return Number.isFinite(amount)
            ? '$' + amount.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '—';
    }

    /** Team display name -> month-to-date cost, from GET month-by-team (keyed by the team's display name); null when it didn't load. */
    function monthByTeam(rows) {
        if (!Array.isArray(rows)) return null;
        const map = {};
        rows.forEach(r => { map[r.dimensionKey || r.dimensionLabel] = r.cost; });
        return map;
    }

    /** The This month cell: the team's month to date, '—' when the figures did not load or the team has none. */
    function monthText(team, byTeam) {
        if (!byTeam || !Object.prototype.hasOwnProperty.call(byTeam, team.displayName)) return '—';
        return money(byTeam[team.displayName]);
    }

    function runningVms(env) {
        if (env && typeof env.runningVmCount === 'number') return env.runningVmCount;
        return ((env && env.groupStates) || []).reduce((sum, g) => sum + (Number(g.running) || 0), 0);
    }

    /**
     * The Environments cell: {total, running, text: "162 · 23 running", groups} where running counts
     * environments with a VM running and groups (one per environment, for a power strip) is null
     * unless every light fits one line of the column.
     */
    function environmentsCell(team, envsById) {
        const envs = team.environments || [];
        const known = envsById || {};
        const running = envs.filter(e => runningVms(known[e.environmentId]) > 0).length;
        const n = v => Math.max(0, Number(v) || 0);
        const groups = envs.map(e => {
            const env = known[e.environmentId];
            const lights = [];
            ((env && env.groupStates) || []).slice().sort((a, b) => (a.sequencePosition || 0) - (b.sequencePosition || 0)).forEach(g => {
                lights.push(...Array(n(g.running)).fill('R'), ...Array(n(g.busy)).fill('B'), ...Array(n(g.problem)).fill('E'),
                    ...Array(n(g.unknown)).fill('U'), ...Array(n(g.stopped)).fill('S'));
            });
            return { name: e.displayName || e.name || '', lights };
        }).filter(g => g.lights.length);
        const lights = groups.reduce((sum, g) => sum + g.lights.length, 0);
        const fits = lights > 0 && lights <= STRIP_MAX_LIGHTS && groups.length <= STRIP_MAX_GROUPS;
        return { total: envs.length, running, text: envs.length ? `${envs.length} · ${running} running` : 'None yet',
            groups: fits ? groups : null };
    }

    /** The counts line: teams, without a cost centre, with no members, environments without a team (null when unknown). */
    function counts(teams, environments) {
        const list = teams || [];
        return {
            teams: list.length,
            noCostCentre: list.filter(t => !t.costCenter).length,
            noMembers: list.filter(t => !(t.memberCount > 0)).length,
            unassigned: Array.isArray(environments) ? environments.filter(e => e.isActive !== false && !e.teamId).length : null
        };
    }

    /** The teams matching a search (display name, short name or cost centre) and a Show filter. */
    function filterList(teams, search, show) {
        const q = String(search || '').toLowerCase().trim();
        return (teams || []).filter(t => {
            if (q && ![t.displayName, t.name, t.costCenter].some(v => String(v || '').toLowerCase().includes(q))) return false;
            if (show === 'no-cost-centre' && t.costCenter) return false;
            if (show === 'no-members' && t.memberCount > 0) return false;
            if (show === 'entra' && !t.entraGroupId) return false;
            if (show === 'manual' && t.entraGroupId) return false;
            return true;
        });
    }

    function teamHash(teamId) {
        return '#/teams/' + encodeURIComponent(teamId);
    }

    // ---- page ----

    function quotasOn() {
        return typeof Capabilities !== 'undefined' && Capabilities.has('quotas');
    }

    function load() {
        pageToken = ContentRouter.token();
        if (!Auth.isAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3">Access denied. Admin only.</div>');
            return;
        }
        // Old links: #/teams?team=<id> now opens the team's page.
        const q = ContentRouter.query ? ContentRouter.query() : {};
        if (q.team) {
            window.location.replace(teamHash(q.team));
            return;
        }
        state = { teams: null, environments: null, envsById: {}, byTeam: null, filtered: [], fitAttached: false,
            search: q.q || '', show: SHOW_FILTERS.some(f => f[0] === q.show) ? q.show : '', page: Math.max(1, Number(q.page) || 1) };
        $('#content-area').html(`
            <div class="content-view page page-frame teams-page" id="teams-view">
                ${PageHeader.markup({ title: 'Teams',
                    summaryHtml: "Who owns which environments, which cost centre pays, and each team's monthly limit.",
                    actionsHtml: `<button type="button" class="btn btn-primary" data-action="team-new">
                        <svg width="14" height="14" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M10 4v12M4 10h12"></path></svg>
                        New team</button>`,
                    helpPage: typeof HelpLink !== 'undefined' ? 'teams' : null, helpLabel: 'Teams' })}
                <div id="teams-gaps" hidden></div>
                <div class="um-counts" id="teams-counts" aria-live="polite"></div>
                <div class="um-filters">
                    <label class="um-search">
                        <svg width="15" height="15" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><circle cx="9" cy="9" r="5.5"></circle><path d="m13.5 13.5 3.5 3.5"></path></svg>
                        <span class="visually-hidden">Search teams</span>
                        <input type="search" class="form-control" id="teams-search" placeholder="Team, short name or cost centre" value="${Utils.escapeHtml(state.search)}">
                    </label>
                    <select class="form-select" id="teams-show" aria-label="Show">${SHOW_FILTERS.map(([v, l]) =>
                        `<option value="${v}"${state.show === v ? ' selected' : ''}>${l}</option>`).join('')}</select>
                </div>
                <div class="um-card list-panel page-fill" id="teams-panel">
                    <div class="um-table-wrap list-panel-body" tabindex="0" role="region" aria-label="Teams, scrolls">
                        <table class="um-table tm-table table-cards" id="teams-table">
                            <thead><tr>
                                <th scope="col">Team</th><th scope="col">Cost centre</th><th scope="col">Members</th>
                                <th scope="col">Environments</th><th scope="col">This month</th><th scope="col">Monthly limit</th>
                                <th scope="col"><span class="visually-hidden">Actions</span></th>
                            </tr></thead>
                            <tbody id="teams-table-body"><tr><td colspan="7" class="um-empty">${UiStates.loading('rows', { count: 4 })}</td></tr></tbody>
                        </table>
                    </div>
                    <div id="teams-pagination" class="list-panel-foot"></div>
                </div>
            </div>`);
        bindFilters();
        refresh();
        drawGaps();
    }

    /** Environments missing required allocation fields, as one line with links (E23-T04). */
    function drawGaps() {
        call('GET', Config.API.teams.allocationGaps).then(gaps => {
            if (!gaps || !gaps.length) { $('#teams-gaps').html('').prop('hidden', true); return; }
            const shown = gaps.slice(0, 5).map(g => Utils.html`<a href="#/environments/${g.environmentId}">${g.name}</a> (${g.missing.join(', ')})`).join(', ');
            $('#teams-gaps').prop('hidden', false).html(Utils.html`<p class="tm-gaps" role="status"><strong>${gaps.length} environment${gaps.length === 1 ? '' : 's'} missing allocation fields:</strong>
                ${Utils.raw(shown)}${gaps.length > 5 ? ` and ${gaps.length - 5} more` : ''}</p>`);
        }).catch(() => {});
    }

    function refresh() {
        const token = pageToken;
        Promise.all([
            call('GET', Config.API.teams.overview),
            call('GET', Config.API.environments.listWithGroups).catch(() => null), // U22-T09: groupStates, or every strip read 0/0
            call('GET', Config.API.costManagement.monthByTeam).catch(() => null)  // U24-T03: each team's month to date
        ]).then(([teams, envs, month]) => {
            if (!ContentRouter.isCurrent(token)) return;
            state.teams = teams || [];
            state.environments = Array.isArray(envs) ? envs : null;
            state.envsById = {};
            (state.environments || []).forEach(e => { state.envsById[e.environmentId] = e; });
            state.byTeam = monthByTeam(month);
            state.filtered = filterList(state.teams, state.search, state.show);
            drawCounts();
            drawTable();
            attachFit();
        }).catch(xhr => {
            if (!ContentRouter.isCurrent(token)) return;
            $('#teams-table-body').html(`<tr><td colspan="7" class="um-empty">${UiStates.failed({ what: 'Teams', xhr, retryAction: 'team-refresh' })}</td></tr>`);
        });
    }

    function drawCounts() {
        const c = counts(state.teams, state.environments);
        $('#teams-counts').html(`<span><b>${c.teams}</b> team${c.teams === 1 ? '' : 's'}</span>
            <span${c.noCostCentre ? ' class="um-count-warn"' : ''}><b>${c.noCostCentre}</b> without a cost centre</span>
            <span><b>${c.noMembers}</b> with no members</span>
            ${c.unassigned === null ? '' : `<span${c.unassigned ? ' class="um-count-warn"' : ''}><b>${c.unassigned}</b> environment${c.unassigned === 1 ? '' : 's'} without a team</span>`}`);
    }

    /** Rows a page follow the window: measured once the page shows and when a resize settles (U18-T03). */
    function attachFit() {
        const panel = document.getElementById('teams-panel');
        if (!panel || typeof ListPanel === 'undefined' || state.fitAttached) return;
        state.fitAttached = true;
        const apply = count => {
            if (!count || count === pageSize()) return;
            const first = (state.page - 1) * pageSize();
            fitSize = count;
            state.page = ListPanel.pageKeepingFirst(first, count);
            drawTable();
        };
        const handle = ListPanel.attach(panel, { rowSelector: '#teams-table-body tr', rowHeight: 56, min: 4, max: PAGE_SIZE, onFit: apply });
        apply(handle.fitCount());
    }

    function rowHtml(t) {
        const page = teamHash(t.teamId);
        const entra = entraText(t);
        const n = t.memberCount || 0;
        const envs = environmentsCell(t, state.envsById);
        const strip = envs.groups && typeof PowerStrip !== 'undefined' ? PowerStrip.render(envs.groups, { showCount: false }) : '';
        const limit = limitCell(t, quotasOn());
        const month = monthText(t, state.byTeam);
        const limitHtml = limit.none ? 'No limit'
            : Utils.html`<span class="tm-mono${Utils.raw(limit.over ? ' tm-over' : '')}" title="${limit.label}">${limit.text}</span><span class="visually-hidden"> (${limit.label})</span>`;
        return Utils.html`<tr data-team-id="${t.teamId}">
                <td class="um-person-cell" data-label=""><span class="um-who"><a class="um-name tm-name" href="${page}">${t.displayName}</a><span class="um-email">${t.name || ''}</span></span></td>
                <td data-label="Cost centre">${Utils.raw(t.costCenter ? Utils.html`<span class="tm-mono">${t.costCenter}</span>` : '<span class="tm-muted">—</span>')}</td>
                <td data-label="Members"><span class="um-who"><span>${plural(n, 'person', 'people')}</span>${Utils.raw(entra ? Utils.html`<span class="um-email">${entra}</span>` : '')}</span></td>
                <td data-label="Environments"><span class="tm-envs${Utils.raw(envs.total ? '' : ' tm-muted')}">${envs.text}${Utils.raw(strip)}</span></td>
                <td data-label="This month"><span class="tm-mono${Utils.raw(month === '—' ? ' tm-muted' : '')}">${month}</span></td>
                <td data-label="Monthly limit"><span class="${limit.none ? 'tm-muted' : 'tm-limit'}">${Utils.raw(limitHtml)}</span></td>
                <td class="um-actions" data-label=""><a class="um-act" href="${page}" aria-label="Open ${t.displayName}">Open</a><button type="button"
                    class="um-act" data-action="team-edit-card" data-id="${t.teamId}" aria-label="Edit ${t.displayName}">Edit</button><button type="button"
                    class="um-act um-danger" data-action="team-deactivate" data-id="${t.teamId}" aria-label="Deactivate ${t.displayName}">Deactivate</button></td>
            </tr>`;
    }

    function drawTable() {
        if (!state || !state.teams) return;
        const list = state.filtered;
        const size = pageSize();
        const pages = Math.max(1, Math.ceil(list.length / size));
        state.page = Math.min(Math.max(1, state.page), pages);
        let body;
        if (list.length) {
            body = Pagination.slice(list, state.page, size).map(rowHtml).join('');
        } else if (state.teams.length) {
            body = `<tr><td colspan="7" class="um-empty">${UiStates.filtered({ title: 'No teams match these filters',
                text: 'Check the spelling, or show all teams.', clearLabel: 'Show all teams', clearAction: 'team-clear-filters' })}</td></tr>`;
        } else {
            body = `<tr><td colspan="7" class="um-empty">${UiStates.empty({ title: 'No teams yet',
                text: 'Add one with New team, then give it environments and people.', action: { label: 'New team', action: 'team-new' } })}</td></tr>`;
        }
        $('#teams-table-body').html(body);
        $('#teams-pagination').html(list.length > size ? Pagination.buildPager({ page: state.page, pageSize: size,
            totalItems: list.length, action: 'team-page', key: 'teams' }) : '');
        syncAddress();
    }

    /** The search, filter and page in the address, so Back returns to them. */
    function syncAddress() {
        const params = new URLSearchParams();
        if (state.search) params.set('q', state.search);
        if (state.show) params.set('show', state.show);
        if (state.page > 1) params.set('page', String(state.page));
        const hash = `#/teams${params.toString() ? '?' + params.toString() : ''}`;
        if (window.location.hash !== hash && window.history && window.history.replaceState) window.history.replaceState(null, '', hash);
    }

    function applyFilters() {
        if (!state || !state.teams) return;
        state.filtered = filterList(state.teams, state.search, state.show);
        state.page = 1;
        drawTable();
    }

    function bindFilters() {
        $('#teams-search').off('input').on('input', Utils.debounce(function() {
            state.search = $(this).val();
            applyFilters();
        }, 250));
        $('#teams-show').off('change').on('change', function() {
            state.show = $(this).val();
            applyFilters();
        });
    }

    function clearFilters() {
        if (!state) return;
        state.search = '';
        state.show = '';
        $('#teams-search').val('');
        $('#teams-show').val('');
        applyFilters();
    }

    /**
     * The New team / Edit name and cost centre dialog. onSaved(team) runs after a save; without it a
     * new team opens its page and an edited one refreshes the list.
     */
    function openForm(team, onSaved) {
        Modals.show({
            id: 'teamModal',
            title: team ? `Edit team: ${team.displayName}` : 'New team',
            body: Utils.html`
                <div class="mb-3"><label class="form-label" for="team-display">Display name</label>
                    <input type="text" class="form-control" id="team-display" maxlength="255" value="${team ? team.displayName : ''}" required></div>
                <div class="mb-3"><label class="form-label" for="team-name">Short name</label>
                    <input type="text" class="form-control" id="team-name" maxlength="100" value="${team ? team.name : ''}" placeholder="made from the display name">
                    <div class="form-text">Letters, digits and dashes; used in tags and reports.</div></div>
                <div class="mb-0"><label class="form-label" for="team-cost-center">Cost centre</label>${Utils.raw(InfoTip.markup('team-cc-tip', 'Cost centre', InfoTip.TERMS.costCentre))}
                    <input type="text" class="form-control" id="team-cost-center" maxlength="100" value="${team ? (team.costCenter || '') : ''}"
                        placeholder="e.g. CC-4410"></div>`,
            buttons: [{ text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: team ? 'Save' : 'Create team', class: 'btn-primary', id: 'team-save-btn' }],
            onShow: () => $('#team-display').trigger('focus')
        });
        $('#team-save-btn').on('click', function() {
            const body = { displayName: $('#team-display').val().trim(), name: $('#team-name').val().trim() || null,
                costCenter: $('#team-cost-center').val().trim() || null };
            if (!body.displayName) { Notifications.showError('A display name is required.'); return; }
            const $btn = $(this).prop('disabled', true);
            (team ? call('PUT', Config.API.teams.update(team.teamId), body) : call('POST', Config.API.teams.create, body))
                .then(saved => {
                    Modals.hide('teamModal');
                    if (onSaved) onSaved(saved);
                    else if (!team) window.location.hash = teamHash(saved.teamId);
                    else refresh();
                })
                .catch(e => { Notifications.showError(serverMessage(e, 'Could not save the team.')); $btn.prop('disabled', false); });
        });
    }

    function deactivate(team, onDone) {
        if (!team) return;
        Modals.confirm('Deactivate team', `Deactivate ${team.displayName}? It must own no active environments.`, () => {
            call('DELETE', Config.API.teams.delete(team.teamId))
                .then(() => { Notifications.show('Team deactivated.', 'success'); if (onDone) onDone(); else refresh(); })
                .catch(e => Notifications.showError(serverMessage(e, 'Could not deactivate the team.')));
        }, { confirmText: 'Deactivate', confirmClass: 'btn-danger' });
    }

    /** Marks a team picker required when the server requires a team (E23-T04). */
    function markRequired(selectors) {
        call('GET', Config.API.teams.allocationRules).then(rules => {
            if (!rules || !rules.requireTeam) return;
            selectors.forEach(sel => {
                const $select = $(sel);
                $select.attr('required', 'required').attr('aria-required', 'true');
                const $label = $(`label[for="${$select.attr('id')}"]`);
                if (!$label.find('.text-danger').length) $label.append(' <span class="text-danger" aria-hidden="true">*</span>');
            });
        }).catch(() => {});
    }

    /** Options for an environment's team picker (E23-T03); resolves to [] when teams cannot load. */
    function options(selectedTeamId) {
        return call('GET', Config.API.teams.list(false)).catch(() => []).then(teams => {
            const list = teams || [];
            return `<option value="">Unassigned</option>${list.map(t =>
                Utils.html`<option value="${t.teamId}" ${t.teamId === selectedTeamId ? 'selected' : ''}>${t.displayName}</option>`).join('')}`;
        });
    }

    const byId = id => ((state && state.teams) || []).find(t => t.teamId === id);

    return {
        load, refresh, openForm, deactivate, options, markRequired,
        editTeam: id => openForm(byId(id)),
        deactivateTeam: id => deactivate(byId(id)),
        clearFilters,
        page: n => { state.page = n; drawTable(); },
        model: { membersText, entraText, vmHours, limitCell, money, monthByTeam, monthText, environmentsCell, counts, filterList }
    };
})();

if (typeof window !== 'undefined') window.Teams = Teams;

if (typeof Actions !== 'undefined') Actions.registerAll({
    'team-new': () => Teams.openForm(null),
    'team-edit-card': el => Teams.editTeam(el.dataset.id),
    'team-deactivate': el => Teams.deactivateTeam(el.dataset.id),
    'team-clear-filters': () => Teams.clearFilters(),
    'team-page': el => Teams.page(Number(el.dataset.page) || 1),
    'team-refresh': () => Teams.refresh()
});
