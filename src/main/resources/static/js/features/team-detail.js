/**
 * VM Self-Service Platform - Team detail (U13-T08, TeamDetail.dc.html), #/teams/<teamId>
 * "Teams / Payments", "payments · cost centre CC-4410 · 3 environments · 14 members", Edit name and
 * cost centre. Members: the Entra group line (Sync now, Change group), 8 people a page with their
 * source ("Entra" / "Added by hand"), Remove only for people added by hand, Add people. Aside:
 * Monthly limits (VM hours and cost: used of limit with the warning point, Limit, Warn at %, At the
 * limit) while quotas are on, and the team's environments with a dialog to change them.
 * U25-T05: the environments are a table like the Teams list (Environment, VMs running, This month),
 * with a search, as many rows a page as fit the panel; the page itself does not scroll at 1280 x 600
 * and up (page frame). VM counts and this month's cost come from the environments list (GET
 * /environments/page, 100 a call), the figures the Environments page shows.
 */
const TeamDetail = (function() {
    'use strict';

    const QUIET = { suppressGlobalError: true };
    const PAGE_SIZE = 8;
    const ENV_PAGE_SIZE = 8;
    const ENV_FETCH_SIZE = 100;
    const METRICS = [['VM_HOURS', 'VM hours'], ['COST', 'Cost']];
    const ENFORCEMENT = [['WARN', 'Warn only'], ['REQUIRE_APPROVAL', 'Ask an owner to approve each start'], ['BLOCK', 'Block new starts']];

    let s = null; // page state

    // ---- pure model (node tests: src/test/js/team-detail.test.mjs) ----

    function amount(metric, value) {
        const n = Math.round(Number(value || 0));
        return metric === 'COST' ? `$${n.toLocaleString('en-US')}` : `${n.toLocaleString('en-US')} h`;
    }

    /** "payments · cost centre CC-4410 · 3 environments · 14 members" */
    function headline(team) {
        const envs = (team.environments || []).length;
        const parts = [team.name];
        if (team.costCenter) parts.push(`cost centre ${team.costCenter}`);
        parts.push(`${envs} environment${envs === 1 ? '' : 's'}`, `${team.memberCount} member${team.memberCount === 1 ? '' : 's'}`);
        return parts.join(' · ');
    }

    /** "Linked to Entra group SG-Payments · 12 members from it, last synced 09:00", or why nothing is linked. */
    function entraLine(team, now) {
        if (!team.entraGroupId) return 'Not linked to an Entra group: members are added here by hand.';
        const n = team.entraMemberCount || 0;
        let synced = '';
        if (team.lastSyncedAt) {
            const d = new Date(team.lastSyncedAt);
            const today = now || new Date();
            const time = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
            const sameDay = d.toDateString() === today.toDateString();
            synced = `, last synced ${sameDay ? time : `${d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })} ${time}`}`;
        }
        return `Linked to Entra group ${team.entraGroupName || team.entraGroupId} · ${n} member${n === 1 ? '' : 's'} from it${synced}`;
    }

    /** "$1,234.56", or '—' without a figure. */
    function money(value) {
        if (value === null || value === undefined || value === '') return '—';
        const n = Number(value);
        return Number.isFinite(n) ? '$' + n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '—';
    }

    /** "3 of 5", "No VMs", or '—' while the environment's row is unknown. */
    function vmsText(row) {
        if (!row || typeof row.vmCount !== 'number') return '—';
        if (!row.vmCount) return 'No VMs';
        return `${row.runningVmCount || 0} of ${row.vmCount}`;
    }

    /**
     * The Environments table's rows: the team's environments matching a search (name), each with its
     * VMs running and this month's cost from the environments list rows (by id; null while not loaded).
     */
    function envRows(team, rowsById, search) {
        const q = String(search || '').toLowerCase().trim();
        return (team.environments || [])
            .filter(e => !q || [e.displayName, e.name].some(v => String(v || '').toLowerCase().includes(q)))
            .map(e => {
                const row = rowsById ? rowsById[e.environmentId] : null;
                return { environmentId: e.environmentId, name: e.displayName || e.name, vms: vmsText(row),
                    cost: row ? money(row.monthToDateCost) : '—' };
            });
    }

    /** The PUT /teams/{id}/quotas body from the form's values: a metric without a limit has no quota. */
    function quotasBody(rows) {
        return rows.filter(r => r.limit !== '' && r.limit !== null && Number(r.limit) > 0).map(r => ({
            metric: r.metric, limit: Number(r.limit), enforcement: r.enforcement, warnPercent: Number(r.warnPercent) || 80
        }));
    }

    // ---- page ----

    function call(method, url, data) {
        return new Promise((resolve, reject) => {
            ApiClient.request(method, url, data || null, QUIET).done(resolve).fail(reject);
        });
    }

    function serverMessage(error, fallback) {
        return (error && error.responseJSON && error.responseJSON.message) || fallback;
    }

    function current() {
        return s && ContentRouter.isCurrent(s.token);
    }

    function quotasOn() {
        return typeof Capabilities !== 'undefined' && Capabilities.has('quotas');
    }

    async function load(params) {
        const token = ContentRouter.token();
        s = { token, teamId: params && params.teamId, team: null, members: [], page: 1, users: null,
            envSearch: '', envPage: 1, envFit: null, envRows: null };
        Loading.showPage('Loading the team…');
        await refresh(true);
        loadEnvRows();
    }

    /** Every environment's list row (VM counts, this month), 100 a call; the table shows '—' until they come. */
    async function loadEnvRows() {
        if (!current() || !s.team || !(s.team.environments || []).length || s.envLoading) return;
        const token = s.token;
        s.envLoading = true;
        const pageOf = data => ((data && data.content) || []);
        let rows = {};
        try {
            const first = await call('GET', Config.API.environments.page(0, ENV_FETCH_SIZE));
            const pages = Math.min(20, Number((first && first.page && first.page.totalPages) || (first && first.totalPages)) || 1);
            const rest = await Promise.all(Array.from({ length: pages - 1 }, (_, i) =>
                call('GET', Config.API.environments.page(i + 1, ENV_FETCH_SIZE)).catch(() => null)));
            [first].concat(rest).forEach(data => pageOf(data).forEach(r => { rows[r.environmentId] = r; }));
        } catch (error) {
            rows = {};
        }
        if (!ContentRouter.isCurrent(token)) return;
        s.envLoading = false;
        s.envRows = rows;
        drawEnvs();
    }

    async function refresh(first) {
        const token = s.token;
        let team;
        let members;
        try {
            [team, members] = await Promise.all([call('GET', Config.API.teams.teamOverview(s.teamId)),
                call('GET', Config.API.teams.members(s.teamId)).catch(() => [])]);
        } catch (error) {
            if (!ContentRouter.isCurrent(token)) return;
            $('#content-area').html(Utils.html`<div class="content-view"><div class="alert alert-danger" role="alert">${
                serverMessage(error, 'This team could not be loaded.')}</div><a href="#/teams">Back to Teams</a></div>`);
            return;
        }
        if (!ContentRouter.isCurrent(token)) return;
        s.team = team;
        s.members = members || [];
        if (first) render(); else { drawHeader(); drawMembers(); drawAside(); }
        if (!first && s.envRows === null) loadEnvRows(); // the team had no environments when the page opened
    }

    function render() {
        $('#content-area').html(`
            <div class="content-view page page-frame td-page" id="team-detail-view">
                <div id="td-header"></div>
                <div class="td-grid">
                    <section class="td-card" aria-labelledby="td-members-title">
                        <div class="td-card-head"><h2 id="td-members-title">Members</h2>
                            <button type="button" class="btn btn-secondary btn-sm" id="td-add-people">Add people</button></div>
                        <div class="td-entra" id="td-entra"></div>
                        <div id="td-members"></div>
                    </section>
                    <aside class="td-aside" id="td-aside"></aside>
                </div>
            </div>`);
        drawHeader();
        drawMembers();
        drawAside();
        bind();
    }

    function drawHeader() {
        const t = s.team;
        $('#td-header').html(PageHeader.markup({ title: t.displayName,
            breadcrumb: [{ label: 'Teams', href: '#/teams' }, { label: t.displayName }],
            summaryHtml: Utils.escapeHtml(headline(t)),
            actionsHtml: `<button type="button" class="btn btn-secondary" id="td-edit">Edit name and cost centre</button>
                <button type="button" class="td-link td-danger" id="td-deactivate">Deactivate team</button>` }));
    }

    function drawMembers() {
        const t = s.team;
        $('#td-entra').html(Utils.html`<span>${entraLine(t)}</span>${Utils.raw(t.entraGroupId
            ? '<button type="button" class="td-link" id="td-sync">Sync now</button><button type="button" class="td-link" id="td-change-group">Change group</button>'
            : '<button type="button" class="td-link" id="td-change-group">Link a group</button>')}`);
        const list = s.members;
        const pages = Math.max(1, Math.ceil(list.length / PAGE_SIZE));
        s.page = Math.min(Math.max(1, s.page), pages);
        const rows = Pagination.slice(list, s.page, PAGE_SIZE).map(m => {
            const name = m.displayName || m.email || m.userId;
            const manual = m.source !== 'ENTRA';
            return Utils.html`<tr><td class="td-name">${name}</td><td class="td-email">${m.email || ''}</td>
                <td><span class="td-tag td-tag-${manual ? 'manual' : 'entra'}">${manual ? 'Added by hand' : 'Entra'}</span></td>
                <td class="td-actions">${Utils.raw(manual ? Utils.html`<button type="button" class="td-link td-danger" data-td-remove="${m.userId}" aria-label="Remove ${name}">Remove</button>` : '')}</td></tr>`;
        }).join('');
        const note = t.entraGroupId ? '<span class="td-note">People from the Entra group are removed there.</span>' : '';
        $('#td-members').html(list.length ? `<div class="td-table-wrap"><table class="td-table" id="td-member-table">
                <thead class="visually-hidden"><tr><th>Name</th><th>Email</th><th>Source</th><th>Actions</th></tr></thead>
                <tbody>${rows}</tbody></table></div>
            <div class="td-foot">${list.length > PAGE_SIZE ? Pagination.buildPager({ page: s.page, pageSize: PAGE_SIZE, totalItems: list.length,
                action: 'td-page', key: 'members' }) : `<span class="td-note">${list.length} member${list.length === 1 ? '' : 's'}</span>`}${note}</div>`
            : '<p class="td-note td-empty">No members yet. Add people, or link an Entra group to sync them.</p>');
    }

    function drawAside() {
        const t = s.team;
        const limits = quotasOn() ? `<section class="td-card" aria-labelledby="td-limits-title">
                <div><h2 id="td-limits-title">Monthly limits</h2><p class="td-note">Across the team's environments, from the 1st of each month</p></div>
                <form id="td-limits" novalidate>${METRICS.map(([metric, label]) => limitBlock(metric, label, (t.quotas || []).find(q => q.metric === metric))).join('')}
                    <div class="re-error" id="td-limits-error" role="alert" hidden></div>
                    <button type="submit" class="btn btn-primary btn-sm">Save limits</button></form>
            </section>` : '';
        $('#td-aside').html(`${limits}<section class="list-panel td-envs-panel" id="td-envs-panel" aria-labelledby="td-envs-title">
                <div class="list-panel-head"><h2 id="td-envs-title">Environments</h2>
                    <label class="um-search td-env-search">
                        <svg width="15" height="15" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><circle cx="9" cy="9" r="5.5"></circle><path d="m13.5 13.5 3.5 3.5"></path></svg>
                        <span class="visually-hidden">Search the team's environments</span>
                        <input type="search" class="form-control form-control-sm" id="td-env-search" placeholder="Environment name" autocomplete="off" value="${Utils.escapeHtml(s.envSearch)}">
                    </label></div>
                <div class="list-panel-body" tabindex="0" role="region" aria-label="${Utils.escapeHtml(`Environments of ${t.displayName}, scrolls`)}">
                    <table class="um-table td-env-table table-cards" id="td-env-table">
                        <thead><tr><th scope="col">Environment</th><th scope="col">VMs running</th><th scope="col">This month</th></tr></thead>
                        <tbody id="td-env-body"></tbody>
                    </table>
                </div>
                <div class="list-panel-foot td-foot"><div id="td-env-pager"></div>
                    <button type="button" class="td-link" id="td-change-envs">${Utils.escapeHtml(`Change which environments belong to ${t.displayName}`)}</button></div>
            </section>`);
        drawEnvs();
        attachEnvFit();
    }

    const envPageSize = () => s.envFit || ENV_PAGE_SIZE;

    function drawEnvs() {
        const t = s.team;
        const all = t.environments || [];
        const rows = envRows(t, s.envRows, s.envSearch);
        const size = envPageSize();
        s.envPage = Math.min(Math.max(1, s.envPage), Math.max(1, Math.ceil(rows.length / size)));
        let body;
        if (rows.length) {
            body = Pagination.slice(rows, s.envPage, size).map(r => Utils.html`<tr>
                <td class="um-person-cell" data-label=""><a class="td-env-name" href="#/environments/${r.environmentId}">${r.name}</a></td>
                <td data-label="VMs running"><span class="td-mono">${r.vms}</span></td>
                <td data-label="This month"><span class="td-mono">${r.cost}</span></td></tr>`).join('');
        } else if (all.length) {
            body = `<tr><td colspan="3" class="um-empty">${UiStates.filtered({ title: `No environments match "${s.envSearch.trim()}"`,
                text: 'Check the spelling, or clear the search.', clearLabel: 'Show all environments', onClear: clearEnvSearch })}</td></tr>`;
        } else {
            body = `<tr><td colspan="3" class="um-empty">${UiStates.empty({ title: 'No environments yet',
                text: `Choose them with "Change which environments belong to ${t.displayName}".` })}</td></tr>`;
        }
        $('#td-env-body').html(body);
        $('#td-env-pager').html(rows.length > size ? Pagination.buildPager({ page: s.envPage, pageSize: size, totalItems: rows.length,
            action: 'td-env-page', key: 'environments' }) : `<span class="td-note">${all.length} environment${all.length === 1 ? '' : 's'}</span>`);
    }

    function clearEnvSearch() {
        s.envSearch = '';
        s.envPage = 1;
        $('#td-env-search').val('');
        drawEnvs();
    }

    /** Environment rows a page follow the panel's height on the page frame (U18-T03). */
    function attachEnvFit() {
        const panel = document.getElementById('td-envs-panel');
        if (!panel || typeof ListPanel === 'undefined') return;
        const apply = count => {
            if (!count || count === envPageSize()) return;
            const first = (s.envPage - 1) * envPageSize();
            s.envFit = count;
            s.envPage = ListPanel.pageKeepingFirst(first, count);
            drawEnvs();
        };
        const handle = ListPanel.attach(panel, { rowSelector: '#td-env-body tr', rowHeight: 38, min: 3, max: 25, onFit: apply });
        apply(handle.fitCount());
    }

    function limitBlock(metric, label, q) {
        const used = q ? Number(q.used || 0) : 0;
        const limit = q ? Number(q.limit || 0) : 0;
        const pct = limit > 0 ? Math.min(100, Math.round((used / limit) * 100)) : 0;
        const warn = q ? q.warnPercent : 80;
        const enf = q ? q.enforcement : 'WARN';
        return Utils.html`<div class="td-limit" data-metric="${metric}">
            <div class="td-limit-line"><b>${label}</b><span class="td-mono">${q ? `${amount(metric, used)} of ${amount(metric, limit)}` : 'No limit'}</span></div>
            ${Utils.raw(q ? Utils.html`<div class="td-bar" role="img" aria-label="${pct}% of the ${label.toLowerCase()} limit used; warning at ${warn}%">
                <span class="td-bar-fill${Utils.raw(pct >= warn ? ' td-bar-warn' : '')}" style="width:${pct}%"></span><span class="td-bar-warn-mark" style="left:${warn}%"></span></div>` : '')}
            <div class="td-limit-fields">
                <label class="ap-field">Limit${metric === 'COST' ? ' ($)' : ' (hours)'}<input type="number" min="1" step="${metric === 'COST' ? '0.01' : '1'}"
                    class="form-control form-control-sm td-mono" data-td-limit="limit" value="${q ? Number(q.limit) : ''}" placeholder="No limit"></label>
                <label class="ap-field">Warn at (%)<input type="number" min="1" max="100" class="form-control form-control-sm td-mono" data-td-limit="warnPercent" value="${warn}"></label>
            </div>
            <label class="ap-field">At the limit<select class="form-select form-select-sm" data-td-limit="enforcement">${Utils.raw(ENFORCEMENT.map(([v, l]) =>
                Utils.html`<option value="${v}"${Utils.raw(v === enf ? ' selected' : '')}>${l}</option>`).join(''))}</select></label>
        </div>`;
    }

    // ---- actions

    async function saveLimits() {
        const rows = $('#td-limits .td-limit').map(function() {
            const $b = $(this);
            return { metric: $b.data('metric'), limit: $b.find('[data-td-limit="limit"]').val(),
                warnPercent: $b.find('[data-td-limit="warnPercent"]').val(), enforcement: $b.find('[data-td-limit="enforcement"]').val() };
        }).get();
        const $error = $('#td-limits-error').prop('hidden', true);
        const $btn = $('#td-limits button[type="submit"]').prop('disabled', true);
        try {
            await call('PUT', Config.API.teams.quotas(s.teamId), quotasBody(rows));
            if (!current()) return;
            Notifications.success('Limits saved.');
            await refresh(false);
        } catch (error) {
            $error.text(serverMessage(error, 'The limits were not saved.')).prop('hidden', false);
        } finally {
            $btn.prop('disabled', false);
        }
    }

    async function syncNow() {
        try {
            const r = await call('POST', Config.API.teams.sync(s.teamId));
            Notifications.success(`Synced: ${r.added} added, ${r.removed} removed${r.skippedUnknown ? `, ${r.skippedUnknown} without an account` : ''}.`);
            await refresh(false);
        } catch (error) {
            Notifications.showError(serverMessage(error, 'Could not sync the members.'));
        }
    }

    function changeGroup() {
        const t = s.team;
        Modals.show({
            id: 'teamGroupModal',
            title: Utils.escapeHtml(`Entra group for ${t.displayName}`),
            html: true,
            body: Utils.html`<p class="td-note">${t.entraGroupId ? `Now linked to ${t.entraGroupName || t.entraGroupId}. Members sync from the group you link.`
                : 'Members sync from the group you link.'}</p>
                <div class="td-group-search"><label class="visually-hidden" for="td-group-q">Search Entra groups</label>
                    <input type="search" class="form-control" id="td-group-q" placeholder="Search Entra groups by name" autocomplete="off">
                    <button type="button" class="btn btn-secondary" id="td-group-find">Search</button></div>
                <ul class="td-group-results" id="td-group-results" aria-live="polite"></ul>
                <div class="re-error" id="td-group-error" role="alert" hidden></div>`,
            buttons: [{ text: 'Close', class: 'btn-secondary', dismiss: true }].concat(t.entraGroupId
                ? [{ text: 'Unlink the group', class: 'btn-outline-danger', id: 'td-group-unlink' }] : []),
            onShow: () => $('#td-group-q').trigger('focus')
        });
        const find = async () => {
            const q = $('#td-group-q').val().trim();
            const $list = $('#td-group-results');
            if (q.length < 2) { $list.html('<li class="td-note">Type at least 2 characters.</li>'); return; }
            try {
                const groups = await call('GET', Config.API.teams.entraGroups(q)) || [];
                $list.html(groups.length ? groups.map(g => Utils.html`<li><span>${g.displayName}${g.mail ? ` · ${g.mail}` : ''}</span>
                    <button type="button" class="btn btn-sm btn-primary" data-td-link="${g.id}" data-name="${g.displayName}">Link</button></li>`).join('')
                    : '<li class="td-note">No groups match.</li>');
            } catch (error) {
                $list.html(Utils.html`<li class="td-note">${serverMessage(error, 'Group search is unavailable.')}</li>`);
            }
        };
        $('#td-group-find').on('click', find);
        $('#td-group-q').on('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); find(); } });
        const link = async (groupId, groupName) => {
            try {
                await call('PUT', Config.API.teams.entraGroup(s.teamId), { groupId, groupName: groupName || null });
                Modals.hide('teamGroupModal');
                Notifications.success(groupId ? 'Entra group linked.' : 'Entra group unlinked.');
                await refresh(false);
            } catch (error) {
                $('#td-group-error').text(serverMessage(error, 'Could not change the Entra group.')).prop('hidden', false);
            }
        };
        $('#td-group-results').on('click', '[data-td-link]', function() { link(this.dataset.tdLink, this.dataset.name); });
        $('#td-group-unlink').on('click', () => link(null, null));
    }

    async function addPeople() {
        if (!s.users) s.users = await call('GET', Config.API.users.list).catch(() => []) || [];
        if (!current()) return;
        const inTeam = new Set(s.members.map(m => m.userId));
        const candidates = s.users.filter(u => u.active !== false && u.isActive !== false && !inTeam.has(u.userId))
            .sort((a, b) => (a.displayName || a.email).localeCompare(b.displayName || b.email));
        Modals.show({
            id: 'teamAddModal',
            title: Utils.escapeHtml(`Add people to ${s.team.displayName}`),
            html: true,
            body: Utils.html`<label class="visually-hidden" for="td-add-q">Filter people</label>
                <input type="search" class="form-control" id="td-add-q" placeholder="Filter by name or email" autocomplete="off">
                <ul class="td-pick" id="td-pick">${Utils.raw(candidates.map(u => Utils.html`<li data-q="${`${u.displayName || ''} ${u.email}`.toLowerCase()}">
                    <label class="re-check"><input type="checkbox" value="${u.userId}"> ${u.displayName || u.email} <span class="td-email">${u.email}</span></label></li>`).join('')
                    || '<li class="td-note">Everyone is already in the team.</li>')}</ul>
                <div class="re-error" id="td-add-error" role="alert" hidden></div>`,
            buttons: [{ text: 'Cancel', class: 'btn-secondary', dismiss: true }, { text: 'Add', class: 'btn-primary', id: 'td-add-confirm', disabled: true }],
            onShow: () => $('#td-add-q').trigger('focus')
        });
        $('#td-add-q').on('input', function() {
            const q = this.value.trim().toLowerCase();
            $('#td-pick li[data-q]').each(function() { this.hidden = !!q && !this.dataset.q.includes(q); });
        });
        $('#td-pick').on('change', 'input', () => {
            const n = $('#td-pick input:checked').length;
            $('#td-add-confirm').prop('disabled', !n).text(n ? `Add ${n} ${n === 1 ? 'person' : 'people'}` : 'Add');
        });
        $('#td-add-confirm').on('click', async function() {
            const ids = $('#td-pick input:checked').map(function() { return this.value; }).get();
            $(this).prop('disabled', true);
            try {
                for (const userId of ids) await call('POST', Config.API.teams.members(s.teamId), { userId });
                Modals.hide('teamAddModal');
                Notifications.success(`Added ${ids.length} ${ids.length === 1 ? 'person' : 'people'}.`);
                await refresh(false);
            } catch (error) {
                $('#td-add-error').text(serverMessage(error, 'Not everyone was added.')).prop('hidden', false);
                await refresh(false);
            }
        });
    }

    async function removeMember(userId) {
        const m = s.members.find(x => x.userId === userId);
        const name = m ? (m.displayName || m.email) : userId;
        Modals.confirm('Remove from the team', `Remove ${name} from ${s.team.displayName}?`, async () => {
            try {
                await call('DELETE', Config.API.teams.member(s.teamId, userId));
                Notifications.success(`${name} removed.`);
                await refresh(false);
            } catch (error) {
                Notifications.showError(serverMessage(error, 'Could not remove them.'));
            }
        }, { confirmText: 'Remove', confirmClass: 'btn-danger' });
    }

    async function changeEnvironments() {
        const all = (await call('GET', Config.API.environments.list).catch(() => []) || []).filter(e => e.isActive !== false)
            .sort((a, b) => (a.displayName || a.name).localeCompare(b.displayName || b.name));
        if (!current()) return;
        const mine = new Set((s.team.environments || []).map(e => e.environmentId));
        Modals.show({
            id: 'teamEnvsModal',
            title: Utils.escapeHtml(`Environments of ${s.team.displayName}`),
            html: true,
            body: Utils.html`<p class="td-note">Ticked environments belong to ${s.team.displayName}. Ticking one that belongs to another team moves it here;
                    unticking leaves it without a team.</p>
                <ul class="td-pick" id="td-env-pick">${Utils.raw(all.map(e => Utils.html`<li><label class="re-check">
                    <input type="checkbox" value="${e.environmentId}"${Utils.raw(mine.has(e.environmentId) ? ' checked' : '')}> ${e.displayName || e.name}
                    ${Utils.raw(e.teamId && e.teamId !== s.teamId ? Utils.html`<span class="td-email">now in ${e.teamName || 'another team'}</span>` : '')}</label></li>`).join(''))}</ul>
                <div class="re-error" id="td-envs-error" role="alert" hidden></div>`,
            buttons: [{ text: 'Cancel', class: 'btn-secondary', dismiss: true }, { text: 'Save environments', class: 'btn-primary', id: 'td-envs-save' }]
        });
        $('#td-envs-save').on('click', async function() {
            const ids = $('#td-env-pick input:checked').map(function() { return this.value; }).get();
            $(this).prop('disabled', true);
            try {
                await call('PUT', Config.API.teams.environments(s.teamId), { environmentIds: ids });
                Modals.hide('teamEnvsModal');
                Notifications.success('Environments saved.');
                await refresh(false);
            } catch (error) {
                $('#td-envs-error').text(serverMessage(error, 'The environments were not saved.')).prop('hidden', false);
                $(this).prop('disabled', false);
            }
        });
    }

    function bind() {
        const $view = $('#team-detail-view');
        $view.off('.teamDetail')
            .on('click.teamDetail', '#td-edit', () => Teams.openForm(s.team, () => refresh(false)))
            .on('click.teamDetail', '#td-deactivate', () => Teams.deactivate(s.team, () => { window.location.hash = '#/teams'; }))
            .on('click.teamDetail', '#td-sync', syncNow)
            .on('click.teamDetail', '#td-change-group', changeGroup)
            .on('click.teamDetail', '#td-add-people', addPeople)
            .on('click.teamDetail', '[data-td-remove]', function() { removeMember(this.dataset.tdRemove); })
            .on('click.teamDetail', '[data-action="td-page"]', function() { s.page = Number(this.dataset.page) || 1; drawMembers(); })
            .on('click.teamDetail', '[data-action="td-env-page"]', function() { s.envPage = Number(this.dataset.page) || 1; drawEnvs(); })
            .on('input.teamDetail', '#td-env-search', Utils.debounce(function() { s.envSearch = this.value; s.envPage = 1; drawEnvs(); }, 250))
            .on('submit.teamDetail', '#td-limits', e => { e.preventDefault(); saveLimits(); })
            .on('click.teamDetail', '#td-change-envs', changeEnvironments);
    }

    return { load, model: { headline, entraLine, quotasBody, amount, money, vmsText, envRows } };
})();

if (typeof window !== 'undefined') window.TeamDetail = TeamDetail;
