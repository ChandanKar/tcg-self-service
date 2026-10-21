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

    // Onboard-modal state
    let onboardSelected = null;   // picked directory user, or null
    let onboardManual = false;    // manual-entry mode (directory disabled / not found)
    let onboardEnvs = [];         // environments for the optional grant picker
    let onboardResults = [];      // last directory-search results, indexed by data-idx

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
        const add = `<button type="button" class="btn btn-primary" id="btn-onboard-user">
                <svg width="14" height="14" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M10 4v12M4 10h12"></path></svg>
                Add people</button>`;
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
        $('#btn-onboard-user').off('click').on('click', showOnboardModal);
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
                const userId = $(this).data('user-id');
                const user = allUsers.find(u => u.userId === userId);
                Modals.confirm('Deactivate user', `Deactivate "${user?.displayName || user?.email}"? They can no longer sign in.`, function() {
                    deactivateUser(userId);
                }, { confirmText: 'Deactivate', confirmClass: 'btn-danger' });
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

    /** Change role: one of Administrator, Environment admin or User (U13-T04 brings the full dialog). */
    function showChangeRoleModal(user) {
        if (!user) return;
        const current = user.admin ? 'ADMIN' : user.envAdmin ? 'ENV_ADMIN' : 'USER';
        const option = (value, title, text) => `<label class="um-role-choice"><input type="radio" name="um-role" value="${value}"${current === value ? ' checked' : ''}>
            <span><b>${title}</b><span>${text}</span></span></label>`;
        Modals.show({
            id: 'changeRoleModal',
            title: Utils.escapeHtml(`Change role of ${user.displayName || user.email}`),
            html: true,
            body: `<fieldset class="um-roles"><legend class="visually-hidden">Role</legend>
                ${option('ADMIN', 'Administrator', 'Everything, on every environment.')}
                ${option('ENV_ADMIN', 'Environment admin', 'Looks after environments: access, settings, schedules.')}
                ${option('USER', 'User', 'Uses the environments they are given access to.')}</fieldset>
                <div class="re-error" id="um-role-error" role="alert" hidden></div>`,
            buttons: [{ text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Change role', class: 'btn-primary', id: 'um-role-save' }],
            onShow: () => {
                $('input[name="um-role"]:checked').trigger('focus');
                $('#um-role-save').off('click').on('click', function() {
                    const role = $('input[name="um-role"]:checked').val();
                    const $btn = $(this).prop('disabled', true);
                    ApiClient.patch(Config.API.users.roles(user.userId), { admin: role === 'ADMIN', envAdmin: role === 'ENV_ADMIN' }, { suppressGlobalError: true })
                        .done(() => {
                            Modals.hide('changeRoleModal');
                            Notifications.success('Role changed');
                            fetchUsers();
                        })
                        .fail(xhr => {
                            $('#um-role-error').text(xhr.responseJSON?.message || 'The role was not changed.').prop('hidden', false);
                            $btn.prop('disabled', false);
                        });
                });
            }
        });
    }

    function deactivateUser(userId) {
        ApiClient.delete(Config.API.users.get(userId))
            .done(function() {
                Notifications.success('User deactivated');
                fetchUsers();
            })
            .fail(function(xhr) {
                Notifications.error(xhr.responseJSON?.message || 'Failed to deactivate user');
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

    function showSetPasswordModal(user) {
        if (!user) return;
        Modals.show({
            id: 'setPasswordModal',
            title: '<i class="fas fa-key me-2"></i>' + (user.hasPassword ? 'Reset Password' : 'Set Password'),
            html: true,
            body: `
                <p class="small text-muted mb-3">
                    Password sign-in for <strong>${Utils.escapeHtml(user.displayName || user.email)}</strong>
                    (username <code>${Utils.escapeHtml(user.username)}</code>). Entra ID sign-in is not affected.
                    Share the new password with the user over a separate, secure channel.
                </p>
                <div id="set-password-error" class="alert alert-danger py-2 px-3 small" hidden></div>
                <div class="mb-2">
                    <label class="form-label" for="set-password-new">New password</label>
                    <input type="password" class="form-control" id="set-password-new" autocomplete="new-password"
                           minlength="${PASSWORD_MIN}" maxlength="${PASSWORD_MAX}">
                    <div class="form-text">${PASSWORD_MIN}–${PASSWORD_MAX} characters.</div>
                </div>
                <div class="mb-2">
                    <label class="form-label" for="set-password-confirm">Confirm password</label>
                    <input type="password" class="form-control" id="set-password-confirm" autocomplete="new-password"
                           maxlength="${PASSWORD_MAX}">
                </div>`,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Save Password', class: 'btn-primary', id: 'set-password-submit' }
            ],
            onShow: function() {
                $('#set-password-new').trigger('focus');
                $('#set-password-submit').off('click').on('click', function() {
                    submitSetPassword(user);
                });
            }
        });
    }

    function submitSetPassword(user) {
        const password = $('#set-password-new').val() || '';
        const confirm = $('#set-password-confirm').val() || '';
        const $error = $('#set-password-error');

        let problem = null;
        if (password.length < PASSWORD_MIN || password.length > PASSWORD_MAX) {
            problem = `Password must be between ${PASSWORD_MIN} and ${PASSWORD_MAX} characters.`;
        } else if (password !== confirm) {
            problem = 'The passwords do not match.';
        }
        if (problem) {
            $error.text(problem).prop('hidden', false);
            return;
        }
        $error.prop('hidden', true);

        const $submit = $('#set-password-submit').prop('disabled', true);
        ApiClient.put(Config.API.users.setPassword(user.userId), { password: password })
            .done(function() {
                Modals.hide('setPasswordModal');
                Notifications.success('Password saved');
                fetchUsers();
            })
            .fail(function(xhr) {
                $error.text(xhr.responseJSON?.message || 'Failed to save password').prop('hidden', false);
            })
            .always(function() {
                $submit.prop('disabled', false);
            });
    }

    // ===================== Onboard User =====================

    function showOnboardModal() {
        onboardSelected = null;
        onboardManual = false;
        onboardEnvs = [];

        Modals.show({
            id: 'onboardUserModal',
            title: '<i class="fas fa-user-plus me-2"></i>Onboard User',
            html: true,
            size: 'lg',
            body: onboardModalBody(),
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Onboard', class: 'btn-primary', id: 'onboard-submit' }
            ],
            onShow: function() {
                bindOnboardEvents();
                loadOnboardEnvironments();
            }
        });
    }

    function onboardModalBody() {
        return `
            <div id="onboard-error" class="alert alert-danger py-2 px-3 small" hidden></div>

            <div id="onboard-dir-block">
                <label class="form-label" for="onboard-dir-search">Find in directory</label>
                <div class="onboard-autocomplete">
                    <input type="text" class="form-control" id="onboard-dir-search" autocomplete="off"
                           placeholder="Search name or email in Entra ID…">
                    <div class="onboard-autocomplete-menu" id="onboard-dir-results" hidden></div>
                </div>
                <div id="onboard-selected" class="onboard-selected" hidden></div>
                <div class="form-text">
                    Can't find them?
                    <a href="#" id="onboard-manual-toggle">Enter details manually</a>.
                </div>
            </div>

            <div id="onboard-manual-block" hidden>
                <div class="row g-2">
                    <div class="col-sm-7">
                        <label class="form-label" for="onboard-email">Email</label>
                        <input type="email" class="form-control" id="onboard-email" placeholder="person@company.com">
                    </div>
                    <div class="col-sm-5">
                        <label class="form-label" for="onboard-name">Display name</label>
                        <input type="text" class="form-control" id="onboard-name" placeholder="(defaults to email)">
                    </div>
                </div>
                <div class="form-text text-warning-emphasis">
                    <i class="fas fa-triangle-exclamation me-1"></i>Not checked against the directory — the row is marked
                    <em>Unverified</em> until this person signs in.
                    <a href="#" id="onboard-dir-toggle">Search the directory instead</a>.
                </div>
            </div>

            <hr class="my-3">

            <div class="d-flex gap-4">
                <div class="form-check">
                    <input class="form-check-input" type="checkbox" id="onboard-admin">
                    <label class="form-check-label" for="onboard-admin">Admin</label>
                </div>
                <div class="form-check">
                    <input class="form-check-input" type="checkbox" id="onboard-envadmin">
                    <label class="form-check-label" for="onboard-envadmin">Env Admin</label>
                </div>
            </div>

            <hr class="my-3">

            <div class="form-check mb-2">
                <input class="form-check-input" type="checkbox" id="onboard-grant-toggle">
                <label class="form-check-label" for="onboard-grant-toggle">Also grant environment access now</label>
            </div>
            <div id="onboard-grant-fields" hidden>
                <div class="row g-2">
                    <div class="col-sm-7">
                        <label class="form-label" for="onboard-grant-env">Environment</label>
                        <select class="form-select" id="onboard-grant-env"><option value="">Loading…</option></select>
                    </div>
                    <div class="col-sm-5">
                        <label class="form-label" for="onboard-grant-level">Access level</label>
                        <select class="form-select" id="onboard-grant-level">
                            <option value="VIEWER">Viewer</option>
                            <option value="USER" selected>User</option>
                            <option value="ADMIN">Admin</option>
                        </select>
                    </div>
                </div>
                <div class="mt-2">
                    <div class="form-check form-check-inline">
                        <input class="form-check-input" type="radio" name="onboard-scope" id="onboard-scope-env" value="ENVIRONMENT" checked>
                        <label class="form-check-label" for="onboard-scope-env">Whole environment</label>
                    </div>
                    <div class="form-check form-check-inline">
                        <input class="form-check-input" type="radio" name="onboard-scope" id="onboard-scope-grp" value="GROUP">
                        <label class="form-check-label" for="onboard-scope-grp">Specific groups</label>
                    </div>
                </div>
                <div id="onboard-group-checklist" class="onboard-group-checklist mt-2" hidden>
                    <input type="text" class="form-control form-control-sm mb-1" id="onboard-group-filter" placeholder="Filter groups…">
                    <ul id="onboard-group-list" class="list-unstyled mb-0"></ul>
                </div>
            </div>
        `;
    }

    function bindOnboardEvents() {
        $('#onboard-dir-search').off('input').on('input', Utils.debounce(function() {
            const q = $(this).val().trim();
            if (q.length < 2) { $('#onboard-dir-results').attr('hidden', true).empty(); return; }
            runDirectorySearch(q);
        }, 300));

        $('#onboard-manual-toggle').off('click').on('click', function(e) {
            e.preventDefault();
            enableManualMode(false);
        });
        $('#onboard-dir-toggle').off('click').on('click', function(e) {
            e.preventDefault();
            onboardManual = false;
            $('#onboard-manual-block').attr('hidden', true);
            $('#onboard-dir-block').removeAttr('hidden');
        });

        // delegate: pick a directory result
        $('#onboard-dir-results').off('click', '[data-oid]').on('click', '[data-oid]', function() {
            pickDirectoryUser(onboardResults[$(this).data('idx')]);
        });

        $('#onboard-selected').off('click', '.onboard-clear').on('click', '.onboard-clear', function(e) {
            e.preventDefault();
            clearDirectoryPick();
        });

        $('#onboard-grant-toggle').off('change').on('change', function() {
            document.getElementById('onboard-grant-fields').hidden = !this.checked;
        });
        $('input[name="onboard-scope"]').off('change').on('change', function() {
            const isGroup = onboardScope() === 'GROUP';
            document.getElementById('onboard-group-checklist').hidden = !isGroup;
            if (isGroup) loadOnboardGroups($('#onboard-grant-env').val());
        });
        $('#onboard-grant-env').off('change').on('change', function() {
            if (onboardScope() === 'GROUP') loadOnboardGroups(this.value);
        });
        $('#onboard-group-filter').off('input').on('input', function() {
            const q = this.value.trim().toLowerCase();
            $('#onboard-group-list li').each(function() {
                const name = ($(this).data('name') || '').toLowerCase();
                $(this).toggle(!q || name.includes(q));
            });
        });

        $('#onboard-submit').off('click').on('click', submitOnboard);
    }

    function runDirectorySearch(q) {
        const $results = $('#onboard-dir-results');
        $results.removeAttr('hidden').html('<div class="p-2 text-muted small"><i class="fas fa-spinner fa-spin me-1"></i>Searching…</div>');
        // suppressGlobalError: an expected 409 (lookup disabled) / 502 is handled inline here;
        // ApiClient's global handler would otherwise pop a misleading red toast.
        ApiClient.get(Config.API.directory.search(q), { suppressGlobalError: true })
            .done(function(list) {
                onboardResults = list || [];
                renderDirectoryResults(onboardResults);
            })
            .fail(function(xhr) {
                if (xhr.status === 409) {
                    enableManualMode(true);
                } else if (xhr.status === 502) {
                    $results.html('<div class="p-2 text-danger small">Directory lookup is unavailable right now. Enter details manually.</div>');
                } else {
                    $results.html('<div class="p-2 text-danger small">Search failed.</div>');
                }
            });
    }

    function renderDirectoryResults(list) {
        const $results = $('#onboard-dir-results');
        if (!list.length) {
            $results.html('<div class="p-2 text-muted small">No matches.</div>');
            return;
        }
        $results.html(list.map((u, idx) => `
            <button type="button" class="onboard-result" data-oid="${Utils.escapeHtml(u.directoryObjectId)}" data-idx="${idx}"
                    ${u.alreadyInApp ? 'disabled' : ''}>
                <span class="onboard-result-name">${Utils.escapeHtml(u.displayName || u.email || u.userPrincipalName || '')}</span>
                <span class="onboard-result-email">${Utils.escapeHtml(u.email || u.userPrincipalName || '')}</span>
                ${u.alreadyInApp ? '<span class="onboard-result-tag">already a user</span>' : ''}
            </button>
        `).join(''));
    }

    function pickDirectoryUser(u) {
        if (!u) return;
        onboardSelected = u;
        onboardManual = false;
        $('#onboard-dir-results').attr('hidden', true).empty();
        $('#onboard-dir-search').val('');
        $('#onboard-selected').removeAttr('hidden').html(`
            <div>
                <div class="onboard-selected-name">${Utils.escapeHtml(u.displayName || '')}</div>
                <div class="onboard-selected-email">${Utils.escapeHtml(u.email || u.userPrincipalName || '')}</div>
            </div>
            <a href="#" class="onboard-clear">change</a>
        `);
    }

    function clearDirectoryPick() {
        onboardSelected = null;
        $('#onboard-selected').attr('hidden', true).empty();
        $('#onboard-dir-search').val('').focus();
    }

    function enableManualMode(becauseDisabled) {
        onboardManual = true;
        onboardSelected = null;
        $('#onboard-dir-results').attr('hidden', true).empty();
        $('#onboard-selected').attr('hidden', true).empty();
        $('#onboard-dir-block').attr('hidden', true);
        $('#onboard-manual-block').removeAttr('hidden');
        if (becauseDisabled) {
            $('#onboard-dir-toggle').hide();
        }
    }

    function loadOnboardEnvironments() {
        ApiClient.get(Config.API.environments.list, { suppressGlobalError: true })
            .done(function(envs) {
                onboardEnvs = envs || [];
                const opts = ['<option value="">Select an environment…</option>']
                    .concat(onboardEnvs.map(e =>
                        `<option value="${Utils.escapeHtml(e.environmentId)}">${Utils.escapeHtml(e.displayName || e.name)}</option>`));
                $('#onboard-grant-env').html(opts.join(''));
            })
            .fail(function() {
                $('#onboard-grant-env').html('<option value="">Failed to load environments</option>');
            });
    }

    function onboardScope() {
        return $('input[name="onboard-scope"]:checked').val() || 'ENVIRONMENT';
    }

    function loadOnboardGroups(envId) {
        const $list = $('#onboard-group-list');
        if (!envId) { $list.html('<li class="text-muted small">Pick an environment first.</li>'); return; }
        $list.html('<li class="text-muted small"><i class="fas fa-spinner fa-spin me-1"></i>Loading groups…</li>');
        ApiClient.get(Config.API.groups.list(envId), { suppressGlobalError: true })
            .done(function(groups) {
                if (!groups || !groups.length) {
                    $list.html('<li class="text-muted small">This environment has no groups.</li>');
                    return;
                }
                $list.html(groups.map(g => `
                    <li data-name="${Utils.escapeHtml(g.displayName || g.name)}">
                        <label class="d-flex align-items-center gap-2 mb-1">
                            <input type="checkbox" class="onboard-group-cb" value="${Utils.escapeHtml(g.groupId)}">
                            <span>${Utils.escapeHtml(g.displayName || g.name)}</span>
                            <span class="text-muted small ms-auto">${g.vmCount || 0} VMs</span>
                        </label>
                    </li>`).join(''));
            })
            .fail(() => $list.html('<li class="text-danger small">Failed to load groups.</li>'));
    }

    function onboardError(msg) {
        $('#onboard-error').text(msg).removeAttr('hidden');
    }

    function clearOnboardError() {
        $('#onboard-error').attr('hidden', true).empty();
    }

    function submitOnboard() {
        clearOnboardError();
        const payload = {};

        if (onboardManual) {
            payload.email = $('#onboard-email').val().trim();
            payload.displayName = $('#onboard-name').val().trim() || undefined;
            if (!payload.email) { onboardError('Email is required.'); return; }
        } else {
            if (!onboardSelected) { onboardError('Pick someone from the directory, or switch to manual entry.'); return; }
            payload.directoryObjectId = onboardSelected.directoryObjectId;
            payload.email = onboardSelected.email || onboardSelected.userPrincipalName;
            payload.displayName = onboardSelected.displayName || undefined;
        }

        payload.admin = $('#onboard-admin').is(':checked');
        payload.envAdmin = $('#onboard-envadmin').is(':checked');

        if ($('#onboard-grant-toggle').is(':checked')) {
            const environmentId = $('#onboard-grant-env').val();
            if (!environmentId) { onboardError('Choose an environment for the grant, or turn off "grant access".'); return; }
            const scopeType = onboardScope();
            const grant = { environmentId, accessLevel: $('#onboard-grant-level').val(), scopeType };
            if (scopeType === 'GROUP') {
                grant.groupIds = $('#onboard-group-list .onboard-group-cb:checked').map((i, el) => el.value).get();
                if (!grant.groupIds.length) { onboardError('Select at least one group, or use "Whole environment".'); return; }
            }
            payload.initialGrant = grant;
        }

        const $btn = $('#onboard-submit').prop('disabled', true);
        // suppressGlobalError: 409 (already a user) / 400 (validation) are shown inline in the
        // modal; the global toast would duplicate or mislead.
        ApiClient.post(Config.API.users.create, payload, { suppressGlobalError: true })
            .done(function(res) {
                const name = res.user?.displayName || res.user?.email || 'user';
                const grantMsg = res.grants && res.grants.length ? ` with ${res.grants.length} access grant(s)` : '';
                Notifications.success(`Onboarded ${name}${grantMsg}.`);
                Modals.hide('onboardUserModal');
                fetchUsers();
            })
            .fail(function(xhr) {
                const j = xhr.responseJSON || {};
                if (xhr.status === 409 && j.error === 'user_inactive') {
                    onboardError('A deactivated user with this identity already exists — reactivate them from the list instead.');
                } else if (xhr.status === 409) {
                    onboardError('This person already has an account.');
                } else {
                    onboardError(j.message || 'Onboarding failed.');
                }
                $btn.prop('disabled', false);
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
        model: { roleLabel, signInLabel, signsInWithPasswordOnly, counts, lastSignIn, filterList }
    };
})();

// Make available globally
window.UserManagement = UserManagement;

