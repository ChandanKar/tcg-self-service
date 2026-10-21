/**
 * Teams page (E23-T03, U13-T07 Teams.dc.html): one card per team with its cost centre, members
 * ("6 people · synced from Entra group "Payments Engineering""), each environment with its power
 * strip, and this month's VM hours against the team's limit (red over 90%; no bar while quotas are
 * off), 6 cards a page. Admins create and edit teams; Members and Environments open the team's page
 * (U13-T08, team-detail.js). Also the team picker of the environment dialogs.
 */
const Teams = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    const PAGE_SIZE = 6;
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

    // ---- pure model (node tests: src/test/js/teams-cards.test.mjs) ----

    /** "6 people · synced from Entra group "Payments Engineering"" */
    function membersText(team) {
        const n = team.memberCount || 0;
        const people = `${n} ${n === 1 ? 'person' : 'people'}`;
        if (!team.entraGroupId) return people;
        return `${people} · synced from Entra group "${team.entraGroupName || 'linked group'}"`;
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

    function teamHash(teamId) {
        return '#/teams/' + encodeURIComponent(teamId);
    }

    // ---- page ----

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
        state = { teams: null, environments: {}, page: 1 };
        $('#content-area').html(`
            <div class="content-view teams-page" id="teams-view">
                ${PageHeader.markup({ title: 'Teams',
                    summaryHtml: "Who owns which environments, which cost centre pays, and each team's monthly limit.",
                    actionsHtml: `<button type="button" class="btn btn-primary" data-action="team-new">
                        <svg width="14" height="14" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M10 4v12M4 10h12"></path></svg>
                        New team</button>`,
                    helpPage: typeof HelpLink !== 'undefined' ? 'teams' : null, helpLabel: 'Teams' })}
                <div id="teams-gaps"></div>
                <div id="teams-list"><div class="ar-empty" role="status">Loading teams…</div></div>
            </div>`);
        refresh();
        drawGaps();
    }

    /** Environments missing required allocation fields, as one line with links (E23-T04). */
    function drawGaps() {
        call('GET', Config.API.teams.allocationGaps).then(gaps => {
            if (!gaps || !gaps.length) { $('#teams-gaps').html(''); return; }
            const shown = gaps.slice(0, 5).map(g => Utils.html`<a href="#/environments/${g.environmentId}">${g.name}</a> (${g.missing.join(', ')})`).join(', ');
            $('#teams-gaps').html(Utils.html`<p class="tm-gaps" role="status"><strong>${gaps.length} environment${gaps.length === 1 ? '' : 's'} missing allocation fields:</strong>
                ${Utils.raw(shown)}${gaps.length > 5 ? ` and ${gaps.length - 5} more` : ''}</p>`);
        }).catch(() => {});
    }

    function refresh() {
        const token = pageToken;
        Promise.all([call('GET', Config.API.teams.overview), call('GET', Config.API.environments.list).catch(() => [])]).then(([teams, envs]) => {
            if (!ContentRouter.isCurrent(token)) return;
            state.teams = teams || [];
            state.environments = {};
            (Array.isArray(envs) ? envs : []).forEach(e => { state.environments[e.environmentId] = e; });
            drawList();
        }).catch(() => {
            if (!ContentRouter.isCurrent(token)) return;
            $('#teams-list').html(`<div class="cost-panel-error" role="alert"><span>Couldn't load teams.</span>
                <button type="button" class="btn btn-sm btn-ghost" data-action="team-refresh">Retry</button></div>`);
        });
    }

    function stripHtml(environmentId) {
        const env = state.environments[environmentId];
        if (!env || typeof PowerStrip === 'undefined') return '';
        return PowerStrip.render(PowerStrip.fromGroupStates(env.groupStates || []), {});
    }

    function cardHtml(t) {
        const quotasOn = typeof Capabilities !== 'undefined' && Capabilities.has('quotas');
        const hours = vmHours(t);
        const page = teamHash(t.teamId);
        const envs = (t.environments || []).length ? t.environments.map(e => Utils.html`<div class="tm-env">
                <a href="#/environments/${e.environmentId}">${e.displayName || e.name}</a>${Utils.raw(stripHtml(e.environmentId))}</div>`).join('')
            : Utils.html`<div class="tm-env-none">No environments yet. <a class="tm-link" href="${page}">Add environments</a></div>`;
        let quota = '';
        if (quotasOn) {
            quota = hours ? Utils.html`<div class="tm-quota">
                    <div class="tm-quota-line"><span>VM hours this month</span><span class="tm-mono">${hours.text}</span></div>
                    <div class="tm-bar${Utils.raw(hours.over ? ' tm-bar-over' : '')}" role="img" aria-label="${hours.label}"><span style="width:${Math.min(100, hours.percent)}%"></span></div>
                </div>` : '<div class="tm-quota tm-quota-none">No monthly limit</div>';
        }
        return Utils.html`<article class="tm-card" data-team-id="${t.teamId}">
                <div class="tm-head"><h2><a href="${page}">${t.displayName}</a></h2>${Utils.raw(t.costCenter ? Utils.html`<span class="tm-mono tm-cc">${t.costCenter}</span>` : '')}</div>
                <div class="tm-members">${membersText(t)}</div>
                <div class="tm-envs">${Utils.raw(envs)}</div>
                ${Utils.raw(quota)}
                <div class="tm-links">
                    <button type="button" class="tm-link" data-action="team-edit-card" data-id="${t.teamId}" aria-label="Edit ${t.displayName}">Edit</button>
                    <a class="tm-link" href="${page}" aria-label="Members of ${t.displayName}">Members</a>
                    <a class="tm-link" href="${page}" aria-label="Environments of ${t.displayName}">Environments</a>
                </div>
            </article>`;
    }

    function drawList() {
        const teams = state.teams;
        if (!teams.length) {
            $('#teams-list').html('<div class="ar-empty">No teams yet. Add one with New team, then give it environments and people.</div>');
            return;
        }
        const pages = Math.max(1, Math.ceil(teams.length / PAGE_SIZE));
        state.page = Math.min(Math.max(1, state.page), pages);
        const items = Pagination.slice(teams, state.page, PAGE_SIZE);
        $('#teams-list').html(`<div class="tm-grid" id="teams-cards">${items.map(cardHtml).join('')}</div>
            ${teams.length > PAGE_SIZE ? Pagination.buildPager({ page: state.page, pageSize: PAGE_SIZE, totalItems: teams.length,
                action: 'team-page', key: 'teams' }) : ''}`);
    }

    /**
     * The New team / Edit name and cost centre dialog. onSaved(team) runs after a save; without it a
     * new team opens its page and an edited one refreshes the cards.
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
                <div class="mb-0"><label class="form-label" for="team-cost-center">Cost centre</label>
                    <input type="text" class="form-control" id="team-cost-center" maxlength="100" value="${team ? (team.costCenter || '') : ''}"></div>`,
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

    return {
        load, refresh, openForm, deactivate, options, markRequired,
        editTeam: id => openForm((state.teams || []).find(t => t.teamId === id)),
        page: n => { state.page = n; drawList(); },
        model: { membersText, vmHours }
    };
})();

if (typeof window !== 'undefined') window.Teams = Teams;

if (typeof Actions !== 'undefined') Actions.registerAll({
    'team-new': () => Teams.openForm(null),
    'team-edit-card': el => Teams.editTeam(el.dataset.id),
    'team-page': el => Teams.page(Number(el.dataset.page) || 1),
    'team-refresh': () => Teams.refresh()
});
