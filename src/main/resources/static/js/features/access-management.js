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

    // Autocomplete instances for the Grant Access modal (initialized in bindEvents)
    let grantEnvironmentAutocomplete = null;
    let grantUserAutocomplete = null;

    // Set by openForEnvironment() before navigating here; consumed once the view renders.
    let pendingOpenEnvId = null;

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

            [allAccess, activityLogs] = await Promise.all([
                fetchAccessForSelection(selectedEnvironmentId),
                fetchActivityLogsForSelection(selectedEnvironmentId)
            ]);
            filteredAccess = [...allAccess];

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
     * Fetch access list for the current environment selection.
     * Empty selection means all environments.
     */
    async function fetchAccessForSelection(envId) {
        if (envId) {
            return fetchEnvironmentAccess(envId);
        }

        const accessLists = await Promise.all(
            environments.map(env => fetchEnvironmentAccess(env.environmentId))
        );
        return accessLists.flat();
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
                    resolve(filterAccessActivity(entries));
                })
                .fail(function() {
                    resolve([]);
                });
        });
    }

    /**
     * Fetch access-related activity for the current environment selection.
     * Empty selection means all access-related activity visible to the admin.
     */
    function fetchActivityLogsForSelection(envId) {
        if (envId) {
            return fetchActivityLogs(envId);
        }

        return new Promise((resolve) => {
            ApiClient.get(`${Config.API.audit.allLogs}?page=0&size=50`)
                .done(function(data) {
                    const entries = (data && data.content) ? data.content : (Array.isArray(data) ? data : []);
                    resolve(filterAccessActivity(entries));
                })
                .fail(function() {
                    resolve([]);
                });
        });
    }

    function filterAccessActivity(entries) {
        const accessActions = ['ACCESS_REQUESTED', 'ACCESS_GRANTED', 'ACCESS_REVOKED', 'ACCESS_DENIED'];
        return entries.filter(e => accessActions.includes(e.action));
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
        applyPendingOpen();
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

        const envName = getSelectedEnvironmentName();

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
                            <button class="btn btn-ghost btn-sm" id="btn-refresh-access" title="Refresh">
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

                <div class="access-workspace">
                    <!-- Environment Access Section -->
                    <section class="access-panel access-panel-main">
                        <div class="access-panel-header">
                            <div>
                                <h5><i class="fas fa-users me-2"></i>Environment Access</h5>
                                <p>${escapeHtml(envName)}</p>
                            </div>
                            <span class="access-panel-count">${filteredAccess.length} grants</span>
                        </div>
                        <div class="access-table-shell">
                            <div class="access-table-wrapper">
                                <table class="table table-hover access-table mb-0">
                                    <thead>
                                        <tr>
                                            <th>Environment</th>
                                            <th>Scope</th>
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
                            <div id="access-pagination" class="pagination-bar-wrap"></div>
                        </div>
                    </section>

                    <aside class="access-panel access-panel-review">
                        <div class="access-panel-header">
                            <div>
                                <h5><i class="fas fa-clipboard-check me-2"></i>Review</h5>
                                <p>Requests and recent access events</p>
                            </div>
                        </div>
                        <ul class="nav nav-tabs access-review-tabs" role="tablist">
                            <li class="nav-item" role="presentation">
                                <button class="nav-link active" id="pending-review-tab" data-bs-toggle="tab"
                                        data-bs-target="#pending-review-pane" type="button" role="tab">
                                    Pending
                                    ${pendingRequests.length > 0 ? `<span class="badge text-bg-warning ms-1">${pendingRequests.length}</span>` : ''}
                                </button>
                            </li>
                            <li class="nav-item" role="presentation">
                                <button class="nav-link" id="activity-review-tab" data-bs-toggle="tab"
                                        data-bs-target="#activity-review-pane" type="button" role="tab">
                                    Activity
                                </button>
                            </li>
                        </ul>
                        <div class="tab-content access-review-content">
                            <div class="tab-pane fade show active" id="pending-review-pane" role="tabpanel" aria-labelledby="pending-review-tab">
                                <div id="pending-table-body" class="access-request-list"></div>
                            </div>
                            <div class="tab-pane fade" id="activity-review-pane" role="tabpanel" aria-labelledby="activity-review-tab">
                                <div class="access-activity-scope">${escapeHtml(envName)}</div>
                                <div id="activity-log-body" class="access-activity-list"></div>
                            </div>
                        </div>
                    </aside>
                </div>
            </div>

            <!-- Grant Access Modal -->
            <div class="modal fade" id="grantAccessModal" tabindex="-1" aria-hidden="true">
                <div class="modal-dialog">
                    <div class="modal-content">
                        <div class="modal-header">
                            <h5 class="modal-title" id="grant-modal-title"><i class="fas fa-user-plus me-2"></i>Grant Access</h5>
                            <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
                        </div>
                        <div class="modal-body">
                            <form id="grant-access-form">
                                <input type="hidden" id="grant-access-id">
                                <div class="mb-3 access-autocomplete-field">
                                    <label class="form-label" for="grant-environment-search">Environment</label>
                                    <input type="text" class="form-control" id="grant-environment-search" autocomplete="off"
                                           placeholder="Type at least 2 characters to search environments...">
                                    <input type="hidden" id="grant-environment">
                                    <div class="access-autocomplete-menu" id="grant-environment-menu"></div>
                                </div>
                                <div class="mb-3 access-autocomplete-field">
                                    <label class="form-label" for="grant-user-search">User</label>
                                    <input type="text" class="form-control" id="grant-user-search" autocomplete="off"
                                           placeholder="Type at least 2 characters to search by name or email...">
                                    <input type="hidden" id="grant-user-id">
                                    <div class="access-autocomplete-menu" id="grant-user-menu"></div>
                                    <div class="form-text">Searches users who have already signed in to this app.</div>
                                </div>
                                <div class="mb-3" id="grant-scope-block">
                                    <label class="form-label d-block">Scope</label>
                                    <div class="form-check">
                                        <input class="form-check-input" type="radio" name="grant-scope" id="grant-scope-env" value="ENVIRONMENT" checked>
                                        <label class="form-check-label" for="grant-scope-env">Whole environment <span class="text-muted">— every group, now and future</span></label>
                                    </div>
                                    <div class="form-check">
                                        <input class="form-check-input" type="radio" name="grant-scope" id="grant-scope-group" value="GROUP">
                                        <label class="form-check-label" for="grant-scope-group">Specific groups</label>
                                    </div>
                                </div>
                                <div class="mb-3 grant-group-checklist" id="grant-group-checklist" hidden>
                                    <div class="grant-checklist-head">
                                        <input type="text" class="form-control form-control-sm" id="grant-group-filter" placeholder="Filter groups">
                                        <label class="grant-selall mb-0"><input type="checkbox" id="grant-group-selall"> Select all</label>
                                    </div>
                                    <ul id="grant-group-list" class="grant-checklist-items">
                                        <li class="text-muted small p-2">Pick an environment first.</li>
                                    </ul>
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
                                <i class="fas fa-check me-1"></i><span id="btn-confirm-grant-label">Grant Access</span>
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
        $('.access-panel-count').text(`${filteredAccess.length} grant${filteredAccess.length !== 1 ? 's' : ''}`);

        if (pageAccess.length === 0) {
            $('#access-table-body').html(`
                <tr>
                    <td colspan="8" class="text-center text-muted py-4">
                        <i class="fas fa-users-slash fa-2x mb-2 d-block opacity-50"></i>
                        ${currentSearch ? 'No users match your search' : 'No users have access for this selection'}
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

        const isGroup = access.scopeType === 'GROUP';
        const scopeChip = isGroup
            ? `<span class="access-scope-chip access-scope-chip--group" title="Group scope"><i class="fas fa-layer-group me-1"></i>${escapeHtml(access.scopeName || access.scopeId)}</span>`
            : `<span class="access-scope-chip">Environment</span>`;

        return `
            <tr data-access-id="${access.accessId}">
                <td>${escapeHtml(access.environmentName || access.environmentId || '-')}</td>
                <td>${scopeChip}</td>
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
                <td class="text-end text-nowrap">
                    <button class="btn btn-sm btn-outline-secondary btn-action me-1" data-action="edit"
                            data-access-id="${access.accessId}"
                            title="Edit grant" aria-label="Edit grant">
                        <i class="fas fa-pen"></i>
                    </button>
                    <button class="btn btn-sm btn-outline-danger btn-action" data-action="revoke"
                            data-access-id="${access.accessId}"
                            data-user-name="${escapeHtml(access.userDisplayName || access.userEmail)}"
                            data-scope-name="${escapeHtml(isGroup ? (access.scopeName || 'this group') : 'the environment')}"
                            title="Revoke access" aria-label="Revoke access">
                        <i class="fas fa-user-minus"></i>
                    </button>
                </td>
            </tr>
        `;
    }

    /**
     * Render access pagination
     */
    function renderAccessPagination() {
        Pagination.renderNumbered('#access-pagination', {
            page: currentPage,
            totalItems: filteredAccess.length,
            pageSize: PAGE_SIZE,
            itemLabel: 'users',
            onPageChange: (target) => {
                currentPage = target;
                renderAccessTable();
                renderAccessPagination();
            }
        });
    }

    /**
     * Render pending requests table
     */
    function renderPendingRequestsTable() {
        if (pendingRequests.length === 0) {
            $('#pending-table-body').html(`
                <div class="access-empty-panel">
                    <i class="fas fa-inbox"></i>
                    <p>No pending access requests</p>
                </div>
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
            <div class="access-request-card" data-request-id="${request.requestId}">
                <div class="access-request-main">
                    <div class="user-cell">
                        <div class="user-avatar-sm">${initials}</div>
                        <div class="user-info">
                            <div class="user-name">${escapeHtml(request.requesterDisplayName || 'Unknown')}</div>
                            <div class="user-email">${escapeHtml(request.requesterEmail || '')}</div>
                        </div>
                    </div>
                    <div class="access-request-meta">
                        <span>${escapeHtml(request.environmentName || 'Unknown')}</span>
                        <span>${requestedDate}</span>
                    </div>
                    <div class="access-request-justification" title="${escapeHtml(justification)}">
                        ${escapeHtml(justification)}
                    </div>
                </div>
                <div class="access-request-actions">
                    <span class="access-level-badge ${levelClass}">${request.requestedAccessLevel}</span>
                    <div class="access-action-buttons">
                        <button class="btn btn-sm btn-tonal btn-success" data-action="approve" data-request-id="${request.requestId}">
                            <i class="fas fa-check"></i> Approve
                        </button>
                        <button class="btn btn-sm btn-outline-danger" data-action="deny" data-request-id="${request.requestId}" title="Deny">
                            <i class="fas fa-times"></i>
                        </button>
                    </div>
                </div>
            </div>
        `;
    }

    /**
     * Render activity log table
     */
    function renderActivityLogsTable() {
        if (!activityLogs || activityLogs.length === 0) {
            $('#activity-log-body').html(`
                <div class="access-empty-panel">
                    <i class="fas fa-history"></i>
                    <p>No access activity recorded</p>
                </div>
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
        const performedBy = escapeHtml(log.userDisplayName || log.userEmail || 'System');
        const details = escapeHtml(log.details || '-');
        const badgeClass = getActionBadgeClass(log.action);
        return `
            <div class="access-activity-item">
                <div class="access-activity-marker"></div>
                <div class="access-activity-content">
                    <div class="access-activity-topline">
                        <span class="badge ${badgeClass}">${action}</span>
                        <span>${when}</span>
                    </div>
                    <div class="access-activity-by"><i class="fas fa-user-circle me-1" aria-hidden="true"></i>${performedBy}</div>
                    <div class="access-activity-details">${details}</div>
                </div>
            </div>
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

            try {
                [allAccess, activityLogs] = await Promise.all([
                    fetchAccessForSelection(selectedEnvironmentId),
                    fetchActivityLogsForSelection(selectedEnvironmentId)
                ]);
                filteredAccess = [...allAccess];
            } catch (error) {
                console.error('Failed to fetch access:', error);
                allAccess = [];
                filteredAccess = [];
                activityLogs = [];
            }

            // Update section headers
            const envName = getSelectedEnvironmentName();
            $('.access-panel-main .access-panel-header p').text(envName);
            $('.access-activity-scope').text(envName);

            // Update stats
            const stats = calculateStats();
            updateStatsDisplay(stats);

            renderAccessTable();
            renderAccessPagination();
            renderActivityLogsTable();
        });

        // Grant Access button
        $('#btn-grant-access').off('click').on('click', showGrantAccessModal);

        // Confirm grant
        $('#btn-confirm-grant').off('click').on('click', handleGrantAccess);

        // Environment / User autocompletes in the Grant Access modal
        grantEnvironmentAutocomplete = initAutocomplete({
            inputSelector: '#grant-environment-search',
            hiddenSelector: '#grant-environment',
            menuSelector: '#grant-environment-menu',
            minChars: 2,
            fetchSuggestions: (query) => {
                const q = query.toLowerCase();
                return Promise.resolve(
                    environments
                        .filter(env => (env.displayName || env.name || '').toLowerCase().includes(q))
                        .slice(0, 20)
                        .map(env => ({ id: env.environmentId, label: env.displayName || env.name }))
                );
            },
            onSelect: (envId) => { if (grantScope() === 'GROUP') loadGroupsForGrant(envId); }
        });

        // Scope toggle + group checklist wiring
        $('input[name="grant-scope"]').off('change').on('change', function() {
            const isGroup = grantScope() === 'GROUP';
            document.getElementById('grant-group-checklist').hidden = !isGroup;
            if (isGroup) {
                const envId = $('#grant-environment').val();
                if (envId) loadGroupsForGrant(envId);
            }
        });
        $('#grant-group-selall').off('change').on('change', function() {
            $('#grant-group-list .grant-group-cb:visible').prop('checked', this.checked);
        });
        $('#grant-group-filter').off('input').on('input', function() {
            const q = this.value.trim().toLowerCase();
            $('#grant-group-list li').each(function() {
                const name = ($(this).data('name') || '').toLowerCase();
                $(this).toggle(!q || name.includes(q));
            });
        });

        grantUserAutocomplete = initAutocomplete({
            inputSelector: '#grant-user-search',
            hiddenSelector: '#grant-user-id',
            menuSelector: '#grant-user-menu',
            minChars: 2,
            fetchSuggestions: (query) => new Promise((resolve, reject) => {
                ApiClient.get(Config.API.users.search(query))
                    .done(users => resolve((users || []).map(user => ({
                        id: user.email,
                        label: `${user.displayName || user.email} (${user.email})`
                    }))))
                    .fail(reject);
            })
        });

        // Revoke access
        $('#access-table-body').off('click', '[data-action="revoke"]').on('click', '[data-action="revoke"]', function() {
            handleRevokeAccess($(this).data('access-id'), $(this).data('user-name'), $(this).data('scope-name'));
        });

        // Edit grant
        $('#access-table-body').off('click', '[data-action="edit"]').on('click', '[data-action="edit"]', function() {
            const access = allAccess.find(a => a.accessId === $(this).data('access-id'));
            if (access) showGrantAccessModal(access);
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
    function grantScope() {
        return $('input[name="grant-scope"]:checked').val() || 'ENVIRONMENT';
    }

    /**
     * Load an environment's groups into the modal checklist (name · N VMs · M running).
     */
    function loadGroupsForGrant(envId, preCheckedGroupIds, readOnly) {
        const $list = $('#grant-group-list');
        if (!envId) { $list.html('<li class="text-muted small p-2">Pick an environment first.</li>'); return; }
        $list.html('<li class="text-muted small p-2"><i class="fas fa-spinner fa-spin me-1"></i>Loading groups…</li>');
        ApiClient.get(Config.API.groups.list(envId))
            .done(groups => {
                const checked = new Set(preCheckedGroupIds || []);
                if (!groups || groups.length === 0) {
                    $list.html('<li class="text-muted small p-2">This environment has no groups.</li>');
                    return;
                }
                const rows = readOnly ? groups.filter(g => checked.has(g.groupId)) : groups;
                $list.html(rows.map(g => `
                    <li data-name="${escapeHtml(g.displayName || g.name)}">
                        <label>
                            <input type="checkbox" class="grant-group-cb" value="${g.groupId}"
                                   ${checked.has(g.groupId) ? 'checked' : ''} ${readOnly ? 'disabled' : ''}>
                            <span class="grant-group-name">${escapeHtml(g.displayName || g.name)}</span>
                        </label>
                        <span class="grant-group-meta">${g.vmCount || 0} VMs · ${g.runningVmCount || 0} running</span>
                    </li>`).join(''));
            })
            .fail(() => $list.html('<li class="text-danger small p-2">Failed to load groups.</li>'));
    }

    /**
     * Open the Grant / Edit Access modal. Pass a grant object to edit an existing one.
     */
    function showGrantAccessModal(editAccess) {
        const editing = !!editAccess;
        $('#grant-access-id').val(editing ? editAccess.accessId : '');
        $('#grant-modal-title').html(`<i class="fas fa-user-${editing ? 'pen' : 'plus'} me-2"></i>${editing ? 'Edit Access' : 'Grant Access'}`);
        $('#btn-confirm-grant-label').text(editing ? 'Save changes' : 'Grant Access');

        // Scope selector + group search are only meaningful for a NEW grant.
        $('#grant-scope-block, #grant-group-filter').toggle(!editing);
        $('#grant-user-search, #grant-environment-search').prop('disabled', editing);

        if (editing) {
            grantUserAutocomplete && grantUserAutocomplete.setValue(
                `${editAccess.userDisplayName || editAccess.userEmail} (${editAccess.userEmail})`, editAccess.userEmail);
            grantEnvironmentAutocomplete && grantEnvironmentAutocomplete.setValue(
                editAccess.environmentName || editAccess.environmentId, editAccess.environmentId);
            const isGroup = editAccess.scopeType === 'GROUP';
            $('#grant-scope-env').prop('checked', !isGroup);
            $('#grant-scope-group').prop('checked', isGroup);
            document.getElementById('grant-group-checklist').hidden = !isGroup;
            if (isGroup) loadGroupsForGrant(editAccess.environmentId, [editAccess.scopeId], true);
            $('#grant-access-level').val(editAccess.accessLevel || 'USER');
            $('#grant-duration').val('');
            $('#grant-notes').val(editAccess.notes || '');
        } else {
            grantUserAutocomplete && grantUserAutocomplete.reset();
            const preselected = selectedEnvironmentId && environments.find(env => env.environmentId === selectedEnvironmentId);
            if (grantEnvironmentAutocomplete) {
                preselected
                    ? grantEnvironmentAutocomplete.setValue(preselected.displayName || preselected.name, preselected.environmentId)
                    : grantEnvironmentAutocomplete.reset();
            }
            $('#grant-scope-env').prop('checked', true);
            $('#grant-scope-group').prop('checked', false);
            document.getElementById('grant-group-checklist').hidden = true;
            $('#grant-group-list').html('<li class="text-muted small p-2">Pick an environment first.</li>');
            $('#grant-group-selall').prop('checked', false);
            $('#grant-group-filter').val('');
            $('#grant-access-level').val('USER');
            $('#grant-duration').val('');
            $('#grant-notes').val('');
        }

        new bootstrap.Modal(document.getElementById('grantAccessModal')).show();
    }

    /**
     * Public entry point: jump to Access Management scoped to one environment and open the
     * grant modal. Used by the "Manage access" button on the environment detail view.
     */
    function openForEnvironment(envId) {
        pendingOpenEnvId = envId;
        if ($('#access-table-body').length) {
            applyPendingOpen();
        } else if (typeof Router !== 'undefined' && Router.navigate) {
            Router.navigate('access-management');
        }
    }

    function applyPendingOpen() {
        if (!pendingOpenEnvId) return;
        const envId = pendingOpenEnvId;
        pendingOpenEnvId = null;
        if (environments.find(e => e.environmentId === envId)) {
            selectedEnvironmentId = envId;
            $('#filter-environment').val(envId).trigger('change');
        }
        setTimeout(() => showGrantAccessModal(), 150);
    }

    /**
     * Wires a text input to a suggestion dropdown backed by a hidden id field — used for both
     * the Environment and User fields in the Grant Access modal. Typing invalidates any
     * previously selected value until a suggestion is clicked again.
     */
    function initAutocomplete({ inputSelector, hiddenSelector, menuSelector, minChars, fetchSuggestions, onSelect }) {
        const $input = $(inputSelector);
        const $hidden = $(hiddenSelector);
        const $menu = $(menuSelector);

        function closeMenu() {
            $menu.empty().hide();
        }

        function renderSuggestions(items) {
            if (!items || items.length === 0) {
                $menu.html('<div class="access-autocomplete-empty">No matches</div>').show();
                return;
            }
            $menu.data('items', items);
            $menu.html(items.map((item, idx) => `
                <button type="button" class="access-autocomplete-item" data-idx="${idx}">
                    ${escapeHtml(item.label)}
                </button>
            `).join('')).show();
        }

        async function handleInput() {
            const query = $input.val().trim();
            $hidden.val('');
            if (query.length < minChars) {
                closeMenu();
                return;
            }
            try {
                renderSuggestions(await fetchSuggestions(query));
            } catch (error) {
                console.error('Autocomplete search failed:', error);
                $menu.html('<div class="access-autocomplete-empty">Search failed</div>').show();
            }
        }

        $input.off('input.autocomplete').on('input.autocomplete', Utils.debounce(handleInput, 300));

        $menu.off('click').on('click', '.access-autocomplete-item', function() {
            const item = ($menu.data('items') || [])[$(this).data('idx')];
            if (!item) return;
            $input.val(item.label);
            $hidden.val(item.id);
            closeMenu();
            if (typeof onSelect === 'function') onSelect(item.id, item.label);
        });

        const outsideClickNamespace = 'click.autocomplete-' + inputSelector.replace(/[^a-zA-Z0-9]/g, '');
        $(document).off(outsideClickNamespace).on(outsideClickNamespace, function(e) {
            if (!$(e.target).closest(inputSelector).length && !$(e.target).closest(menuSelector).length) {
                closeMenu();
            }
        });

        return {
            reset() {
                $input.val('');
                $hidden.val('');
                closeMenu();
            },
            setValue(label, id) {
                $input.val(label || '');
                $hidden.val(id || '');
                closeMenu();
            }
        };
    }

    async function refreshAccessData() {
        [allAccess, activityLogs] = await Promise.all([
            fetchAccessForSelection(selectedEnvironmentId),
            fetchActivityLogsForSelection(selectedEnvironmentId)
        ]);
        filteredAccess = [...allAccess];
        updateStatsDisplay(calculateStats());
        applyFilters();
        renderActivityLogsTable();
    }

    /**
     * Handle grant / edit access form submission.
     */
    async function handleGrantAccess() {
        const accessId = $('#grant-access-id').val();
        const editing = !!accessId;
        const envId = $('#grant-environment').val();
        const userEmail = $('#grant-user-id').val();
        const accessLevel = $('#grant-access-level').val();
        const durationRaw = $('#grant-duration').val();
        const durationDays = durationRaw ? parseInt(durationRaw, 10) : null;
        const notes = $('#grant-notes').val().trim() || null;
        const scopeType = grantScope();
        const groupIds = scopeType === 'GROUP'
            ? $('#grant-group-list .grant-group-cb:checked').map((_, el) => el.value).get()
            : [];

        if (!editing && (!envId || !userEmail || !accessLevel)) {
            showToast('Pick a user, an environment and an access level', 'warning');
            return;
        }
        if (!editing && scopeType === 'GROUP' && groupIds.length === 0) {
            showToast('Tick at least one group', 'warning');
            return;
        }

        const $btn = $('#btn-confirm-grant');
        const origLabel = $('#btn-confirm-grant-label').text();
        $btn.prop('disabled', true).find('#btn-confirm-grant-label').text(editing ? 'Saving…' : 'Granting…');

        try {
            await new Promise((resolve, reject) => {
                const req = editing
                    ? ApiClient.patch(Config.API.access.updateGrant(accessId), {
                        accessLevel, durationDays,
                        clearExpiry: durationRaw === '' ? true : null,
                        notes
                    })
                    : ApiClient.post(Config.API.access.accessGrants, {
                        userEmail, environmentId: envId, accessLevel, scopeType,
                        groupIds, durationDays, notes
                    });
                req.done(resolve).fail(reject);
            });

            bootstrap.Modal.getInstance(document.getElementById('grantAccessModal')).hide();
            showToast(editing ? 'Grant updated' : 'Access granted', 'success');
            await refreshAccessData();
        } catch (error) {
            console.error('Grant/edit access failed:', error);
            showToast(error.responseJSON?.message || 'Failed to save access', 'danger');
        } finally {
            $btn.prop('disabled', false).find('#btn-confirm-grant-label').text(origLabel);
        }
    }

    /**
     * Handle revoke access (by grant id — works for environment and group scopes).
     */
    async function handleRevokeAccess(accessId, userName, scopeName) {
        Modals.confirm('Revoke Access',
            `Revoke access for <strong>${escapeHtml(userName || 'this user')}</strong> on <strong>${escapeHtml(scopeName || 'this scope')}</strong>?`,
            async function() {
            try {
                await new Promise((resolve, reject) => {
                    ApiClient.delete(Config.API.access.revokeGrant(accessId))
                        .done(resolve)
                        .fail(reject);
                });

                showToast('Access revoked', 'success');
                await refreshAccessData();
            } catch (error) {
                console.error('Revoke access failed:', error);
                showToast(error.responseJSON?.message || 'Failed to revoke access', 'danger');
            }
        }, { confirmText: 'Revoke', confirmClass: 'btn-danger' });
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
            [pendingRequests, allAccess, activityLogs] = await Promise.all([
                fetchPendingRequests(),
                fetchAccessForSelection(selectedEnvironmentId),
                fetchActivityLogsForSelection(selectedEnvironmentId)
            ]);
            filteredAccess = [...allAccess];

            // Update stats
            const stats = calculateStats();
            updateStatsDisplay(stats);

            // Update pending badge
            updatePendingTabBadge();

            renderPendingRequestsTable();
            renderActivityLogsTable();
            applyFilters();
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
                fetchActivityLogsForSelection(selectedEnvironmentId)
            ]);

            // Update stats
            const stats = calculateStats();
            updateStatsDisplay(stats);

            // Update pending badge
            updatePendingTabBadge();

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

    function getSelectedEnvironmentName() {
        if (!selectedEnvironmentId) {
            return 'All Environments';
        }
        const selectedEnv = environments.find(e => e.environmentId === selectedEnvironmentId);
        return selectedEnv ? (selectedEnv.displayName || selectedEnv.name) : 'Select Environment';
    }

    function updatePendingTabBadge() {
        $('#pending-review-tab').html(`
            Pending
            ${pendingRequests.length > 0 ? `<span class="badge text-bg-warning ms-1">${pendingRequests.length}</span>` : ''}
        `);
    }

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
        // Delegate to the app-wide toast system (js/ui/notifications.js).
        // 'danger' (Bootstrap naming, used by callers in this file) maps to
        // Notifications' 'error' type.
        if (window.Notifications && typeof Notifications.show === 'function') {
            const notifType = (type === 'danger') ? 'error' : type;
            Notifications.show(message, notifType);
        } else {
            console.log(`[${type.toUpperCase()}] ${message}`);
        }
    }

    // Public API
    return {
        load: load,
        openForEnvironment: openForEnvironment
    };

})();

// Make AccessManagement available globally
window.AccessManagement = AccessManagement;

