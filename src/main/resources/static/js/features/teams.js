/**
 * Teams page (E23-T03, U13-T07 Teams.dc.html): one card per team with its cost centre, members
 * ("6 people · synced from Entra group "Payments Engineering""), each environment with its power
 * strip, and this month's VM hours against the team's limit (red over 90%; no bar while quotas are
 * off), 6 cards a page. Admins create and edit teams; Members and Environments open the team's
 * detail below the cards (members by hand, the Entra group, quotas) until U13-T08's page.
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

    // ---- page ----

    function load() {
        pageToken = ContentRouter.token();
        if (!Auth.isAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3">Access denied. Admin only.</div>');
            return;
        }
        // ?team=<id> opens that team's details (and the page with its card).
        const q = ContentRouter.query ? ContentRouter.query() : {};
        state = { teams: null, environments: {}, selectedId: q.team || null, members: [], users: null, error: false, page: 1 };
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
                <div id="team-detail"></div>
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
            state.error = false;
            drawList();
            if (state.selectedId) select(state.selectedId);
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
        const envs = (t.environments || []).length ? t.environments.map(e => Utils.html`<div class="tm-env">
                <a href="#/environments/${e.environmentId}">${e.displayName || e.name}</a>${Utils.raw(stripHtml(e.environmentId))}</div>`).join('')
            : Utils.html`<div class="tm-env-none">No environments yet. <button type="button" class="tm-link" data-action="team-envs" data-id="${t.teamId}">Add environments</button></div>`;
        let quota = '';
        if (quotasOn) {
            quota = hours ? Utils.html`<div class="tm-quota">
                    <div class="tm-quota-line"><span>VM hours this month</span><span class="tm-mono">${hours.text}</span></div>
                    <div class="tm-bar${Utils.raw(hours.over ? ' tm-bar-over' : '')}" role="img" aria-label="${hours.label}"><span style="width:${Math.min(100, hours.percent)}%"></span></div>
                </div>` : '<div class="tm-quota tm-quota-none">No monthly limit</div>';
        }
        return Utils.html`<article class="tm-card${Utils.raw(t.teamId === state.selectedId ? ' tm-card-open' : '')}" data-team-id="${t.teamId}">
                <div class="tm-head"><h2>${t.displayName}</h2>${Utils.raw(t.costCenter ? Utils.html`<span class="tm-mono tm-cc">${t.costCenter}</span>` : '')}</div>
                <div class="tm-members">${membersText(t)}</div>
                <div class="tm-envs">${Utils.raw(envs)}</div>
                ${Utils.raw(quota)}
                <div class="tm-links">
                    <button type="button" class="tm-link" data-action="team-edit-card" data-id="${t.teamId}" aria-label="Edit ${t.displayName}">Edit</button>
                    <button type="button" class="tm-link" data-action="team-select" data-id="${t.teamId}" aria-label="Members of ${t.displayName}">Members</button>
                    <button type="button" class="tm-link" data-action="team-envs" data-id="${t.teamId}" aria-label="Environments of ${t.displayName}">Environments</button>
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

    function select(teamId) {
        state.selectedId = teamId;
        const team = (state.teams || []).find(t => t.teamId === teamId);
        if (!team) {
            $('#team-detail').html('');
            return;
        }
        state.page = Math.floor(state.teams.indexOf(team) / PAGE_SIZE) + 1; // the opened team's card is on the page shown
        drawList();
        const token = pageToken;
        Promise.allSettled([call('GET', Config.API.teams.members(teamId)),
            state.users ? Promise.resolve(state.users) : call('GET', Config.API.users.list)]).then(([members, users]) => {
            if (!ContentRouter.isCurrent(token) || state.selectedId !== teamId) return;
            state.members = members.status === 'fulfilled' ? (members.value || []) : [];
            if (users.status === 'fulfilled') state.users = users.value || [];
            drawDetail(team, members.status === 'rejected');
            drawQuotas(teamId);
            const el = document.getElementById('team-detail');
            if (el && el.scrollIntoView) el.scrollIntoView({ block: 'nearest' });
        });
    }

    function drawDetail(team, membersFailed) {
        const memberIds = new Set(state.members.map(m => m.userId));
        const candidates = (state.users || []).filter(u => !memberIds.has(u.userId) && u.isActive !== false);
        const memberRows = state.members.length ? state.members.map(m => Utils.html`
            <li class="d-flex justify-content-between align-items-center gap-2">
                <span>${m.displayName || m.email || m.userId}<span class="badge text-bg-light border ms-1">${m.source === 'ENTRA' ? 'Entra' : 'Manual'}</span></span>
                <button type="button" class="btn btn-sm btn-ghost" data-action="team-remove-member" data-user="${m.userId}"
                    aria-label="Remove ${m.displayName || m.email || m.userId}"><i class="fas fa-times" aria-hidden="true"></i></button>
            </li>`).join('') : '<li class="text-muted small">No members yet.</li>';
        $('#team-detail').html(Utils.html`
            <section class="card" aria-labelledby="team-detail-title"><div class="card-body">
                <div class="d-flex justify-content-between align-items-start gap-2 mb-2">
                    <div><h2 class="h5 mb-0" id="team-detail-title">${team.displayName}</h2>
                        <div class="small text-muted">${team.name}${team.costCenter ? ' · cost center ' + team.costCenter : ''}</div></div>
                    <div class="d-flex gap-1">
                        <button type="button" class="btn btn-sm btn-ghost" data-action="team-edit">Edit</button>
                        ${Utils.raw(team.active ? '<button type="button" class="btn btn-sm btn-ghost text-danger" data-action="team-deactivate">Deactivate</button>' : '')}
                    </div>
                </div>
                <h3 class="h6 mt-3">Entra group</h3>
                <div class="small mb-2">${team.entraGroupId
                    ? Utils.raw(Utils.html`Linked to ${team.entraGroupName || team.entraGroupId}
                        <button type="button" class="btn btn-sm btn-ghost" data-action="team-sync">Sync now</button>
                        <button type="button" class="btn btn-sm btn-ghost" data-action="team-unlink">Unlink</button>`)
                    : 'Not linked: members are managed here by hand.'}</div>
                <div class="d-flex gap-2 mb-2">
                    <label class="visually-hidden" for="team-group-search">Search Entra groups</label>
                    <input type="search" class="form-control form-control-sm" id="team-group-search" placeholder="Search Entra groups to link">
                    <button type="button" class="btn btn-sm btn-ghost" data-action="team-group-search">Search</button>
                </div>
                <ul class="list-unstyled small" id="team-group-results" aria-live="polite"></ul>
                <h3 class="h6 mt-3">Monthly quotas</h3>
                <div id="team-quotas" class="mb-2"><div class="text-muted small">Loading quotas…</div></div>
                <h3 class="h6 mt-3">Members</h3>
                ${Utils.raw(membersFailed ? '<div class="text-danger small">Could not load the members.</div>' : '')}
                <ul class="list-unstyled team-members">${Utils.raw(memberRows)}</ul>
                <div class="d-flex gap-2">
                    <label class="visually-hidden" for="team-add-user">User to add</label>
                    <select class="form-select form-select-sm" id="team-add-user">
                        <option value="">Add a member…</option>
                        ${Utils.raw(candidates.map(u => Utils.html`<option value="${u.userId}">${u.displayName || u.email}</option>`).join(''))}
                    </select>
                    <button type="button" class="btn btn-sm btn-primary" data-action="team-add-member">Add</button>
                </div>
            </div></section>`);
    }

    // ============= Quotas (E25-T08) =============

    const QUOTA_METRICS = [['VM_HOURS', 'Running VM-hours'], ['COST', 'Spend ($)']];

    function drawQuotas(teamId) {
        call('GET', Config.API.teams.quotas(teamId)).then(quotas => {
            if (state.selectedId !== teamId) return;
            const byMetric = {};
            (quotas || []).forEach(q => { byMetric[q.metric] = q; });
            const bars = (quotas || []).length && typeof CostPreview !== 'undefined'
                ? quotas.map(q => CostPreview.quotaBar(q)).join('') : '<div class="small text-muted mb-2">No quotas: the team can run without a monthly limit.</div>';
            const rows = QUOTA_METRICS.map(([metric, label]) => {
                const q = byMetric[metric];
                return Utils.html`<div class="row g-2 align-items-end mb-2 quota-row" data-metric="${metric}">
                    <div class="col-12 col-sm-3"><div class="form-check mt-sm-4">
                        <input class="form-check-input quota-on" type="checkbox" id="quota-on-${metric}" ${q ? 'checked' : ''}>
                        <label class="form-check-label" for="quota-on-${metric}">${label}</label></div></div>
                    <div class="col-4 col-sm-3"><label class="form-label small" for="quota-limit-${metric}">Limit</label>
                        <input class="form-control form-control-sm quota-limit" type="number" min="1" step="${metric === 'COST' ? '0.01' : '1'}"
                            id="quota-limit-${metric}" value="${q ? Number(q.limit) : ''}"></div>
                    <div class="col-5 col-sm-4"><label class="form-label small" for="quota-enf-${metric}">When over</label>
                        <select class="form-select form-select-sm quota-enforcement" id="quota-enf-${metric}">
                            <option value="WARN" ${q && q.enforcement === 'WARN' ? 'selected' : ''}>Warn</option>
                            <option value="REQUIRE_APPROVAL" ${q && q.enforcement === 'REQUIRE_APPROVAL' ? 'selected' : ''}>Needs approval</option>
                            <option value="BLOCK" ${q && q.enforcement === 'BLOCK' ? 'selected' : ''}>Block</option></select></div>
                    <div class="col-3 col-sm-2"><label class="form-label small" for="quota-warn-${metric}">Warn at %</label>
                        <input class="form-control form-control-sm quota-warn" type="number" min="1" max="100" id="quota-warn-${metric}"
                            value="${q ? q.warnPercent : 80}"></div></div>`;
            }).join('');
            $('#team-quotas').html(`${bars}${rows}<button type="button" class="btn btn-sm btn-primary" data-action="team-save-quotas">Save quotas</button>`);
        }).catch(() => $('#team-quotas').html('<div class="text-danger small">Could not load the quotas.</div>'));
    }

    function saveQuotas() {
        const team = selectedTeam();
        if (!team) return;
        const body = $('#team-quotas .quota-row').map(function() {
            const $r = $(this);
            if (!$r.find('.quota-on').is(':checked')) return null;
            return { metric: $r.data('metric'), limit: Number($r.find('.quota-limit').val()),
                enforcement: $r.find('.quota-enforcement').val(), warnPercent: Number($r.find('.quota-warn').val()) || 80 };
        }).get();
        call('PUT', Config.API.teams.quotas(team.teamId), body)
            .then(() => { Notifications.show('Quotas saved.', 'success'); drawQuotas(team.teamId); })
            .catch(e => Notifications.showError(serverMessage(e, 'Could not save the quotas.')));
    }

    function selectedTeam() {
        return (state.teams || []).find(t => t.teamId === state.selectedId);
    }

    function openForm(team) {
        Modals.show({
            id: 'teamModal',
            title: team ? `Edit team: ${team.displayName}` : 'New team',
            body: Utils.html`
                <div class="mb-3"><label class="form-label" for="team-display">Display name</label>
                    <input type="text" class="form-control" id="team-display" maxlength="255" value="${team ? team.displayName : ''}" required></div>
                <div class="mb-3"><label class="form-label" for="team-name">Short name</label>
                    <input type="text" class="form-control" id="team-name" maxlength="100" value="${team ? team.name : ''}" placeholder="made from the display name">
                    <div class="form-text">Letters, digits and dashes; used in tags and reports.</div></div>
                <div class="mb-0"><label class="form-label" for="team-cost-center">Cost center</label>
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
                    bootstrap.Modal.getInstance(document.getElementById('teamModal')).hide();
                    state.selectedId = saved.teamId;
                    refresh();
                })
                .catch(e => { Notifications.showError(serverMessage(e, 'Could not save the team.')); $btn.prop('disabled', false); });
        });
    }

    function deactivate() {
        const team = selectedTeam();
        if (!team) return;
        Modals.confirm('Deactivate team', `Deactivate ${team.displayName}? It must own no active environments.`, () => {
            call('DELETE', Config.API.teams.delete(team.teamId))
                .then(() => { Notifications.show('Team deactivated.', 'success'); refresh(); })
                .catch(e => Notifications.showError(serverMessage(e, 'Could not deactivate the team.')));
        }, { confirmText: 'Deactivate', confirmClass: 'btn-danger' });
    }

    function searchGroups() {
        const q = ($('#team-group-search').val() || '').trim();
        if (q.length < 2) { Notifications.showError('Type at least 2 characters.'); return; }
        call('GET', Config.API.teams.entraGroups(q)).then(groups => {
            $('#team-group-results').html((groups || []).length ? groups.map(g => Utils.html`
                <li class="d-flex justify-content-between align-items-center gap-2"><span>${g.displayName}${g.mail ? ' · ' + g.mail : ''}</span>
                <button type="button" class="btn btn-sm btn-ghost" data-action="team-link" data-group="${g.id}" data-group-name="${g.displayName}">Link</button></li>`).join('')
                : '<li class="text-muted">No groups found.</li>');
        }).catch(e => $('#team-group-results').html(Utils.html`<li class="text-muted">${serverMessage(e, 'Group search is unavailable.')}</li>`));
    }

    function link(groupId, groupName) {
        const team = selectedTeam();
        if (!team) return;
        call('PUT', Config.API.teams.entraGroup(team.teamId), { groupId, groupName: groupName || null })
            .then(() => { Notifications.show(groupId ? 'Entra group linked.' : 'Entra group unlinked.', 'success'); refresh(); })
            .catch(e => Notifications.showError(serverMessage(e, 'Could not change the Entra group.')));
    }

    function sync() {
        const team = selectedTeam();
        if (!team) return;
        call('POST', Config.API.teams.sync(team.teamId))
            .then(r => { Notifications.show(`Synced: ${r.added} added, ${r.removed} removed, ${r.skippedUnknown} without an account.`, 'success'); refresh(); })
            .catch(e => Notifications.showError(serverMessage(e, 'Could not sync the members.')));
    }

    function addMember() {
        const team = selectedTeam();
        const userId = $('#team-add-user').val();
        if (!team || !userId) return;
        call('POST', Config.API.teams.members(team.teamId), { userId })
            .then(() => refresh())
            .catch(e => Notifications.showError(serverMessage(e, 'Could not add the member.')));
    }

    function removeMember(userId) {
        const team = selectedTeam();
        if (!team) return;
        call('DELETE', Config.API.teams.member(team.teamId, userId))
            .then(() => refresh())
            .catch(e => Notifications.showError(serverMessage(e, 'Could not remove the member.')));
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
        load, refresh, select, openForm, deactivate, searchGroups, link, sync, addMember, removeMember, options, markRequired, saveQuotas,
        edit: () => openForm(selectedTeam()),
        editTeam: id => openForm((state.teams || []).find(t => t.teamId === id)),
        page: n => { state.page = n; drawList(); },
        model: { membersText, vmHours }
    };
})();

if (typeof window !== 'undefined') window.Teams = Teams;

if (typeof Actions !== 'undefined') Actions.registerAll({
    'team-new': () => Teams.openForm(null),
    'team-edit-card': el => Teams.editTeam(el.dataset.id),
    // Environments open the team below the cards until U13-T08's team page.
    'team-envs': el => Teams.select(el.dataset.id),
    'team-page': el => Teams.page(Number(el.dataset.page) || 1),
    'team-refresh': () => Teams.refresh(),
    'team-select': el => Teams.select(el.dataset.id),
    'team-edit': () => Teams.edit(),
    'team-deactivate': () => Teams.deactivate(),
    'team-group-search': () => Teams.searchGroups(),
    'team-link': el => Teams.link(el.dataset.group, el.dataset.groupName),
    'team-unlink': () => Teams.link(null),
    'team-sync': () => Teams.sync(),
    'team-add-member': () => Teams.addMember(),
    'team-remove-member': el => Teams.removeMember(el.dataset.user),
    'team-save-quotas': () => Teams.saveQuotas()
});
