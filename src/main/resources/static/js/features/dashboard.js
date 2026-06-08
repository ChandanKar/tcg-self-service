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
            if (error.status === 403) {
                showError('You do not have access to any environments. Please request access from an administrator.');
            } else {
                showError('Failed to load dashboard data. Please try again.');
            }
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

        return {
            environments,
            metrics
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
                    <!-- Metric Cards -->
                    ${buildMetricCards(data.metrics)}

                    <!-- Environments Table -->
                    ${buildEnvironmentsTable(data.environments)}
                </div>
            </div>
        `;
    }

    /**
     * Build metric cards section — number only, detail on tooltip
     */
    function buildMetricCards(metrics) {
        return `
            <div class="row g-2 flex-shrink-0">
                <div class="col-md-3">
                    <div class="metric-card"
                         data-bs-toggle="tooltip" data-bs-placement="bottom"
                         title="My Environments — ${metrics.environments} accessible environment${metrics.environments !== 1 ? 's' : ''}">
                        <div class="metric-value" id="metric-environments">${metrics.environments}</div>
                        <div class="metric-label-hint">My Environments</div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="metric-card"
                         data-bs-toggle="tooltip" data-bs-placement="bottom"
                         title="Total VMs — ${metrics.totalVms} registered instance${metrics.totalVms !== 1 ? 's' : ''}">
                        <div class="metric-value" id="metric-total-vms">${metrics.totalVms}</div>
                        <div class="metric-label-hint">Total VMs</div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="metric-card"
                         data-bs-toggle="tooltip" data-bs-placement="bottom"
                         title="Running VMs — ${metrics.runningPercent}% of total (${metrics.runningVms} of ${metrics.totalVms})">
                        <div class="metric-value text-success" id="metric-running-vms">${metrics.runningVms}</div>
                        <div class="metric-label-hint">Running</div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="metric-card"
                         data-bs-toggle="tooltip" data-bs-placement="bottom"
                         title="Stopped VMs — ${100 - metrics.runningPercent}% of total (${metrics.totalVms - metrics.runningVms} of ${metrics.totalVms})">
                        <div class="metric-value text-secondary" id="metric-stopped-vms">${metrics.totalVms - metrics.runningVms}</div>
                        <div class="metric-label-hint">Stopped</div>
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
                        <i class="fas fa-server fa-3x text-muted"></i>
                        <p class="mt-3 mb-1">No environments assigned</p>
                        <p class="text-muted small">You don't have access to any environments yet.<br>Please contact your administrator for environment access.</p>
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
                        <input type="text" class="form-control form-control-sm" id="env-search" placeholder="Search..."
                               oninput="Dashboard._search(this.value)">
                    </div>
                </div>
                <div class="env-table-wrapper">
                    <table class="table table-hover mb-0">
                        <thead class="table-light sticky-top">
                            <tr>
                                <th>Environment</th>
                                <th class="col-type text-center">Type</th>
                                <th>Total VMs</th>
                                <th>Running</th>
                                <th>Lock Status</th>
                                <th>Cloud</th>
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

        const dataRows = pageItems.map(env => {
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

            const serviceType = (env.serviceType || 'EC2').toUpperCase();
            const typeColor = serviceType === 'EKS' ? '#1d4ed8' : '#c2410c';
            const typeIcon = serviceType === 'EKS' ? 'fas fa-dharmachakra' : 'fas fa-server';

            const tooltip = env.description ? ` data-bs-toggle="tooltip" data-bs-placement="right" title="${Utils.escapeHtml(env.description)}"` : '';
            return `
                <tr class="env-row" data-env-id="${env.environmentId}">
                    <td><strong${tooltip}>${Utils.escapeHtml(env.name)}</strong></td>
                    <td class="text-center"><i class="${typeIcon} env-type-icon" style="color:${typeColor}" data-bs-toggle="tooltip" title="${serviceType}"></i></td>
                    <td>${env.totalVms}</td>
                    <td>
                        <span class="status-badge ${statusClass}">
                            <i class="fas fa-circle"></i> ${env.runningVms}/${env.totalVms}
                        </span>
                    </td>
                    <td>${lockDisplay}</td>
                    <td>${providerIcons}</td>
                </tr>
            `;
        }).join('');

        // Single filler row — expands via CSS height:100% to fill leftover space
        const filler = pageItems.length < PAGE_SIZE
            ? `<tr class="env-table-filler"><td colspan="6"></td></tr>`
            : '';

        return dataRows + filler;
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
                    <button class="btn btn-sm btn-outline-secondary env-page-btn" data-page="1"
                            ${page === 1 ? 'disabled' : ''} title="First page">
                        <i class="fas fa-angle-double-left"></i>
                    </button>
                    <button class="btn btn-sm btn-outline-secondary env-page-btn ms-1" data-page="${page - 1}"
                            ${page === 1 ? 'disabled' : ''} title="Previous page">
                        <i class="fas fa-chevron-left"></i>
                    </button>
                    ${pageButtons}
                    <button class="btn btn-sm btn-outline-secondary env-page-btn ms-1" data-page="${page + 1}"
                            ${page === totalPages ? 'disabled' : ''} title="Next page">
                        <i class="fas fa-chevron-right"></i>
                    </button>
                    <button class="btn btn-sm btn-outline-secondary env-page-btn ms-1" data-page="${totalPages}"
                            ${page === totalPages ? 'disabled' : ''} title="Last page">
                        <i class="fas fa-angle-double-right"></i>
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

        // Search — delegated binding (fallback) + direct binding on rendered element
        $('#content-area').off('input', '#env-search').on('input', '#env-search', function() {
            filterAndRender($(this).val().toLowerCase().trim());
        });
        // Direct bind on the already-rendered element (most reliable after innerHTML swap)
        $('#env-search').off('input.dash').on('input.dash', function() {
            filterAndRender($(this).val().toLowerCase().trim());
        });

        // Initial pagination render
        if (dashboardData && dashboardData.environments) {
            renderPagination(dashboardData.environments.length, currentPage);
        }

        // Init tooltips
        initTooltips();
    }

    /**
     * Initialise Bootstrap tooltips — table rows, metric cards, cloud bar
     */
    function initTooltips() {
        const els = document.querySelectorAll(
            '#env-table-body [data-bs-toggle="tooltip"], ' +
            '#dashboard-view [data-bs-toggle="tooltip"]'
        );
        els.forEach(el => {
            if (typeof bootstrap !== 'undefined' && bootstrap.Tooltip) {
                // Dispose existing to avoid duplicates
                const existing = bootstrap.Tooltip.getInstance(el);
                if (existing) existing.dispose();
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
        getData,
        _search: function(val) {
            filterAndRender((val || '').toLowerCase().trim());
        }
    };
})();
