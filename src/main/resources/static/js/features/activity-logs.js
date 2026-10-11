/**
 * VM Self-Service Platform - Activity Logs Module
 * Handles user's personal activity log viewing and filtering
 */

const ActivityLogs = (function() {
    'use strict';

    /**
     * Spring Data serializes Page<T> as { content: [...], page: { totalElements, totalPages,
     * number, size } } — flatten that back onto the response so the rest of this module's
     * data.totalPages/totalElements/number reads keep working regardless of shape.
     */
    function normalizePage(data) {
        if (!data || !data.page) return data;
        return {
            ...data,
            totalElements: data.page.totalElements ?? data.totalElements ?? 0,
            totalPages: data.page.totalPages ?? data.totalPages ?? 0,
            number: data.page.number ?? data.number ?? 0,
            size: data.page.size ?? data.size
        };
    }

    // Cache for filter state
    // from/to are ISO instants (E11-T06); customStart/customEnd back the date inputs.
    let currentFilters = {
        from: '',
        to: '',
        customStart: '',
        customEnd: '',
        environmentId: '',
        actionType: '',
        timeRange: '7d',
        page: 0,
        size: 50
    };

    let userEnvironments = [];
    let allActions = [];

    const RANGE_HOURS = { '24h': 24, '7d': 24 * 7, '30d': 24 * 30 };
    const EXPORT_PAGE_SIZE = 500;
    const EXPORT_MAX_ROWS = 10000;

    /** An exact range ending now. */
    function setPresetRange(range) {
        const hours = RANGE_HOURS[range] || 24 * 7;
        const now = new Date();
        currentFilters.from = new Date(now.getTime() - hours * 3600 * 1000).toISOString();
        currentFilters.to = now.toISOString();
    }

    /** Local calendar days: from local midnight of start to local midnight after end. */
    function setCustomRange(startDate, endDate) {
        const [sy, sm, sd] = startDate.split('-').map(Number);
        const [ey, em, ed] = endDate.split('-').map(Number);
        currentFilters.from = new Date(sy, sm - 1, sd).toISOString();
        currentFilters.to = new Date(ey, em - 1, ed + 1).toISOString();
        currentFilters.customStart = startDate;
        currentFilters.customEnd = endDate;
    }

    function fetchActions() {
        return new Promise(resolve => {
            ApiClient.get(Config.API.audit.actions, { suppressGlobalError: true })
                .done(data => resolve(Array.isArray(data) ? data : []))
                .fail(() => resolve([]));
        });
    }

    /**
     * Load activity logs page
     */
    /** Router loader (see the page contract in core/router.js). */
    async function loadMyActivityLogs() {
        const t = ContentRouter.token();
        try {
            showLoading();

            if (currentFilters.timeRange !== 'custom') {
                setPresetRange(currentFilters.timeRange);
            }
            currentFilters.page = 0;

            // Load environments, actions and logs in parallel
            const [logs, environments, actions] = await Promise.all([
                fetchActivityLogs(currentFilters),
                fetchUserEnvironments(),
                fetchActions()
            ]);
            if (!ContentRouter.isCurrent(t)) return;

            userEnvironments = environments || [];
            allActions = actions || [];

            const html = buildActivityLogsHtml(logs);
            $('#content-area').html(html);
            bindActivityLogEvents();
            renderActivityLogsPagination(logs);

            console.log('Activity logs loaded successfully');
        } catch (error) {
            if (!ContentRouter.isCurrent(t)) return;
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

            if (filters.from) params.append('from', filters.from);
            if (filters.to) params.append('to', filters.to);
            if (filters.environmentId) params.append('environmentId', filters.environmentId);
            if (filters.actionType) params.append('action', filters.actionType);

            const url = `${Config.API.audit.myLogs}?${params.toString()}`;

            ApiClient.get(url)
                .done(function(data) {
                    resolve(normalizePage(data));
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
                        ${userEnvironments.map(env => `<option value="${Utils.escapeHtml(env.id)}" ${currentFilters.environmentId === env.id ? 'selected' : ''}>${Utils.escapeHtml(env.name)}</option>`).join('')}
                    </select>
                    <select class="form-select form-select-sm" id="al-action-type-filter">
                        <option value="">All Actions</option>
                        ${allActions.map(a => Utils.html`<option value="${a}" ${currentFilters.actionType === a ? 'selected' : ''}>${formatActionName(a)}</option>`).join('')}
                    </select>
                    <select class="form-select form-select-sm" id="al-page-size-filter" title="Rows per fetch">
                        <option value="50"    ${currentFilters.size === 50    ? 'selected' : ''}>50</option>
                        <option value="100"   ${currentFilters.size === 100   ? 'selected' : ''}>100</option>
                        <option value="500"   ${currentFilters.size === 500   ? 'selected' : ''}>500</option>
                    </select>
                    <button class="btn btn-outline-danger btn-ghost btn-sm" id="al-clear-filters-btn" title="Clear all filters">
                        <i class="fas fa-times"></i> Clear
                    </button>
                    <button class="btn btn-ghost btn-sm" id="al-export-logs-btn" title="Export CSV">
                        <i class="fas fa-download"></i> Export
                    </button>
                </div>

                <!-- Custom Date Range (shown only when "Custom range" is selected) -->
                <div id="al-custom-date-range" class="activity-logs-custom-range mb-2" style="display:${currentFilters.timeRange === 'custom' ? 'flex' : 'none'};">
                    <input type="date" class="form-control form-control-sm" id="al-start-date-input" value="${Utils.escapeHtml(currentFilters.customStart)}" aria-label="From date">
                    <input type="date" class="form-control form-control-sm" id="al-end-date-input"   value="${Utils.escapeHtml(currentFilters.customEnd)}" aria-label="To date">
                    <button class="btn btn-primary btn-sm" id="al-apply-custom-range-btn">Apply</button>
                </div>

                <!-- Table Card: fills remaining space -->
                <div class="card activity-logs-table-card">
                    <div class="card-body activity-logs-card-body">
                        <div class="activity-logs-table-wrapper">
                            <table class="table table-hover mb-0 table-cards">
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
                        <div class="pagination-bar-wrap" id="al-pagination"></div>
                    </div>
                </div>
            </div>
        `;
    }

    /**
     * Render pagination controls into #al-pagination — called after the container has been
     * inserted into the DOM (buildActivityLogsHtml leaves it empty for this).
     */
    function renderActivityLogsPagination(logs) {
        Pagination.renderNumbered('#al-pagination', {
            page: logs.number || 0,
            totalItems: logs.totalElements || 0,
            pageSize: currentFilters.size,
            itemLabel: 'entries',
            zeroIndexed: true,
            bindAncestor: '#content-area',
            onPageChange: (page) => {
                currentFilters.page = page;
                loadActivityLogs();
            }
        });
    }

    /**
     * Build a single activity log row
     */
    function buildActivityLogRow(log) {
        const timestamp = formatTimestamp(log.createdAt);
        const actionBadgeClass = getActionBadgeClass(log.action);
        const actionDisplay = Utils.escapeHtml(log.actionDisplay || formatActionName(log.action));
        const resultIcon = log.success ? '<i class="fas fa-check-circle text-success"></i>' : '<i class="fas fa-times-circle text-danger"></i>';
        const details = log.details ? log.details.substring(0, 50) + (log.details.length > 50 ? '...' : '') : '-';
        const environment = Utils.escapeHtml(getEnvironmentDisplay(log));
        const targetName = Utils.escapeHtml(log.targetName || '-');
        const detailsTitle = Utils.escapeHtml(log.details || '');
        const detailsText = Utils.escapeHtml(details);

        return `
            <tr>
                <td>
                    <div>${timestamp.relative}</div>
                    <small class="text-muted">${timestamp.absolute}</small>
                </td>
                <td>${environment}</td>
                <td>
                    <span class="badge ${actionBadgeClass}">${actionDisplay}</span>
                </td>
                <td>${targetName}</td>
                <td class="text-center">${resultIcon}</td>
                <td title="${detailsTitle}">${detailsText}</td>
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
            setPresetRange('7d');
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
            if (startDate > endDate) {
                Notifications.show('Start date must be before end date', 'warning');
                return;
            }
            setCustomRange(startDate, endDate);
            currentFilters.page = 0;
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
        if (!RANGE_HOURS[range]) return;
        currentFilters.timeRange = range;
        setPresetRange(range);
        currentFilters.page = 0;
        loadActivityLogs();
    }

    /**
     * Reload activity logs with current filters
     */
    function loadActivityLogs() {
        const t = ContentRouter.token();
        showLoading();
        fetchActivityLogs(currentFilters)
            .then(logs => {
                if (!ContentRouter.isCurrent(t)) return;
                const html = buildActivityLogsHtml(logs);
                $('#content-area').html(html);
                bindActivityLogEvents();
                renderActivityLogsPagination(logs);
            })
            .catch(error => {
                if (!ContentRouter.isCurrent(t)) return;
                console.error('Error loading activity logs:', error);
                showError('Failed to load activity logs.');
            });
    }

    /**
     * Export activity logs to CSV
     */
    async function exportActivityLogs() {
        const t = ContentRouter.token();
        const fileName = `activity-logs-${new Date().toISOString().split('T')[0]}.csv`;
        const headers = ['Timestamp', 'Environment', 'Action', 'Target', 'Result', 'Details'];
        const rows = [];
        try {
            // Page through (500 a page, at most 10,000 rows): the server caps the page size.
            for (let page = 0; rows.length < EXPORT_MAX_ROWS; page++) {
                const data = await fetchActivityLogs({ ...currentFilters, page, size: EXPORT_PAGE_SIZE });
                const logs = data.content || [];
                logs.forEach(log => rows.push([
                    log.createdAt,
                    getEnvironmentDisplay(log),
                    log.actionDisplay || log.action || '',
                    log.targetName || '',
                    log.success ? 'Success' : 'Failed',
                    log.details || ''
                ]));
                if (page + 1 >= (data.totalPages || 0) || logs.length === 0) break;
            }
            if (!ContentRouter.isCurrent(t)) return;
            Utils.downloadCsv(fileName, headers, rows.slice(0, EXPORT_MAX_ROWS));
            Notifications.show('Activity logs exported successfully', 'success');
        } catch (error) {
            console.error('Error exporting logs:', error);
            Notifications.show('Failed to export logs', 'danger');
        }
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
     * Helper: Get badge CSS class for action type
     */
    function getActionBadgeClass(action) {
        if (!action) return 'bg-secondary';

        const actionStr = action.toString().toLowerCase();
        if (actionStr.includes('start')) return 'bg-success';
        if (actionStr.includes('stop')) return 'bg-danger';
        if (actionStr.includes('lock')) return 'text-bg-warning';
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
                    <button class="btn btn-primary" data-action="activity-retry">Retry</button>
                </div>
            </div>
        `);
    }

    return {
        loadMyActivityLogs
    };
})();

window.ActivityLogs = ActivityLogs;

Actions.register('activity-retry', () => ActivityLogs.loadMyActivityLogs());
