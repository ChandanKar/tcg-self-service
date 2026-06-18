/**
 * VM Self-Service Platform - Audit Logs Module
 * Handles audit log viewing and filtering
 */

const AuditLogs = (function() {
    'use strict';

    // Cache for filter state
    let currentFilters = {
        environmentId: '',
        actionType: '',
        userId: '',
        startDate: '',
        endDate: '',
        page: 0,
        size: 50
    };

    /**
     * Load My Activity Logs page (user view)
     */
    async function loadMyActivityLogs() {
        showLoading();

        try {
            const logs = await fetchMyLogs();
            const html = buildMyActivityLogsHtml(logs);
            $('#content-area').html(html);
            bindActivityLogEvents();
        } catch (error) {
            console.error('Failed to load activity logs:', error);
            showError('Failed to load activity logs.');
        }
    }

    /**
     * Load All Audit Logs page (admin view)
     */
    async function loadAllAuditLogs() {
        if (!Auth.isEnvAdmin()) {
            $('#content-area').html('<div class="alert alert-danger">Access denied</div>');
            return;
        }

        showLoading();

        try {
            // Load filter options and logs in parallel
            const [environments, logs] = await Promise.all([
                fetchEnvironments(),
                fetchAuditLogs(currentFilters)
            ]);

            const html = buildAllAuditLogsHtml(environments, logs);
            $('#content-area').html(html);
            bindAuditLogEvents();
        } catch (error) {
            console.error('Failed to load audit logs:', error);
            showError('Failed to load audit logs.');
        }
    }

    /**
     * Fetch user's own logs
     */
    function fetchMyLogs() {
        return new Promise((resolve, reject) => {
            ApiClient.get(Config.API.audit.myLogs(Auth.getCurrentUserId()))
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
     * Fetch all audit logs with filters
     */
    function fetchAuditLogs(filters) {
        return new Promise((resolve, reject) => {
            const params = new URLSearchParams();
            if (filters.environmentId) params.append('environmentId', filters.environmentId);
            if (filters.actionType) params.append('actionType', filters.actionType);
            if (filters.userId) params.append('userId', filters.userId);
            if (filters.startDate) params.append('startDate', filters.startDate);
            if (filters.endDate) params.append('endDate', filters.endDate);
            params.append('page', filters.page);
            params.append('size', filters.size);

            const url = `${Config.API.audit.allLogs}?${params.toString()}`;

            ApiClient.get(url)
                .done(resolve)
                .fail(function(xhr) {
                    if (xhr.status === 404) {
                        resolve({ content: [], totalElements: 0, totalPages: 0 });
                    } else {
                        reject(xhr);
                    }
                });
        });
    }

    /**
     * Fetch environments for filter dropdown
     */
    function fetchEnvironments() {
        return new Promise((resolve) => {
            ApiClient.get(Config.API.environments.list)
                .done(resolve)
                .fail(() => resolve([]));
        });
    }

    /**
     * Show loading state
     */
    function showLoading() {
        $('#content-area').html(`
            <div class="loading-state">
                <div class="spinner-border text-primary" role="status"></div>
                <p>Loading audit logs...</p>
            </div>
        `);
    }

    /**
     * Show error state
     */
    function showError(message) {
        $('#content-area').html(`
            <div class="error-state">
                <i class="fas fa-exclamation-triangle fa-3x text-warning"></i>
                <h4 class="mt-3">Error</h4>
                <p>${message}</p>
                <button class="btn btn-primary" onclick="Dashboard.load()">Back to Dashboard</button>
            </div>
        `);
    }

    /**
     * Build My Activity Logs HTML
     */
    function buildMyActivityLogsHtml(logs) {
        const logsArray = logs.content || logs || [];

        return `
            <div class="content-header">
                <h1>My Activity Logs</h1>
                <p>Track your start/stop operations and lock management</p>
            </div>

            <!-- Quick Filters -->
            <div class="row mb-4">
                <div class="col-md-3">
                    <select class="form-select" id="time-filter">
                        <option value="24h">Last 24 hours</option>
                        <option value="7d" selected>Last 7 days</option>
                        <option value="30d">Last 30 days</option>
                        <option value="all">All time</option>
                    </select>
                </div>
                <div class="col-md-3">
                    <select class="form-select" id="action-filter">
                        <option value="">All Actions</option>
                        <option value="START">Start Operations</option>
                        <option value="STOP">Stop Operations</option>
                        <option value="LOCK">Lock Operations</option>
                        <option value="ACCESS">Access Changes</option>
                    </select>
                </div>
                <div class="col-md-4">
                    <div class="input-group">
                        <span class="input-group-text"><i class="fas fa-search"></i></span>
                        <input type="text" class="form-control" id="log-search" placeholder="Search logs...">
                    </div>
                </div>
                <div class="col-md-2">
                    <button class="btn btn-outline-primary w-100" id="export-logs">
                        <i class="fas fa-download"></i> Export
                    </button>
                </div>
            </div>

            <!-- Activity Summary -->
            ${buildActivitySummary(logsArray)}

            <!-- Logs Table -->
            <div class="card">
                <div class="card-body p-0">
                    ${buildLogsTable(logsArray)}
                </div>
            </div>
        `;
    }

    /**
     * Build All Audit Logs HTML (admin)
     */
    function buildAllAuditLogsHtml(environments, logs) {
        const logsData = logs.content || logs || [];
        const totalPages = logs.totalPages || 1;
        const currentPage = currentFilters.page;

        const envOptions = environments.map(env =>
            `<option value="${env.environmentId}" ${currentFilters.environmentId === env.environmentId ? 'selected' : ''}>
                ${Utils.escapeHtml(env.name)}
            </option>`
        ).join('');

        return `
            <div class="content-header d-flex justify-content-between align-items-start">
                <div>
                    <h1>Audit Logs</h1>
                    <p>View all system activity across environments</p>
                </div>
                <button class="btn btn-outline-primary" id="export-all-logs">
                    <i class="fas fa-download"></i> Export CSV
                </button>
            </div>

            <!-- Advanced Filters -->
            <div class="card mb-4">
                <div class="card-header">
                    <h6 class="mb-0"><i class="fas fa-filter me-2"></i>Filters</h6>
                </div>
                <div class="card-body">
                    <form id="audit-filter-form">
                        <div class="row">
                            <div class="col-md-3 mb-3">
                                <label class="form-label">Environment</label>
                                <select class="form-select" id="filter-env">
                                    <option value="">All Environments</option>
                                    ${envOptions}
                                </select>
                            </div>
                            <div class="col-md-2 mb-3">
                                <label class="form-label">Action Type</label>
                                <select class="form-select" id="filter-action">
                                    <option value="">All Actions</option>
                                    <option value="START_VM">Start VM</option>
                                    <option value="STOP_VM">Stop VM</option>
                                    <option value="START_GROUP">Start Group</option>
                                    <option value="STOP_GROUP">Stop Group</option>
                                    <option value="START_ENVIRONMENT">Start Env</option>
                                    <option value="STOP_ENVIRONMENT">Stop Env</option>
                                    <option value="LOCK_ACQUIRED">Lock Acquired</option>
                                    <option value="LOCK_RELEASED">Lock Released</option>
                                    <option value="LOCK_BROKEN">Lock Broken</option>
                                    <option value="ACCESS_GRANTED">Access Granted</option>
                                    <option value="ACCESS_REVOKED">Access Revoked</option>
                                    <option value="ACCESS_REQUESTED">Access Requested</option>
                                </select>
                            </div>
                            <div class="col-md-2 mb-3">
                                <label class="form-label">Start Date</label>
                                <input type="date" class="form-control" id="filter-start-date"
                                       value="${currentFilters.startDate}">
                            </div>
                            <div class="col-md-2 mb-3">
                                <label class="form-label">End Date</label>
                                <input type="date" class="form-control" id="filter-end-date"
                                       value="${currentFilters.endDate}">
                            </div>
                            <div class="col-md-3 mb-3">
                                <label class="form-label">Search</label>
                                <input type="text" class="form-control" id="filter-search"
                                       placeholder="User, target, message...">
                            </div>
                        </div>
                        <div class="d-flex gap-2">
                            <button type="submit" class="btn btn-primary">
                                <i class="fas fa-search"></i> Apply Filters
                            </button>
                            <button type="button" class="btn btn-outline-secondary" id="clear-filters">
                                <i class="fas fa-times"></i> Clear
                            </button>
                        </div>
                    </form>
                </div>
            </div>

            <!-- Logs Table -->
            <div class="card">
                <div class="card-body p-0">
                    ${buildAdminLogsTable(logsData)}
                </div>
                ${totalPages > 1 ? buildPagination(currentPage, totalPages) : ''}
            </div>
        `;
    }

    /**
     * Build activity summary cards
     */
    function buildActivitySummary(logs) {
        const today = new Date();
        today.setHours(0, 0, 0, 0);

        const todayLogs = logs.filter(log => new Date(log.timestamp) >= today);
        const startOps = logs.filter(log => log.actionType?.includes('START')).length;
        const stopOps = logs.filter(log => log.actionType?.includes('STOP')).length;
        const lockOps = logs.filter(log => log.actionType?.includes('LOCK')).length;

        return `
            <div class="row mb-4">
                <div class="col-md-3">
                    <div class="metric-card">
                        <div class="metric-title">Today's Activity</div>
                        <div class="metric-value">${todayLogs.length}</div>
                        <div class="metric-subtitle">operations</div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="metric-card">
                        <div class="metric-title">Start Operations</div>
                        <div class="metric-value text-success">${startOps}</div>
                        <div class="metric-subtitle">total</div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="metric-card">
                        <div class="metric-title">Stop Operations</div>
                        <div class="metric-value text-danger">${stopOps}</div>
                        <div class="metric-subtitle">total</div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="metric-card">
                        <div class="metric-title">Lock Operations</div>
                        <div class="metric-value text-warning">${lockOps}</div>
                        <div class="metric-subtitle">total</div>
                    </div>
                </div>
            </div>
        `;
    }

    /**
     * Build logs table (user view)
     */
    function buildLogsTable(logs) {
        if (!logs || logs.length === 0) {
            return `
                <div class="empty-state p-5">
                    <i class="fas fa-clipboard-list fa-3x text-muted mb-3"></i>
                    <p>No activity logs found</p>
                </div>
            `;
        }

        const rows = logs.map(log => {
            const actionBadge = getActionBadge(log.actionType);
            const statusIcon = log.status === 'SUCCESS' ?
                '<i class="fas fa-check-circle text-success"></i>' :
                log.status === 'FAILED' ?
                '<i class="fas fa-times-circle text-danger"></i>' :
                '<i class="fas fa-clock text-muted"></i>';

            return `
                <tr class="log-row" data-action="${log.actionType || ''}">
                    <td class="text-nowrap">${Utils.formatRelativeTime(log.timestamp)}</td>
                    <td><strong>${Utils.escapeHtml(log.environmentName || '-')}</strong></td>
                    <td>${actionBadge}</td>
                    <td>${Utils.escapeHtml(log.targetName || log.targetId || '-')}</td>
                    <td>${statusIcon} ${log.status || '-'}</td>
                    <td>
                        ${log.details || log.message ?
                            `<button class="btn btn-sm btn-outline-secondary" data-log-id="${log.logId}" data-action="view-details">
                                <i class="fas fa-info-circle"></i>
                            </button>` : '-'
                        }
                    </td>
                </tr>
            `;
        }).join('');

        return `
            <table class="table table-hover mb-0">
                <thead>
                    <tr>
                        <th>Time</th>
                        <th>Environment</th>
                        <th>Action</th>
                        <th>Target</th>
                        <th>Result</th>
                        <th>Details</th>
                    </tr>
                </thead>
                <tbody id="logs-table-body">
                    ${rows}
                </tbody>
            </table>
        `;
    }

    /**
     * Build logs table (admin view)
     */
    function buildAdminLogsTable(logs) {
        if (!logs || logs.length === 0) {
            return `
                <div class="empty-state p-5">
                    <i class="fas fa-clipboard-list fa-3x text-muted mb-3"></i>
                    <p>No audit logs found matching your criteria</p>
                </div>
            `;
        }

        const rows = logs.map(log => {
            const actionBadge = getActionBadge(log.actionType);
            const statusIcon = log.status === 'SUCCESS' ?
                '<i class="fas fa-check-circle text-success"></i>' :
                log.status === 'FAILED' ?
                '<i class="fas fa-times-circle text-danger"></i>' :
                '<i class="fas fa-clock text-muted"></i>';

            return `
                <tr>
                    <td class="text-nowrap">
                        <small>${Utils.formatDate(log.timestamp)}</small>
                    </td>
                    <td>
                        <strong>${Utils.escapeHtml(log.userDisplayName || 'Unknown')}</strong>
                        <br><small class="text-muted">${Utils.escapeHtml(log.userEmail || '')}</small>
                    </td>
                    <td><strong>${Utils.escapeHtml(log.environmentName || '-')}</strong></td>
                    <td>${actionBadge}</td>
                    <td>${Utils.escapeHtml(log.targetName || log.targetId || '-')}</td>
                    <td>${statusIcon}</td>
                    <td>
                        <button class="btn btn-sm btn-outline-secondary" data-log='${JSON.stringify(log).replace(/'/g, "&#39;")}' data-action="view-log-details">
                            <i class="fas fa-eye"></i>
                        </button>
                    </td>
                </tr>
            `;
        }).join('');

        return `
            <table class="table table-sm table-hover mb-0">
                <thead>
                    <tr>
                        <th>Timestamp</th>
                        <th>User</th>
                        <th>Environment</th>
                        <th>Action</th>
                        <th>Target</th>
                        <th>Status</th>
                        <th></th>
                    </tr>
                </thead>
                <tbody>
                    ${rows}
                </tbody>
            </table>
        `;
    }

    /**
     * Build pagination
     */
    function buildPagination(currentPage, totalPages) {
        let pages = '';
        const maxVisible = 5;
        let start = Math.max(0, currentPage - Math.floor(maxVisible / 2));
        let end = Math.min(totalPages, start + maxVisible);

        if (end - start < maxVisible) {
            start = Math.max(0, end - maxVisible);
        }

        for (let i = start; i < end; i++) {
            pages += `
                <li class="page-item ${i === currentPage ? 'active' : ''}">
                    <a class="page-link" href="#" data-page="${i}">${i + 1}</a>
                </li>
            `;
        }

        return `
            <div class="card-footer d-flex justify-content-between align-items-center">
                <span class="text-muted">Page ${currentPage + 1} of ${totalPages}</span>
                <nav>
                    <ul class="pagination pagination-sm mb-0">
                        <li class="page-item ${currentPage === 0 ? 'disabled' : ''}">
                            <a class="page-link" href="#" data-page="${currentPage - 1}">
                                <i class="fas fa-chevron-left"></i>
                            </a>
                        </li>
                        ${pages}
                        <li class="page-item ${currentPage >= totalPages - 1 ? 'disabled' : ''}">
                            <a class="page-link" href="#" data-page="${currentPage + 1}">
                                <i class="fas fa-chevron-right"></i>
                            </a>
                        </li>
                    </ul>
                </nav>
            </div>
        `;
    }

    /**
     * Get action badge HTML
     */
    function getActionBadge(actionType) {
        const actionConfig = {
            'START_VM': { class: 'bg-success', icon: 'fa-play', label: 'Start VM' },
            'STOP_VM': { class: 'bg-danger', icon: 'fa-stop', label: 'Stop VM' },
            'START_GROUP': { class: 'bg-success', icon: 'fa-play', label: 'Start Group' },
            'STOP_GROUP': { class: 'bg-danger', icon: 'fa-stop', label: 'Stop Group' },
            'START_ENVIRONMENT': { class: 'bg-success', icon: 'fa-play', label: 'Start Env' },
            'STOP_ENVIRONMENT': { class: 'bg-danger', icon: 'fa-stop', label: 'Stop Env' },
            'LOCK_ACQUIRED': { class: 'bg-warning', icon: 'fa-lock', label: 'Lock' },
            'LOCK_RELEASED': { class: 'bg-secondary', icon: 'fa-unlock', label: 'Unlock' },
            'LOCK_BROKEN': { class: 'bg-danger', icon: 'fa-hammer', label: 'Break Lock' },
            'ACCESS_GRANTED': { class: 'bg-info', icon: 'fa-user-plus', label: 'Grant Access' },
            'ACCESS_REVOKED': { class: 'bg-warning', icon: 'fa-user-minus', label: 'Revoke Access' },
            'ACCESS_REQUESTED': { class: 'bg-primary', icon: 'fa-paper-plane', label: 'Request Access' },
            'ACCESS_APPROVED': { class: 'bg-success', icon: 'fa-check', label: 'Approve' },
            'ACCESS_DENIED': { class: 'bg-danger', icon: 'fa-times', label: 'Deny' },
            'ENV_CREATED': { class: 'bg-primary', icon: 'fa-plus', label: 'Create Env' },
            'ENV_UPDATED': { class: 'bg-info', icon: 'fa-edit', label: 'Update Env' },
            'ENV_DELETED': { class: 'bg-danger', icon: 'fa-trash', label: 'Delete Env' },
            'VM_REGISTERED': { class: 'bg-primary', icon: 'fa-plus', label: 'Register VM' },
            'VM_DELETED': { class: 'bg-danger', icon: 'fa-trash', label: 'Delete VM' },
            'GROUP_CREATED': { class: 'bg-primary', icon: 'fa-plus', label: 'Create Group' },
            'GROUP_DELETED': { class: 'bg-danger', icon: 'fa-trash', label: 'Delete Group' }
        };

        const config = actionConfig[actionType] || {
            class: 'bg-secondary',
            icon: 'fa-circle',
            label: actionType || 'Unknown'
        };

        return `
            <span class="badge ${config.class}">
                <i class="fas ${config.icon} me-1"></i>${config.label}
            </span>
        `;
    }

    /**
     * Bind events for My Activity Logs
     */
    function bindActivityLogEvents() {
        // Time filter
        $('#time-filter').off('change').on('change', function() {
            filterLogs();
        });

        // Action filter
        $('#action-filter').off('change').on('change', function() {
            filterLogs();
        });

        // Search
        $('#log-search').off('input').on('input', Utils.debounce(function() {
            filterLogs();
        }, 300));

        // View details
        $('[data-action="view-details"]').off('click').on('click', function() {
            const logId = $(this).data('log-id');
            showLogDetails(logId);
        });

        // Export
        $('#export-logs').off('click').on('click', function() {
            exportLogs('my');
        });
    }

    /**
     * Bind events for All Audit Logs
     */
    function bindAuditLogEvents() {
        // Filter form submit
        $('#audit-filter-form').off('submit').on('submit', function(e) {
            e.preventDefault();
            applyFilters();
        });

        // Clear filters
        $('#clear-filters').off('click').on('click', function() {
            clearFilters();
        });

        // Pagination
        $('.page-link').off('click').on('click', function(e) {
            e.preventDefault();
            const page = $(this).data('page');
            if (page >= 0) {
                currentFilters.page = page;
                loadAllAuditLogs();
            }
        });

        // View details
        $('[data-action="view-log-details"]').off('click').on('click', function() {
            const log = $(this).data('log');
            showLogDetailsModal(log);
        });

        // Export
        $('#export-all-logs').off('click').on('click', function() {
            exportLogs('all');
        });
    }

    /**
     * Filter logs (client-side for user view)
     */
    function filterLogs() {
        const timeFilter = $('#time-filter').val();
        const actionFilter = $('#action-filter').val();
        const searchQuery = $('#log-search').val().toLowerCase();

        const now = new Date();
        let cutoffDate = null;

        switch (timeFilter) {
            case '24h':
                cutoffDate = new Date(now - 24 * 60 * 60 * 1000);
                break;
            case '7d':
                cutoffDate = new Date(now - 7 * 24 * 60 * 60 * 1000);
                break;
            case '30d':
                cutoffDate = new Date(now - 30 * 24 * 60 * 60 * 1000);
                break;
        }

        $('#logs-table-body tr').each(function() {
            const $row = $(this);
            const action = $row.data('action') || '';
            const text = $row.text().toLowerCase();

            let visible = true;

            // Action filter
            if (actionFilter && !action.includes(actionFilter)) {
                visible = false;
            }

            // Search filter
            if (searchQuery && !text.includes(searchQuery)) {
                visible = false;
            }

            $row.toggle(visible);
        });
    }

    /**
     * Apply filters and reload (admin view)
     */
    function applyFilters() {
        currentFilters.environmentId = $('#filter-env').val();
        currentFilters.actionType = $('#filter-action').val();
        currentFilters.startDate = $('#filter-start-date').val();
        currentFilters.endDate = $('#filter-end-date').val();
        currentFilters.page = 0; // Reset to first page

        loadAllAuditLogs();
    }

    /**
     * Clear all filters
     */
    function clearFilters() {
        currentFilters = {
            environmentId: '',
            actionType: '',
            userId: '',
            startDate: '',
            endDate: '',
            page: 0,
            size: 50
        };

        loadAllAuditLogs();
    }

    /**
     * Show log details modal
     */
    function showLogDetailsModal(log) {
        const details = `
            <table class="table table-sm">
                <tr><th width="30%">Timestamp</th><td>${Utils.formatDate(log.timestamp)}</td></tr>
                <tr><th>User</th><td>${Utils.escapeHtml(log.userDisplayName || log.userId || '-')}</td></tr>
                <tr><th>Email</th><td>${Utils.escapeHtml(log.userEmail || '-')}</td></tr>
                <tr><th>Environment</th><td>${Utils.escapeHtml(log.environmentName || '-')}</td></tr>
                <tr><th>Action</th><td>${getActionBadge(log.actionType)}</td></tr>
                <tr><th>Target</th><td>${Utils.escapeHtml(log.targetName || log.targetId || '-')}</td></tr>
                <tr><th>Status</th><td>
                    ${log.status === 'SUCCESS' ?
                        '<span class="badge bg-success">Success</span>' :
                        log.status === 'FAILED' ?
                        '<span class="badge bg-danger">Failed</span>' :
                        `<span class="badge bg-secondary">${log.status || 'Unknown'}</span>`
                    }
                </td></tr>
                ${log.errorMessage ? `<tr><th>Error</th><td class="text-danger">${Utils.escapeHtml(log.errorMessage)}</td></tr>` : ''}
                ${log.details ? `<tr><th>Details</th><td><pre class="mb-0">${Utils.escapeHtml(JSON.stringify(log.details, null, 2))}</pre></td></tr>` : ''}
                ${log.ipAddress ? `<tr><th>IP Address</th><td><code>${Utils.escapeHtml(log.ipAddress)}</code></td></tr>` : ''}
                ${log.userAgent ? `<tr><th>User Agent</th><td><small>${Utils.escapeHtml(log.userAgent)}</small></td></tr>` : ''}
            </table>
        `;

        Modals.show({
            id: 'logDetailsModal',
            title: 'Audit Log Details',
            body: details,
            buttons: [
                { text: 'Close', class: 'btn-secondary', dismiss: true }
            ]
        });
    }

    /**
     * Export logs to CSV
     */
    function exportLogs(type) {
        Notifications.info('Preparing export...');

        const endpoint = type === 'all' ?
            `${Config.API.audit.export}?${new URLSearchParams(currentFilters).toString()}` :
            Config.API.audit.exportMy;

        // Create a download link
        const link = document.createElement('a');
        link.href = endpoint;
        link.download = `audit-logs-${new Date().toISOString().split('T')[0]}.csv`;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);

        Notifications.success('Export started');
    }

    // Public API
    return {
        loadMyActivityLogs,
        loadAllAuditLogs
    };
})();

