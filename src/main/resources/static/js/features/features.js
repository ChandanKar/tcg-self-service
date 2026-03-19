/**
 * VM Self-Service Platform - Additional Features
 * Placeholder implementations for various features
 * These use mock data and will be connected to APIs later
 */

const Features = (function() {
    'use strict';


    /**
     * Load Access Management view (Admin)
     */
    function loadAccessManagement() {
        const html = `
            <div class="content-header">
                <h1>Access Management</h1>
                <p>Grant or revoke user access to environments</p>
            </div>

            <div class="row mb-3">
                <div class="col-md-6">
                    <input type="text" class="form-control" placeholder="Search users by name or email...">
                </div>
                <div class="col-md-4">
                    <select class="form-select">
                        <option>All Environments</option>
                        <option>mcube-demo-env</option>
                        <option>analytics-sandbox</option>
                    </select>
                </div>
                <div class="col-md-2">
                    <button class="btn btn-primary w-100"><i class="fas fa-plus"></i> Grant Access</button>
                </div>
            </div>

            <h5 class="mb-3">Environment: mcube-demo-env</h5>
            <div class="custom-table mb-4">
                <table class="table">
                    <thead>
                        <tr>
                            <th>User</th>
                            <th>Role</th>
                            <th>Granted By</th>
                            <th>Granted Date</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr>
                            <td>john.doe@company.com</td>
                            <td><span class="badge bg-primary">Owner</span></td>
                            <td>System</td>
                            <td>3 months ago</td>
                            <td>-</td>
                        </tr>
                        <tr>
                            <td>jane.smith@company.com</td>
                            <td><span class="badge bg-secondary">User</span></td>
                            <td>admin</td>
                            <td>1 month ago</td>
                            <td><button class="btn btn-sm btn-danger">Revoke</button></td>
                        </tr>
                        <tr>
                            <td>bob.jones@company.com</td>
                            <td><span class="badge bg-secondary">User</span></td>
                            <td>john.doe</td>
                            <td>2 weeks ago</td>
                            <td><button class="btn btn-sm btn-danger">Revoke</button></td>
                        </tr>
                    </tbody>
                </table>
            </div>

            <h5 class="mb-3">Pending Access Requests (2)</h5>
            <div class="custom-table">
                <table class="table">
                    <thead>
                        <tr>
                            <th>User</th>
                            <th>Environment</th>
                            <th>Requested</th>
                            <th>Justification</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr>
                            <td>alice.wang@company.com</td>
                            <td>mcube-demo-env</td>
                            <td>2 days ago</td>
                            <td>Need access for feature development</td>
                            <td>
                                <button class="btn btn-sm btn-success me-1">Approve</button>
                                <button class="btn btn-sm btn-danger">Deny</button>
                            </td>
                        </tr>
                        <tr>
                            <td>chris.lee@company.com</td>
                            <td>analytics-sandbox</td>
                            <td>1 day ago</td>
                            <td>Testing new analytics pipeline</td>
                            <td>
                                <button class="btn btn-sm btn-success me-1">Approve</button>
                                <button class="btn btn-sm btn-danger">Deny</button>
                            </td>
                        </tr>
                    </tbody>
                </table>
            </div>
        `;
        $('#content-area').html(html);
    }

    // VM Registry feature moved to js/features/vm-registry.js (VmRegistry module)
    // Router now calls VmRegistry.load() directly. This shim keeps backward compatibility.
    function loadVmRegistry() { return window.VmRegistry ? VmRegistry.load() : undefined; }


    /**
     * Load Automation Rules view
     */
    function loadAutomationRules() {
        const html = `
            <div class="content-header">
                <h1>Automation Rules</h1>
                <p>Schedule automatic start/stop operations</p>
            </div>

            <div class="mb-3">
                <button class="btn btn-primary"><i class="fas fa-plus"></i> Create Rule</button>
            </div>

            <h5 class="mb-3">Active Rules (3)</h5>

            <div class="card mb-3">
                <div class="card-body">
                    <div class="d-flex justify-content-between align-items-start">
                        <div>
                            <h5 class="card-title">Nightly Shutdown</h5>
                            <p class="mb-1"><strong>Environment:</strong> mcube-demo-env</p>
                            <p class="mb-1"><strong>Action:</strong> <span class="badge bg-danger">STOP_ALL</span></p>
                            <p class="mb-1"><strong>Schedule:</strong> Every day at 7:00 PM EST</p>
                            <p class="mb-1"><strong>Status:</strong> <span class="badge bg-success">Active</span></p>
                            <p class="mb-1 text-muted">Last Run: Today at 7:00 PM (Success)</p>
                        </div>
                        <div>
                            <button class="btn btn-sm btn-primary me-1">Edit</button>
                            <button class="btn btn-sm btn-warning me-1">Disable</button>
                            <button class="btn btn-sm btn-danger">Delete</button>
                        </div>
                    </div>
                </div>
            </div>

            <div class="card mb-3">
                <div class="card-body">
                    <div class="d-flex justify-content-between align-items-start">
                        <div>
                            <h5 class="card-title">Morning Startup</h5>
                            <p class="mb-1"><strong>Environment:</strong> mcube-demo-env</p>
                            <p class="mb-1"><strong>Action:</strong> <span class="badge bg-success">START_ALL</span></p>
                            <p class="mb-1"><strong>Schedule:</strong> Weekdays at 8:00 AM EST</p>
                            <p class="mb-1"><strong>Status:</strong> <span class="badge bg-success">Active</span></p>
                            <p class="mb-1 text-muted">Last Run: Today at 8:00 AM (Success)</p>
                        </div>
                        <div>
                            <button class="btn btn-sm btn-primary me-1">Edit</button>
                            <button class="btn btn-sm btn-warning me-1">Disable</button>
                            <button class="btn btn-sm btn-danger">Delete</button>
                        </div>
                    </div>
                </div>
            </div>
        `;
        $('#content-area').html(html);
    }

    /**
     * Load System Health view
     */
    function loadSystemHealth() {
        const html = `
            <div class="content-header">
                <h1>System Health</h1>
                <p>Monitor platform health and state synchronization</p>
            </div>

            <div class="row mb-4">
                <div class="col-md-3">
                    <div class="metric-card">
                        <div class="metric-title">API Status</div>
                        <div class="metric-value text-success">
                            <i class="fas fa-check-circle"></i>
                        </div>
                        <div class="metric-subtitle">All services healthy</div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="metric-card">
                        <div class="metric-title">Last State Sync</div>
                        <div class="metric-value">2m ago</div>
                        <div class="metric-subtitle">47 VMs synced</div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="metric-card">
                        <div class="metric-title">Drift Detected</div>
                        <div class="metric-value text-warning">2</div>
                        <div class="metric-subtitle">In last 24 hours</div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="metric-card">
                        <div class="metric-title">Pending Operations</div>
                        <div class="metric-value">0</div>
                        <div class="metric-subtitle">All operations complete</div>
                    </div>
                </div>
            </div>

            <h5 class="mb-3">Cloud Provider Connectivity</h5>
            <div class="custom-table mb-4">
                <table class="table">
                    <thead>
                        <tr>
                            <th>Provider</th>
                            <th>Region</th>
                            <th>Status</th>
                            <th>Last Check</th>
                            <th>Response Time</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr>
                            <td><i class="fab fa-aws text-warning"></i> AWS</td>
                            <td>us-east-1</td>
                            <td><span class="badge bg-success">Connected</span></td>
                            <td>30s ago</td>
                            <td>45ms</td>
                        </tr>
                        <tr>
                            <td><i class="fab fa-aws text-warning"></i> AWS</td>
                            <td>eu-west-1</td>
                            <td><span class="badge bg-success">Connected</span></td>
                            <td>30s ago</td>
                            <td>120ms</td>
                        </tr>
                        <tr>
                            <td><i class="fab fa-microsoft text-primary"></i> Azure</td>
                            <td>East US</td>
                            <td><span class="badge bg-success">Connected</span></td>
                            <td>30s ago</td>
                            <td>55ms</td>
                        </tr>
                        <tr>
                            <td><i class="fab fa-google text-info"></i> GCP</td>
                            <td>us-central1</td>
                            <td><span class="badge bg-warning">Degraded</span></td>
                            <td>30s ago</td>
                            <td>350ms</td>
                        </tr>
                    </tbody>
                </table>
            </div>

            <div class="d-flex gap-2">
                <button class="btn btn-primary"><i class="fas fa-sync"></i> Trigger State Sync</button>
                <button class="btn btn-secondary"><i class="fas fa-download"></i> Download Health Report</button>
            </div>
        `;
        $('#content-area').html(html);
    }

    function showLoading() {
        $('#content-area').html(`
            <div class="loading-state">
                <div class="spinner-border text-primary" role="status"></div>
                <p>Loading...</p>
            </div>
        `);
    }

    /**
     * Load User Management view (Admin only)
     */
    function loadUserManagement() {
        if (!Auth.isAdmin()) {
            $('#content-area').html('<div class="alert alert-danger">Access denied. Admin only.</div>');
            return;
        }

        showLoading();

        ApiClient.get(Config.API.users.list)
            .done(function(users) {
                const html = buildUserManagementHtml(users);
                $('#content-area').html(html);
                bindUserManagementEvents();
            })
            .fail(function() {
                $('#content-area').html(`
                    <div class="content-header">
                        <h1>User Management</h1>
                    </div>
                    <div class="alert alert-danger">Failed to load users</div>
                `);
            });
    }

    function buildUserManagementHtml(users) {
        const rows = users.map(user => {
            const roleClass = user.admin ? 'role-badge-admin' :
                             user.envAdmin ? 'role-badge-env-admin' : 'role-badge-user';
            const roleLabel = user.admin ? 'Admin' :
                             user.envAdmin ? 'Env Admin' : 'User';

            return `
                <tr>
                    <td>
                        <strong>${Utils.escapeHtml(user.displayName)}</strong>
                        <br><small class="text-muted">${Utils.escapeHtml(user.email)}</small>
                    </td>
                    <td><span class="role-badge ${roleClass}">${roleLabel}</span></td>
                    <td>
                        <span class="badge ${user.active ? 'bg-success' : 'bg-secondary'}">
                            ${user.active ? 'Active' : 'Inactive'}
                        </span>
                    </td>
                    <td>${user.lastLoginAt ? Utils.formatRelativeTime(user.lastLoginAt) : 'Never'}</td>
                    <td>
                        <div class="btn-group btn-group-sm">
                            <button class="btn btn-outline-primary dropdown-toggle" data-bs-toggle="dropdown">
                                Actions
                            </button>
                            <ul class="dropdown-menu">
                                <li>
                                    <a class="dropdown-item" href="#" data-user-id="${user.userId}" data-action="toggle-admin">
                                        ${user.admin ? 'Remove Admin' : 'Make Admin'}
                                    </a>
                                </li>
                                <li>
                                    <a class="dropdown-item" href="#" data-user-id="${user.userId}" data-action="toggle-env-admin">
                                        ${user.envAdmin ? 'Remove Env Admin' : 'Make Env Admin'}
                                    </a>
                                </li>
                                <li><hr class="dropdown-divider"></li>
                                <li>
                                    <a class="dropdown-item ${user.active ? 'text-danger' : 'text-success'}" href="#"
                                       data-user-id="${user.userId}" data-action="${user.active ? 'deactivate' : 'reactivate'}">
                                        ${user.active ? 'Deactivate' : 'Reactivate'}
                                    </a>
                                </li>
                            </ul>
                        </div>
                    </td>
                </tr>
            `;
        }).join('');

        return `
            <div class="content-header">
                <h1>User Management</h1>
                <p>Manage users and their roles</p>
            </div>
            <div class="mb-3">
                <input type="text" class="form-control" id="user-search" placeholder="Search users...">
            </div>
            <div class="custom-table">
                <table class="table table-hover">
                    <thead>
                        <tr>
                            <th>User</th>
                            <th>Role</th>
                            <th>Status</th>
                            <th>Last Login</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody id="users-table-body">
                        ${rows}
                    </tbody>
                </table>
            </div>
        `;
    }

    function bindUserManagementEvents() {
        $('#user-search').off('input').on('input', Utils.debounce(function() {
            const query = $(this).val().toLowerCase();
            $('#users-table-body tr').each(function() {
                const text = $(this).text().toLowerCase();
                $(this).toggle(text.includes(query));
            });
        }, 300));

        $('[data-action="toggle-admin"]').off('click').on('click', function(e) {
            e.preventDefault();
            const userId = $(this).data('user-id');
            toggleAdmin(userId);
        });

        $('[data-action="toggle-env-admin"]').off('click').on('click', function(e) {
            e.preventDefault();
            const userId = $(this).data('user-id');
            toggleEnvAdmin(userId);
        });

        $('[data-action="deactivate"]').off('click').on('click', function(e) {
            e.preventDefault();
            const userId = $(this).data('user-id');
            if (confirm('Deactivate this user?')) {
                deactivateUser(userId);
            }
        });

        $('[data-action="reactivate"]').off('click').on('click', function(e) {
            e.preventDefault();
            const userId = $(this).data('user-id');
            reactivateUser(userId);
        });
    }

    function toggleAdmin(userId) {
        ApiClient.post(Config.API.users.setAdmin(userId))
            .done(function() {
                Notifications.success('Admin status updated');
                loadUserManagement();
            })
            .fail(function(xhr) {
                Notifications.error(xhr.responseJSON?.message || 'Failed to update admin status');
            });
    }

    function toggleEnvAdmin(userId) {
        ApiClient.post(Config.API.users.setEnvAdmin(userId))
            .done(function() {
                Notifications.success('Env admin status updated');
                loadUserManagement();
            })
            .fail(function(xhr) {
                Notifications.error(xhr.responseJSON?.message || 'Failed to update env admin status');
            });
    }

    function deactivateUser(userId) {
        ApiClient.post(Config.API.users.deactivate(userId))
            .done(function() {
                Notifications.success('User deactivated');
                loadUserManagement();
            })
            .fail(function(xhr) {
                Notifications.error(xhr.responseJSON?.message || 'Failed to deactivate user');
            });
    }

    function reactivateUser(userId) {
        ApiClient.post(Config.API.users.reactivate(userId))
            .done(function() {
                Notifications.success('User reactivated');
                loadUserManagement();
            })
            .fail(function(xhr) {
                Notifications.error(xhr.responseJSON?.message || 'Failed to reactivate user');
            });
    }

    return {
        loadAccessManagement,
        loadVmRegistry,       // shim — delegates to VmRegistry.load()
        loadAutomationRules,
        loadSystemHealth,
        loadUserManagement
    };
})();

// Make Features available on window for router and sidebar navigation
window.Features = Features;
