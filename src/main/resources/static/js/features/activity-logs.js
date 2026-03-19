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
        timeRange: '7d',
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

        return `
            <div class="activity-logs-container">
                <!-- Header -->
                <div class="content-header">
                    <div class="d-flex justify-content-between align-items-center">
                        <div>
                            <h1><i class="fas fa-clipboard-list"></i> My Activity Logs</h1>
                            <p class="text-muted">Your start/stop, restart and lock operations across all environments</p>
                        </div>
                    </div>
                </div>

                <!-- Filter Bar: dropdowns only, no labels -->
                <div class="activity-logs-filter-bar mb-2">
                    <select class="form-select form-select-sm" id="al-time-range-filter">
                        <option value="24h" ${currentFilters.timeRange === '24h' ? 'selected' : ''}>Last 24h</option>
                        <option value="7d"  ${currentFilters.timeRange === '7d'  ? 'selected' : ''}>Last 7 days</option>
                        <option value="30d" ${currentFilters.timeRange === '30d' ? 'selected' : ''}>Last 30 days</option>
                        <option value="custom" ${currentFilters.timeRange === 'custom' ? 'selected' : ''}>Custom range</option>
                    </select>
                    <select class="form-select form-select-sm" id="al-environment-filter">
                        <option value="">All Environments</option>
                        ${userEnvironments.map(env => `<option value="${env.id}" ${currentFilters.environmentId === env.id ? 'selected' : ''}>${env.name}</option>`).join('')}
                    </select>
                    <select class="form-select form-select-sm" id="al-action-type-filter">
                        <option value="">All Actions</option>
                        <optgroup label="VM Operations">
                            <option value="VM_START_REQUESTED"   ${currentFilters.actionType === 'VM_START_REQUESTED'   ? 'selected' : ''}>VM Start</option>
                            <option value="VM_STOP_REQUESTED"    ${currentFilters.actionType === 'VM_STOP_REQUESTED'    ? 'selected' : ''}>VM Stop</option>
                            <option value="VM_RESTART_REQUESTED" ${currentFilters.actionType === 'VM_RESTART_REQUESTED' ? 'selected' : ''}>VM Restart</option>
                        </optgroup>
                        <optgroup label="Lock Management">
                            <option value="LOCK_ACQUIRED" ${currentFilters.actionType === 'LOCK_ACQUIRED' ? 'selected' : ''}>Lock Acquired</option>
                            <option value="LOCK_RELEASED" ${currentFilters.actionType === 'LOCK_RELEASED' ? 'selected' : ''}>Lock Released</option>
                        </optgroup>
                    </select>
                    <select class="form-select form-select-sm" id="al-page-size-filter" title="Rows per fetch">
                        <option value="50"    ${currentFilters.size === 50    ? 'selected' : ''}>50</option>
                        <option value="100"   ${currentFilters.size === 100   ? 'selected' : ''}>100</option>
                        <option value="500"   ${currentFilters.size === 500   ? 'selected' : ''}>500</option>
                        <option value="10000" ${currentFilters.size === 10000 ? 'selected' : ''}>All</option>
                    </select>
                    <button class="btn btn-outline-danger btn-sm" id="al-clear-filters-btn" title="Clear all filters">
                        <i class="fas fa-times"></i> Clear
                    </button>
                    <button class="btn btn-outline-secondary btn-sm" id="al-export-logs-btn" title="Export CSV">
                        <i class="fas fa-download"></i> Export
                    </button>
                </div>

                <!-- Custom Date Range (shown only when "Custom range" is selected) -->
                <div id="al-custom-date-range" class="activity-logs-custom-range mb-2" style="display:${currentFilters.timeRange === 'custom' ? 'flex' : 'none'};">
                    <input type="date" class="form-control form-control-sm" id="al-start-date-input" value="${currentFilters.startDate}">
                    <input type="date" class="form-control form-control-sm" id="al-end-date-input"   value="${currentFilters.endDate}">
                    <button class="btn btn-primary btn-sm" id="al-apply-custom-range-btn">Apply</button>
                </div>

                <!-- Table Card: fills remaining space -->
                <div class="card activity-logs-table-card">
                    <div class="card-body activity-logs-card-body">
                        <div class="activity-logs-table-wrapper">
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
                                                <i class="fas fa-inbox fa-3x mb-2 d-block" style="opacity:0.5;"></i>
                                                No activity found for the selected filters
                                            </td>
                                        </tr>
                                    `}
                                </tbody>
                            </table>
                        </div>
                        <!-- Pagination: pinned to bottom of card -->
                        <div class="activity-logs-pagination" id="al-pagination">
                            ${buildActivityLogsPagination(totalElements, currentPage, totalPages)}
                        </div>
                    </div>
                </div>
            </div>
        `;
    }

    /**
     * Build pagination controls — same style as VM Registry / All Logs
     */
    function buildActivityLogsPagination(totalElements, currentPage, totalPages) {
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
            pageButtons += `<button class="btn btn-sm ${i === currentPage ? 'btn-primary' : 'btn-outline-secondary'} al-page ms-1" data-page="${i}">${i + 1}</button>`;
        }

        return `
            <div class="d-flex justify-content-between align-items-center">
                <span class="text-muted small">Showing ${start}–${end} of ${totalElements} entries</span>
                <div>
                    <button class="btn btn-sm btn-outline-secondary al-page" data-page="0" ${currentPage === 0 ? 'disabled' : ''} title="First page">
                        <i class="fas fa-angle-double-left"></i>
                    </button>
                    <button class="btn btn-sm btn-outline-secondary al-page ms-1" data-page="${currentPage - 1}" ${currentPage === 0 ? 'disabled' : ''} title="Previous page">
                        <i class="fas fa-chevron-left"></i>
                    </button>
                    ${pageButtons}
                    <button class="btn btn-sm btn-outline-secondary al-page ms-1" data-page="${currentPage + 1}" ${currentPage >= totalPages - 1 ? 'disabled' : ''} title="Next page">
                        <i class="fas fa-chevron-right"></i>
                    </button>
                    <button class="btn btn-sm btn-outline-secondary al-page ms-1" data-page="${totalPages - 1}" ${currentPage >= totalPages - 1 ? 'disabled' : ''} title="Last page">
                        <i class="fas fa-angle-double-right"></i>
                    </button>
                </div>
            </div>
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
     * Bind event handlers for filter changes and pagination
     */
    function bindActivityLogEvents() {
        // Time range filter
        $('#al-time-range-filter').on('change', function() {
            const value = $(this).val();
            currentFilters.timeRange = value;
            if (value === 'custom') {
                $('#al-custom-date-range').show();
            } else {
                $('#al-custom-date-range').hide();
                applyTimeRangeFilter(value);
            }
        });

        // Environment filter
        $('#al-environment-filter').on('change', function() {
            currentFilters.environmentId = $(this).val();
            currentFilters.page = 0;
            loadActivityLogs();
        });

        // Action type filter
        $('#al-action-type-filter').on('change', function() {
            currentFilters.actionType = $(this).val();
            currentFilters.page = 0;
            loadActivityLogs();
        });

        // Page size
        $('#al-page-size-filter').on('change', function() {
            currentFilters.size = parseInt($(this).val());
            currentFilters.page = 0;
            loadActivityLogs();
        });

        // Clear all filters
        $('#al-clear-filters-btn').on('click', function() {
            const endDate = new Date();
            const startDate = new Date();
            startDate.setDate(startDate.getDate() - 7);
            currentFilters.startDate = formatDateForApi(startDate);
            currentFilters.endDate = formatDateForApi(endDate);
            currentFilters.environmentId = '';
            currentFilters.actionType = '';
            currentFilters.timeRange = '7d';
            currentFilters.page = 0;
            loadActivityLogs();
        });

        // Custom date range
        $('#al-apply-custom-range-btn').on('click', function() {
            const startDate = $('#al-start-date-input').val();
            const endDate = $('#al-end-date-input').val();
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

        // Pagination — delegated on #content-area so it survives re-renders
        $('#content-area').off('click', '.al-page').on('click', '.al-page', function() {
            if ($(this).prop('disabled')) return;
            const target = parseInt($(this).data('page'));
            if (isNaN(target) || target < 0) return;
            currentFilters.page = target;
            loadActivityLogs();
        });

        // Export
        $('#al-export-logs-btn').on('click', function() {
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
            case '24h': startDate.setDate(startDate.getDate() - 1);  break;
            case '7d':  startDate.setDate(startDate.getDate() - 7);  break;
            case '30d': startDate.setDate(startDate.getDate() - 30); break;
            default: return;
        }

        currentFilters.timeRange = range;
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

window.ActivityLogs = ActivityLogs;




