/**
 * VM Self-Service Platform - System Health
 * Wired to all monitoring + audit-report endpoints:
 *   GET  /monitoring/sync-status
 *   POST /monitoring/sync
 *   GET  /monitoring/state-changes
 *   GET  /monitoring/drift-events
 *   GET  /monitoring/drift-events/count
 *   GET  /monitoring/drift-events/report
 *   GET  /audit/report
 *   GET  /audit/report/locks
 *   GET  /audit/report/vm-operations
 *   GET  /audit/actions  (used to populate action labels)
 */

const SystemHealth = (function () {
    'use strict';

    // ─── helpers ─────────────────────────────────────────────────────────────

    function formatDate(date) {
        return date.toISOString().split('T')[0];
    }

    function relativeTime(ts) {
        if (!ts) return '—';
        const diff = Date.now() - new Date(ts).getTime();
        const m = Math.floor(diff / 60000);
        if (m < 1)  return 'Just now';
        if (m < 60) return `${m}m ago`;
        const h = Math.floor(m / 60);
        if (h < 24) return `${h}h ago`;
        return `${Math.floor(h / 24)}d ago`;
    }

    function absTime(ts) {
        if (!ts) return '—';
        return new Date(ts).toLocaleString();
    }

    function stateBadge(state) {
        if (!state) return '<span class="badge bg-secondary">—</span>';
        const map = {
            RUNNING:  'bg-success',
            STOPPED:  'bg-danger',
            UNKNOWN:  'bg-secondary',
            NOT_FOUND:'bg-warning',
            TERMINATED:'bg-dark'
        };
        return `<span class="badge ${map[state] || 'bg-secondary'}">${state}</span>`;
    }

    function actionLabel(action) {
        if (!action) return '—';
        return action.replace(/_/g, ' ')
            .toLowerCase()
            .replace(/\b\w/g, c => c.toUpperCase());
    }

    // ─── fetch helpers ────────────────────────────────────────────────────────

    function apiFetch(url, fallback) {
        return new Promise(resolve => {
            ApiClient.get(url, { suppressGlobalError: true })
                .done(resolve)
                .fail(() => resolve(fallback));
        });
    }

    function apiPost(url) {
        return ApiClient.post(url, {}, { suppressGlobalError: true });
    }

    function fetchSyncStatus() {
        return apiFetch(Config.API.monitoring.syncStatus, {
            inProgress: false, lastSyncTime: null, vmCount: 0, driftCount: 0, errorCount: 0
        });
    }

    function fetchStateChanges() {
        return apiFetch(Config.API.monitoring.stateChanges, []);
    }

    function fetchDriftEvents() {
        return new Promise(resolve => {
            ApiClient.get(Config.API.monitoring.driftEvents, { suppressGlobalError: true })
                .done(data => resolve(Array.isArray(data) ? data : (data.content || [])))
                .fail(() => resolve([]));
        });
    }

    function fetchDriftCount(startDate, endDate) {
        return new Promise(resolve => {
            ApiClient.get(
                `${Config.API.monitoring.driftCount}?startDate=${startDate}&endDate=${endDate}`,
                { suppressGlobalError: true }
            )
            .done(data => {
                // Backend may return a plain number or a wrapped object
                if (typeof data === 'number') resolve(data);
                else resolve(Number(data.count ?? data.driftCount ?? data.value ?? data) || 0);
            })
            .fail(() => resolve(0));
        });
    }

    function fetchAuditReport(startDate, endDate) {
        return apiFetch(
            `${Config.API.audit.report}?startDate=${startDate}&endDate=${endDate}`, null
        );
    }

    function fetchLockReport(startDate, endDate) {
        return apiFetch(
            `${Config.API.audit.lockReport}?startDate=${startDate}&endDate=${endDate}`, []
        );
    }

    function fetchVmOpsReport(startDate, endDate) {
        return apiFetch(
            `${Config.API.audit.vmReport}?startDate=${startDate}&endDate=${endDate}`, []
        );
    }

    // ─── public: load ─────────────────────────────────────────────────────────

    async function load() {
        showLoading();

        const now       = new Date();
        const yesterday = new Date(now);
        yesterday.setDate(yesterday.getDate() - 1);
        const startDate = formatDate(yesterday);
        const endDate   = formatDate(now);

        try {
            const [
                syncStatus,
                stateChanges,
                driftEvents,
                driftCount,
                auditReport,
                lockReport,
                vmOpsReport
            ] = await Promise.all([
                fetchSyncStatus(),
                fetchStateChanges(),
                fetchDriftEvents(),
                fetchDriftCount(startDate, endDate),
                fetchAuditReport(startDate, endDate),
                fetchLockReport(startDate, endDate),
                fetchVmOpsReport(startDate, endDate)
            ]);

            render({
                syncStatus, stateChanges, driftEvents,
                driftCount, auditReport, lockReport, vmOpsReport
            });
        } catch (err) {
            console.error('System Health load error:', err);
            showError('Failed to load system health data.');
        }
    }

    // ─── trigger sync ─────────────────────────────────────────────────────────

    function triggerSync() {
        const $btn = $('#trigger-sync-btn');
        $btn.prop('disabled', true).html('<i class="fas fa-spinner fa-spin me-1"></i>Syncing…');

        apiPost(Config.API.monitoring.triggerSync)
            .done(() => {
                Notifications.show('State sync triggered — refreshing in 3s…', 'success');
                setTimeout(() => load(), 3000);
            })
            .fail(xhr => {
                if (xhr && xhr.status === 409) {
                    Notifications.show('Sync already in progress', 'warning');
                } else {
                    Notifications.show('Failed to trigger sync', 'danger');
                }
                $btn.prop('disabled', false).html('<i class="fas fa-sync me-1"></i>Trigger State Sync');
            });
    }

    function triggerEksSync() {
        const $btn = $('#trigger-eks-sync-btn');
        $btn.prop('disabled', true).html('<i class="fas fa-spinner fa-spin me-1"></i>Syncing EKS…');

        apiPost(Config.API.monitoring.triggerEksSync)
            .done(data => {
                const n = data && data.nodeGroupsSynced != null ? data.nodeGroupsSynced : 0;
                Notifications.show(`EKS sync complete — ${n} node group(s) processed`, 'success');
                setTimeout(() => load(), 2000);
            })
            .fail(xhr => {
                if (xhr && xhr.status === 503) {
                    Notifications.show('EKS not available — check AWS credentials in .env', 'warning');
                } else {
                    Notifications.show('EKS sync failed', 'danger');
                }
            })
            .always(() => {
                $btn.prop('disabled', false).html('<i class="fab fa-aws me-1"></i>EKS Sync Now');
            });
    }

    // ─── render ───────────────────────────────────────────────────────────────

    function render({ syncStatus, stateChanges, driftEvents, driftCount, auditReport, lockReport, vmOpsReport }) {

        const auditTotal    = auditReport ? (auditReport.totalActions || 0) : 0;
        // successfulActions/failedActions are not computed by the backend report endpoint;
        // derive from recentLogs sample as a best-effort indicator
        const recentLogs    = (auditReport && auditReport.recentLogs) || [];
        const auditSuccess  = recentLogs.filter(l => l.success !== false).length;
        const auditFailures = recentLogs.filter(l => l.success === false).length;
        const successRate   = recentLogs.length > 0
            ? Math.round((auditSuccess / recentLogs.length) * 100) : 0;

        const topEnvList = (auditReport && auditReport.environmentActivities) || [];
        const topEnv     = topEnvList.length > 0
            ? (topEnvList[0].environmentName || topEnvList[0].environmentId || '—')
            : '—';

        const syncBadge = syncStatus.syncInProgress
            ? '<span class="badge bg-warning text-dark"><i class="fas fa-spinner fa-spin me-1"></i>Syncing</span>'
            : '<span class="badge bg-success"><i class="fas fa-check me-1"></i>Idle</span>';

        const html = `
        <div class="content-view" id="system-health-view">

            <!-- Header -->
            <div class="content-header d-flex justify-content-between align-items-center">
                <div>
                    <h1><i class="fas fa-heartbeat me-2"></i>System Health</h1>
                    <p class="text-muted mb-0">Live platform health and state synchronization</p>
                </div>
                <div class="d-flex gap-2 flex-shrink-0">
                    <button class="btn btn-sm btn-primary" id="trigger-sync-btn" onclick="SystemHealth.triggerSync()">
                        <i class="fas fa-sync me-1"></i>Trigger Sync
                    </button>
                    <button class="btn btn-sm btn-warning" id="trigger-eks-sync-btn" onclick="SystemHealth.triggerEksSync()">
                        <i class="fab fa-aws me-1"></i>EKS Sync Now
                    </button>
                    <button class="btn btn-sm btn-outline-secondary" onclick="SystemHealth.load()">
                        <i class="fas fa-redo me-1"></i>Refresh
                    </button>
                </div>
            </div>

            <!-- Key Metrics -->
            <div class="row g-2 mb-3">
                <div class="col-md-3">
                    <div class="metric-card">
                        <div class="metric-title">Sync Status</div>
                        <div class="metric-value">${syncBadge}</div>
                        <div class="metric-subtitle">Platform state</div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="metric-card">
                        <div class="metric-title">Last State Sync</div>
                        <div class="metric-value">${relativeTime(syncStatus.lastSyncTime)}</div>
                        <div class="metric-subtitle">${syncStatus.totalVmsSynced || 0} VMs synced</div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="metric-card">
                        <div class="metric-title">Drift Events (24h)</div>
                        <div class="metric-value ${driftCount > 0 ? 'text-warning' : 'text-success'}">${driftCount}</div>
                        <div class="metric-subtitle">${syncStatus.syncErrors || 0} sync error(s)</div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="metric-card">
                        <div class="metric-title">Audit Actions (24h)</div>
                        <div class="metric-value">${auditTotal.toLocaleString()}</div>
                        <div class="metric-subtitle">${successRate}% success rate</div>
                    </div>
                </div>
            </div>

            <!-- Audit Summary -->
            <div class="card sh-summary-card mb-3">
                <div class="card-header d-flex align-items-center gap-2">
                    <i class="fas fa-chart-bar text-primary"></i>
                    <strong>Audit Summary — Last 24 Hours</strong>
                </div>
                <div class="card-body">
                    <div class="row text-center g-0">
                        <div class="col border-end">
                            <div class="sh-stat-value">${auditTotal.toLocaleString()}</div>
                            <div class="sh-stat-label">Total Actions</div>
                        </div>
                        <div class="col border-end">
                            <div class="sh-stat-value text-success">${auditSuccess.toLocaleString()}</div>
                            <div class="sh-stat-label">Successes</div>
                        </div>
                        <div class="col border-end">
                            <div class="sh-stat-value text-danger">${auditFailures.toLocaleString()}</div>
                            <div class="sh-stat-label">Failures</div>
                        </div>
                        <div class="col border-end">
                            <div class="sh-stat-value">${successRate}%</div>
                            <div class="sh-stat-label">Success Rate</div>
                        </div>
                        <div class="col">
                            <div class="sh-stat-value text-truncate" title="${topEnv}">${topEnv}</div>
                            <div class="sh-stat-label">Most Active Env</div>
                        </div>
                    </div>
                </div>
            </div>

            <!-- State Changes + Drift side-by-side -->
            <div class="row g-2 mb-3">
                <div class="col-md-6">
                    <div class="card sh-table-card h-100">
                        <div class="card-header d-flex align-items-center gap-2">
                            <i class="fas fa-exchange-alt text-info"></i>
                            <strong>Recent State Changes</strong>
                            <span class="badge bg-secondary ms-auto">${stateChanges.length}</span>
                        </div>
                        <div class="card-body">
                            ${buildStateChangesTable(stateChanges)}
                        </div>
                    </div>
                </div>
                <div class="col-md-6">
                    <div class="card sh-table-card h-100">
                        <div class="card-header d-flex align-items-center gap-2">
                            <i class="fas fa-exclamation-triangle text-warning"></i>
                            <strong>Drift Events (24h)</strong>
                            <span class="badge bg-warning text-dark ms-auto">${driftEvents.length}</span>
                        </div>
                        <div class="card-body">
                            ${buildDriftTable(driftEvents)}
                        </div>
                    </div>
                </div>
            </div>

            <!-- Lock Compliance Report -->
            <div class="card sh-compliance-card mb-3">
                <div class="card-header d-flex align-items-center gap-2">
                    <i class="fas fa-lock text-warning"></i>
                    <strong>Lock Compliance Report — Last 24 Hours</strong>
                    <span class="badge bg-secondary ms-auto">${lockReport.length} events</span>
                </div>
                <div class="card-body">
                    ${buildComplianceTable(lockReport, ['User', 'Action', 'Environment', 'Target', 'Time'])}
                </div>
            </div>

            <!-- VM Operations Report -->
            <div class="card sh-compliance-card mb-3">
                <div class="card-header d-flex align-items-center gap-2">
                    <i class="fas fa-play-circle text-success"></i>
                    <strong>VM Operations Report — Last 24 Hours</strong>
                    <span class="badge bg-secondary ms-auto">${vmOpsReport.length} events</span>
                </div>
                <div class="card-body">
                    ${buildComplianceTable(vmOpsReport, ['User', 'Action', 'Environment', 'Target', 'Result', 'Time'])}
                </div>
            </div>

        </div>`;

        $('#content-area').html(html);
    }

    // ─── table builders ───────────────────────────────────────────────────────

    function buildStateChangesTable(changes) {
        if (!changes || changes.length === 0) {
            return emptyState('No recent state changes');
        }
        const rows = changes.slice(0, 20).map(c => `
            <tr>
                <td>${c.vmName || c.vmId || '—'}</td>
                <td>${stateBadge(c.previousStatus)}</td>
                <td class="text-center text-muted px-0"><i class="fas fa-arrow-right"></i></td>
                <td>${stateBadge(c.newStatus)}</td>
                <td class="text-muted" title="${absTime(c.changedAt)}">${relativeTime(c.changedAt)}</td>
            </tr>`).join('');
        return `
            <div class="sh-table-wrapper">
                <table class="table table-hover mb-0">
                    <thead><tr><th>VM</th><th>From</th><th></th><th>To</th><th>When</th></tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>`;
    }

    function buildDriftTable(events) {
        if (!events || events.length === 0) {
            return emptyState('No drift events in last 24 hours', true);
        }
        const rows = events.slice(0, 20).map(e => `
            <tr>
                <td>${e.vmName || e.vmId || '—'}</td>
                <td>${stateBadge(e.previousStatus)}</td>
                <td class="text-center text-muted px-0"><i class="fas fa-arrow-right"></i></td>
                <td>${stateBadge(e.newStatus)}</td>
                <td class="text-muted" title="${absTime(e.changedAt)}">${relativeTime(e.changedAt)}</td>
            </tr>`).join('');
        return `
            <div class="sh-table-wrapper">
                <table class="table table-hover mb-0">
                    <thead><tr><th>VM</th><th>Expected</th><th></th><th>Actual</th><th>When</th></tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>`;
    }

    function buildComplianceTable(logs, columns) {
        if (!logs || logs.length === 0) {
            return emptyState('No events in the last 24 hours');
        }
        const hasResult = columns.includes('Result');
        const rows = logs.map(log => {
            const resultCell = hasResult
                ? `<td class="text-center">${log.success !== false
                    ? '<i class="fas fa-check-circle text-success"></i>'
                    : '<i class="fas fa-times-circle text-danger"></i>'}</td>`
                : '';
            return `
                <tr>
                    <td>${log.userEmail || log.userId || 'system'}</td>
                    <td><span class="badge bg-secondary">${log.actionDisplay || actionLabel(log.action)}</span></td>
                    <td>${log.environmentName || log.environmentId || '—'}</td>
                    <td>${log.targetName || '—'}</td>
                    ${resultCell}
                    <td class="text-muted" title="${absTime(log.createdAt)}">${relativeTime(log.createdAt)}</td>
                </tr>`;
        }).join('');
        const headerCells = columns.map(c => `<th>${c}</th>`).join('');
        return `
            <div class="sh-compliance-wrapper">
                <table class="table table-hover mb-0">
                    <thead><tr>${headerCells}</tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>`;
    }

    // ─── loading / error states ───────────────────────────────────────────────

    function emptyState(msg, success = false) {
        const cls = success ? 'text-success' : 'text-muted';
        return `<div class="sh-empty ${cls}">
                    <i class="fas fa-inbox"></i>
                    ${msg}
                </div>`;
    }

    function showLoading() {
        $('#content-area').html(`
            <div class="d-flex justify-content-center align-items-center" style="min-height:400px;">
                <div class="text-center">
                    <div class="spinner-border text-primary mb-3" role="status"></div>
                    <p class="text-muted">Loading system health…</p>
                </div>
            </div>`);
    }

    function showError(message) {
        $('#content-area').html(`
            <div class="d-flex justify-content-center align-items-center" style="min-height:400px;">
                <div class="text-center">
                    <i class="fas fa-exclamation-triangle text-danger fa-3x mb-3"></i>
                    <h5>Error</h5>
                    <p class="text-muted">${message}</p>
                    <button class="btn btn-primary" onclick="SystemHealth.load()">Retry</button>
                </div>
            </div>`);
    }

    return { load, triggerSync, triggerEksSync };
})();

window.SystemHealth = SystemHealth;
