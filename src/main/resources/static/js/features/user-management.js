/**
 * Users (U13-T03, Users.dc.html): everyone who can sign in and their role across the platform.
 * Counts (active, administrators, environment admins, haven't signed in yet), a search and Role /
 * Status filters kept in the address (?q=&role=&status=&page=), and 10 people a page with Person,
 * Role ("Administrator" / "Environment admin" / "User"), Signs in with, Last sign-in and direct
 * actions: Change role, Access (Grant access for that person) or Set password for people who
 * sign in with a password, and Deactivate / Reactivate.
 */
const UserManagement = (function() {
    'use strict';

    const PAGE_SIZE = 10;
    const ROLE_FILTERS = [['', 'All roles'], ['ADMIN', 'Administrator'], ['ENV_ADMIN', 'Environment admin'], ['USER', 'User']];
    const STATUS_FILTERS = [['active', 'Active'], ['inactive', 'Deactivated']];

    // State
    let allUsers = [];
    let filteredUsers = [];
    let currentPage = 1;
    let currentSearch = '';
    let currentRoleFilter = '';
    // Inactive users are loaded too (so they can be reactivated, M21); show active ones first.
    let currentStatusFilter = 'active';

    // Navigation token of the current visit (see the page contract in core/router.js).
    let pageToken = null;

    function isActive() {
        return pageToken !== null && ContentRouter.isCurrent(pageToken);
    }

    // ---- pure model (node tests: src/test/js/users-list.test.mjs) ----

    function roleLabel(user) {
        if (user.serviceAccount) return 'Service account';
        if (user.admin) return 'Administrator';
        if (user.envAdmin) return 'Environment admin';
        return 'User';
    }

    function signInLabel(user) {
        return { MICROSOFT: 'Microsoft', PASSWORD: 'Password', MICROSOFT_AND_PASSWORD: 'Microsoft and password', NONE: 'API tokens' }[user.signInMethod]
            || (user.signsInWithMicrosoft === false ? 'Password' : 'Microsoft');
    }

    /** Password-only people get "Set password" where others get "Access". */
    function signsInWithPasswordOnly(user) {
        return user.signInMethod === 'PASSWORD';
    }

    function counts(users) {
        const active = users.filter(u => u.active);
        return {
            active: active.length,
            admins: active.filter(u => u.admin).length,
            envAdmins: active.filter(u => u.envAdmin && !u.admin).length,
            notSignedIn: active.filter(u => u.pendingFirstLogin && !u.serviceAccount).length
        };
    }

    function sameDay(a, b) {
        return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
    }

    /** "Today 09:02", "Yesterday 19:00", "Fri 19:00" this week, else "12 Oct" (and the year when not this one). */
    function lastSignIn(at, now) {
        if (!at) return null;
        const d = new Date(at);
        const n = now || new Date();
        const time = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
        if (sameDay(d, n)) return `Today ${time}`;
        if (sameDay(d, new Date(n.getFullYear(), n.getMonth(), n.getDate() - 1))) return `Yesterday ${time}`;
        if (new Date(n.getFullYear(), n.getMonth(), n.getDate()).getTime() - d.getTime() < 6 * 86400000) {
            return `${d.toLocaleDateString('en-GB', { weekday: 'short' })} ${time}`;
        }
        const opts = { day: 'numeric', month: 'short' };
        if (d.getFullYear() !== n.getFullYear()) opts.year = 'numeric';
        return d.toLocaleDateString('en-GB', opts);
    }

    /** The people matching a search (name or email), role and status. */
    function filterList(users, search, role, status) {
        const q = String(search || '').toLowerCase().trim();
        return users.filter(user => {
            if (q && !(user.displayName || '').toLowerCase().includes(q) && !(user.email || '').toLowerCase().includes(q)) return false;
            if (role === 'ADMIN' && !user.admin) return false;
            if (role === 'ENV_ADMIN' && (!user.envAdmin || user.admin)) return false;
            if (role === 'USER' && (user.admin || user.envAdmin)) return false;
            if (status === 'active' && !user.active) return false;
            if (status === 'inactive' && user.active) return false;
            return true;
        });
    }

    // ---- page ----

    /** Router loader. */
    function load() {
        pageToken = ContentRouter.token();
        ContentRouter.onLeave(() => $('#content-area').off('.userMgmt'));
        // Who may open this page is decided by the router's access table (U02-T01).
        const q = ContentRouter.query ? ContentRouter.query() : {};
        currentSearch = q.q || '';
        currentRoleFilter = ROLE_FILTERS.some(r => r[0] === q.role) ? q.role : '';
        currentStatusFilter = q.status === 'inactive' ? 'inactive' : 'active';
        currentPage = Math.max(1, Number(q.page) || 1);
        showLoading();
        fetchUsers(true);
    }

    function showLoading() {
        $('#content-area').html('<div class="content-view"><div class="ar-empty" role="status">Loading people…</div></div>');
    }

    /** keepPage: the page from the address on first load; a reload after a change keeps the current one. */
    function fetchUsers(keepPage) {
        ApiClient.get(Config.API.users.listAll)
            .done(function(users) {
                if (!isActive()) return;
                allUsers = users || [];
                // Keep the search and filters the admin has set (e.g. after Reactivate).
                filteredUsers = filterUsers();
                const pages = Math.max(1, Math.ceil(filteredUsers.length / PAGE_SIZE));
                currentPage = keepPage === false ? 1 : Math.min(currentPage, pages);
                render();
            })
            .fail(function(xhr) {
                if (!isActive()) return;
                $('#content-area').html(Utils.html`<div class="content-view"><div class="alert alert-danger" role="alert">
                    Couldn't load the people: ${xhr.responseJSON?.message || 'the server did not answer.'}</div></div>`);
            });
    }

    function render() {
        $('#content-area').html(buildViewHtml());
        renderCounts();
        renderTable();
        bindEvents();
    }

    function buildViewHtml() {
        const add = `<a class="btn btn-primary" id="btn-onboard-user" href="#/user-management/add">
                <svg width="14" height="14" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M10 4v12M4 10h12"></path></svg>
                Add people</a>`;
        return `
            <div class="content-view um-page" id="user-management-view">
                ${PageHeader.markup({ title: 'Users', summaryHtml: 'Everyone who can sign in, and their role across the platform.',
                    actionsHtml: add, helpPage: typeof HelpLink !== 'undefined' ? 'user-management' : null, helpLabel: 'Users' })}
                <div class="um-counts" id="um-counts" aria-live="polite"></div>
                <div class="um-filters">
                    <label class="um-search">
                        <svg width="15" height="15" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><circle cx="9" cy="9" r="5.5"></circle><path d="m13.5 13.5 3.5 3.5"></path></svg>
                        <span class="visually-hidden">Search users</span>
                        <input type="search" class="form-control" id="user-search" placeholder="Name or email" value="${Utils.escapeHtml(currentSearch)}">
                    </label>
                    <select class="form-select" id="filter-role" aria-label="Role">${ROLE_FILTERS.map(([v, l]) =>
                        `<option value="${v}"${currentRoleFilter === v ? ' selected' : ''}>${l}</option>`).join('')}</select>
                    <select class="form-select" id="filter-status" aria-label="Status">${STATUS_FILTERS.map(([v, l]) =>
                        `<option value="${v}"${currentStatusFilter === v ? ' selected' : ''}>${l}</option>`).join('')}</select>
                </div>
                <div class="um-card">
                    <div class="um-table-wrap">
                        <table class="um-table" id="users-table">
                            <thead><tr>
                                <th scope="col">Person</th><th scope="col">Role</th><th scope="col">Signs in with</th>
                                <th scope="col">Last sign-in</th><th scope="col"><span class="visually-hidden">Actions</span></th>
                            </tr></thead>
                            <tbody id="users-table-body"></tbody>
                        </table>
                    </div>
                    <div id="user-pagination"></div>
                </div>
            </div>`;
    }

    function renderCounts() {
        const c = counts(allUsers);
        $('#um-counts').html(`<span><b>${c.active}</b> active</span>
            <span><b>${c.admins}</b> administrator${c.admins === 1 ? '' : 's'}</span>
            <span><b>${c.envAdmins}</b> environment admin${c.envAdmins === 1 ? '' : 's'}</span>
            <span${c.notSignedIn ? ' class="um-count-warn"' : ''}><b>${c.notSignedIn}</b> haven't signed in yet</span>`);
    }

    function renderTable() {
        const pageUsers = Pagination.slice(filteredUsers, currentPage, PAGE_SIZE);
        $('#users-table-body').html(pageUsers.length ? pageUsers.map(buildUserRow).join('')
            : `<tr><td colspan="5" class="um-empty">${allUsers.length ? 'Nobody matches these filters.' : 'No people yet. Add people to get started.'}</td></tr>`);
        $('#user-pagination').html(filteredUsers.length > PAGE_SIZE ? Pagination.buildPager({ page: currentPage, pageSize: PAGE_SIZE,
            totalItems: filteredUsers.length, action: 'um-page', key: 'users' }) : '');
        syncAddress();
    }

    /** The filters and page in the address, so Back returns to them. */
    function syncAddress() {
        const params = new URLSearchParams();
        if (currentSearch) params.set('q', currentSearch);
        if (currentRoleFilter) params.set('role', currentRoleFilter);
        if (currentStatusFilter === 'inactive') params.set('status', 'inactive');
        if (currentPage > 1) params.set('page', String(currentPage));
        const hash = `#/user-management${params.toString() ? '?' + params.toString() : ''}`;
        if (window.location.hash !== hash && window.history && window.history.replaceState) window.history.replaceState(null, '', hash);
    }

    function buildUserRow(user) {
        const name = user.displayName || user.email;
        const last = lastSignIn(user.lastLoginAt);
        const notYet = !last && !user.serviceAccount;
        let middle = '';
        if (user.active && !user.serviceAccount) {
            middle = signsInWithPasswordOnly(user)
                ? Utils.html`<button type="button" class="um-act" data-action="set-password" data-user-id="${user.userId}" aria-label="Set password for ${name}">Set password</button>`
                : Utils.html`<a class="um-act" href="#/access-management/grant?email=${encodeURIComponent(user.email)}&name=${encodeURIComponent(name)}"
                    data-user-id="${user.userId}" aria-label="Access for ${name}">Access</a>`;
        }
        const status = user.active
            ? Utils.html`<button type="button" class="um-act um-danger" data-action="deactivate" data-user-id="${user.userId}" aria-label="Deactivate ${name}">Deactivate</button>`
            : Utils.html`<button type="button" class="um-act" data-action="reactivate" data-user-id="${user.userId}" aria-label="Reactivate ${name}">Reactivate</button>`;
        const role = user.active && !user.serviceAccount
            ? Utils.html`<button type="button" class="um-act" data-action="change-role" data-user-id="${user.userId}" aria-label="Change role of ${name}">Change role</button>`
            : '';
        return Utils.html`
            <tr data-user-id="${user.userId}"${Utils.raw(user.active ? '' : ' class="um-row-off"')}>
                <td class="um-person-cell"><div class="um-person"><span class="um-avatar" aria-hidden="true">${getInitials(name)}</span>
                    <span class="um-who"><span class="um-name">${name}</span><span class="um-email">${user.email}</span></span></div></td>
                <td data-label="Role">${roleLabel(user)}${Utils.raw(user.active ? '' : ' <span class="um-chip">Deactivated</span>')}</td>
                <td data-label="Signs in with">${signInLabel(user)}</td>
                <td data-label="Last sign-in" class="${notYet ? 'um-last um-not-yet' : 'um-last'}">${last || (user.serviceAccount ? '—' : 'Not signed in yet')}</td>
                <td class="um-actions">${Utils.raw(role)}${Utils.raw(middle)}${Utils.raw(status)}</td>
            </tr>`;
    }

    function applyFilters() {
        filteredUsers = filterUsers();
        currentPage = 1;
        renderTable();
    }

    /** allUsers narrowed by the current search, role and status filters. */
    function filterUsers() {
        return filterList(allUsers, currentSearch, currentRoleFilter, currentStatusFilter);
    }

    function bindEvents() {
        $('#user-search').off('input').on('input', Utils.debounce(function() {
            currentSearch = $(this).val();
            applyFilters();
        }, 250));
        $('#filter-role').off('change').on('change', function() {
            currentRoleFilter = $(this).val();
            applyFilters();
        });
        $('#filter-status').off('change').on('change', function() {
            currentStatusFilter = $(this).val();
            applyFilters();
        });
        bindActionEvents();
    }

    function bindActionEvents() {
        const $area = $('#content-area');
        $area.off('click.userMgmt')
            .on('click.userMgmt', '#user-management-view [data-action="um-page"]', function() {
                currentPage = Number(this.dataset.page) || 1;
                renderTable();
            })
            .on('click.userMgmt', '#user-management-view [data-action="change-role"]', function(e) {
                e.preventDefault();
                showChangeRoleModal(allUsers.find(u => u.userId === $(this).data('user-id')));
            })
            .on('click.userMgmt', '#user-management-view [data-action="deactivate"]', function(e) {
                e.preventDefault();
                showDeactivateDialog(allUsers.find(u => u.userId === $(this).data('user-id')));
            })
            .on('click.userMgmt', '#user-management-view [data-action="reactivate"]', function(e) {
                e.preventDefault();
                reactivateUser($(this).data('user-id'));
            })
            .on('click.userMgmt', '#user-management-view [data-action="set-password"]', function(e) {
                e.preventDefault();
                showSetPasswordModal(allUsers.find(u => u.userId === $(this).data('user-id')));
            });
    }

    // ---- dialogs (U13-T04, UserDialogs.dc.html) ----

    const ROLES = [
        ['USER', 'User', 'Uses the environments they have been given access to'],
        ['ENV_ADMIN', 'Environment admin', 'Also approves requests, gives access and manages VMs, schedules and budgets'],
        ['ADMIN', 'Administrator', 'Everything, including users, AWS accounts, cost and settings. Sees instance IDs.']
    ];

    function firstName(user) {
        return String(user.displayName || user.email || '').split(/[\s@]/)[0];
    }

    function activeAdmins() {
        return allUsers.filter(u => u.admin && u.active).length;
    }

    /** Why a person cannot be deactivated, or ''. */
    function deactivateBlocker(user, meId) {
        if (user.userId === meId) return "You can't deactivate yourself.";
        if (user.admin && activeAdmins() <= 1) return 'There must always be at least one administrator.';
        return '';
    }

    function dialogError(id, xhr, fallback) {
        $(id).text((xhr && xhr.responseJSON && xhr.responseJSON.message) || fallback).prop('hidden', false);
    }

    /** Change role: User / Environment admin / Administrator, saved in one request. */
    function showChangeRoleModal(user) {
        if (!user) return;
        const name = user.displayName || user.email;
        const current = user.admin ? 'ADMIN' : user.envAdmin ? 'ENV_ADMIN' : 'USER';
        const how = signInLabel(user) === 'Password' ? 'a password' : signInLabel(user);
        const options = ROLES.map(([value, title, text]) => Utils.html`<label class="um-role-choice">
                <input type="radio" name="um-role" value="${value}"${Utils.raw(current === value ? ' checked' : '')}>
                <span><b>${title}</b><span>${text}</span></span></label>`).join('');
        Modals.show({
            id: 'changeRoleModal',
            title: Utils.escapeHtml(`Change role for ${name}`),
            html: true,
            body: Utils.html`<p class="um-dialog-sub">${user.email} · signs in with ${how}</p>
                <fieldset class="um-roles"><legend class="visually-hidden">Role</legend>${Utils.raw(options)}
                <p class="um-dialog-note">Access to each environment is set separately, in Access management. There must always be at least one administrator.</p>
                </fieldset>
                <div class="re-error" id="um-role-error" role="alert" hidden></div>`,
            buttons: [{ text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Save role', class: 'btn-primary', id: 'um-role-save' }],
            onShow: () => {
                $('input[name="um-role"]:checked').trigger('focus');
                $('#um-role-save').off('click').on('click', function() {
                    const role = $('input[name="um-role"]:checked').val();
                    if (role === current) { Modals.hide('changeRoleModal'); return; }
                    const $btn = $(this).prop('disabled', true);
                    ApiClient.patch(Config.API.users.roles(user.userId), { admin: role === 'ADMIN', envAdmin: role === 'ENV_ADMIN' }, { suppressGlobalError: true })
                        .done(() => {
                            Modals.hide('changeRoleModal');
                            Notifications.success('Role saved.');
                            fetchUsers();
                        })
                        .fail(xhr => {
                            dialogError('#um-role-error', xhr, 'The role was not saved. Try again.');
                            $btn.prop('disabled', false);
                        });
                });
            }
        });
    }

    /** Deactivate, with what it means; not offered for yourself or the last administrator. */
    function showDeactivateDialog(user) {
        if (!user) return;
        const name = user.displayName || user.email;
        const blocker = deactivateBlocker(user, Auth.getUserId ? Auth.getUserId() : null);
        Modals.show({
            id: 'deactivateUserModal',
            title: Utils.escapeHtml(`Deactivate ${name}?`),
            html: true,
            body: Utils.html`<p class="um-dialog-text">${firstName(user)} can no longer sign in. The audit log keeps the account's history,
                    and you can reactivate it later. You can't deactivate yourself or the last administrator.</p>
                ${Utils.raw(blocker ? Utils.html`<p class="um-dialog-block" id="um-deactivate-why">${blocker}</p>` : '')}
                <div class="re-error" id="um-deactivate-error" role="alert" hidden></div>`,
            buttons: [{ text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Deactivate', class: 'btn-danger', id: 'um-deactivate-confirm' }],
            onShow: () => {
                $('#deactivateUserModal').attr('role', 'alertdialog');
                const $btn = $('#um-deactivate-confirm').prop('disabled', !!blocker);
                if (blocker) $btn.attr('aria-describedby', 'um-deactivate-why');
                $btn.off('click').on('click', function() {
                    $btn.prop('disabled', true);
                    ApiClient.delete(Config.API.users.get(user.userId), { suppressGlobalError: true })
                        .done(() => {
                            Modals.hide('deactivateUserModal');
                            Notifications.success(`${name} deactivated.`);
                            fetchUsers();
                        })
                        .fail(xhr => {
                            dialogError('#um-deactivate-error', xhr, 'They were not deactivated. Try again.');
                            $btn.prop('disabled', false);
                        });
                });
            }
        });
    }

    function reactivateUser(userId) {
        ApiClient.post(Config.API.users.reactivate(userId))
            .done(function() {
                Notifications.success('User reactivated');
                fetchUsers();
            })
            .fail(function(xhr) {
                Notifications.error(xhr.responseJSON?.message || 'Failed to reactivate user');
            });
    }

    // ===================== Set Password =====================

    const PASSWORD_MIN = 12;
    const PASSWORD_MAX = 72;

    /** The two live checks: length and both the same. */
    function passwordChecks(password, again) {
        return {
            length: password.length >= PASSWORD_MIN && password.length <= PASSWORD_MAX,
            match: password.length > 0 && password === again
        };
    }

    function showSetPasswordModal(user) {
        if (!user) return;
        const name = user.displayName || user.email;
        Modals.show({
            id: 'setPasswordModal',
            title: Utils.escapeHtml(`Set a password for ${name}`),
            html: true,
            body: Utils.html`<p class="um-dialog-sub">For signing in with a username and password instead of Microsoft.${Utils.raw(user.username
                    ? Utils.html` Username: <code>${user.username}</code>.` : '')}</p>
                <div class="um-password">
                    <label class="ap-field">New password
                        <input type="password" class="form-control" id="set-password-new" autocomplete="new-password" maxlength="${PASSWORD_MAX}"></label>
                    <label class="ap-field">Type it again
                        <input type="password" class="form-control" id="set-password-confirm" autocomplete="new-password" maxlength="${PASSWORD_MAX}"></label>
                    <ul class="um-checks" id="set-password-checks" aria-live="polite">
                        <li data-check="length">${PASSWORD_MIN} to ${PASSWORD_MAX} characters<span class="visually-hidden um-check-state"></span></li>
                        <li data-check="match">both match<span class="visually-hidden um-check-state"></span></li>
                    </ul>
                    <p class="um-dialog-note">Give it to ${firstName(user)} in person or by phone, not in the same email as the username.</p>
                </div>
                <div class="re-error" id="set-password-error" role="alert" hidden></div>`,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Set password', class: 'btn-primary', id: 'set-password-submit', disabled: true }
            ],
            onShow: function() {
                $('#set-password-new').trigger('focus');
                const update = () => {
                    const c = passwordChecks($('#set-password-new').val() || '', $('#set-password-confirm').val() || '');
                    [['length', c.length], ['match', c.match]].forEach(([key, ok]) => {
                        $(`#set-password-checks [data-check="${key}"]`).toggleClass('um-check-ok', ok)
                            .find('.um-check-state').text(ok ? ' (met)' : ' (not met)');
                    });
                    $('#set-password-submit').prop('disabled', !(c.length && c.match));
                };
                $('#set-password-new, #set-password-confirm').off('input').on('input', update);
                update();
                $('#set-password-submit').off('click').on('click', function() {
                    submitSetPassword(user);
                });
            }
        });
    }

    function submitSetPassword(user) {
        const password = $('#set-password-new').val() || '';
        const c = passwordChecks(password, $('#set-password-confirm').val() || '');
        if (!(c.length && c.match)) return;
        const $submit = $('#set-password-submit').prop('disabled', true);
        ApiClient.put(Config.API.users.setPassword(user.userId), { password: password }, { suppressGlobalError: true })
            .done(function() {
                Modals.hide('setPasswordModal');
                Notifications.success('Password set.');
                fetchUsers();
            })
            .fail(function(xhr) {
                dialogError('#set-password-error', xhr, 'The password was not set. Try again.');
                $submit.prop('disabled', false);
            });
    }

    /**
     * Get initials from name
     */
    function getInitials(name) {
        if (!name) return '?';
        const parts = name.trim().split(/\s+/);
        if (parts.length >= 2) {
            return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
        }
        return name.substring(0, 2).toUpperCase();
    }

    // Public API
    return {
        load: load,
        model: { roleLabel, signInLabel, signsInWithPasswordOnly, counts, lastSignIn, filterList, passwordChecks }
    };
})();

// Make available globally
window.UserManagement = UserManagement;

