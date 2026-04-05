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
                            <button class="btn btn-outline-secondary btn-sm" id="btn-refresh-users" title="Refresh">
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
                    <div id="user-pagination" class="user-pagination"></div>
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
        const roleClass = user.admin ? 'role-admin' : user.envAdmin ? 'role-env-admin' : 'role-user';
        const roleLabel = user.admin ? 'Admin' : user.envAdmin ? 'Env Admin' : 'User';
        const statusClass = user.active ? 'bg-success' : 'bg-secondary';
        const statusLabel = user.active ? 'Active' : 'Inactive';
        const initials = getInitials(user.displayName || user.email);
        const lastLogin = user.lastLoginAt ? Utils.formatRelativeTime(user.lastLoginAt) : 'Never';

        return `
            <tr data-user-id="${user.userId}">
                <td>
                    <div class="user-cell">
                        <div class="user-avatar-sm">${initials}</div>
                        <div class="user-info">
                            <div class="user-name">${escapeHtml(user.displayName || 'Unknown')}</div>
                            <div class="user-email">${escapeHtml(user.email)}</div>
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
        const totalItems = filteredUsers.length;
        const totalPages = Math.ceil(totalItems / PAGE_SIZE);

        if (totalItems <= PAGE_SIZE) {
            $('#user-pagination').html(
                `<div class="pagination-info">Showing ${totalItems} user${totalItems !== 1 ? 's' : ''}</div>`
            );
            return;
        }

        const start = (currentPage - 1) * PAGE_SIZE + 1;
        const end = Math.min(currentPage * PAGE_SIZE, totalItems);

        // Build page buttons
        const rangeStart = Math.max(1, currentPage - 2);
        const rangeEnd = Math.min(totalPages, currentPage + 2);

        let pageButtons = '';
        for (let i = rangeStart; i <= rangeEnd; i++) {
            pageButtons += `
                <button class="btn btn-sm ${i === currentPage ? 'btn-primary' : 'btn-outline-secondary'} page-btn"
                        data-page="${i}">${i}</button>
            `;
        }

        $('#user-pagination').html(`
            <div class="pagination-info">Showing ${start}–${end} of ${totalItems} users</div>
            <div class="pagination-controls">
                <button class="btn btn-sm btn-outline-secondary page-btn" data-page="1"
                        ${currentPage === 1 ? 'disabled' : ''} title="First">
                    <i class="fas fa-angle-double-left"></i>
                </button>
                <button class="btn btn-sm btn-outline-secondary page-btn" data-page="${currentPage - 1}"
                        ${currentPage === 1 ? 'disabled' : ''} title="Previous">
                    <i class="fas fa-chevron-left"></i>
                </button>
                ${pageButtons}
                <button class="btn btn-sm btn-outline-secondary page-btn" data-page="${currentPage + 1}"
                        ${currentPage === totalPages ? 'disabled' : ''} title="Next">
                    <i class="fas fa-chevron-right"></i>
                </button>
                <button class="btn btn-sm btn-outline-secondary page-btn" data-page="${totalPages}"
                        ${currentPage === totalPages ? 'disabled' : ''} title="Last">
                    <i class="fas fa-angle-double-right"></i>
                </button>
            </div>
        `);
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

        // Pagination
        $('#user-pagination').off('click', '.page-btn').on('click', '.page-btn', function() {
            const page = parseInt($(this).data('page'));
            if (page && page !== currentPage) {
                currentPage = page;
                renderTable();
                renderPagination();
                // Scroll to top of table
                $('.user-table-wrapper').scrollTop(0);
            }
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
            if (confirm(`Deactivate user "${user?.displayName || user?.email}"?`)) {
                deactivateUser(userId);
            }
        });

        // Reactivate
        $(document).off('click', '[data-action="reactivate"]').on('click', '[data-action="reactivate"]', function(e) {
            e.preventDefault();
            const userId = $(this).data('user-id');
            reactivateUser(userId);
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

