/**
 * VM Self-Service Platform - All Logs (Audit) Module
 * Global audit log for admins and environment admins: every filter is applied together on the
 * server, time ranges are exact instants (not UTC calendar days), the stat cards come from the
 * server for the whole filtered set (E11-T05), and the CSV export neutralises formulas.
 */

const AllLogs = (function() {
    'use strict';

    const EXPORT_PAGE_SIZE = 500;
    const EXPORT_MAX_ROWS = 10000;
    const RANGE_HOURS = { '24h': 24, '7d': 24 * 7, '30d': 24 * 30 };

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

    // Filter state. from/to are ISO instants; customStart/customEnd back the date inputs.
    let currentFilters = {
        from: '',
        to: '',
        customStart: '',
        customEnd: '',
        userId: '',
        userLabel: '',
        environmentId: '',
        actionType: '',
        resultStatus: '',
        timeRange: '24h',
        page: 0,
        size: 100
    };

    let allUsers = [];          // ADMIN: full list; ENV_ADMIN: type-ahead results
    let allEnvironments = [];
    let allActions = [];
    let stats = emptyStats();

    function emptyStats() {
        return { total: 0, failures: 0, successRate: 0, topUser: null, topEnvironment: null };
    }

    /** An exact range ending now (E11-T05: "Last 24h" at 02:00 includes today 00:00-02:00). */
    function setPresetRange(range) {
        const hours = RANGE_HOURS[range] || 24;
        const now = new Date();
        currentFilters.from = new Date(now.getTime() - hours * 3600 * 1000).toISOString();
        currentFilters.to = now.toISOString();
    }

    /** Custom days are local calendar days: from local midnight of start to local midnight after end. */
    function setCustomRange(startDate, endDate) {
        const [sy, sm, sd] = startDate.split('-').map(Number);
        const [ey, em, ed] = endDate.split('-').map(Number);
        currentFilters.from = new Date(sy, sm - 1, sd).toISOString();
        currentFilters.to = new Date(ey, em - 1, ed + 1).toISOString();
        currentFilters.customStart = startDate;
        currentFilters.customEnd = endDate;
    }

    function isAdmin() {
        return typeof Auth !== 'undefined' && Auth.isAdmin && Auth.isAdmin();
    }

    /** Router loader (see the page contract in core/router.js). */
    async function loadAllAuditLogs() {
        const t = ContentRouter.token();
        if (!Auth.isEnvAdmin()) {
            showError('Access denied. This page is only available to administrators.');
            return;
        }

        try {
            showLoading();
            if (currentFilters.timeRange !== 'custom') {
                setPresetRange(currentFilters.timeRange);
            }
            currentFilters.page = 0;

            const [logs, statsData, users, environments, actions] = await Promise.all([
                fetchAllAuditLogs(currentFilters),
                fetchStats(currentFilters),
                isAdmin() ? fetchAllUsers() : Promise.resolve([]),
                fetchAllEnvironments(),
                fetchActions()
            ]);
            if (!ContentRouter.isCurrent(t)) return;

            allUsers = users || [];
            allEnvironments = environments || [];
            allActions = actions || [];
            stats = statsData || emptyStats();

            render(logs);
        } catch (error) {
            if (!ContentRouter.isCurrent(t)) return;
            console.error('Failed to load audit logs:', error);
            showError('Failed to load audit logs.');
        }
    }

    /** Query params shared by the logs, stats and export requests. */
    function filterParams(filters) {
        const params = new URLSearchParams();
        if (filters.from) params.append('from', filters.from);
        if (filters.to) params.append('to', filters.to);
        if (filters.userId) params.append('userId', filters.userId);
        if (filters.environmentId) params.append('environmentId', filters.environmentId);
        if (filters.actionType) params.append('action', filters.actionType);
        if (filters.resultStatus === 'true' || filters.resultStatus === 'false') {
            params.append('success', filters.resultStatus);
        }
        return params;
    }

    function fetchAllAuditLogs(filters) {
        return new Promise((resolve, reject) => {
            const params = filterParams(filters);
            params.append('page', filters.page);
            params.append('size', filters.size);
            ApiClient.get(`${Config.API.audit.logs}?${params.toString()}`)
                .done(data => resolve(normalizePage(data)))
                .fail(xhr => {
                    if (xhr.status === 404) {
                        resolve({ content: [], totalElements: 0, totalPages: 0, number: 0, size: filters.size });
                    } else {
                        reject(xhr);
                    }
                });
        });
    }

    /** Server totals for the same filters (never computed from one page). */
    function fetchStats(filters) {
        return new Promise(resolve => {
            ApiClient.get(`${Config.API.audit.stats}?${filterParams(filters).toString()}`, { suppressGlobalError: true })
                .done(resolve)
                .fail(() => resolve(emptyStats()));
        });
    }

    /** ADMIN only: the user list endpoint is ADMIN-only, so an ENV_ADMIN uses type-ahead search. */
    function fetchAllUsers() {
        return new Promise(resolve => {
            ApiClient.get(Config.API.users.list, { suppressGlobalError: true })
                .done(data => resolve((Array.isArray(data) ? data : []).map(toUserOption)))
                .fail(() => resolve([]));
        });
    }

    function toUserOption(u) {
        return { id: u.userId || u.id, email: u.email, name: u.displayName || u.email };
    }

    function fetchAllEnvironments() {
        return new Promise(resolve => {
            ApiClient.get(Config.API.environments.list, { suppressGlobalError: true })
                .done(data => resolve((Array.isArray(data) ? data : []).map(e => ({
                    id: e.environmentId || e.id,
                    name: e.displayName || e.name
                }))))
                .fail(() => resolve([]));
        });
    }

    function fetchActions() {
        return new Promise(resolve => {
            ApiClient.get(Config.API.audit.actions, { suppressGlobalError: true })
                .done(data => resolve(Array.isArray(data) ? data : []))
                .fail(() => resolve([]));
        });
    }

    function render(logs) {
        $('#content-area').html(buildAllLogsHtml(logs));
        bindAllLogsEvents();
        renderAllLogsPagination(logs);
    }

    function statCard(value, label, cls, title) {
        return Utils.html`
            <div class="col">
                <div class="metric-card">
                    <div class="${cls}" title="${title || ''}">${value}</div>
                    <div class="metric-label-hint">${label}</div>
                </div>
            </div>`;
    }

    function buildStatsHtml() {
        const topUser = stats.topUser ? `${stats.topUser.name} (${stats.topUser.count})` : 'N/A';
        const topEnv = stats.topEnvironment ? `${stats.topEnvironment.name} (${stats.topEnvironment.count})` : 'N/A';
        return [
            statCard(Number(stats.total || 0).toLocaleString(), 'Total Actions', 'metric-value'),
            statCard(`${stats.successRate || 0}%`, 'Success Rate', 'metric-value text-success'),
            statCard(Number(stats.failures || 0).toLocaleString(), 'Failures', 'metric-value text-danger'),
            statCard(topUser, 'Most Active User', 'metric-value-sm', topUser),
            statCard(topEnv, 'Most Active Env', 'metric-value-sm', topEnv)
        ].join('');
    }

    function formatActionName(action) {
        if (!action) return '';
        return action.replace(/_/g, ' ').toLowerCase()
            .split(' ')
            .map(word => word.charAt(0).toUpperCase() + word.slice(1))
            .join(' ');
    }

    function buildActionOptions() {
        return allActions.map(a => Utils.html`<option value="${a}" ${currentFilters.actionType === a ? 'selected' : ''}>${formatActionName(a)}</option>`).join('');
    }

    function buildUserFilter() {
        if (isAdmin()) {
            return Utils.html`
                <select class="form-select form-select-sm" id="user-filter" aria-label="User">
                    <option value="">All Users</option>
                    ${Utils.raw(allUsers.map(u => Utils.html`<option value="${u.id}" ${currentFilters.userId === u.id ? 'selected' : ''}>${u.name}</option>`).join(''))}
                </select>`;
        }
        // ENV_ADMIN: type-ahead over /users/search (no ADMIN-only /users call, so no 403).
        return Utils.html`
            <input type="search" class="form-control form-control-sm" id="user-search-input" list="audit-user-options"
                   placeholder="Any user (type to search)" aria-label="User" value="${currentFilters.userLabel}">
            <datalist id="audit-user-options"></datalist>`;
    }

    function buildAllLogsHtml(data) {
        const logs = data.content || [];
        const rows = logs.length > 0 ? logs.map(buildAuditLogRow).join('') : `
            <tr>
                <td colspan="7" class="text-center text-muted py-4">
                    <i class="fas fa-inbox fa-3x mb-2 d-block" style="opacity:0.5;"></i>
                    No audit logs found for the selected filters
                </td>
            </tr>`;

        return `
            <div class="all-logs-container">
                <div class="content-header">
                    <div class="d-flex justify-content-between align-items-center">
                        <div>
                            <h1><i class="fas fa-file-alt"></i> Global Audit Logs</h1>
                            <p class="text-muted">Complete audit trail across ${isAdmin() ? 'all environments' : 'your environments'}</p>
                        </div>
                    </div>
                </div>

                <div class="row g-2 mb-2" id="all-logs-stats">${buildStatsHtml()}</div>

                <div class="all-logs-filter-bar mb-2">
                    <select class="form-select form-select-sm" id="time-range-filter" aria-label="Time range">
                        <option value="24h" ${currentFilters.timeRange === '24h' ? 'selected' : ''}>Last 24h</option>
                        <option value="7d" ${currentFilters.timeRange === '7d' ? 'selected' : ''}>Last 7 days</option>
                        <option value="30d" ${currentFilters.timeRange === '30d' ? 'selected' : ''}>Last 30 days</option>
                        <option value="custom" ${currentFilters.timeRange === 'custom' ? 'selected' : ''}>Custom range</option>
                    </select>
                    ${buildUserFilter()}
                    <select class="form-select form-select-sm" id="environment-filter" aria-label="Environment">
                        <option value="">All Environments</option>
                        ${allEnvironments.map(e => Utils.html`<option value="${e.id}" ${currentFilters.environmentId === e.id ? 'selected' : ''}>${e.name}</option>`).join('')}
                    </select>
                    <select class="form-select form-select-sm" id="action-type-filter" aria-label="Action">
                        <option value="">All Actions</option>
                        ${buildActionOptions()}
                    </select>
                    <select class="form-select form-select-sm" id="result-filter" aria-label="Result">
                        <option value="">All Results</option>
                        <option value="true" ${currentFilters.resultStatus === 'true' ? 'selected' : ''}>Success</option>
                        <option value="false" ${currentFilters.resultStatus === 'false' ? 'selected' : ''}>Failure</option>
                    </select>
                    <select class="form-select form-select-sm" id="page-size-filter" title="Rows per page" aria-label="Rows per page">
                        <option value="50" ${currentFilters.size === 50 ? 'selected' : ''}>50</option>
                        <option value="100" ${currentFilters.size === 100 ? 'selected' : ''}>100</option>
                        <option value="500" ${currentFilters.size === 500 ? 'selected' : ''}>500</option>
                    </select>
                    <button class="btn btn-outline-danger btn-ghost btn-sm" id="clear-filters-btn" title="Clear all filters">
                        <i class="fas fa-times"></i> Clear
                    </button>
                    <button class="btn btn-ghost btn-sm" id="export-logs-btn" title="Export CSV (up to ${EXPORT_MAX_ROWS.toLocaleString()} rows)">
                        <i class="fas fa-download"></i> Export
                    </button>
                </div>

                <div id="custom-date-range" class="all-logs-custom-range mb-2" style="display:${currentFilters.timeRange === 'custom' ? 'flex' : 'none'};">
                    <input type="date" class="form-control form-control-sm" id="start-date-input" value="${Utils.escapeHtml(currentFilters.customStart)}" aria-label="From date">
                    <input type="date" class="form-control form-control-sm" id="end-date-input" value="${Utils.escapeHtml(currentFilters.customEnd)}" aria-label="To date">
                    <button class="btn btn-primary btn-sm" id="apply-custom-range-btn">Apply</button>
                </div>

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
                                <tbody>${rows}</tbody>
                            </table>
                        </div>
                        <div class="pagination-bar-wrap" id="all-logs-pagination"></div>
                    </div>
                </div>
            </div>
        `;
    }

    function renderAllLogsPagination(logs) {
        Pagination.renderNumbered('#all-logs-pagination', {
            page: logs.number || 0,
            totalItems: logs.totalElements || 0,
            pageSize: currentFilters.size,
            itemLabel: 'entries',
            zeroIndexed: true,
            bindAncestor: '#content-area',
            onPageChange: (page) => {
                currentFilters.page = page;
                loadAllLogs(true);
            }
        });
    }

    function buildAuditLogRow(log) {
        const timestamp = formatTimestamp(log.createdAt);
        const actionBadgeClass = getActionBadgeClass(log.action);
        const actionDisplay = log.actionDisplay || formatActionName(log.action);
        const resultIcon = log.success !== false ? '<i class="fas fa-check-circle text-success"></i>' : '<i class="fas fa-times-circle text-danger"></i>';
        const full = log.details || log.errorMessage || '';
        const details = full ? full.substring(0, 40) + (full.length > 40 ? '...' : '') : '-';

        return Utils.html`
            <tr>
                <td>
                    <div>${timestamp.relative}</div>
                    <small class="text-muted">${timestamp.absolute}</small>
                </td>
                <td><small>${getUserDisplay(log)}</small></td>
                <td>${getEnvironmentDisplay(log)}</td>
                <td><span class="badge ${actionBadgeClass}">${actionDisplay}</span></td>
                <td>${log.targetName || '-'}</td>
                <td class="text-center">${Utils.raw(resultIcon)}</td>
                <td title="${full}">${details}</td>
            </tr>
        `;
    }

    function reloadFromFirstPage() {
        currentFilters.page = 0;
        loadAllLogs();
    }

    function bindAllLogsEvents() {
        $('#time-range-filter').on('change', function() {
            const value = $(this).val();
            currentFilters.timeRange = value;
            if (value === 'custom') {
                $('#custom-date-range').show();
            } else {
                $('#custom-date-range').hide();
                setPresetRange(value);
                reloadFromFirstPage();
            }
        });

        $('#user-filter').on('change', function() {
            currentFilters.userId = $(this).val();
            reloadFromFirstPage();
        });

        const searchUsers = Utils.debounce(function(query) {
            ApiClient.get(Config.API.users.search(query), { suppressGlobalError: true }).done(function(users) {
                allUsers = (Array.isArray(users) ? users : []).map(toUserOption);
                $('#audit-user-options').html(allUsers.map(u => Utils.html`<option value="${u.name} <${u.email}>"></option>`).join(''));
            });
        }, 300);
        $('#user-search-input').on('input', function() {
            const value = $(this).val().trim();
            const match = allUsers.find(u => `${u.name} <${u.email}>` === value);
            if (match) {
                currentFilters.userId = match.id;
                currentFilters.userLabel = value;
                reloadFromFirstPage();
            } else if (!value && currentFilters.userId) {
                currentFilters.userId = '';
                currentFilters.userLabel = '';
                reloadFromFirstPage();
            } else if (value.length >= 2) {
                searchUsers(value);
            }
        });

        $('#environment-filter').on('change', function() {
            currentFilters.environmentId = $(this).val();
            reloadFromFirstPage();
        });

        $('#action-type-filter').on('change', function() {
            currentFilters.actionType = $(this).val();
            reloadFromFirstPage();
        });

        $('#result-filter').on('change', function() {
            currentFilters.resultStatus = $(this).val();
            reloadFromFirstPage();
        });

        $('#apply-custom-range-btn').on('click', function() {
            const startDate = $('#start-date-input').val();
            const endDate = $('#end-date-input').val();
            if (!startDate || !endDate) {
                Notifications.show('Please select both start and end dates', 'warning');
                return;
            }
            if (startDate > endDate) {
                Notifications.show('Start date must be before end date', 'warning');
                return;
            }
            setCustomRange(startDate, endDate);
            reloadFromFirstPage();
        });

        $('#page-size-filter').on('change', function() {
            currentFilters.size = parseInt($(this).val(), 10);
            reloadFromFirstPage();
        });

        $('#clear-filters-btn').on('click', function() {
            currentFilters.userId = '';
            currentFilters.userLabel = '';
            currentFilters.environmentId = '';
            currentFilters.actionType = '';
            currentFilters.resultStatus = '';
            currentFilters.timeRange = '24h';
            setPresetRange('24h');
            reloadFromFirstPage();
        });

        $('#export-logs-btn').on('click', exportAuditLogs);
    }

    /**
     * Reload with the current filters; stats are refetched unless only the page changed.
     * @param {boolean} [pageChangeOnly=false]
     */
    function loadAllLogs(pageChangeOnly) {
        const t = ContentRouter.token();
        showLoading();
        Promise.all([
            fetchAllAuditLogs(currentFilters),
            pageChangeOnly ? Promise.resolve(stats) : fetchStats(currentFilters)
        ]).then(([logs, statsData]) => {
            if (!ContentRouter.isCurrent(t)) return;
            stats = statsData || emptyStats();
            render(logs);
        }).catch(error => {
            if (!ContentRouter.isCurrent(t)) return;
            console.error('Error loading audit logs:', error);
            showError('Failed to load audit logs.');
        });
    }

    /** Page through the filtered rows (500 at a time, at most 10,000) and download a CSV. */
    async function exportAuditLogs() {
        const t = ContentRouter.token();
        const fileName = `audit-logs-${new Date().toISOString().split('T')[0]}.csv`;
        const headers = ['Timestamp', 'User', 'Environment', 'Action', 'Target', 'Result', 'Details'];
        const rows = [];
        try {
            for (let page = 0; rows.length < EXPORT_MAX_ROWS; page++) {
                const data = await fetchAllAuditLogs({ ...currentFilters, page, size: EXPORT_PAGE_SIZE });
                const logs = data.content || [];
                logs.forEach(log => rows.push([
                    log.createdAt,
                    getUserDisplay(log),
                    getEnvironmentDisplay(log),
                    log.actionDisplay || log.action || '',
                    log.targetName || '',
                    log.success !== false ? 'Success' : 'Failed',
                    log.details || log.errorMessage || ''
                ]));
                if (page + 1 >= (data.totalPages || 0) || logs.length === 0) break;
            }
            if (!ContentRouter.isCurrent(t)) return;
            Utils.downloadCsv(fileName, headers, rows.slice(0, EXPORT_MAX_ROWS));
            Notifications.show(`Exported ${Math.min(rows.length, EXPORT_MAX_ROWS).toLocaleString()} audit log row(s)`, 'success');
        } catch (error) {
            console.error('Error exporting logs:', error);
            Notifications.show('Failed to export logs', 'danger');
        }
    }

    function formatTimestamp(timestamp) {
        if (!timestamp) return { relative: '-', absolute: '' };

        const date = new Date(timestamp);
        const diff = Date.now() - date.getTime();
        const minutes = Math.floor(diff / 60000);
        const hours = Math.floor(diff / 3600000);
        const days = Math.floor(diff / 86400000);

        let relative;
        if (minutes < 60) {
            relative = `${minutes}m ago`;
        } else if (hours < 24) {
            relative = `${hours}h ago`;
        } else if (days < 7) {
            relative = `${days}d ago`;
        } else {
            relative = date.toLocaleDateString();
        }
        return { relative, absolute: date.toLocaleString() };
    }

    function getActionBadgeClass(action) {
        if (!action) return 'bg-secondary';

        const actionStr = action.toString().toLowerCase();
        // Automation rule actions checked explicitly (before the generic substring
        // checks below) — "automation_rule_triggered"/"_skipped" don't contain any of
        // those generic keywords and would otherwise always fall through to bg-secondary.
        if (actionStr === 'automation_rule_triggered') return 'bg-success';
        if (actionStr === 'automation_rule_skipped') return 'text-bg-warning';
        if (actionStr === 'automation_rule_failed') return 'bg-danger';
        if (actionStr.startsWith('automation_rule_')) return 'bg-primary';

        if (actionStr.includes('failed')) return 'bg-danger';
        if (actionStr.includes('start')) return 'bg-success';
        if (actionStr.includes('stop')) return 'bg-danger';
        if (actionStr.includes('lock')) return 'text-bg-warning';
        if (actionStr.includes('restart')) return 'bg-info';
        if (actionStr.includes('access')) return 'bg-primary';
        if (actionStr.includes('user')) return 'bg-info';

        return 'bg-secondary';
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

    function showError(message) {
        $('#content-area').html(Utils.html`
            <div class="d-flex justify-content-center align-items-center" style="min-height: 400px;">
                <div class="text-center">
                    <i class="fas fa-exclamation-triangle text-danger fa-3x mb-3"></i>
                    <h5>Error</h5>
                    <p class="text-muted">${message}</p>
                    <button class="btn btn-primary" data-action="all-logs-retry">Retry</button>
                </div>
            </div>
        `);
    }

    return {
        loadAllAuditLogs
    };
})();

Actions.register('all-logs-retry', () => AllLogs.loadAllAuditLogs());
