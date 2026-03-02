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

    // Pagination state for environments table
    const PAGE_SIZE = 7;
    let currentPage = 1;
    let filteredEnvironments = [];

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
                <div class="content-header d-flex justify-content-between align-items-start flex-shrink-0">
                    <div>
                        <h1>Dashboard</h1>
                        <p>Overview of your environments and VMs</p>
                    </div>
                    <button class="btn btn-outline-secondary btn-sm" onclick="Dashboard.refresh()">
                        <i class="fas fa-sync"></i> Refresh
                    </button>
                </div>

                <div class="dashboard-body">
                    <!-- Cloud Provider Breakdown -->
                    ${buildCloudBreakdown(data.cloudBreakdown)}

                    <!-- Metric Cards -->
                    ${buildMetricCards(data.metrics)}

                    <!-- Environments Table -->
                    ${buildEnvironmentsTable(data.environments)}
                </div>
            </div>
        `;
    }

    /**
     * Build cloud provider breakdown section
     */
    function buildCloudBreakdown(providers) {
        if (!providers || providers.length === 0) {
            return `
                <div class="row mb-2 flex-shrink-0">
                    <div class="col-md-12">
                        <div class="metric-card">
                            <h5 class="mb-2">Cloud Provider Breakdown</h5>
                            <p class="text-muted">No VMs registered yet</p>
                        </div>
                    </div>
                </div>
            `;
        }

        const providerCards = providers.map(p => `
            <div class="col-md-4 col-lg-3">
                <div class="d-flex align-items-center mb-1">
                    <i class="${p.icon}" style="font-size: 1.75rem; color: ${p.color}; margin-right: 0.75rem;"></i>
                    <div>
                        <div style="font-weight: 600; color: #1e293b;">${p.provider}</div>
                        <div style="color: #64748b; font-size: 0.8rem;">
                            ${p.totalVms} VMs (${p.runningVms} running)
                        </div>
                    </div>
                </div>
            </div>
        `).join('');

        return `
            <div class="row mb-1 flex-shrink-0">
                <div class="col-md-12">
                    <div class="metric-card py-2">
                        <h6 class="mb-2 text-muted text-uppercase" style="font-size:0.7rem;letter-spacing:0.05em;">Cloud Provider Breakdown</h6>
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
            <div class="row mb-1 flex-shrink-0">
                <div class="col-md-3">
                    <div class="metric-card py-2">
                        <div class="metric-title">My Environments</div>
                        <div class="metric-value" id="metric-environments">${metrics.environments}</div>
                        <div class="metric-subtitle">Accessible environments</div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="metric-card py-2">
                        <div class="metric-title">Total VMs</div>
                        <div class="metric-value" id="metric-total-vms">${metrics.totalVms}</div>
                        <div class="metric-subtitle">Registered instances</div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="metric-card py-2">
                        <div class="metric-title">Running VMs</div>
                        <div class="metric-value" id="metric-running-vms">${metrics.runningVms}</div>
                        <div class="metric-subtitle"><span id="metric-running-percent">${metrics.runningPercent}%</span> of total</div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="metric-card py-2">
                        <div class="metric-title">Stopped VMs</div>
                        <div class="metric-value" id="metric-stopped-vms">${metrics.totalVms - metrics.runningVms}</div>
                        <div class="metric-subtitle">${100 - metrics.runningPercent}% of total</div>
                    </div>
                </div>
            </div>
        `;
    }

    /**
     * Build environments table — clickable rows, no actions, pagination, internal scroll
     */
    function buildEnvironmentsTable(environments) {
        if (!environments || environments.length === 0) {
            return `
                <div class="metric-card env-table-card">
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

        filteredEnvironments = environments;
        currentPage = 1;

        return `
            <div class="metric-card env-table-card">
                <div class="d-flex justify-content-between align-items-center mb-2 flex-shrink-0">
                    <h6 class="mb-0 text-muted text-uppercase" style="font-size:0.7rem;letter-spacing:0.05em;">My Environments</h6>
                    <div class="input-group" style="width: 220px;">
                        <span class="input-group-text py-1"><i class="fas fa-search" style="font-size:0.8rem;"></i></span>
                        <input type="text" class="form-control form-control-sm" id="env-search" placeholder="Search...">
                    </div>
                </div>
                <div class="env-table-wrapper">
                    <table class="table table-hover mb-0">
                        <thead class="table-light sticky-top">
                            <tr>
                                <th>Environment</th>
                                <th>Total VMs</th>
                                <th>Running</th>
                                <th>Lock Status</th>
                                <th>Cloud</th>
                                <th style="width:32px;"></th>
                            </tr>
                        </thead>
                        <tbody id="env-table-body">
                            ${buildTableRows(environments, 1)}
                        </tbody>
                    </table>
                </div>
                <div id="env-pagination" class="flex-shrink-0 pt-1"></div>
            </div>
        `;
    }

    /**
     * Build table rows for given page from filtered list
     */
    function buildTableRows(envList, page) {
        const start = (page - 1) * PAGE_SIZE;
        const pageItems = envList.slice(start, start + PAGE_SIZE);

        return pageItems.map(env => {
            const statusClass = env.runningVms === 0 ? 'stopped' :
                               env.runningVms === env.totalVms ? 'running' : 'partial';

            let lockDisplay;
            if (env.lockStatus && env.lockStatus.isLocked) {
                const lockedBy = env.lockStatus.lockedByUserId === Auth.getUserId() ? 'you' :
                                 (env.lockStatus.lockedByDisplayName || 'another user');
                lockDisplay = `<span class="text-warning"><i class="fas fa-lock"></i> ${lockedBy}</span>`;
            } else {
                lockDisplay = `<span class="text-success"><i class="fas fa-unlock"></i> Unlocked</span>`;
            }

            const providerIcons = (env.providers || []).map(p => {
                const config = Config.CLOUD_ICONS[p] || { icon: 'fas fa-cloud', color: '#6b7280' };
                return `<i class="${config.icon}" style="color: ${config.color};" title="${p}"></i>`;
            }).join(' ') || '<span class="text-muted">-</span>';

            const tooltip = env.description ? ` data-bs-toggle="tooltip" data-bs-placement="right" title="${Utils.escapeHtml(env.description)}"` : '';
            return `
                <tr class="env-row" data-env-id="${env.environmentId}">
                    <td>
                        <strong${tooltip}>${Utils.escapeHtml(env.name)}</strong>
                    </td>
                    <td>${env.totalVms}</td>
                    <td>
                        <span class="status-badge ${statusClass}">
                            <i class="fas fa-circle"></i> ${env.runningVms}/${env.totalVms}
                        </span>
                    </td>
                    <td>${lockDisplay}</td>
                    <td>${providerIcons}</td>
                    <td class="text-muted text-end pe-2">
                        <i class="fas fa-chevron-right" style="font-size:0.75rem;"></i>
                    </td>
                </tr>
            `;
        }).join('');
    }

    /**
     * Render pagination controls — only shown when total rows > PAGE_SIZE
     */
    function renderPagination(totalItems, page) {
        const totalPages = Math.ceil(totalItems / PAGE_SIZE);

        if (totalItems <= PAGE_SIZE) {
            $('#env-pagination').html(
                `<div class="text-muted small mt-2">Showing ${totalItems} environment${totalItems !== 1 ? 's' : ''}</div>`
            );
            return;
        }

        const start      = (page - 1) * PAGE_SIZE + 1;
        const end        = Math.min(page * PAGE_SIZE, totalItems);
        const rangeStart = Math.max(1, page - 2);
        const rangeEnd   = Math.min(totalPages, page + 2);

        let pageButtons = '';
        for (let i = rangeStart; i <= rangeEnd; i++) {
            pageButtons += `<button class="btn btn-sm ${i === page ? 'btn-primary' : 'btn-outline-secondary'} env-page-btn ms-1"
                data-page="${i}">${i}</button>`;
        }

        $('#env-pagination').html(`
            <div class="d-flex justify-content-between align-items-center mt-2">
                <span class="text-muted small">Showing ${start}–${end} of ${totalItems} environments</span>
                <div>
                    <button class="btn btn-sm btn-outline-secondary env-page-btn"
                            data-page="${page - 1}" ${page === 1 ? 'disabled' : ''}>
                        <i class="fas fa-chevron-left"></i>
                    </button>
                    ${pageButtons}
                    <button class="btn btn-sm btn-outline-secondary env-page-btn ms-1"
                            data-page="${page + 1}" ${page === totalPages ? 'disabled' : ''}>
                        <i class="fas fa-chevron-right"></i>
                    </button>
                </div>
            </div>
        `);
    }

    /**
     * Apply search filter and re-render table + pagination
     */
    function filterAndRender(searchTerm) {
        const all = dashboardData ? dashboardData.environments : [];
        filteredEnvironments = searchTerm
            ? all.filter(e =>
                (e.name || '').toLowerCase().includes(searchTerm) ||
                (e.description || '').toLowerCase().includes(searchTerm))
            : all;
        currentPage = 1;
        $('#env-table-body').html(buildTableRows(filteredEnvironments, currentPage));
        renderPagination(filteredEnvironments.length, currentPage);
        initTooltips();
    }

    /**
     * Bind dashboard event handlers
     */
    function bindEvents() {
        // Pagination button clicks
        $('#content-area').off('click', '.env-page-btn').on('click', '.env-page-btn', function() {
            const page = parseInt($(this).data('page'));
            if (!page || page < 1) return;
            const totalPages = Math.ceil(filteredEnvironments.length / PAGE_SIZE);
            if (page > totalPages) return;
            currentPage = page;
            $('#env-table-body').html(buildTableRows(filteredEnvironments, currentPage));
            renderPagination(filteredEnvironments.length, currentPage);
            initTooltips();
        });

        // Search — filters and resets to page 1
        $('#content-area').off('input', '#env-search').on('input', '#env-search', Utils.debounce(function() {
            filterAndRender($(this).val().toLowerCase().trim());
        }, 300));

        // Initial pagination render
        if (dashboardData && dashboardData.environments) {
            renderPagination(dashboardData.environments.length, currentPage);
        }

        // Init tooltips
        initTooltips();
    }

    /**
     * Initialise Bootstrap tooltips on env name cells
     */
    function initTooltips() {
        const els = document.querySelectorAll('#env-table-body [data-bs-toggle="tooltip"]');
        els.forEach(el => {
            if (bootstrap && bootstrap.Tooltip) {
                new bootstrap.Tooltip(el, { trigger: 'hover' });
            }
        });
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
