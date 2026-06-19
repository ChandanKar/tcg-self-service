/**
 * Access Management Feature Module
 * Admin view for managing user access to environments
 * Handles: view access, grant/revoke access, approve/deny requests
 */
const AccessManagement = (function() {
    'use strict';

    // Constants
    const PAGE_SIZE = 10;

    // State
    let environments = [];
    let allAccess = [];
    let filteredAccess = [];
    let pendingRequests = [];
    let activityLogs = [];
    let selectedEnvironmentId = '';
    let currentPage = 1;
    let currentSearch = '';

    /**
     * Initialize and load Access Management view
     */
    function load() {
        if (!Auth.isEnvAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3">Access denied. Admin or Env Admin only.</div>');
            return;
        }

        showLoading();
        fetchInitialData();
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
     * Fetch initial data: environments, pending requests, and activity logs
     */
    async function fetchInitialData() {
        try {
            const [envList, requests] = await Promise.all([
                fetchEnvironments(),
                fetchPendingRequests()
            ]);

            environments = envList || [];
            pendingRequests = requests || [];

            // Select first environment by default
            if (environments.length > 0 && !selectedEnvironmentId) {
                selectedEnvironmentId = environments[0].environmentId;
            }

            // Fetch access and activity logs for selected environment
            if (selectedEnvironmentId) {
                [allAccess, activityLogs] = await Promise.all([
                    fetchEnvironmentAccess(selectedEnvironmentId),
                    fetchActivityLogs(selectedEnvironmentId)
                ]);
                filteredAccess = [...allAccess];
            }

            currentPage = 1;
            render();
        } catch (error) {
            console.error('Failed to load access management data:', error);
            if (error.status === 403) {
                showError('Access denied. You do not have permission to manage access.');
            } else {
                showError('Failed to load data. Please try again.');
            }
        }
    }

    /**
     * Fetch all environments
     */
    function fetchEnvironments() {
        return new Promise((resolve, reject) => {
            ApiClient.get(Config.API.environments.list)
                .done(resolve)
                .fail(reject);
        });
    }

    /**
     * Fetch access list for an environment
     */
    function fetchEnvironmentAccess(envId) {
        return new Promise((resolve, reject) => {
            ApiClient.get(Config.API.access.environmentAccess(envId))
                .done(resolve)
                .fail(function(xhr) {
                    if (xhr.status === 404) {
                        resolve([]);
                    } else {
                        reject(xhr);
                    }
                });
        });
    }

    /**
     * Fetch activity logs for an environment (access-related events only, latest 25)
     */
    function fetchActivityLogs(envId) {
        if (!envId) return Promise.resolve([]);
        return new Promise((resolve) => {
            ApiClient.get(`${Config.API.audit.byEnvironment(envId)}?page=0&size=25`)
                .done(function(data) {
                    const entries = (data && data.content) ? data.content : (Array.isArray(data) ? data : []);
                    // Filter to access-related actions only
                    const accessActions = ['ACCESS_REQUESTED', 'ACCESS_GRANTED', 'ACCESS_REVOKED', 'ACCESS_DENIED', 'ACCESS_APPROVED'];
                    resolve(entries.filter(e => accessActions.includes(e.action)));
                })
                .fail(function() {
                    resolve([]);
                });
        });
    }

    /**
     * Fetch pending access requests
     */
    function fetchPendingRequests() {
        return new Promise((resolve, reject) => {
            ApiClient.get(Config.API.access.pendingRequests)
                .done(resolve)
                .fail(function(xhr) {
                    if (xhr.status === 404) {
                        resolve([]);
                    } else {
                        reject(xhr);
                    }
                });
        });
    }

    /**
     * Show error state
     */
    function showError(message) {
        $('#content-area').html(`
            <div class="content-view">
                <div class="content-header">
                    <h1>Access Management</h1>
                </div>
                <div class="alert alert-danger">
                    <i class="fas fa-exclamation-circle me-2"></i>${escapeHtml(message)}
                </div>
            </div>
        `);
    }

    /**
     * Render the complete view
     */
    function render() {
        const html = buildViewHtml();
        $('#content-area').html(html);
        renderAccessTable();
        renderAccessPagination();
        renderPendingRequestsTable();
        renderActivityLogsTable();
        bindEvents();
    }

    /**
     * Build main view HTML structure
     */
    function buildViewHtml() {
        const stats = calculateStats();
        const envOptions = environments.map(env =>
            `<option value="${env.environmentId}" ${env.environmentId === selectedEnvironmentId ? 'selected' : ''}>
                ${escapeHtml(env.displayName || env.name)}
            </option>`
        ).join('');

        const selectedEnv = environments.find(e => e.environmentId === selectedEnvironmentId);
        const envName = selectedEnv ? (selectedEnv.displayName || selectedEnv.name) : 'Select Environment';

        return `
            <div class="content-view" id="access-management-view">
                <!-- Header -->
                <div class="content-header">
                    <div class="d-flex justify-content-between align-items-start">
                        <div>
                            <h1>Access Management</h1>
                            <p>Grant or revoke user access to environments</p>
                        </div>
                        <div class="header-actions">
                            <button class="btn btn-outline-secondary btn-sm" id="btn-refresh-access" title="Refresh">
                                <i class="fas fa-sync-alt"></i>
                            </button>
                        </div>
                    </div>
                </div>

                <!-- Filters Bar -->
                <div class="access-filters-bar">
                    <div class="search-input-wrapper">
                        <div class="input-group input-group-sm">
                            <span class="input-group-text"><i class="fas fa-search"></i></span>
                            <input type="text" class="form-control" id="access-search"
                                   placeholder="Search by name or email..." value="${escapeHtml(currentSearch)}">
                        </div>
                    </div>
                    <select class="form-select form-select-sm" id="filter-environment">
                        <option value="">All Environments</option>
                        ${envOptions}
                    </select>
                    <button class="btn btn-primary btn-sm" id="btn-grant-access">
                        <i class="fas fa-plus me-1"></i>Grant Access
                    </button>
                </div>

                <!-- Stats Row -->
                <div class="access-stats-row">
                    <div class="access-stat-item">
                        <span class="stat-label">Total Access:</span>
                        <span class="stat-value">${stats.totalAccess}</span>
                    </div>
                    <div class="access-stat-item">
                        <span class="stat-label">Admins:</span>
                        <span class="stat-value text-danger">${stats.admins}</span>
                    </div>
                    <div class="access-stat-item">
                        <span class="stat-label">Users:</span>
                        <span class="stat-value text-primary">${stats.users}</span>
                    </div>
                    <div class="access-stat-item">
                        <span class="stat-label">Viewers:</span>
                        <span class="stat-value text-secondary">${stats.viewers}</span>
                    </div>
                    <div class="access-stat-item">
                        <span class="stat-label">Pending:</span>
                        <span class="stat-value text-warning">${stats.pending}</span>
                    </div>
                </div>

                <!-- Environment Access Section -->
                <div class="access-section">
                    <div class="access-section-header">
                        <h5><i class="fas fa-users me-2"></i>Environment Access: ${escapeHtml(envName)}</h5>
                    </div>
                    <div class="access-table-card">
                        <div class="access-table-wrapper">
                            <table class="table table-hover access-table mb-0">
                                <thead>
                                    <tr>
                                        <th>User</th>
                                        <th>Access Level</th>
                                        <th>Granted By</th>
                                        <th>Granted Date</th>
                                        <th>Expires</th>
                                        <th class="text-end">Actions</th>
                                    </tr>
                                </thead>
                                <tbody id="access-table-body">
                                    <!-- Populated by renderAccessTable() -->
                                </tbody>
                            </table>
                        </div>
                        <div id="access-pagination" class="access-pagination"></div>
                    </div>
                </div>

                <!-- Pending Requests Section -->
                <div class="access-section mt-3">
                    <div class="access-section-header">
                        <h5>
                            <i class="fas fa-clock me-2"></i>Pending Access Requests
                            ${pendingRequests.length > 0 ? `<span class="badge bg-warning text-dark ms-2">${pendingRequests.length}</span>` : ''}
                        </h5>
                    </div>
                    <div class="access-table-card">
                        <div class="access-table-wrapper" id="pending-table-wrapper">
                            <table class="table table-hover access-table mb-0">
                                <thead>
                                    <tr>
                                        <th>Requester</th>
                                        <th>Environment</th>
                                        <th>Requested Level</th>
                                        <th>Requested</th>
                                        <th>Justification</th>
                                        <th class="text-end">Actions</th>
                                    </tr>
                                </thead>
                                <tbody id="pending-table-body">
                                    <!-- Populated by renderPendingRequestsTable() -->
                                </tbody>
                            </table>
                        </div>
                    </div>
                </div>

                <!-- Activity Log Section -->
                <div class="access-section mt-3">
                    <div class="access-section-header">
                        <h5><i class="fas fa-history me-2"></i>Activity Log: ${escapeHtml(envName)}</h5>
                    </div>
                    <div class="access-table-card">
                        <div class="access-table-wrapper">
                            <table class="table table-hover access-table mb-0">
                                <thead>
                                    <tr>
                                        <th style="width:140px">When</th>
                                        <th style="width:160px">Action</th>
                                        <th style="width:180px">Performed By</th>
                                        <th>Details</th>
                                    </tr>
                                </thead>
                                <tbody id="activity-log-body">
                                    <!-- Populated by renderActivityLogsTable() -->
                                </tbody>
                            </table>
                        </div>
                    </div>
                </div>
            </div>

            <!-- Grant Access Modal -->
            <div class="modal fade" id="grantAccessModal" tabindex="-1" aria-hidden="true">
                <div class="modal-dialog">
                    <div class="modal-content">
                        <div class="modal-header">
                            <h5 class="modal-title"><i class="fas fa-user-plus me-2"></i>Grant Access</h5>
                            <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
                        </div>
                        <div class="modal-body">
                            <form id="grant-access-form">
                                <div class="mb-3">
                                    <label class="form-label">Environment</label>
                                    <select class="form-select" id="grant-environment" required>
                                        ${envOptions}
                                    </select>
                                </div>
                                <div class="mb-3">
                                    <label class="form-label">User</label>
                                    <input type="text" class="form-control" id="grant-user-search"
                                           placeholder="Search user by email...">
                                    <select class="form-select mt-2" id="grant-user-id" required>
                                        <option value="">Select a user...</option>
                                    </select>
                                </div>
                                <div class="mb-3">
                                    <label class="form-label">Access Level</label>
                                    <select class="form-select" id="grant-access-level" required>
                                        <option value="VIEWER">Viewer - View only</option>
                                        <option value="USER" selected>User - Can perform operations</option>
                                        <option value="ADMIN">Admin - Full control</option>
                                    </select>
                                </div>
                                <div class="mb-3">
                                    <label class="form-label">Duration (optional)</label>
                                    <select class="form-select" id="grant-duration">
                                        <option value="">Permanent</option>
                                        <option value="7">7 days</option>
                                        <option value="30">30 days</option>
                                        <option value="90">90 days</option>
                                        <option value="180">180 days</option>
                                        <option value="365">1 year</option>
                                    </select>
                                </div>
                                <div class="mb-3">
                                    <label class="form-label">Notes (optional)</label>
                                    <textarea class="form-control" id="grant-notes" rows="2"
                                              placeholder="Reason for access grant..."></textarea>
                                </div>
                            </form>
                        </div>
                        <div class="modal-footer">
                            <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">Cancel</button>
                            <button type="button" class="btn btn-primary" id="btn-confirm-grant">
                                <i class="fas fa-check me-1"></i>Grant Access
                            </button>
                        </div>
                    </div>
                </div>
            </div>

            <!-- Deny Request Modal -->
            <div class="modal fade" id="denyRequestModal" tabindex="-1" aria-hidden="true">
                <div class="modal-dialog modal-sm">
                    <div class="modal-content">
                        <div class="modal-header">
                            <h5 class="modal-title"><i class="fas fa-times-circle me-2 text-danger"></i>Deny Request</h5>
                            <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
                        </div>
                        <div class="modal-body">
                            <input type="hidden" id="deny-request-id">
                            <div class="mb-3">
                                <label class="form-label">Reason for denial</label>
                                <textarea class="form-control" id="deny-reason" rows="3"
                                          placeholder="Enter reason..."></textarea>
                            </div>
                        </div>
                        <div class="modal-footer">
                            <button type="button" class="btn btn-secondary btn-sm" data-bs-dismiss="modal">Cancel</button>
                            <button type="button" class="btn btn-danger btn-sm" id="btn-confirm-deny">
                                <i class="fas fa-times me-1"></i>Deny
                            </button>
                        </div>
                    </div>
                </div>
            </div>
        `;
    }

    /**
     * Calculate statistics
     */
    function calculateStats() {
        return {
            totalAccess: allAccess.length,
            admins: allAccess.filter(a => a.accessLevel === 'ADMIN').length,
            users: allAccess.filter(a => a.accessLevel === 'USER').length,
            viewers: allAccess.filter(a => a.accessLevel === 'VIEWER').length,
            pending: pendingRequests.length
        };
    }

    /**
     * Render access table rows for current page
     */
    function renderAccessTable() {
        const startIndex = (currentPage - 1) * PAGE_SIZE;
        const endIndex = startIndex + PAGE_SIZE;
        const pageAccess = filteredAccess.slice(startIndex, endIndex);

        if (pageAccess.length === 0) {
            $('#access-table-body').html(`
                <tr>
                    <td colspan="6" class="text-center text-muted py-4">
                        <i class="fas fa-users-slash fa-2x mb-2 d-block opacity-50"></i>
                        ${currentSearch ? 'No users match your search' : 'No users have access to this environment'}
                    </td>
                </tr>
            `);
            return;
        }

        const rows = pageAccess.map(access => buildAccessRow(access)).join('');
        $('#access-table-body').html(rows);
    }

    /**
     * Build a single access row
     */
    function buildAccessRow(access) {
        const levelClass = getLevelClass(access.accessLevel);
        const initials = getInitials(access.userDisplayName || access.userEmail);
        const grantedDate = access.grantedAt ? Utils.formatRelativeTime(access.grantedAt) : '-';
        const expiresDate = access.expiresAt ? Utils.formatRelativeTime(access.expiresAt) : 'Never';
        const isExpiringSoon = access.expiresAt && isWithinDays(access.expiresAt, 7);

        return `
            <tr data-access-id="${access.accessId}">
                <td>
                    <div class="user-cell">
                        <div class="user-avatar-sm">${initials}</div>
                        <div class="user-info">
                            <div class="user-name">${escapeHtml(access.userDisplayName || 'Unknown')}</div>
                            <div class="user-email">${escapeHtml(access.userEmail || '')}</div>
                        </div>
                    </div>
                </td>
                <td><span class="access-level-badge ${levelClass}">${access.accessLevel}</span></td>
                <td class="text-muted">${escapeHtml(access.grantedByUserName || 'System')}</td>
                <td class="text-muted">${grantedDate}</td>
                <td class="${isExpiringSoon ? 'text-warning' : 'text-muted'}">
                    ${isExpiringSoon ? '<i class="fas fa-exclamation-triangle me-1"></i>' : ''}${expiresDate}
                </td>
                <td class="text-end">
                    <button class="btn btn-sm btn-outline-danger" data-action="revoke"
                            data-user-id="${access.userId}" data-user-name="${escapeHtml(access.userDisplayName || access.userEmail)}">
                        <i class="fas fa-user-minus"></i> Revoke
                    </button>
                </td>
            </tr>
        `;
    }

    /**
     * Render access pagination
     */
    function renderAccessPagination() {
        const totalItems = filteredAccess.length;
        const totalPages = Math.ceil(totalItems / PAGE_SIZE);

        if (totalItems <= PAGE_SIZE) {
            $('#access-pagination').html(
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

        $('#access-pagination').html(`
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
     * Render pending requests table
     */
    function renderPendingRequestsTable() {
        if (pendingRequests.length === 0) {
            $('#pending-table-body').html(`
                <tr>
                    <td colspan="6" class="text-center text-muted py-4">
                        <i class="fas fa-inbox fa-2x mb-2 d-block opacity-50"></i>
                        No pending access requests
                    </td>
                </tr>
            `);
            return;
        }

        const rows = pendingRequests.map(req => buildPendingRow(req)).join('');
        $('#pending-table-body').html(rows);
    }

    /**
     * Build a single pending request row
     */
    function buildPendingRow(request) {
        const levelClass = getLevelClass(request.requestedAccessLevel);
        const initials = getInitials(request.requesterDisplayName || request.requesterEmail);
        const requestedDate = request.createdAt ? Utils.formatRelativeTime(request.createdAt) : '-';
        const justification = request.businessJustification || '-';

        return `
            <tr data-request-id="${request.requestId}">
                <td>
                    <div class="user-cell">
                        <div class="user-avatar-sm">${initials}</div>
                        <div class="user-info">
                            <div class="user-name">${escapeHtml(request.requesterDisplayName || 'Unknown')}</div>
                            <div class="user-email">${escapeHtml(request.requesterEmail || '')}</div>
                        </div>
                    </div>
                </td>
                <td>${escapeHtml(request.environmentName || 'Unknown')}</td>
                <td><span class="access-level-badge ${levelClass}">${request.requestedAccessLevel}</span></td>
                <td class="text-muted">${requestedDate}</td>
                <td class="justification-cell" title="${escapeHtml(justification)}">
                    <span class="justification-text">${escapeHtml(justification)}</span>
                </td>
                <td class="text-end">
                    <div class="btn-group btn-group-sm">
                        <button class="btn btn-success" data-action="approve" data-request-id="${request.requestId}">
                            <i class="fas fa-check"></i> Approve
                        </button>
                        <button class="btn btn-outline-danger" data-action="deny" data-request-id="${request.requestId}">
                            <i class="fas fa-times"></i>
                        </button>
                    </div>
                </td>
            </tr>
        `;
    }

    /**
     * Render activity log table
     */
    function renderActivityLogsTable() {
        if (!activityLogs || activityLogs.length === 0) {
            $('#activity-log-body').html(`
                <tr>
                    <td colspan="4" class="text-center text-muted py-4">
                        <i class="fas fa-history fa-2x mb-2 d-block opacity-50"></i>
                        No access activity recorded for this environment
                    </td>
                </tr>
            `);
            return;
        }
        const rows = activityLogs.map(log => buildActivityRow(log)).join('');
        $('#activity-log-body').html(rows);
    }

    /**
     * Build a single activity log row
     */
    function buildActivityRow(log) {
        const when = log.createdAt ? Utils.formatRelativeTime(log.createdAt) : '-';
        const action = escapeHtml(log.actionDisplay || (log.action || '').replace(/_/g, ' '));
        const performedBy = escapeHtml(log.userEmail || log.userId || 'System');
        const details = escapeHtml(log.details || '-');
        const badgeClass = getActionBadgeClass(log.action);
        return `
            <tr>
                <td class="text-muted small">${when}</td>
                <td><span class="badge ${badgeClass}">${action}</span></td>
                <td class="text-muted small">${performedBy}</td>
                <td class="text-muted small">${details}</td>
            </tr>
        `;
    }

    function getActionBadgeClass(action) {
        if (!action) return 'bg-secondary';
        const s = String(action);
        if (s.includes('GRANTED') || s.includes('APPROVED')) return 'bg-success';
        if (s.includes('REVOKED') || s.includes('DENIED')) return 'bg-danger';
        if (s.includes('REQUESTED')) return 'bg-warning text-dark';
        return 'bg-secondary';
    }

    /**
     * Apply search filter
     */
    function applyFilters() {
        const search = currentSearch.toLowerCase().trim();

        filteredAccess = allAccess.filter(access => {
            if (!search) return true;
            const name = (access.userDisplayName || '').toLowerCase();
            const email = (access.userEmail || '').toLowerCase();
            return name.includes(search) || email.includes(search);
        });

        currentPage = 1;
        renderAccessTable();
        renderAccessPagination();
    }

    /**
     * Bind event handlers
     */
    function bindEvents() {
        // Refresh button
        $('#btn-refresh-access').off('click').on('click', function() {
            const $btn = $(this);
            $btn.find('i').addClass('fa-spin');
            fetchInitialData().finally(() => {
                $btn.find('i').removeClass('fa-spin');
            });
        });

        // Search input
        $('#access-search').off('input').on('input', Utils.debounce(function() {
            currentSearch = $(this).val();
            applyFilters();
        }, 300));

        // Environment filter
        $('#filter-environment').off('change').on('change', async function() {
            selectedEnvironmentId = $(this).val();
            currentPage = 1;
            currentSearch = '';
            $('#access-search').val('');

            if (selectedEnvironmentId) {
                try {
                    [allAccess, activityLogs] = await Promise.all([
                        fetchEnvironmentAccess(selectedEnvironmentId),
                        fetchActivityLogs(selectedEnvironmentId)
                    ]);
                    filteredAccess = [...allAccess];
                } catch (error) {
                    console.error('Failed to fetch access:', error);
                    allAccess = [];
                    filteredAccess = [];
                    activityLogs = [];
                }
            } else {
                allAccess = [];
                filteredAccess = [];
                activityLogs = [];
            }

            // Update section headers
            const selectedEnv = environments.find(e => e.environmentId === selectedEnvironmentId);
            const envName = selectedEnv ? (selectedEnv.displayName || selectedEnv.name) : 'Select Environment';
            $('.access-section-header h5').first().html(`<i class="fas fa-users me-2"></i>Environment Access: ${escapeHtml(envName)}`);
            $('.access-section-header h5').last().html(`<i class="fas fa-history me-2"></i>Activity Log: ${escapeHtml(envName)}`);

            // Update stats
            const stats = calculateStats();
            updateStatsDisplay(stats);

            renderAccessTable();
            renderAccessPagination();
            renderActivityLogsTable();
        });

        // Pagination
        $('#access-pagination').off('click', '.page-btn').on('click', '.page-btn', function() {
            if ($(this).prop('disabled')) return;
            currentPage = parseInt($(this).data('page'));
            renderAccessTable();
            renderAccessPagination();
        });

        // Grant Access button
        $('#btn-grant-access').off('click').on('click', showGrantAccessModal);

        // Confirm grant
        $('#btn-confirm-grant').off('click').on('click', handleGrantAccess);

        // User search in modal
        $('#grant-user-search').off('input').on('input', Utils.debounce(handleUserSearch, 300));

        // Revoke access
        $('#access-table-body').off('click', '[data-action="revoke"]').on('click', '[data-action="revoke"]', function() {
            const userId = $(this).data('user-id');
            const userName = $(this).data('user-name');
            handleRevokeAccess(userId, userName);
        });

        // Approve request
        $('#pending-table-body').off('click', '[data-action="approve"]').on('click', '[data-action="approve"]', function() {
            const requestId = $(this).data('request-id');
            handleApproveRequest(requestId);
        });

        // Deny request - show modal
        $('#pending-table-body').off('click', '[data-action="deny"]').on('click', '[data-action="deny"]', function() {
            const requestId = $(this).data('request-id');
            $('#deny-request-id').val(requestId);
            $('#deny-reason').val('');
            const modal = new bootstrap.Modal(document.getElementById('denyRequestModal'));
            modal.show();
        });

        // Confirm deny
        $('#btn-confirm-deny').off('click').on('click', handleDenyRequest);
    }

    /**
     * Update stats display
     */
    function updateStatsDisplay(stats) {
        $('.access-stats-row .access-stat-item').eq(0).find('.stat-value').text(stats.totalAccess);
        $('.access-stats-row .access-stat-item').eq(1).find('.stat-value').text(stats.admins);
        $('.access-stats-row .access-stat-item').eq(2).find('.stat-value').text(stats.users);
        $('.access-stats-row .access-stat-item').eq(3).find('.stat-value').text(stats.viewers);
        $('.access-stats-row .access-stat-item').eq(4).find('.stat-value').text(stats.pending);
    }

    /**
     * Show grant access modal
     */
    function showGrantAccessModal() {
        $('#grant-environment').val(selectedEnvironmentId || '');
        $('#grant-user-search').val('');
        $('#grant-user-id').html('<option value="">Select a user...</option>');
        $('#grant-access-level').val('USER');
        $('#grant-duration').val('');
        $('#grant-notes').val('');

        const modal = new bootstrap.Modal(document.getElementById('grantAccessModal'));
        modal.show();
    }

    /**
     * Handle user search in grant modal
     */
    async function handleUserSearch() {
        const query = $('#grant-user-search').val().trim();
        if (query.length < 2) {
            $('#grant-user-id').html('<option value="">Type at least 2 characters...</option>');
            return;
        }

        try {
            const response = await new Promise((resolve, reject) => {
                ApiClient.get(Config.API.users.search(query))
                    .done(resolve)
                    .fail(reject);
            });

            const users = response || [];
            if (users.length === 0) {
                $('#grant-user-id').html('<option value="">No users found</option>');
                return;
            }

            const options = users.map(user =>
                `<option value="${escapeHtml(user.email)}">${escapeHtml(user.displayName || user.email)} (${escapeHtml(user.email)})</option>`
            ).join('');
            $('#grant-user-id').html('<option value="">Select a user...</option>' + options);
        } catch (error) {
            console.error('User search failed:', error);
            $('#grant-user-id').html('<option value="">Search failed</option>');
        }
    }

    /**
     * Handle grant access form submission
     */
    async function handleGrantAccess() {
        const envId = $('#grant-environment').val();
        const userEmail = $('#grant-user-id').val();
        const accessLevel = $('#grant-access-level').val();
        const durationDays = $('#grant-duration').val() || null;
        const notes = $('#grant-notes').val().trim() || null;

        if (!envId || !userEmail || !accessLevel) {
            showToast('Please fill in all required fields', 'warning');
            return;
        }

        const $btn = $('#btn-confirm-grant');
        $btn.prop('disabled', true).html('<i class="fas fa-spinner fa-spin me-1"></i>Granting...');

        try {
            await new Promise((resolve, reject) => {
                ApiClient.post(Config.API.access.grantAccess(envId), {
                    userEmail: userEmail,
                    accessLevel: accessLevel,
                    durationDays: durationDays ? parseInt(durationDays) : null,
                    notes: notes
                })
                .done(resolve)
                .fail(reject);
            });

            bootstrap.Modal.getInstance(document.getElementById('grantAccessModal')).hide();
            showToast('Access granted successfully', 'success');

            // Refresh data
            if (envId === selectedEnvironmentId) {
                [allAccess, activityLogs] = await Promise.all([
                    fetchEnvironmentAccess(selectedEnvironmentId),
                    fetchActivityLogs(selectedEnvironmentId)
                ]);
                filteredAccess = [...allAccess];
                const stats = calculateStats();
                updateStatsDisplay(stats);
                renderAccessTable();
                renderAccessPagination();
                renderActivityLogsTable();
            }
        } catch (error) {
            console.error('Grant access failed:', error);
            const message = error.responseJSON?.message || 'Failed to grant access';
            showToast(message, 'danger');
        } finally {
            $btn.prop('disabled', false).html('<i class="fas fa-check me-1"></i>Grant Access');
        }
    }

    /**
     * Handle revoke access
     */
    async function handleRevokeAccess(userId, userName) {
        if (!confirm(`Are you sure you want to revoke access for ${userName}?`)) {
            return;
        }

        try {
            await new Promise((resolve, reject) => {
                ApiClient.delete(Config.API.access.revokeAccess(selectedEnvironmentId, userId))
                    .done(resolve)
                    .fail(reject);
            });

            showToast('Access revoked successfully', 'success');

            // Refresh data
            [allAccess, activityLogs] = await Promise.all([
                fetchEnvironmentAccess(selectedEnvironmentId),
                fetchActivityLogs(selectedEnvironmentId)
            ]);
            filteredAccess = [...allAccess];
            const stats = calculateStats();
            updateStatsDisplay(stats);
            applyFilters();
            renderActivityLogsTable();
        } catch (error) {
            console.error('Revoke access failed:', error);
            const message = error.responseJSON?.message || 'Failed to revoke access';
            showToast(message, 'danger');
        }
    }

    /**
     * Handle approve request
     */
    async function handleApproveRequest(requestId) {
        const $btn = $(`[data-action="approve"][data-request-id="${requestId}"]`);
        $btn.prop('disabled', true).html('<i class="fas fa-spinner fa-spin"></i>');

        try {
            await new Promise((resolve, reject) => {
                ApiClient.post(Config.API.access.approveRequest(requestId), {})
                    .done(resolve)
                    .fail(reject);
            });

            showToast('Request approved successfully', 'success');

            // Refresh pending requests and activity logs
            [pendingRequests, activityLogs] = await Promise.all([
                fetchPendingRequests(),
                fetchActivityLogs(selectedEnvironmentId)
            ]);

            // Update stats
            const stats = calculateStats();
            updateStatsDisplay(stats);

            // Update pending badge
            $('.access-section-header h5').eq(1).html(`
                <i class="fas fa-clock me-2"></i>Pending Access Requests
                ${pendingRequests.length > 0 ? `<span class="badge bg-warning text-dark ms-2">${pendingRequests.length}</span>` : ''}
            `);

            renderPendingRequestsTable();
            renderActivityLogsTable();

            // Refresh access list if same environment
            const request = pendingRequests.find(r => r.requestId === requestId);
            if (request && request.environmentId === selectedEnvironmentId) {
                allAccess = await fetchEnvironmentAccess(selectedEnvironmentId);
                filteredAccess = [...allAccess];
                renderAccessTable();
                renderAccessPagination();
            }
        } catch (error) {
            console.error('Approve request failed:', error);
            const message = error.responseJSON?.message || 'Failed to approve request';
            showToast(message, 'danger');
            $btn.prop('disabled', false).html('<i class="fas fa-check"></i> Approve');
        }
    }

    /**
     * Handle deny request
     */
    async function handleDenyRequest() {
        const requestId = $('#deny-request-id').val();
        const reason = $('#deny-reason').val().trim();

        const $btn = $('#btn-confirm-deny');
        $btn.prop('disabled', true).html('<i class="fas fa-spinner fa-spin me-1"></i>Denying...');

        try {
            await new Promise((resolve, reject) => {
                ApiClient.post(Config.API.access.denyRequest(requestId), { notes: reason })
                    .done(resolve)
                    .fail(reject);
            });

            bootstrap.Modal.getInstance(document.getElementById('denyRequestModal')).hide();
            showToast('Request denied', 'success');

            // Refresh pending requests and activity logs
            [pendingRequests, activityLogs] = await Promise.all([
                fetchPendingRequests(),
                fetchActivityLogs(selectedEnvironmentId)
            ]);

            // Update stats
            const stats = calculateStats();
            updateStatsDisplay(stats);

            // Update pending badge
            $('.access-section-header h5').eq(1).html(`
                <i class="fas fa-clock me-2"></i>Pending Access Requests
                ${pendingRequests.length > 0 ? `<span class="badge bg-warning text-dark ms-2">${pendingRequests.length}</span>` : ''}
            `);

            renderPendingRequestsTable();
            renderActivityLogsTable();
        } catch (error) {
            console.error('Deny request failed:', error);
            const message = error.responseJSON?.message || 'Failed to deny request';
            showToast(message, 'danger');
        } finally {
            $btn.prop('disabled', false).html('<i class="fas fa-times me-1"></i>Deny');
        }
    }

    // ============= Helper Functions =============

    function getLevelClass(level) {
        const classes = {
            'ADMIN': 'level-admin',
            'USER': 'level-user',
            'VIEWER': 'level-viewer'
        };
        return classes[level] || 'level-user';
    }

    function getInitials(name) {
        if (!name) return '?';
        const parts = name.split(/[\s@]+/);
        if (parts.length >= 2) {
            return (parts[0][0] + parts[1][0]).toUpperCase();
        }
        return name.substring(0, 2).toUpperCase();
    }

    function escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    function isWithinDays(dateStr, days) {
        const date = new Date(dateStr);
        const now = new Date();
        const diff = date.getTime() - now.getTime();
        const daysDiff = diff / (1000 * 60 * 60 * 24);
        return daysDiff > 0 && daysDiff <= days;
    }

    function showToast(message, type = 'info') {
        // Use existing toast system if available, otherwise console
        if (window.Toast && typeof Toast.show === 'function') {
            Toast.show(message, type);
        } else if (window.showNotification) {
            showNotification(message, type);
        } else {
            console.log(`[${type.toUpperCase()}] ${message}`);
            // Fallback: simple alert for errors
            if (type === 'danger' || type === 'error') {
                alert(message);
            }
        }
    }

    // Public API
    return {
        load: load
    };

})();

// Make AccessManagement available globally
window.AccessManagement = AccessManagement;

