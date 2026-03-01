/**
 * VM Self-Service Platform - Activity Logs Module
 * Handles user's personal activity log viewing and filtering
 */

const ActivityLogs = (function() {
    'use strict';

    // Cache for filter state
    let currentFilters = {
        startDate: '',
        endDate: '',
        environmentId: '',
        actionType: '',
        page: 0,
        size: 50
    };

    let userEnvironments = [];
    let userId = null;

    /**
     * Load activity logs page
     */
    async function loadMyActivityLogs() {
        try {
            // Get current user ID
            userId = Auth.getUserId();
            if (!userId) {
                showError('Unable to identify current user.');
                return;
            }

            showLoading();

            // Set default date range (last 7 days)
            const endDate = new Date();
            const startDate = new Date();
            startDate.setDate(startDate.getDate() - 7);

            currentFilters.startDate = formatDateForApi(startDate);
            currentFilters.endDate = formatDateForApi(endDate);
            currentFilters.page = 0;

            // Load environments and logs in parallel
            const [logs, environments] = await Promise.all([
                fetchActivityLogs(currentFilters),
                fetchUserEnvironments()
            ]);

            userEnvironments = environments || [];

            const html = buildActivityLogsHtml(logs);
            $('#content-area').html(html);
            bindActivityLogEvents();

            console.log('Activity logs loaded successfully');
        } catch (error) {
            console.error('Failed to load activity logs:', error);
            showError('Failed to load activity logs.');
        }
    }

    /**
     * Fetch user's activity logs with current filters
     */
    function fetchActivityLogs(filters) {
        return new Promise((resolve, reject) => {
            const params = new URLSearchParams();
            params.append('page', filters.page);
            params.append('size', filters.size);

            if (filters.startDate) params.append('startDate', filters.startDate);
            if (filters.endDate) params.append('endDate', filters.endDate);
            if (filters.environmentId) params.append('environmentId', filters.environmentId);
            if (filters.actionType) params.append('action', filters.actionType);

            const url = `${Config.API.audit.byUser(userId)}?${params.toString()}`;

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
     * Fetch user's accessible environments
     */
    function fetchUserEnvironments() {
        return new Promise((resolve) => {
            ApiClient.get(Config.API.access.myEnvironments)
                .done(function(data) {
                    // Extract unique environments from access records
                    const envMap = new Map();
                    if (Array.isArray(data)) {
                        data.forEach(access => {
                            if (access.environmentId && access.environmentName) {
                                envMap.set(access.environmentId, access.environmentName);
                            }
                        });
                    }
                    resolve(Array.from(envMap, ([id, name]) => ({ id, name })));
                })
                .fail(() => {
                    resolve([]);
                });
        });
    }

    /**
     * Build activity logs page HTML
     */
    function buildActivityLogsHtml(data) {
        const logs = data.content || [];
        const totalElements = data.totalElements || 0;
        const totalPages = data.totalPages || 0;
        const currentPage = data.number || 0;

        const startDate = currentFilters.startDate;
        const endDate = currentFilters.endDate;

        return `
            <div class="content-header">
                <h1><i class="fas fa-clipboard-list"></i> My Activity Logs</h1>
                <p>Track your start/stop operations and lock management</p>
            </div>

            <!-- Filter Bar -->
            <div class="card mb-4">
                <div class="card-body">
                    <div class="row g-3">
                        <div class="col-md-3">
                            <label class="form-label">Time Range</label>
                            <select class="form-select" id="time-range-filter">
                                <option value="24h" ${isTimeRangeSelected(startDate, endDate, '24h') ? 'selected' : ''}>Last 24 hours</option>
                                <option value="7d" ${isTimeRangeSelected(startDate, endDate, '7d') ? 'selected' : ''}>Last 7 days</option>
                                <option value="30d" ${isTimeRangeSelected(startDate, endDate, '30d') ? 'selected' : ''}>Last 30 days</option>
                                <option value="custom">Custom range</option>
                            </select>
                        </div>
                        <div class="col-md-3">
                            <label class="form-label">Environment</label>
                            <select class="form-select" id="environment-filter">
                                <option value="">All Environments</option>
                                ${userEnvironments.map(env => `<option value="${env.id}" ${currentFilters.environmentId === env.id ? 'selected' : ''}>${env.name}</option>`).join('')}
                            </select>
                        </div>
                        <div class="col-md-3">
                            <label class="form-label">Action Type</label>
                            <select class="form-select" id="action-type-filter">
                                <option value="">All Actions</option>
                                <optgroup label="Execution">
                                    <option value="VM_START_REQUESTED" ${currentFilters.actionType === 'VM_START_REQUESTED' ? 'selected' : ''}>Start Operations</option>
                                    <option value="VM_STOP_REQUESTED" ${currentFilters.actionType === 'VM_STOP_REQUESTED' ? 'selected' : ''}>Stop Operations</option>
                                    <option value="VM_RESTART_REQUESTED" ${currentFilters.actionType === 'VM_RESTART_REQUESTED' ? 'selected' : ''}>Restart Operations</option>
                                </optgroup>
                                <optgroup label="Lock Management">
                                    <option value="LOCK_ACQUIRED" ${currentFilters.actionType === 'LOCK_ACQUIRED' ? 'selected' : ''}>Lock Acquire</option>
                                    <option value="LOCK_RELEASED" ${currentFilters.actionType === 'LOCK_RELEASED' ? 'selected' : ''}>Lock Release</option>
                                </optgroup>
                            </select>
                        </div>
                        <div class="col-md-3 d-flex align-items-end">
                            <button class="btn btn-outline-primary w-100" id="export-logs-btn">
                                <i class="fas fa-download"></i> Export CSV
                            </button>
                        </div>
                    </div>
                    <!-- Custom Date Range (hidden by default) -->
                    <div id="custom-date-range" class="row g-3 mt-2" style="display: none;">
                        <div class="col-md-4">
                            <label class="form-label">Start Date</label>
                            <input type="date" class="form-control" id="start-date-input" value="${startDate}">
                        </div>
                        <div class="col-md-4">
                            <label class="form-label">End Date</label>
                            <input type="date" class="form-control" id="end-date-input" value="${endDate}">
                        </div>
                        <div class="col-md-4 d-flex align-items-end">
                            <button class="btn btn-primary w-100" id="apply-custom-range-btn">Apply</button>
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
                                <th>Environment</th>
                                <th>Action</th>
                                <th>Target</th>
                                <th>Result</th>
                                <th>Details</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${logs.length > 0 ? logs.map(log => buildActivityLogRow(log)).join('') : `
                                <tr>
                                    <td colspan="6" class="text-center text-muted py-4">
                                        <i class="fas fa-inbox fa-3x mb-2" style="opacity: 0.5;"></i>
                                        <p>No activity found for the selected filters</p>
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
     * Build a single activity log row
     */
    function buildActivityLogRow(log) {
        const timestamp = formatTimestamp(log.createdAt);
        const actionBadgeClass = getActionBadgeClass(log.action);
        const actionDisplay = log.actionDisplay || formatActionName(log.action);
        const resultIcon = log.success ? '<i class="fas fa-check-circle text-success"></i>' : '<i class="fas fa-times-circle text-danger"></i>';
        const details = log.details ? log.details.substring(0, 50) + (log.details.length > 50 ? '...' : '') : '-';

        return `
            <tr>
                <td>
                    <div>${timestamp.relative}</div>
                    <small class="text-muted">${timestamp.absolute}</small>
                </td>
                <td>${log.environmentName || log.environmentId || '-'}</td>
                <td>
                    <span class="badge ${actionBadgeClass}">${actionDisplay}</span>
                </td>
                <td>${log.targetName || '-'}</td>
                <td class="text-center">${resultIcon}</td>
                <td title="${log.details || ''}">${details}</td>
            </tr>
        `;
    }

    /**
     * Bind event handlers for filter changes
     */
    function bindActivityLogEvents() {
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

        // Environment filter
        $('#environment-filter').on('change', function() {
            currentFilters.environmentId = $(this).val();
            currentFilters.page = 0;
            loadActivityLogs();
        });

        // Action type filter
        $('#action-type-filter').on('change', function() {
            currentFilters.actionType = $(this).val();
            currentFilters.page = 0;
            loadActivityLogs();
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
            loadActivityLogs();
        });

        // Pagination
        $('#prev-page-btn').on('click', function() {
            if (currentFilters.page > 0) {
                currentFilters.page--;
                loadActivityLogs();
            }
        });

        $('#next-page-btn').on('click', function() {
            currentFilters.page++;
            loadActivityLogs();
        });

        // Export
        $('#export-logs-btn').on('click', function() {
            exportActivityLogs();
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
        loadActivityLogs();
    }

    /**
     * Reload activity logs with current filters
     */
    function loadActivityLogs() {
        showLoading();
        fetchActivityLogs(currentFilters)
            .then(logs => {
                const html = buildActivityLogsHtml(logs);
                $('#content-area').html(html);
                bindActivityLogEvents();
            })
            .catch(error => {
                console.error('Error loading activity logs:', error);
                showError('Failed to load activity logs.');
            });
    }

    /**
     * Export activity logs to CSV
     */
    function exportActivityLogs() {
        const fileName = `activity-logs-${new Date().toISOString().split('T')[0]}.csv`;
        const headers = ['Timestamp', 'Environment', 'Action', 'Target', 'Result', 'Details'];

        // Fetch all logs for export (without pagination)
        const exportParams = { ...currentFilters, page: 0, size: 10000 };
        fetchActivityLogs(exportParams)
            .then(data => {
                const logs = data.content || [];
                const rows = logs.map(log => [
                    log.createdAt,
                    log.environmentName || log.environmentId || '',
                    log.actionDisplay || log.action || '',
                    log.targetName || '',
                    log.success ? 'Success' : 'Failed',
                    log.details || ''
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

                Notifications.show('Activity logs exported successfully', 'success');
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
     * Helper: Check if time range matches current filters
     */
    function isTimeRangeSelected(startDate, endDate, range) {
        if (!startDate || !endDate) return false;

        const start = new Date(startDate);
        const end = new Date(endDate);
        const today = new Date();
        const todayStart = new Date(today.getFullYear(), today.getMonth(), today.getDate());

        let rangeStart = new Date(todayStart);
        switch (range) {
            case '24h':
                rangeStart.setDate(rangeStart.getDate() - 1);
                break;
            case '7d':
                rangeStart.setDate(rangeStart.getDate() - 7);
                break;
            case '30d':
                rangeStart.setDate(rangeStart.getDate() - 30);
                break;
            default:
                return false;
        }

        return start.toDateString() === rangeStart.toDateString() && end.toDateString() === todayStart.toDateString();
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
        if (actionStr.includes('cancelled')) return 'bg-secondary';

        return 'bg-primary';
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
                    <p class="text-muted">Loading activity logs...</p>
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
                    <button class="btn btn-primary" onclick="ActivityLogs.loadMyActivityLogs()">Retry</button>
                </div>
            </div>
        `);
    }

    return {
        loadMyActivityLogs
    };
})();




