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
        timeRange: '24h',
        page: 0,
        size: 1000
    };

    let allUsers = [];
    let allEnvironments = [];
    let stats = {
        totalActions: 0,
        successRate: '0%',
        failures: 0,
        topUser: 'N/A',
        topEnvironment: 'N/A'
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
            if (filters.resultStatus !== '') params.append('success', filters.resultStatus);

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
        const successRate = logs.length > 0 ? Math.round((successful / logs.length) * 100) : 0;

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
            topUser: topUser ? (allUsers.find(u => u.id === topUser[0])?.name || 'Unknown User') : 'N/A',
            topEnvironment: topEnv ? (allEnvironments.find(e => e.id === topEnv[0])?.name || topEnv[0]) : 'N/A'
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
            <div class="all-logs-container">
                <!-- Header -->
                <div class="content-header">
                    <div class="d-flex justify-content-between align-items-center">
                        <div>
                            <h1><i class="fas fa-file-alt"></i> Global Audit Logs</h1>
                            <p class="text-muted">Complete audit trail across all environments</p>
                        </div>
                    </div>
                </div>

                <!-- Stats: compact metric cards — directly after header, same as Dashboard -->
                <div class="row g-2 mb-2">
                    <div class="col">
                        <div class="metric-card">
                            <div class="metric-value">${stats.totalActions.toLocaleString()}</div>
                            <div class="metric-label-hint">Total Actions</div>
                        </div>
                    </div>
                    <div class="col">
                        <div class="metric-card">
                            <div class="metric-value text-success">${stats.successRate}</div>
                            <div class="metric-label-hint">Success Rate</div>
                        </div>
                    </div>
                    <div class="col">
                        <div class="metric-card">
                            <div class="metric-value text-danger">${stats.failures}</div>
                            <div class="metric-label-hint">Failures</div>
                        </div>
                    </div>
                    <div class="col">
                        <div class="metric-card">
                            <div class="metric-value-sm" title="${stats.topUser}">${stats.topUser}</div>
                            <div class="metric-label-hint">Most Active User</div>
                        </div>
                    </div>
                    <div class="col">
                        <div class="metric-card">
                            <div class="metric-value-sm" title="${stats.topEnvironment}">${stats.topEnvironment}</div>
                            <div class="metric-label-hint">Most Active Env</div>
                        </div>
                    </div>
                </div>

                <!-- Filter Bar: dropdowns only, no labels -->
                <div class="all-logs-filter-bar mb-2">
                    <select class="form-select form-select-sm" id="time-range-filter">
                        <option value="24h" ${currentFilters.timeRange === '24h' || !currentFilters.timeRange ? 'selected' : ''}>Last 24h</option>
                        <option value="7d" ${currentFilters.timeRange === '7d' ? 'selected' : ''}>Last 7 days</option>
                        <option value="30d" ${currentFilters.timeRange === '30d' ? 'selected' : ''}>Last 30 days</option>
                        <option value="custom" ${currentFilters.timeRange === 'custom' ? 'selected' : ''}>Custom range</option>
                    </select>
                    <select class="form-select form-select-sm" id="user-filter">
                        <option value="">All Users</option>
                        ${allUsers.map(u => `<option value="${u.id}" ${currentFilters.userId === u.id ? 'selected' : ''}>${escapeHtml(u.name)}</option>`).join('')}
                    </select>
                    <select class="form-select form-select-sm" id="environment-filter">
                        <option value="">All Environments</option>
                        ${allEnvironments.map(e => `<option value="${e.id}" ${currentFilters.environmentId === e.id ? 'selected' : ''}>${e.name}</option>`).join('')}
                    </select>
                    <select class="form-select form-select-sm" id="action-type-filter">
                        <option value="">All Actions</option>
                        <optgroup label="VM Operations">
                            <option value="VM_START_REQUESTED" ${currentFilters.actionType === 'VM_START_REQUESTED' ? 'selected' : ''}>VM Start</option>
                            <option value="VM_STOP_REQUESTED" ${currentFilters.actionType === 'VM_STOP_REQUESTED' ? 'selected' : ''}>VM Stop</option>
                            <option value="VM_RESTART_REQUESTED" ${currentFilters.actionType === 'VM_RESTART_REQUESTED' ? 'selected' : ''}>VM Restart</option>
                        </optgroup>
                        <optgroup label="Lock Operations">
                            <option value="LOCK_ACQUIRED" ${currentFilters.actionType === 'LOCK_ACQUIRED' ? 'selected' : ''}>Lock Acquired</option>
                            <option value="LOCK_RELEASED" ${currentFilters.actionType === 'LOCK_RELEASED' ? 'selected' : ''}>Lock Released</option>
                            <option value="LOCK_BROKEN" ${currentFilters.actionType === 'LOCK_BROKEN' ? 'selected' : ''}>Lock Broken</option>
                        </optgroup>
                        <optgroup label="Access">
                            <option value="ACCESS_GRANTED" ${currentFilters.actionType === 'ACCESS_GRANTED' ? 'selected' : ''}>Access Granted</option>
                            <option value="ACCESS_REVOKED" ${currentFilters.actionType === 'ACCESS_REVOKED' ? 'selected' : ''}>Access Revoked</option>
                        </optgroup>
                        <optgroup label="User Management">
                            <option value="USER_CREATED" ${currentFilters.actionType === 'USER_CREATED' ? 'selected' : ''}>User Created</option>
                            <option value="USER_PROMOTED_TO_ADMIN" ${currentFilters.actionType === 'USER_PROMOTED_TO_ADMIN' ? 'selected' : ''}>User Promoted</option>
                        </optgroup>
                    </select>
                    <select class="form-select form-select-sm" id="result-filter">
                        <option value="">All Results</option>
                        <option value="true" ${currentFilters.resultStatus === 'true' ? 'selected' : ''}>Success</option>
                        <option value="false" ${currentFilters.resultStatus === 'false' ? 'selected' : ''}>Failure</option>
                    </select>
                    <select class="form-select form-select-sm" id="page-size-filter" title="Rows per fetch">
                        <option value="100"  ${currentFilters.size === 100  ? 'selected' : ''}>100</option>
                        <option value="500"  ${currentFilters.size === 500  ? 'selected' : ''}>500</option>
                        <option value="1000" ${currentFilters.size === 1000 ? 'selected' : ''}>1000</option>
                        <option value="10000" ${currentFilters.size === 10000 ? 'selected' : ''}>All</option>
                    </select>
                    <button class="btn btn-outline-danger btn-sm" id="clear-filters-btn" title="Clear all filters">
                        <i class="fas fa-times"></i> Clear
                    </button>
                    <button class="btn btn-outline-secondary btn-sm" id="export-logs-btn" title="Export CSV">
                        <i class="fas fa-download"></i> Export
                    </button>
                </div>

                <!-- Custom Date Range (shown only when "Custom range" is selected) -->
                <div id="custom-date-range" class="all-logs-custom-range mb-2" style="display:${currentFilters.timeRange === 'custom' ? 'flex' : 'none'};">
                    <input type="date" class="form-control form-control-sm" id="start-date-input" value="${currentFilters.startDate}">
                    <input type="date" class="form-control form-control-sm" id="end-date-input" value="${currentFilters.endDate}">
                    <button class="btn btn-primary btn-sm" id="apply-custom-range-btn">Apply</button>
                </div>

                <!-- Table Card: fills remaining space -->
                <div class="card all-logs-table-card">
                    <div class="card-body all-logs-card-body">
                        <div class="all-logs-table-wrapper">
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
                                                <i class="fas fa-inbox fa-3x mb-2 d-block" style="opacity:0.5;"></i>
                                                No audit logs found for the selected filters
                                            </td>
                                        </tr>
                                    `}
                                </tbody>
                            </table>
                        </div>
                        <!-- Pagination: pinned to bottom of card -->
                        <div class="all-logs-pagination" id="all-logs-pagination">
                            ${buildAllLogsPagination(totalElements, currentPage, totalPages)}
                        </div>
                    </div>
                </div>
            </div>
        `;
    }

    /**
     * Build pagination controls — same style as VM Registry
     */
    function buildAllLogsPagination(totalElements, currentPage, totalPages) {
        if (totalElements === 0) {
            return `<div class="text-muted small">No results</div>`;
        }
        const pageSize = currentFilters.size;
        const start = currentPage * pageSize + 1;
        const end = Math.min((currentPage + 1) * pageSize, totalElements);

        if (totalPages <= 1) {
            return `<div class="text-muted small">Showing ${start}–${end} of ${totalElements} entries</div>`;
        }

        const rangeStart = Math.max(0, currentPage - 2);
        const rangeEnd = Math.min(totalPages - 1, currentPage + 2);
        let pageButtons = '';
        for (let i = rangeStart; i <= rangeEnd; i++) {
            pageButtons += `<button class="btn btn-sm ${i === currentPage ? 'btn-primary' : 'btn-outline-secondary'} all-logs-page ms-1" data-page="${i}">${i + 1}</button>`;
        }

        return `
            <div class="d-flex justify-content-between align-items-center">
                <span class="text-muted small">Showing ${start}–${end} of ${totalElements} entries</span>
                <div>
                    <button class="btn btn-sm btn-outline-secondary all-logs-page" data-page="0" ${currentPage === 0 ? 'disabled' : ''} title="First page">
                        <i class="fas fa-angle-double-left"></i>
                    </button>
                    <button class="btn btn-sm btn-outline-secondary all-logs-page ms-1" data-page="${currentPage - 1}" ${currentPage === 0 ? 'disabled' : ''} title="Previous page">
                        <i class="fas fa-chevron-left"></i>
                    </button>
                    ${pageButtons}
                    <button class="btn btn-sm btn-outline-secondary all-logs-page ms-1" data-page="${currentPage + 1}" ${currentPage >= totalPages - 1 ? 'disabled' : ''} title="Next page">
                        <i class="fas fa-chevron-right"></i>
                    </button>
                    <button class="btn btn-sm btn-outline-secondary all-logs-page ms-1" data-page="${totalPages - 1}" ${currentPage >= totalPages - 1 ? 'disabled' : ''} title="Last page">
                        <i class="fas fa-angle-double-right"></i>
                    </button>
                </div>
            </div>
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
        const userName = getUserDisplay(log);
        const environment = escapeHtml(getEnvironmentDisplay(log));
        const safeUserName = escapeHtml(userName);
        const safeActionDisplay = escapeHtml(actionDisplay);
        const safeTargetName = escapeHtml(log.targetName || '-');
        const safeDetails = escapeHtml(details);
        const safeDetailsTitle = escapeHtml(log.details || log.errorMessage || '');

        return `
            <tr>
                <td>
                    <div>${timestamp.relative}</div>
                    <small class="text-muted">${timestamp.absolute}</small>
                </td>
                <td><small>${safeUserName}</small></td>
                <td>${environment}</td>
                <td>
                    <span class="badge ${actionBadgeClass}">${safeActionDisplay}</span>
                </td>
                <td>${safeTargetName}</td>
                <td class="text-center">${resultIcon}</td>
                <td title="${safeDetailsTitle}">${safeDetails}</td>
            </tr>
        `;
    }

    /**
     * Bind event handlers for filter changes and pagination
     */
    function bindAllLogsEvents() {
        // Time range filter
        $('#time-range-filter').on('change', function() {
            const value = $(this).val();
            currentFilters.timeRange = value;
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

        // Pagination — delegated on #content-area so it survives re-renders
        $('#content-area').off('click', '.all-logs-page').on('click', '.all-logs-page', function() {
            if ($(this).prop('disabled')) return;
            const target = parseInt($(this).data('page'));
            if (isNaN(target) || target < 0) return;
            currentFilters.page = target;
            loadAllLogs(true);  // page change only — preserve stats
        });

        // Page size
        $('#page-size-filter').on('change', function() {
            currentFilters.size = parseInt($(this).val());
            currentFilters.page = 0;
            loadAllLogs();
        });

        // Clear all filters
        $('#clear-filters-btn').on('click', function() {
            const endDate = new Date();
            const startDate = new Date();
            startDate.setDate(startDate.getDate() - 1);
            currentFilters.startDate = formatDateForApi(startDate);
            currentFilters.endDate = formatDateForApi(endDate);
            currentFilters.userId = '';
            currentFilters.environmentId = '';
            currentFilters.actionType = '';
            currentFilters.resultStatus = '';
            currentFilters.timeRange = '24h';
            currentFilters.page = 0;
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
     * @param {boolean} [pageChangeOnly=false] — skip stats recalculation on page changes
     */
    function loadAllLogs(pageChangeOnly) {
        showLoading();
        fetchAllAuditLogs(currentFilters)
            .then(logs => {
                if (!pageChangeOnly) calculateStats(logs);
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
                    getUserDisplay(log),
                    getEnvironmentDisplay(log),
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

    function getEnvironmentDisplay(log) {
        if (log.environmentName || log.environmentId) {
            return log.environmentName || log.environmentId;
        }
        const targetType = (log.targetType || '').toLowerCase();
        if (targetType === 'environment' || targetType === 'environment_access' || targetType === 'lock') {
            return log.targetName || log.targetId || '-';
        }
        return '-';
    }

    function getUserDisplay(log) {
        return log.userDisplayName || log.userEmail || 'System';
    }

    function escapeHtml(text) {
        if (text === null || text === undefined) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
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



