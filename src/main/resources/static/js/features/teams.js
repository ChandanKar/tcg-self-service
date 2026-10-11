/**
 * Teams page (E23-T03): teams that own environments and are charged for them. Admins create,
 * rename and deactivate teams, link an Entra group (members sync from it when directory lookup
 * and the team sync are on), and add or remove members by hand.
 */
const Teams = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
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

    function load() {
        pageToken = ContentRouter.token();
        if (!Auth.isAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3">Access denied. Admin only.</div>');
            return;
        }
        state = { teams: null, selectedId: null, members: [], users: null, error: false };
        $('#content-area').html(`
            <div class="content-view teams-page" id="teams-view">
                <div class="content-header d-flex justify-content-between align-items-center flex-wrap gap-2">
                    <div>
                        <h1><i class="fas fa-users me-2" aria-hidden="true"></i>Teams</h1>
                        <p class="text-muted mb-0">Teams own environments; costs and budgets are reported per team</p>
                    </div>
                    <button type="button" class="btn btn-primary" data-action="team-new"><i class="fas fa-plus" aria-hidden="true"></i> New team</button>
                </div>
                <div id="teams-gaps"></div>
                <div class="teams-layout">
                    <div id="teams-list"><div class="text-muted small p-2"><i class="fas fa-spinner fa-spin me-1" aria-hidden="true"></i>Loading teams…</div></div>
                    <div id="team-detail"></div>
                </div>
            </div>`);
        refresh();
        drawGaps();
    }

    /** Environments missing required allocation fields, with an Edit link (E23-T04). */
    function drawGaps() {
        call('GET', Config.API.teams.allocationGaps).then(gaps => {
            if (!gaps || !gaps.length) { $('#teams-gaps').html(''); return; }
            $('#teams-gaps').html(Utils.html`<div class="alert alert-warning" role="status">
                <strong>${gaps.length} environment${gaps.length === 1 ? '' : 's'} missing allocation fields:</strong>
                <ul class="mb-0">${Utils.raw(gaps.slice(0, 10).map(g => Utils.html`<li><a href="#/environments/${g.environmentId}">${g.name}</a>
                    — ${g.missing.join(', ')}</li>`).join(''))}</ul></div>`);
        }).catch(() => {});
    }

    function refresh() {
        const token = pageToken;
        call('GET', Config.API.teams.list(true)).then(teams => {
            if (!ContentRouter.isCurrent(token)) return;
            state.teams = teams || [];
            state.error = false;
            drawList();
            if (state.selectedId) select(state.selectedId);
        }).catch(() => {
            if (!ContentRouter.isCurrent(token)) return;
            $('#teams-list').html(`<div class="cost-panel-error" role="alert"><span>Couldn't load teams.</span>
                <button type="button" class="btn btn-sm btn-ghost" data-action="team-refresh">Retry</button></div>`);
        });
    }

    function drawList() {
        const rows = state.teams.length ? state.teams.map(t => Utils.html`
            <tr class="${t.teamId === state.selectedId ? 'table-active' : ''} ${t.active ? '' : 'text-muted'}">
                <td data-label="Team"><button type="button" class="btn btn-link p-0 fw-semibold" data-action="team-select" data-id="${t.teamId}">${t.displayName}</button>
                    <div class="small text-muted">${t.name}${t.active ? '' : ' · inactive'}</div></td>
                <td data-label="Cost center">${t.costCenter || '—'}</td>
                <td data-label="Environments">${t.environmentCount}</td>
                <td data-label="Members">${t.memberCount}</td>
                <td data-label="Entra group">${t.entraGroupId ? Utils.raw('<i class="fas fa-link" aria-hidden="true"></i> linked') : '—'}</td>
            </tr>`).join('')
            : '<tr><td colspan="5" class="text-muted text-center py-3">No teams yet</td></tr>';
        $('#teams-list').html(`<section class="card"><div class="card-body">
            <div class="table-responsive"><table class="table table-sm table-cards mb-0" id="teams-table">
                <thead><tr><th>Team</th><th>Cost center</th><th>Environments</th><th>Members</th><th>Entra group</th></tr></thead>
                <tbody>${rows}</tbody></table></div></div></section>`);
    }

    function select(teamId) {
        state.selectedId = teamId;
        const team = (state.teams || []).find(t => t.teamId === teamId);
        if (!team) {
            $('#team-detail').html('');
            return;
        }
        drawList();
        const token = pageToken;
        Promise.allSettled([call('GET', Config.API.teams.members(teamId)),
            state.users ? Promise.resolve(state.users) : call('GET', Config.API.users.list)]).then(([members, users]) => {
            if (!ContentRouter.isCurrent(token) || state.selectedId !== teamId) return;
            state.members = members.status === 'fulfilled' ? (members.value || []) : [];
            if (users.status === 'fulfilled') state.users = users.value || [];
            drawDetail(team, members.status === 'rejected');
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
                    ? Utils.raw(Utils.html`Linked to <code>${team.entraGroupId}</code>
                        <button type="button" class="btn btn-sm btn-ghost" data-action="team-sync">Sync now</button>
                        <button type="button" class="btn btn-sm btn-ghost" data-action="team-unlink">Unlink</button>`)
                    : 'Not linked: members are managed here by hand.'}</div>
                <div class="d-flex gap-2 mb-2">
                    <label class="visually-hidden" for="team-group-search">Search Entra groups</label>
                    <input type="search" class="form-control form-control-sm" id="team-group-search" placeholder="Search Entra groups to link">
                    <button type="button" class="btn btn-sm btn-ghost" data-action="team-group-search">Search</button>
                </div>
                <ul class="list-unstyled small" id="team-group-results" aria-live="polite"></ul>
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
                <button type="button" class="btn btn-sm btn-ghost" data-action="team-link" data-group="${g.id}">Link</button></li>`).join('')
                : '<li class="text-muted">No groups found.</li>');
        }).catch(e => $('#team-group-results').html(Utils.html`<li class="text-muted">${serverMessage(e, 'Group search is unavailable.')}</li>`));
    }

    function link(groupId) {
        const team = selectedTeam();
        if (!team) return;
        call('PUT', Config.API.teams.entraGroup(team.teamId), { groupId })
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
        load, refresh, select, openForm, deactivate, searchGroups, link, sync, addMember, removeMember, options, markRequired,
        edit: () => openForm(selectedTeam())
    };
})();

window.Teams = Teams;

Actions.registerAll({
    'team-new': () => Teams.openForm(null),
    'team-refresh': () => Teams.refresh(),
    'team-select': el => Teams.select(el.dataset.id),
    'team-edit': () => Teams.edit(),
    'team-deactivate': () => Teams.deactivate(),
    'team-group-search': () => Teams.searchGroups(),
    'team-link': el => Teams.link(el.dataset.group),
    'team-unlink': () => Teams.link(null),
    'team-sync': () => Teams.sync(),
    'team-add-member': () => Teams.addMember(),
    'team-remove-member': el => Teams.removeMember(el.dataset.user)
});
