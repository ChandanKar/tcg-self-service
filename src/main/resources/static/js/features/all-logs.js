/**
 * VM Self-Service Platform - All Logs (Audit) Module
 * Handles admin audit log viewing, filtering, and reporting
 */

const AllLogs = (function() {
    'use strict';

    // Cache for filter state
    let currentFilters = {
        startDate: '',
        endDate: '',
        userId: '',
        environmentId: '',
        actionType: '',
        resultStatus: '',
        page: 0,
        size: 50
    };

    let allUsers = [];
    let allEnvironments = [];
    let stats = {
        totalActions: 0,
        successRate: '0%',
        failures: 0,
        topUser: '-',
        topEnvironment: '-'
    };

    /**
     * Load all audit logs page (admin only)
     */
    async function loadAllAuditLogs() {
        // Check admin permission
        if (!Auth.isEnvAdmin()) {
            showError('Access denied. This page is only available to administrators.');
            return;
        }

        try {
            showLoading();

            // Set default date range (last 24 hours)
            const endDate = new Date();
            const startDate = new Date();
            startDate.setDate(startDate.getDate() - 1);

            currentFilters.startDate = formatDateForApi(startDate);
            currentFilters.endDate = formatDateForApi(endDate);
            currentFilters.page = 0;

            // Load data in parallel
            const [logs, users, environments] = await Promise.all([
                fetchAllAuditLogs(currentFilters),
                fetchAllUsers(),
                fetchAllEnvironments()
            ]);

            allUsers = users || [];
            allEnvironments = environments || [];

            // Calculate stats from logs
            calculateStats(logs);

            const html = buildAllLogsHtml(logs);
            $('#content-area').html(html);
            bindAllLogsEvents();

            console.log('All audit logs loaded successfully');
        } catch (error) {
            console.error('Failed to load audit logs:', error);
            showError('Failed to load audit logs.');
        }
    }

    /**
     * Fetch all audit logs with filters
     */
    function fetchAllAuditLogs(filters) {
        return new Promise((resolve, reject) => {
            const params = new URLSearchParams();
            params.append('page', filters.page);
            params.append('size', filters.size);

            if (filters.startDate) params.append('startDate', filters.startDate);
            if (filters.endDate) params.append('endDate', filters.endDate);
            if (filters.userId) params.append('userId', filters.userId);
            if (filters.environmentId) params.append('environmentId', filters.environmentId);
            if (filters.actionType) params.append('action', filters.actionType);

            const url = `${Config.API.audit.logs}?${params.toString()}`;

            ApiClient.get(url)
                .done(function(data) {
                    resolve(data);
                })
                .fail(function(xhr) {
                    if (xhr.status === 404) {
                        resolve({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 50 });
                    } else {
                        reject(xhr);
                    }
                });
        });
    }

    /**
     * Fetch all users for filter dropdown
     */
    function fetchAllUsers() {
        return new Promise((resolve) => {
            ApiClient.get(Config.API.users.list)
                .done(function(data) {
                    const users = Array.isArray(data) ? data : [];
                    resolve(users.map(u => ({
                        id: u.userId || u.id,
                        email: u.email,
                        name: u.displayName || u.email
                    })));
                })
                .fail(() => {
                    resolve([]);
                });
        });
    }

    /**
     * Fetch all environments for filter dropdown
     */
    function fetchAllEnvironments() {
        return new Promise((resolve) => {
            ApiClient.get(Config.API.environments.list)
                .done(function(data) {
                    const envs = Array.isArray(data) ? data : [];
                    resolve(envs.map(e => ({
                        id: e.environmentId || e.id,
                        name: e.displayName || e.name
                    })));
                })
                .fail(() => {
                    resolve([]);
                });
        });
    }

    /**
     * Calculate statistics from audit logs
     */
    function calculateStats(data) {
        const logs = data.content || [];

        if (logs.length === 0) {
            stats = {
                totalActions: 0,
                successRate: '0%',
                failures: 0,
                topUser: '-',
                topEnvironment: '-'
            };
            return;
        }

        const total = data.totalElements || 0;
        const successful = logs.filter(l => l.success !== false).length;
        const failed = logs.filter(l => l.success === false).length;
        const successRate = total > 0 ? Math.round((successful / total) * 100) : 0;

        // Find top user and environment from current page
        const userCounts = {};
        const envCounts = {};

        logs.forEach(log => {
            if (log.userId) {
                userCounts[log.userId] = (userCounts[log.userId] || 0) + 1;
            }
            if (log.environmentId) {
                envCounts[log.environmentId] = (envCounts[log.environmentId] || 0) + 1;
            }
        });

        const topUser = Object.entries(userCounts)
            .sort((a, b) => b[1] - a[1])[0];
        const topEnv = Object.entries(envCounts)
            .sort((a, b) => b[1] - a[1])[0];

        stats = {
            totalActions: total,
            successRate: `${successRate}%`,
            failures: failed,
            topUser: topUser ? allUsers.find(u => u.id === topUser[0])?.email || topUser[0] : '-',
            topEnvironment: topEnv ? allEnvironments.find(e => e.id === topEnv[0])?.name || topEnv[0] : '-'
        };
    }

    /**
     * Build all logs page HTML
     */
    function buildAllLogsHtml(data) {
        const logs = data.content || [];
        const totalElements = data.totalElements || 0;
        const totalPages = data.totalPages || 0;
        const currentPage = data.number || 0;

        return `
            <div class="content-header">
                <h1><i class="fas fa-file-alt"></i> Global Audit Logs</h1>
                <p>Complete audit trail across all environments</p>
            </div>

            <!-- Admin Alert -->
            <div class="alert alert-info alert-dismissible fade show mb-4" role="alert">
                <i class="fas fa-info-circle"></i>
                <strong>Admin View</strong> - Showing activity from all users across all environments
                <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
            </div>

            <!-- Filter Bar -->
            <div class="card mb-4">
                <div class="card-body">
                    <div class="row g-3">
                        <div class="col-md-2">
                            <label class="form-label">Time Range</label>
                            <select class="form-select" id="time-range-filter">
                                <option value="24h">Last 24 hours</option>
                                <option value="7d">Last 7 days</option>
                                <option value="30d">Last 30 days</option>
                                <option value="custom">Custom range</option>
                            </select>
                        </div>
                        <div class="col-md-2">
                            <label class="form-label">User</label>
                            <select class="form-select" id="user-filter">
                                <option value="">All Users</option>
                                ${allUsers.map(u => `<option value="${u.id}" ${currentFilters.userId === u.id ? 'selected' : ''}>${u.email}</option>`).join('')}
                            </select>
                        </div>
                        <div class="col-md-2">
                            <label class="form-label">Environment</label>
                            <select class="form-select" id="environment-filter">
                                <option value="">All Environments</option>
                                ${allEnvironments.map(e => `<option value="${e.id}" ${currentFilters.environmentId === e.id ? 'selected' : ''}>${e.name}</option>`).join('')}
                            </select>
                        </div>
                        <div class="col-md-2">
                            <label class="form-label">Action</label>
                            <select class="form-select" id="action-type-filter">
                                <option value="">All Actions</option>
                                <optgroup label="VM Operations">
                                    <option value="VM_START_REQUESTED">VM Start</option>
                                    <option value="VM_STOP_REQUESTED">VM Stop</option>
                                    <option value="VM_RESTART_REQUESTED">VM Restart</option>
                                </optgroup>
                                <optgroup label="Lock Operations">
                                    <option value="LOCK_ACQUIRED">Lock Acquired</option>
                                    <option value="LOCK_RELEASED">Lock Released</option>
                                    <option value="LOCK_BROKEN">Lock Broken</option>
                                </optgroup>
                                <optgroup label="Access">
                                    <option value="ACCESS_GRANTED">Access Granted</option>
                                    <option value="ACCESS_REVOKED">Access Revoked</option>
                                </optgroup>
                                <optgroup label="User Management">
                                    <option value="USER_CREATED">User Created</option>
                                    <option value="USER_PROMOTED_TO_ADMIN">User Promoted</option>
                                </optgroup>
                            </select>
                        </div>
                        <div class="col-md-2">
                            <label class="form-label">Result</label>
                            <select class="form-select" id="result-filter">
                                <option value="">All Results</option>
                                <option value="true" ${currentFilters.resultStatus === 'true' ? 'selected' : ''}>
                                    <i class="fas fa-check-circle text-success"></i> Success
                                </option>
                                <option value="false" ${currentFilters.resultStatus === 'false' ? 'selected' : ''}>
                                    <i class="fas fa-times-circle text-danger"></i> Failure
                                </option>
                            </select>
                        </div>
                        <div class="col-md-2 d-flex align-items-end gap-2">
                            <button class="btn btn-outline-primary flex-grow-1" id="export-logs-btn">
                                <i class="fas fa-download"></i> Export
                            </button>
                        </div>
                    </div>

                    <!-- Custom Date Range -->
                    <div id="custom-date-range" class="row g-3 mt-2" style="display: none;">
                        <div class="col-md-3">
                            <label class="form-label">Start Date</label>
                            <input type="date" class="form-control" id="start-date-input" value="${currentFilters.startDate}">
                        </div>
                        <div class="col-md-3">
                            <label class="form-label">End Date</label>
                            <input type="date" class="form-control" id="end-date-input" value="${currentFilters.endDate}">
                        </div>
                        <div class="col-md-3 d-flex align-items-end">
                            <button class="btn btn-primary w-100" id="apply-custom-range-btn">Apply</button>
                        </div>
                    </div>
                </div>
            </div>

            <!-- Stats Cards -->
            <div class="row g-3 mb-4">
                <div class="col-md-2">
                    <div class="card text-center">
                        <div class="card-body">
                            <small class="text-muted d-block mb-2">Total Actions</small>
                            <h4 class="mb-0">${stats.totalActions.toLocaleString()}</h4>
                        </div>
                    </div>
                </div>
                <div class="col-md-2">
                    <div class="card text-center">
                        <div class="card-body">
                            <small class="text-muted d-block mb-2">Success Rate</small>
                            <h4 class="mb-0 text-success">${stats.successRate}</h4>
                        </div>
                    </div>
                </div>
                <div class="col-md-2">
                    <div class="card text-center">
                        <div class="card-body">
                            <small class="text-muted d-block mb-2">Failures</small>
                            <h4 class="mb-0 text-danger">${stats.failures}</h4>
                        </div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="card text-center">
                        <div class="card-body">
                            <small class="text-muted d-block mb-2">Most Active User</small>
                            <p class="mb-0" style="font-size: 0.9rem; overflow: hidden; text-overflow: ellipsis;">${stats.topUser}</p>
                        </div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="card text-center">
                        <div class="card-body">
                            <small class="text-muted d-block mb-2">Most Active Env</small>
                            <p class="mb-0" style="font-size: 0.9rem; overflow: hidden; text-overflow: ellipsis;">${stats.topEnvironment}</p>
                        </div>
                    </div>
                </div>
            </div>

            <!-- Results Table -->
            <div class="card">
                <div class="table-responsive">
                    <table class="table table-hover mb-0">
                        <thead class="table-light">
                            <tr>
                                <th>Timestamp</th>
                                <th>User</th>
                                <th>Environment</th>
                                <th>Action</th>
                                <th>Target</th>
                                <th>Result</th>
                                <th>Details</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${logs.length > 0 ? logs.map(log => buildAuditLogRow(log)).join('') : `
                                <tr>
                                    <td colspan="7" class="text-center text-muted py-4">
                                        <i class="fas fa-inbox fa-3x mb-2" style="opacity: 0.5;"></i>
                                        <p>No audit logs found for the selected filters</p>
                                    </td>
                                </tr>
                            `}
                        </tbody>
                    </table>
                </div>
            </div>

            <!-- Pagination -->
            ${totalPages > 0 ? `
            <nav aria-label="Pagination" class="mt-4">
                <ul class="pagination justify-content-between">
                    <li class="me-auto">
                        <small class="text-muted">Showing ${logs.length > 0 ? (currentPage * currentFilters.size + 1) : 0}-${Math.min((currentPage + 1) * currentFilters.size, totalElements)} of ${totalElements} results</small>
                    </li>
                    <li class="page-item ${currentPage === 0 ? 'disabled' : ''}">
                        <button class="page-link" id="prev-page-btn" ${currentPage === 0 ? 'disabled' : ''}>
                            <i class="fas fa-chevron-left"></i> Previous
                        </button>
                    </li>
                    <li class="page-item ms-2">
                        <small class="text-muted d-inline-block" style="padding: 0.375rem 0.75rem;">
                            Page ${currentPage + 1} of ${totalPages}
                        </small>
                    </li>
                    <li class="page-item ${currentPage >= totalPages - 1 ? 'disabled' : ''} ms-2">
                        <button class="page-link" id="next-page-btn" ${currentPage >= totalPages - 1 ? 'disabled' : ''}>
                            Next <i class="fas fa-chevron-right"></i>
                        </button>
                    </li>
                </ul>
            </nav>
            ` : ''}
        `;
    }

    /**
     * Build a single audit log row
     */
    function buildAuditLogRow(log) {
        const timestamp = formatTimestamp(log.createdAt);
        const actionBadgeClass = getActionBadgeClass(log.action);
        const actionDisplay = log.actionDisplay || formatActionName(log.action);
        const resultIcon = log.success !== false ? '<i class="fas fa-check-circle text-success"></i>' : '<i class="fas fa-times-circle text-danger"></i>';
        const details = log.details ? log.details.substring(0, 40) + (log.details.length > 40 ? '...' : '') : (log.errorMessage ? log.errorMessage.substring(0, 40) + (log.errorMessage.length > 40 ? '...' : '') : '-');
        const userEmail = log.userEmail || log.userId || 'system';

        return `
            <tr>
                <td>
                    <div>${timestamp.relative}</div>
                    <small class="text-muted">${timestamp.absolute}</small>
                </td>
                <td><small>${userEmail}</small></td>
                <td>${log.environmentName || log.environmentId || '-'}</td>
                <td>
                    <span class="badge ${actionBadgeClass}">${actionDisplay}</span>
                </td>
                <td>${log.targetName || '-'}</td>
                <td class="text-center">${resultIcon}</td>
                <td title="${log.details || log.errorMessage || ''}">${details}</td>
            </tr>
        `;
    }

    /**
     * Bind event handlers for filter changes
     */
    function bindAllLogsEvents() {
        // Time range filter
        $('#time-range-filter').on('change', function() {
            const value = $(this).val();
            if (value === 'custom') {
                $('#custom-date-range').show();
            } else {
                $('#custom-date-range').hide();
                applyTimeRangeFilter(value);
            }
        });

        // User filter
        $('#user-filter').on('change', function() {
            currentFilters.userId = $(this).val();
            currentFilters.page = 0;
            loadAllLogs();
        });

        // Environment filter
        $('#environment-filter').on('change', function() {
            currentFilters.environmentId = $(this).val();
            currentFilters.page = 0;
            loadAllLogs();
        });

        // Action type filter
        $('#action-type-filter').on('change', function() {
            currentFilters.actionType = $(this).val();
            currentFilters.page = 0;
            loadAllLogs();
        });

        // Result filter
        $('#result-filter').on('change', function() {
            currentFilters.resultStatus = $(this).val();
            currentFilters.page = 0;
            loadAllLogs();
        });

        // Custom date range
        $('#apply-custom-range-btn').on('click', function() {
            const startDate = $('#start-date-input').val();
            const endDate = $('#end-date-input').val();

            if (!startDate || !endDate) {
                Notifications.show('Please select both start and end dates', 'warning');
                return;
            }

            if (new Date(startDate) > new Date(endDate)) {
                Notifications.show('Start date must be before end date', 'warning');
                return;
            }

            currentFilters.startDate = startDate;
            currentFilters.endDate = endDate;
            currentFilters.page = 0;
            loadAllLogs();
        });

        // Pagination
        $('#prev-page-btn').on('click', function() {
            if (currentFilters.page > 0) {
                currentFilters.page--;
                loadAllLogs();
            }
        });

        $('#next-page-btn').on('click', function() {
            currentFilters.page++;
            loadAllLogs();
        });

        // Export
        $('#export-logs-btn').on('click', function() {
            exportAuditLogs();
        });
    }

    /**
     * Apply time range filter
     */
    function applyTimeRangeFilter(range) {
        const endDate = new Date();
        const startDate = new Date();

        switch (range) {
            case '24h':
                startDate.setDate(startDate.getDate() - 1);
                break;
            case '7d':
                startDate.setDate(startDate.getDate() - 7);
                break;
            case '30d':
                startDate.setDate(startDate.getDate() - 30);
                break;
            default:
                return;
        }

        currentFilters.startDate = formatDateForApi(startDate);
        currentFilters.endDate = formatDateForApi(endDate);
        currentFilters.page = 0;
        loadAllLogs();
    }

    /**
     * Reload all logs with current filters
     */
    function loadAllLogs() {
        showLoading();
        fetchAllAuditLogs(currentFilters)
            .then(logs => {
                calculateStats(logs);
                const html = buildAllLogsHtml(logs);
                $('#content-area').html(html);
                bindAllLogsEvents();
            })
            .catch(error => {
                console.error('Error loading audit logs:', error);
                showError('Failed to load audit logs.');
            });
    }

    /**
     * Export audit logs to CSV
     */
    function exportAuditLogs() {
        const fileName = `audit-logs-${new Date().toISOString().split('T')[0]}.csv`;
        const headers = ['Timestamp', 'User', 'Environment', 'Action', 'Target', 'Result', 'Details'];

        // Fetch all logs for export (without pagination)
        const exportParams = { ...currentFilters, page: 0, size: 10000 };
        fetchAllAuditLogs(exportParams)
            .then(data => {
                const logs = data.content || [];
                const rows = logs.map(log => [
                    log.createdAt,
                    log.userEmail || log.userId || 'system',
                    log.environmentName || log.environmentId || '',
                    log.actionDisplay || log.action || '',
                    log.targetName || '',
                    log.success !== false ? 'Success' : 'Failed',
                    log.details || log.errorMessage || ''
                ]);

                const csv = [
                    headers.join(','),
                    ...rows.map(row => row.map(cell => `"${(cell || '').toString().replace(/"/g, '""')}"`).join(','))
                ].join('\n');

                const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
                const link = document.createElement('a');
                link.setAttribute('href', URL.createObjectURL(blob));
                link.setAttribute('download', fileName);
                link.style.visibility = 'hidden';
                document.body.appendChild(link);
                link.click();
                document.body.removeChild(link);

                Notifications.show('Audit logs exported successfully', 'success');
            })
            .catch(error => {
                console.error('Error exporting logs:', error);
                Notifications.show('Failed to export logs', 'danger');
            });
    }

    /**
     * Helper: Format timestamp for display
     */
    function formatTimestamp(timestamp) {
        if (!timestamp) return { relative: '-', absolute: '' };

        const date = new Date(timestamp);
        const now = new Date();
        const diff = now - date;
        const minutes = Math.floor(diff / 60000);
        const hours = Math.floor(diff / 3600000);
        const days = Math.floor(diff / 86400000);

        let relative = '';
        if (minutes < 60) {
            relative = `${minutes}m ago`;
        } else if (hours < 24) {
            relative = `${hours}h ago`;
        } else if (days < 7) {
            relative = `${days}d ago`;
        } else {
            relative = date.toLocaleDateString();
        }

        const absolute = date.toLocaleString();

        return { relative, absolute };
    }

    /**
     * Helper: Format date for API calls (YYYY-MM-DD)
     */
    function formatDateForApi(date) {
        return date.toISOString().split('T')[0];
    }

    /**
     * Helper: Get badge CSS class for action type
     */
    function getActionBadgeClass(action) {
        if (!action) return 'bg-secondary';

        const actionStr = action.toString().toLowerCase();
        if (actionStr.includes('start')) return 'bg-success';
        if (actionStr.includes('stop')) return 'bg-danger';
        if (actionStr.includes('lock')) return 'bg-warning';
        if (actionStr.includes('restart')) return 'bg-info';
        if (actionStr.includes('failed')) return 'bg-danger';
        if (actionStr.includes('access')) return 'bg-primary';
        if (actionStr.includes('user')) return 'bg-info';

        return 'bg-secondary';
    }

    /**
     * Helper: Format action enum name
     */
    function formatActionName(action) {
        if (!action) return '';
        return action.replace(/_/g, ' ').toLowerCase()
            .split(' ')
            .map(word => word.charAt(0).toUpperCase() + word.slice(1))
            .join(' ');
    }

    /**
     * Show loading state
     */
    function showLoading() {
        $('#content-area').html(`
            <div class="d-flex justify-content-center align-items-center" style="min-height: 400px;">
                <div class="text-center">
                    <div class="spinner-border text-primary mb-3" role="status">
                        <span class="visually-hidden">Loading...</span>
                    </div>
                    <p class="text-muted">Loading audit logs...</p>
                </div>
            </div>
        `);
    }

    /**
     * Show error state
     */
    function showError(message) {
        $('#content-area').html(`
            <div class="d-flex justify-content-center align-items-center" style="min-height: 400px;">
                <div class="text-center">
                    <i class="fas fa-exclamation-triangle text-danger fa-3x mb-3"></i>
                    <h5>Error</h5>
                    <p class="text-muted">${message}</p>
                    <button class="btn btn-primary" onclick="AllLogs.loadAllAuditLogs()">Retry</button>
                </div>
            </div>
        `);
    }

    return {
        loadAllAuditLogs
    };
})();



