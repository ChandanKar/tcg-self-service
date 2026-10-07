/**
 * User Management Feature Module
 * Handles admin user management with pagination, search, and filters
 */
const UserManagement = (function() {
    'use strict';

    // Constants
    const PAGE_SIZE = 10;

    // State
    let allUsers = [];
    let filteredUsers = [];
    let currentPage = 1;
    let currentSearch = '';
    let currentRoleFilter = '';
    let currentStatusFilter = '';

    // Onboard-modal state
    let onboardSelected = null;   // picked directory user, or null
    let onboardManual = false;    // manual-entry mode (directory disabled / not found)
    let onboardEnvs = [];         // environments for the optional grant picker
    let onboardResults = [];      // last directory-search results, indexed by data-idx

    /**
     * Initialize and load User Management view
     */
    function load() {
        if (!Auth.isAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3">Access denied. Admin only.</div>');
            return;
        }

        showLoading();
        fetchUsers();
    }

    /**
     * Show loading state
     */
    function showLoading() {
        $('#content-area').html(`
            <div class="d-flex justify-content-center align-items-center" style="height: 200px;">
                <div class="spinner-border text-primary" role="status">
                    <span class="visually-hidden">Loading...</span>
                </div>
            </div>
        `);
    }

    /**
     * Fetch users from API
     */
    function fetchUsers() {
        ApiClient.get(Config.API.users.list)
            .done(function(users) {
                allUsers = users || [];
                filteredUsers = [...allUsers];
                currentPage = 1;
                render();
            })
            .fail(function(xhr) {
                $('#content-area').html(`
                    <div class="content-view">
                        <div class="content-header">
                            <h1>User Management</h1>
                        </div>
                        <div class="alert alert-danger">
                            <i class="fas fa-exclamation-circle me-2"></i>
                            Failed to load users: ${xhr.responseJSON?.message || 'Unknown error'}
                        </div>
                    </div>
                `);
            });
    }

    /**
     * Render the complete view
     */
    function render() {
        const html = buildViewHtml();
        $('#content-area').html(html);
        renderTable();
        renderPagination();
        bindEvents();
    }

    /**
     * Build main view HTML structure
     */
    function buildViewHtml() {
        const stats = calculateStats();

        return `
            <div class="content-view" id="user-management-view">
                <!-- Header -->
                <div class="content-header">
                    <div class="d-flex justify-content-between align-items-start">
                        <div>
                            <h1>User Management</h1>
                            <p>Manage platform users and their roles</p>
                        </div>
                        <div class="header-actions">
                            <button class="btn btn-primary btn-sm" id="btn-onboard-user">
                                <i class="fas fa-user-plus me-1"></i> Onboard User
                            </button>
                            <button class="btn btn-ghost btn-sm" id="btn-refresh-users" title="Refresh">
                                <i class="fas fa-sync-alt"></i>
                            </button>
                        </div>
                    </div>
                </div>

                <!-- Filters Bar -->
                <div class="user-filters-bar">
                    <div class="search-input-wrapper">
                        <div class="input-group input-group-sm">
                            <span class="input-group-text"><i class="fas fa-search"></i></span>
                            <input type="text" class="form-control" id="user-search"
                                   placeholder="Search by name or email..." value="${escapeHtml(currentSearch)}">
                            <button class="btn btn-primary" type="button" id="btn-search-users" title="Search">
                                <i class="fas fa-arrow-right"></i>
                            </button>
                        </div>
                    </div>
                    <select class="form-select form-select-sm" id="filter-role">
                        <option value="">All Roles</option>
                        <option value="ADMIN" ${currentRoleFilter === 'ADMIN' ? 'selected' : ''}>Admin</option>
                        <option value="ENV_ADMIN" ${currentRoleFilter === 'ENV_ADMIN' ? 'selected' : ''}>Env Admin</option>
                        <option value="USER" ${currentRoleFilter === 'USER' ? 'selected' : ''}>User</option>
                    </select>
                    <select class="form-select form-select-sm" id="filter-status">
                        <option value="">All Status</option>
                        <option value="active" ${currentStatusFilter === 'active' ? 'selected' : ''}>Active</option>
                        <option value="inactive" ${currentStatusFilter === 'inactive' ? 'selected' : ''}>Inactive</option>
                    </select>
                </div>

                <!-- Stats Row -->
                <div class="user-stats-row">
                    <div class="user-stat-item">
                        <span class="stat-label">Total:</span>
                        <span class="stat-value">${stats.total}</span>
                    </div>
                    <div class="user-stat-item">
                        <span class="stat-label">Active:</span>
                        <span class="stat-value text-success">${stats.active}</span>
                    </div>
                    <div class="user-stat-item">
                        <span class="stat-label">Admins:</span>
                        <span class="stat-value text-primary">${stats.admins}</span>
                    </div>
                    <div class="user-stat-item">
                        <span class="stat-label">Env Admins:</span>
                        <span class="stat-value text-warning">${stats.envAdmins}</span>
                    </div>
                </div>

                <!-- Table Container -->
                <div class="user-table-card">
                    <div class="user-table-wrapper">
                        <table class="table table-hover user-table mb-0">
                            <thead>
                                <tr>
                                    <th>User</th>
                                    <th>Role</th>
                                    <th>Status</th>
                                    <th>Last Login</th>
                                    <th class="text-end">Actions</th>
                                </tr>
                            </thead>
                            <tbody id="users-table-body">
                                <!-- Populated by renderTable() -->
                            </tbody>
                        </table>
                    </div>
                    <div id="user-pagination" class="pagination-bar-wrap"></div>
                </div>
            </div>
        `;
    }

    /**
     * Calculate user statistics
     */
    function calculateStats() {
        return {
            total: allUsers.length,
            active: allUsers.filter(u => u.active).length,
            admins: allUsers.filter(u => u.admin).length,
            envAdmins: allUsers.filter(u => u.envAdmin && !u.admin).length
        };
    }

    /**
     * Render table rows for current page
     */
    function renderTable() {
        const startIndex = (currentPage - 1) * PAGE_SIZE;
        const endIndex = startIndex + PAGE_SIZE;
        const pageUsers = filteredUsers.slice(startIndex, endIndex);

        if (pageUsers.length === 0) {
            $('#users-table-body').html(`
                <tr>
                    <td colspan="5" class="text-center text-muted py-4">
                        <i class="fas fa-users fa-2x mb-2 d-block"></i>
                        No users found matching your criteria
                    </td>
                </tr>
            `);
            return;
        }

        const rows = pageUsers.map(user => buildUserRow(user)).join('');
        $('#users-table-body').html(rows);

        // Initialize tooltips
        initTooltips();
    }

    /**
     * Build a single user row
     */
    function buildUserRow(user) {
        const roleClass = user.admin ? 'role-badge-admin' : user.envAdmin ? 'role-badge-env-admin' : 'role-badge-user';
        const roleLabel = user.admin ? 'Admin' : user.envAdmin ? 'Env Admin' : 'User';
        const statusClass = user.active ? 'bg-success' : 'bg-secondary';
        const statusLabel = user.active ? 'Active' : 'Inactive';
        const initials = getInitials(user.displayName || user.email);
        const lastLogin = user.lastLoginAt ? Utils.formatRelativeTime(user.lastLoginAt) : 'Never';

        const onboardBadges = [
            user.pendingFirstLogin
                ? '<span class="onboard-badge onboard-badge-pending" title="Onboarded from the panel — has not signed in yet">Not signed in</span>'
                : '',
            user.unverified
                ? '<span class="onboard-badge onboard-badge-unverified" title="Added by manual entry — not checked against the Entra directory">Unverified</span>'
                : ''
        ].join('');

        return `
            <tr data-user-id="${user.userId}">
                <td>
                    <div class="user-cell">
                        <div class="user-avatar-sm">${initials}</div>
                        <div class="user-info">
                            <div class="user-name">${escapeHtml(user.displayName || 'Unknown')}</div>
                            <div class="user-email">${escapeHtml(user.email)}</div>
                            ${onboardBadges ? `<div class="user-onboard-badges">${onboardBadges}</div>` : ''}
                        </div>
                    </div>
                </td>
                <td><span class="role-badge ${roleClass}">${roleLabel}</span></td>
                <td><span class="badge ${statusClass}">${statusLabel}</span></td>
                <td class="text-muted">${lastLogin}</td>
                <td class="text-end">
                    <div class="btn-group btn-group-sm">
                        <button class="btn btn-outline-secondary dropdown-toggle" data-bs-toggle="dropdown"
                                aria-expanded="false">
                            Actions
                        </button>
                        <ul class="dropdown-menu dropdown-menu-end">
                            <li>
                                <a class="dropdown-item" href="#" data-action="toggle-admin" data-user-id="${user.userId}">
                                    <i class="fas fa-user-shield me-2"></i>
                                    ${user.admin ? 'Remove Admin' : 'Make Admin'}
                                </a>
                            </li>
                            <li>
                                <a class="dropdown-item" href="#" data-action="toggle-env-admin" data-user-id="${user.userId}">
                                    <i class="fas fa-user-cog me-2"></i>
                                    ${user.envAdmin ? 'Remove Env Admin' : 'Make Env Admin'}
                                </a>
                            </li>
                            ${user.username ? `
                            <li>
                                <a class="dropdown-item" href="#" data-action="set-password" data-user-id="${user.userId}">
                                    <i class="fas fa-key me-2"></i>
                                    ${user.hasPassword ? 'Reset Password' : 'Set Password'}
                                </a>
                            </li>` : ''}
                            <li><hr class="dropdown-divider"></li>
                            <li>
                                <a class="dropdown-item ${user.active ? 'text-danger' : 'text-success'}" href="#"
                                   data-action="${user.active ? 'deactivate' : 'reactivate'}" data-user-id="${user.userId}">
                                    <i class="fas ${user.active ? 'fa-user-slash' : 'fa-user-check'} me-2"></i>
                                    ${user.active ? 'Deactivate' : 'Reactivate'}
                                </a>
                            </li>
                        </ul>
                    </div>
                </td>
            </tr>
        `;
    }

    /**
     * Render pagination controls
     */
    function renderPagination() {
        Pagination.renderNumbered('#user-pagination', {
            page: currentPage,
            totalItems: filteredUsers.length,
            pageSize: PAGE_SIZE,
            itemLabel: 'users',
            onPageChange: (target) => {
                currentPage = target;
                renderTable();
                renderPagination();
                $('.user-table-wrapper').scrollTop(0);
            }
        });
    }

    /**
     * Apply filters and search
     */
    function applyFilters() {
        const search = currentSearch.toLowerCase().trim();

        filteredUsers = allUsers.filter(user => {
            // Search filter
            if (search) {
                const name = (user.displayName || '').toLowerCase();
                const email = (user.email || '').toLowerCase();
                if (!name.includes(search) && !email.includes(search)) {
                    return false;
                }
            }

            // Role filter
            if (currentRoleFilter) {
                if (currentRoleFilter === 'ADMIN' && !user.admin) return false;
                if (currentRoleFilter === 'ENV_ADMIN' && (!user.envAdmin || user.admin)) return false;
                if (currentRoleFilter === 'USER' && (user.admin || user.envAdmin)) return false;
            }

            // Status filter
            if (currentStatusFilter) {
                if (currentStatusFilter === 'active' && !user.active) return false;
                if (currentStatusFilter === 'inactive' && user.active) return false;
            }

            return true;
        });

        // Reset to page 1 when filters change
        currentPage = 1;
        renderTable();
        renderPagination();
    }

    /**
     * Bind event handlers
     */
    function bindEvents() {
        // Refresh button
        $('#btn-refresh-users').off('click').on('click', function() {
            const $btn = $(this);
            $btn.find('i').addClass('fa-spin');
            fetchUsers();
            setTimeout(() => $btn.find('i').removeClass('fa-spin'), 500);
        });

        // Onboard User button
        $('#btn-onboard-user').off('click').on('click', showOnboardModal);

        // Search input - auto search on typing (3+ chars with debounce)
        $('#user-search').off('input').on('input', Utils.debounce(function() {
            const val = $(this).val();
            if (val.length >= 3 || val.length === 0) {
                currentSearch = val;
                applyFilters();
            }
        }, 300));

        // Search input - Enter key for immediate search
        $('#user-search').off('keypress').on('keypress', function(e) {
            if (e.which === 13) {
                e.preventDefault();
                currentSearch = $(this).val();
                applyFilters();
            }
        });

        // Search button click
        $('#btn-search-users').off('click').on('click', function() {
            currentSearch = $('#user-search').val();
            applyFilters();
        });

        // Role filter
        $('#filter-role').off('change').on('change', function() {
            currentRoleFilter = $(this).val();
            applyFilters();
        });

        // Status filter
        $('#filter-status').off('change').on('change', function() {
            currentStatusFilter = $(this).val();
            applyFilters();
        });

        // Action buttons
        bindActionEvents();
    }

    /**
     * Bind action button events
     */
    function bindActionEvents() {
        // Toggle Admin
        $(document).off('click', '[data-action="toggle-admin"]').on('click', '[data-action="toggle-admin"]', function(e) {
            e.preventDefault();
            const userId = $(this).data('user-id');
            toggleAdmin(userId);
        });

        // Toggle Env Admin
        $(document).off('click', '[data-action="toggle-env-admin"]').on('click', '[data-action="toggle-env-admin"]', function(e) {
            e.preventDefault();
            const userId = $(this).data('user-id');
            toggleEnvAdmin(userId);
        });

        // Deactivate
        $(document).off('click', '[data-action="deactivate"]').on('click', '[data-action="deactivate"]', function(e) {
            e.preventDefault();
            const userId = $(this).data('user-id');
            const user = allUsers.find(u => u.userId === userId);
            Modals.confirm('Deactivate User', `Deactivate user "${user?.displayName || user?.email}"?`, function() {
                deactivateUser(userId);
            }, { confirmText: 'Deactivate', confirmClass: 'btn-danger' });
        });

        // Reactivate
        $(document).off('click', '[data-action="reactivate"]').on('click', '[data-action="reactivate"]', function(e) {
            e.preventDefault();
            const userId = $(this).data('user-id');
            reactivateUser(userId);
        });

        // Set / reset password (password sign-in)
        $(document).off('click', '[data-action="set-password"]').on('click', '[data-action="set-password"]', function(e) {
            e.preventDefault();
            const userId = $(this).data('user-id');
            showSetPasswordModal(allUsers.find(u => u.userId === userId));
        });
    }

    /**
     * Toggle admin status
     */
    function toggleAdmin(userId) {
        ApiClient.patch(Config.API.users.setAdmin(userId))
            .done(function() {
                Notifications.success('Admin status updated');
                fetchUsers();
            })
            .fail(function(xhr) {
                Notifications.error(xhr.responseJSON?.message || 'Failed to update admin status');
            });
    }

    /**
     * Toggle env admin status
     */
    function toggleEnvAdmin(userId) {
        ApiClient.patch(Config.API.users.setEnvAdmin(userId))
            .done(function() {
                Notifications.success('Environment admin status updated');
                fetchUsers();
            })
            .fail(function(xhr) {
                Notifications.error(xhr.responseJSON?.message || 'Failed to update env admin status');
            });
    }

    /**
     * Deactivate user
     */
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

    /**
     * Reactivate user
     */
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
            body: `
                <p class="small text-muted mb-3">
                    Password sign-in for <strong>${escapeHtml(user.displayName || user.email)}</strong>
                    (username <code>${escapeHtml(user.username)}</code>). Entra ID sign-in is not affected.
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
            <button type="button" class="onboard-result" data-oid="${escapeHtml(u.directoryObjectId)}" data-idx="${idx}"
                    ${u.alreadyInApp ? 'disabled' : ''}>
                <span class="onboard-result-name">${escapeHtml(u.displayName || u.email || u.userPrincipalName || '')}</span>
                <span class="onboard-result-email">${escapeHtml(u.email || u.userPrincipalName || '')}</span>
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
                <div class="onboard-selected-name">${escapeHtml(u.displayName || '')}</div>
                <div class="onboard-selected-email">${escapeHtml(u.email || u.userPrincipalName || '')}</div>
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
                        `<option value="${escapeHtml(e.environmentId)}">${escapeHtml(e.displayName || e.name)}</option>`));
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
                    <li data-name="${escapeHtml(g.displayName || g.name)}">
                        <label class="d-flex align-items-center gap-2 mb-1">
                            <input type="checkbox" class="onboard-group-cb" value="${escapeHtml(g.groupId)}">
                            <span>${escapeHtml(g.displayName || g.name)}</span>
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

    /**
     * Escape HTML
     */
    function escapeHtml(str) {
        if (!str) return '';
        const div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    }

    /**
     * Initialize Bootstrap tooltips
     */
    function initTooltips() {
        if (typeof bootstrap !== 'undefined' && bootstrap.Tooltip) {
            document.querySelectorAll('#users-table-body [data-bs-toggle="tooltip"]').forEach(el => {
                new bootstrap.Tooltip(el, { trigger: 'hover', delay: { show: 300, hide: 100 } });
            });
        }
    }

    // Public API
    return {
        load: load
    };
})();

// Make available globally
window.UserManagement = UserManagement;

