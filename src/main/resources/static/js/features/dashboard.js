/**
 * VM Self-Service Platform - Dashboard Feature
 * Handles dashboard view and data with real API integration
 */

const Dashboard = (function() {
    'use strict';

    // Cache for dashboard data
    let dashboardData = null;
    let isLoading = false;
    let autoRefreshEnabled = true;

    /**
     * Load dashboard view
     */
    async function load() {
        // Stop any existing polling when loading dashboard
        RealTime.unregister('dashboard');

        // Show loading state
        showLoading();

        try {
            // Fetch all dashboard data
            dashboardData = await fetchDashboardData();

            // Build and display dashboard
            const html = buildDashboardHtml(dashboardData);
            $('#content-area').html(html);
            bindEvents();

            // Register for auto-refresh
            if (autoRefreshEnabled) {
                RealTime.registerDashboardRefresh(silentRefresh);
            }

        } catch (error) {
            console.error('Failed to load dashboard:', error);
            showError('Failed to load dashboard data. Please try again.');
        }
    }

    /**
     * Silent refresh (no loading indicator)
     */
    async function silentRefresh() {
        if (isLoading) return;

        try {
            dashboardData = await fetchDashboardData();
            updateDashboardUI(dashboardData);
        } catch (error) {
            console.error('Silent refresh failed:', error);
        }
    }

    /**
     * Update dashboard UI without full rebuild
     */
    function updateDashboardUI(data) {
        // Update metrics
        $('#metric-environments').text(data.metrics.totalEnvironments);
        $('#metric-total-vms').text(data.metrics.totalVms);
        $('#metric-running-vms').text(data.metrics.runningVms);
        $('#metric-running-percent').text(`${data.metrics.runningPercent}%`);

        // Update last refresh time
        $('#last-refresh-time').text('Just now');
    }

    /**
     * Fetch all dashboard data from APIs
     */
    async function fetchDashboardData() {
        // Fetch environments list
        const environments = await fetchEnvironments();

        // Calculate metrics from environments
        const metrics = calculateMetrics(environments);

        // Calculate cloud provider breakdown
        const cloudBreakdown = calculateCloudBreakdown(environments);

        return {
            environments,
            metrics,
            cloudBreakdown
        };
    }

    /**
     * Fetch environments with their details
     */
    function fetchEnvironments() {
        return new Promise((resolve, reject) => {
            ApiClient.get(Config.API.environments.list)
                .done(async function(environments) {
                    // Fetch additional details for each environment
                    const enrichedEnvs = await enrichEnvironments(environments);
                    resolve(enrichedEnvs);
                })
                .fail(function(xhr) {
                    console.error('Failed to fetch environments:', xhr);
                    reject(new Error('Failed to fetch environments'));
                });
        });
    }

    /**
     * Enrich environments with VM and lock data
     */
    async function enrichEnvironments(environments) {
        const enriched = [];

        for (const env of environments) {
            try {
                // Fetch VMs for this environment
                const vmsData = await fetchEnvironmentVms(env.environmentId);

                // Fetch lock status
                const lockStatus = await fetchLockStatus(env.environmentId);

                // Calculate VM counts
                let totalVms = 0;
                let runningVms = 0;
                const providers = new Set();

                if (vmsData && vmsData.length > 0) {
                    vmsData.forEach(group => {
                        if (group.vms) {
                            group.vms.forEach(vm => {
                                totalVms++;
                                if (vm.status === 'RUNNING') {
                                    runningVms++;
                                }
                                if (vm.provider) {
                                    providers.add(vm.provider);
                                }
                            });
                        }
                    });
                }

                enriched.push({
                    ...env,
                    totalVms: env.vmCount || totalVms,
                    runningVms: runningVms,
                    lockStatus: lockStatus,
                    providers: Array.from(providers)
                });
            } catch (error) {
                // If we can't get details, use basic info
                enriched.push({
                    ...env,
                    totalVms: env.vmCount || 0,
                    runningVms: 0,
                    lockStatus: { isLocked: false },
                    providers: []
                });
            }
        }

        return enriched;
    }

    /**
     * Fetch VMs for an environment
     */
    function fetchEnvironmentVms(envId) {
        return new Promise((resolve) => {
            ApiClient.get(Config.API.vms.list(envId))
                .done(function(vms) {
                    resolve(vms || []);
                })
                .fail(function() {
                    resolve([]);
                });
        });
    }

    /**
     * Fetch lock status for an environment
     */
    function fetchLockStatus(envId) {
        return new Promise((resolve) => {
            ApiClient.get(Config.API.locks.status(envId))
                .done(function(lock) {
                    resolve(lock || { isLocked: false });
                })
                .fail(function() {
                    resolve({ isLocked: false });
                });
        });
    }

    /**
     * Calculate metrics from environments data
     */
    function calculateMetrics(environments) {
        let totalVms = 0;
        let runningVms = 0;

        environments.forEach(env => {
            totalVms += env.totalVms || 0;
            runningVms += env.runningVms || 0;
        });

        const runningPercent = totalVms > 0 ? Math.round((runningVms / totalVms) * 100) : 0;

        return {
            environments: environments.length,
            totalVms,
            runningVms,
            runningPercent,
            estimatedCost: 0 // Cost calculation TBD
        };
    }

    /**
     * Calculate cloud provider breakdown from environments
     */
    function calculateCloudBreakdown(environments) {
        const providerMap = {};

        // Initialize with known providers
        const knownProviders = ['AWS', 'AZURE', 'GCP', 'OCI'];
        knownProviders.forEach(p => {
            providerMap[p] = { totalVms: 0, runningVms: 0 };
        });

        // Aggregate from environments
        environments.forEach(env => {
            if (env.providers) {
                env.providers.forEach(provider => {
                    if (!providerMap[provider]) {
                        providerMap[provider] = { totalVms: 0, runningVms: 0 };
                    }
                    // Approximate distribution (would need per-VM data for accuracy)
                    providerMap[provider].totalVms += Math.ceil(env.totalVms / env.providers.length);
                    providerMap[provider].runningVms += Math.ceil(env.runningVms / env.providers.length);
                });
            }
        });

        // Convert to array and filter out empty providers
        return Object.entries(providerMap)
            .filter(([_, data]) => data.totalVms > 0)
            .map(([provider, data]) => {
                const config = Config.CLOUD_ICONS[provider] || {
                    icon: 'fas fa-cloud',
                    color: '#6b7280',
                    label: provider
                };
                return {
                    provider: config.label,
                    icon: config.icon,
                    color: config.color,
                    totalVms: data.totalVms,
                    runningVms: data.runningVms
                };
            });
    }

    /**
     * Show loading state
     */
    function showLoading() {
        isLoading = true;
        $('#content-area').html(`
            <div class="content-view" id="dashboard-view">
                <div class="content-header">
                    <h1>Dashboard</h1>
                    <p>Overview of your environments and VMs</p>
                </div>
                <div class="loading-state">
                    <div class="spinner-border text-primary" role="status">
                        <span class="visually-hidden">Loading...</span>
                    </div>
                    <p>Loading dashboard data...</p>
                </div>
            </div>
        `);
    }

    /**
     * Show error state
     */
    function showError(message) {
        isLoading = false;
        $('#content-area').html(`
            <div class="content-view" id="dashboard-view">
                <div class="content-header">
                    <h1>Dashboard</h1>
                    <p>Overview of your environments and VMs</p>
                </div>
                <div class="error-state">
                    <i class="fas fa-exclamation-triangle fa-3x text-warning"></i>
                    <h4 class="mt-3">Unable to Load Dashboard</h4>
                    <p>${message}</p>
                    <button class="btn btn-primary" onclick="Dashboard.refresh()">
                        <i class="fas fa-sync"></i> Try Again
                    </button>
                </div>
            </div>
        `);
    }

    /**
     * Build dashboard HTML
     */
    function buildDashboardHtml(data) {
        isLoading = false;

        return `
            <div class="content-view" id="dashboard-view">
                <div class="content-header d-flex justify-content-between align-items-start">
                    <div>
                        <h1>Dashboard</h1>
                        <p>Overview of your environments and VMs</p>
                    </div>
                    <button class="btn btn-outline-secondary btn-sm" onclick="Dashboard.refresh()">
                        <i class="fas fa-sync"></i> Refresh
                    </button>
                </div>

                <!-- Cloud Provider Breakdown -->
                ${buildCloudBreakdown(data.cloudBreakdown)}

                <!-- Metric Cards -->
                ${buildMetricCards(data.metrics)}

                <!-- Environments Table -->
                ${buildEnvironmentsTable(data.environments)}
            </div>
        `;
    }

    /**
     * Build cloud provider breakdown section
     */
    function buildCloudBreakdown(providers) {
        if (!providers || providers.length === 0) {
            return `
                <div class="row mb-4">
                    <div class="col-md-12">
                        <div class="metric-card">
                            <h5 class="mb-3">Cloud Provider Breakdown</h5>
                            <p class="text-muted">No VMs registered yet</p>
                        </div>
                    </div>
                </div>
            `;
        }

        const providerCards = providers.map(p => `
            <div class="col-md-4 col-lg-3">
                <div class="d-flex align-items-center mb-2">
                    <i class="${p.icon}" style="font-size: 2rem; color: ${p.color}; margin-right: 1rem;"></i>
                    <div>
                        <div style="font-weight: 600; color: #1e293b;">${p.provider}</div>
                        <div style="color: #64748b; font-size: 0.875rem;">
                            ${p.totalVms} VMs (${p.runningVms} running)
                        </div>
                    </div>
                </div>
            </div>
        `).join('');

        return `
            <div class="row mb-4">
                <div class="col-md-12">
                    <div class="metric-card">
                        <h5 class="mb-3">Cloud Provider Breakdown</h5>
                        <div class="row">
                            ${providerCards}
                        </div>
                    </div>
                </div>
            </div>
        `;
    }

    /**
     * Build metric cards section
     */
    function buildMetricCards(metrics) {
        return `
            <div class="row mb-4">
                <div class="col-md-3">
                    <div class="metric-card">
                        <div class="metric-title">My Environments</div>
                        <div class="metric-value" id="metric-environments">${metrics.environments}</div>
                        <div class="metric-subtitle">Accessible environments</div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="metric-card">
                        <div class="metric-title">Total VMs</div>
                        <div class="metric-value" id="metric-total-vms">${metrics.totalVms}</div>
                        <div class="metric-subtitle">Registered instances</div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="metric-card">
                        <div class="metric-title">Running VMs</div>
                        <div class="metric-value" id="metric-running-vms">${metrics.runningVms}</div>
                        <div class="metric-subtitle"><span id="metric-running-percent">${metrics.runningPercent}%</span> of total</div>
                        <div class="progress mt-2" style="height: 6px;">
                            <div class="progress-bar bg-success" id="metric-running-bar" style="width: ${metrics.runningPercent}%"></div>
                        </div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="metric-card">
                        <div class="metric-title">Stopped VMs</div>
                        <div class="metric-value" id="metric-stopped-vms">${metrics.totalVms - metrics.runningVms}</div>
                        <div class="metric-subtitle">${100 - metrics.runningPercent}% of total</div>
                        <div class="progress mt-2" style="height: 6px;">
                            <div class="progress-bar bg-secondary" style="width: ${100 - metrics.runningPercent}%"></div>
                        </div>
                    </div>
                </div>
            </div>
        `;
    }

    /**
     * Build environments table
     */
    function buildEnvironmentsTable(environments) {
        if (!environments || environments.length === 0) {
            return `
                <div class="metric-card">
                    <h5 class="mb-3">My Environments</h5>
                    <div class="empty-state">
                        <i class="fas fa-folder-open fa-3x text-muted"></i>
                        <p class="mt-3">No environments available</p>
                        <p class="text-muted">Request access to environments to get started</p>
                        <a href="#" class="btn btn-primary" data-content="request-access">
                            <i class="fas fa-paper-plane"></i> Request Access
                        </a>
                    </div>
                </div>
            `;
        }

        const rows = environments.map(env => {
            const statusClass = env.runningVms === 0 ? 'stopped' :
                               env.runningVms === env.totalVms ? 'running' : 'partial';

            // Lock status display
            let lockDisplay;
            if (env.lockStatus && env.lockStatus.isLocked) {
                const lockedBy = env.lockStatus.lockedByUserId === Auth.getUserId() ? 'you' :
                                 (env.lockStatus.lockedByDisplayName || 'another user');
                lockDisplay = `<span class="text-warning"><i class="fas fa-lock"></i> ${lockedBy}</span>`;
            } else {
                lockDisplay = `<span class="text-success"><i class="fas fa-unlock"></i> Unlocked</span>`;
            }

            // Cloud provider icons
            const providerIcons = (env.providers || []).map(p => {
                const config = Config.CLOUD_ICONS[p] || { icon: 'fas fa-cloud', color: '#6b7280' };
                return `<i class="${config.icon}" style="color: ${config.color};" title="${p}"></i>`;
            }).join(' ') || '<span class="text-muted">-</span>';

            return `
                <tr>
                    <td>
                        <strong>${Utils.escapeHtml(env.name)}</strong>
                        ${env.displayName && env.displayName !== env.name ?
                            `<br><small class="text-muted">${Utils.escapeHtml(env.displayName)}</small>` : ''}
                    </td>
                    <td>${env.totalVms}</td>
                    <td>
                        <span class="status-badge ${statusClass}">
                            <i class="fas fa-circle"></i> ${env.runningVms}/${env.totalVms}
                        </span>
                    </td>
                    <td>${lockDisplay}</td>
                    <td>${providerIcons}</td>
                    <td>
                        <button class="btn btn-sm btn-primary" data-env-id="${env.environmentId}" data-action="view">
                            <i class="fas fa-eye"></i> View
                        </button>
                        ${env.totalVms > 0 ? `
                            <button class="btn btn-sm btn-success" data-env-id="${env.environmentId}" data-action="start-all"
                                    ${env.runningVms === env.totalVms ? 'disabled' : ''}>
                                <i class="fas fa-play"></i>
                            </button>
                            <button class="btn btn-sm btn-danger" data-env-id="${env.environmentId}" data-action="stop-all"
                                    ${env.runningVms === 0 ? 'disabled' : ''}>
                                <i class="fas fa-stop"></i>
                            </button>
                        ` : ''}
                    </td>
                </tr>
            `;
        }).join('');

        return `
            <div class="metric-card">
                <div class="d-flex justify-content-between align-items-center mb-3">
                    <h5 class="mb-0">My Environments</h5>
                    <div class="input-group" style="width: 250px;">
                        <span class="input-group-text"><i class="fas fa-search"></i></span>
                        <input type="text" class="form-control" id="env-search" placeholder="Search...">
                    </div>
                </div>
                <div class="custom-table">
                    <table class="table table-hover">
                        <thead>
                            <tr>
                                <th>Environment</th>
                                <th>Total VMs</th>
                                <th>Running</th>
                                <th>Lock Status</th>
                                <th>Cloud</th>
                                <th>Actions</th>
                            </tr>
                        </thead>
                        <tbody id="env-table-body">
                            ${rows}
                        </tbody>
                    </table>
                </div>
            </div>
        `;
    }

    /**
     * Bind dashboard event handlers
     */
    function bindEvents() {
        // View environment button
        $('#content-area').off('click', '[data-action="view"]').on('click', '[data-action="view"]', function() {
            const envId = $(this).data('env-id');
            const env = dashboardData.environments.find(e => e.environmentId === envId);
            if (env) {
                Environments.loadDetail({ environmentId: envId, environmentName: env.name });
            }
        });

        // Start all VMs
        $('#content-area').off('click', '[data-action="start-all"]').on('click', '[data-action="start-all"]', function() {
            const envId = $(this).data('env-id');
            startEnvironment(envId);
        });

        // Stop all VMs
        $('#content-area').off('click', '[data-action="stop-all"]').on('click', '[data-action="stop-all"]', function() {
            const envId = $(this).data('env-id');
            stopEnvironment(envId);
        });

        // Search filter
        $('#env-search').off('input').on('input', Utils.debounce(function() {
            const searchTerm = $(this).val().toLowerCase();
            filterEnvironments(searchTerm);
        }, 300));
    }

    /**
     * Filter environments table
     */
    function filterEnvironments(searchTerm) {
        $('#env-table-body tr').each(function() {
            const text = $(this).text().toLowerCase();
            $(this).toggle(text.includes(searchTerm));
        });
    }

    /**
     * Start all VMs in an environment
     */
    function startEnvironment(envId) {
        const env = dashboardData.environments.find(e => e.environmentId === envId);
        if (!env) return;

        Modals.confirm(
            'Start Environment',
            `Start all VMs in "<strong>${Utils.escapeHtml(env.name)}</strong>"?`,
            function() {
                VmOperations.startEnvironment(envId, env.name)
                    .then(() => refresh())
                    .catch(() => {});
            },
            { confirmText: 'Start All', confirmClass: 'btn-success' }
        );
    }

    /**
     * Stop all VMs in an environment
     */
    function stopEnvironment(envId) {
        const env = dashboardData.environments.find(e => e.environmentId === envId);
        if (!env) return;

        Modals.confirm(
            'Stop Environment',
            `Stop all VMs in "<strong>${Utils.escapeHtml(env.name)}</strong>"?`,
            function() {
                VmOperations.stopEnvironment(envId, env.name)
                    .then(() => refresh())
                    .catch(() => {});
            },
            { confirmText: 'Stop All', confirmClass: 'btn-danger' }
        );
    }

    /**
     * Refresh dashboard data
     */
    function refresh() {
        if (isLoading) return;
        load();
    }

    /**
     * Get cached dashboard data
     */
    function getData() {
        return dashboardData;
    }

    return {
        load,
        refresh,
        getData
    };
})();

