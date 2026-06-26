/**
 * VM Self-Service Platform - Environments Feature
 * Handles environment list and detail views with real API integration
 */

const Environments = (function() {
    'use strict';

    // Safe HTML escaping utility
    const escapeHtml = (text) => {
        if (!text) return '';
        try {
            // Use Utils if available
            if (typeof Utils !== 'undefined' && Utils.escapeHtml) {
                return Utils.escapeHtml(text);
            }
        } catch (e) {
            // Fall back to DOM method
        }
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    };

    // Cache for current environment data
    let currentEnvironment = null;
    let environmentsList = [];

    // Pagination state for list view
    const ENV_PAGE_SIZE = 10;
    let envCurrentPage = 1;
    let envFiltered = [];
    let operationRefreshTimer = null;
    let operationStatusHandler = null;
    let metricChartCounter = 0;

    function isTransitionalStatus(status) {
        return ['STARTING', 'STOPPING'].includes((status || '').toUpperCase());
    }

    function getEnvironmentVms(env) {
        return (env.groups || []).flatMap(groupData => groupData.vms || []);
    }

    function hasTransitionalVms(env) {
        return getEnvironmentVms(env).some(vm => isTransitionalStatus(vm.status));
    }

    /**
     * Load environment list view
     */
    async function loadList() {
        showLoading('Loading environments...');

        try {
            environmentsList = await fetchEnvironments();
            const html = buildListHtml(environmentsList);
            $('#content-area').html(html);
            bindListEvents();
        } catch (error) {
            console.error('Failed to load environments:', error);
            if (error.status === 403) {
                showError('You do not have access to any environments. Please request access from an administrator.');
            } else {
                showError('Failed to load environments. Please try again.');
            }
        }
    }

    /**
     * Load environment detail view
     */
    async function loadDetail(params) {
        disposeAllBootstrapTooltips();
        let envId = params.environmentId;
        const envName = params.environmentName;

        showLoading(`Loading ${envName || 'environment'}...`);

        try {
            // If no environmentId provided, try to resolve it by name from the environments list
            if (!envId && envName) {
                const envList = await new Promise((resolve, reject) => {
                    ApiClient.get(Config.API.environments.list)
                        .done(resolve)
                        .fail(reject);
                });
                const match = envList.find(e =>
                    e.name === envName ||
                    e.displayName === envName ||
                    (e.name && e.name.toLowerCase() === envName.toLowerCase())
                );
                if (match) {
                    envId = match.environmentId;
                } else {
                    showError(`Environment "${envName}" not found or you do not have access.`);
                    return;
                }
            }

            if (!envId) {
                showError('No environment specified.');
                return;
            }

            // Fetch environment details
            const env = await fetchEnvironmentDetails(envId);
            currentEnvironment = env;

            const html = buildDetailHtml(env);
            $('#content-area').html(html);
            bindDetailEvents(env);
        } catch (error) {
            console.error('Failed to load environment:', error);
            if (error.status === 403) {
                showError('You do not have access to this environment. Please request access from an administrator.');
            } else if (error.status === 404) {
                showError('Environment not found.');
            } else {
                showError('Failed to load environment details.');
            }
        }
    }

    /**
     * Fetch environments list
     */
    function fetchEnvironments() {
        return new Promise((resolve, reject) => {
            ApiClient.get(Config.API.environments.list)
                .done(async function(environments) {
                    // Enrich list rows with the same VM status data used by the detail view.
                    const enriched = await Promise.all(
                        environments.map(async (env) => {
                            try {
                                const [lock, vmsData] = await Promise.all([
                                    fetchLockStatus(env.environmentId),
                                    fetchEnvironmentVms(env.environmentId)
                                ]);
                                const counts = calculateVmCounts(vmsData);
                                return {
                                    ...env,
                                    ...counts,
                                    groups: vmsData || [],
                                    lockStatus: lock
                                };
                            } catch (e) {
                                return {
                                    ...env,
                                    totalVms: env.vmCount || 0,
                                    runningVms: 0,
                                    groups: [],
                                    lockStatus: { isLocked: false }
                                };
                            }
                        })
                    );
                    resolve(enriched);
                })
                .fail(reject);
        });
    }

    /**
     * Fetch single environment details with groups and VMs
     */
    async function fetchEnvironmentDetails(envId) {
        // Fetch environment basic info
        const env = await new Promise((resolve, reject) => {
            ApiClient.get(Config.API.environments.get(envId))
                .done(resolve)
                .fail(reject);
        });

        // Fetch groups with VMs
        const vmsData = await fetchEnvironmentVms(envId);

        // Fetch lock status
        const lockStatus = await fetchLockStatus(envId);

        // Calculate counts
        const counts = calculateVmCounts(vmsData);

        return {
            ...env,
            groups: vmsData || [],
            totalVms: env.vmCount || counts.totalVms,
            runningVms: counts.runningVms,
            lockStatus
        };
    }

    function calculateVmCounts(vmsData) {
        let totalVms = 0;
        let runningVms = 0;

        if (vmsData && vmsData.length > 0) {
            vmsData.forEach(group => {
                if (group.vms) {
                    group.vms.forEach(vm => {
                        totalVms++;
                        if (vm.status === 'RUNNING') {
                            runningVms++;
                        }
                    });
                }
            });
        }

        return { totalVms, runningVms };
    }

    function fetchEnvironmentVms(envId) {
        return new Promise((resolve) => {
            ApiClient.get(Config.API.vms.list(envId))
                .done(resolve)
                .fail(() => resolve([]));
        });
    }

    /**
     * Fetch lock status
     */
    function fetchLockStatus(envId) {
        return new Promise((resolve) => {
            ApiClient.get(Config.API.locks.status(envId))
                .done(resolve)
                .fail(() => resolve({ isLocked: false }));
        });
    }

    /**
     * Show loading state
     */
    function showLoading(message) {
        $('#content-area').html(`
            <div class="content-view">
                <div class="loading-state">
                    <div class="spinner-border text-primary" role="status"></div>
                    <p>${message || 'Loading...'}</p>
                </div>
            </div>
        `);
    }

    /**
     * Show error state
     */
    function showError(message) {
        $('#content-area').html(`
            <div class="content-view">
                <div class="error-state">
                    <i class="fas fa-exclamation-triangle fa-3x text-warning"></i>
                    <h4 class="mt-3">Error</h4>
                    <p>${message}</p>
                    <button class="btn btn-primary" onclick="Dashboard.load()">
                        <i class="fas fa-arrow-left"></i> Back to Dashboard
                    </button>
                </div>
            </div>
        `);
    }

    /**
     * Build environment list HTML — dashboard-style compact table with pagination
     */
    function buildListHtml(environments) {
        if (!environments || environments.length === 0) {
            return `
                <div class="content-header d-flex justify-content-between align-items-start">
                    <div>
                        <h1>My Environments</h1>
                        <p>Manage and monitor your accessible environments</p>
                    </div>
                </div>
                <div class="empty-state">
                    <i class="fas fa-server fa-3x text-muted"></i>
                    <p class="mt-3 mb-1">No environments assigned</p>
                    <p class="text-muted small">You don't have access to any environments yet.<br>
                    ${Auth.isEnvAdmin() ? 'Go to <strong>Admin › VM Registry</strong> to create environments.' : 'Please contact your administrator for environment access.'}</p>
                </div>
            `;
        }

        envFiltered = environments;
        envCurrentPage = 1;

        return `
            <div id="environments-list-view">
            <div class="content-header d-flex justify-content-between align-items-start flex-shrink-0">
                <div>
                    <h1>My Environments</h1>
                    <p>Manage and monitor your accessible environments</p>
                </div>
            </div>
            <div class="metric-card env-list-card">
                <div class="d-flex justify-content-between align-items-center mb-2 flex-shrink-0">
                    <h6 class="mb-0 text-muted text-uppercase" style="font-size:0.7rem;letter-spacing:0.05em;">My Environments</h6>
                    <div class="input-group" style="width:220px;">
                        <span class="input-group-text py-1"><i class="fas fa-search" style="font-size:0.8rem;"></i></span>
                        <input type="text" class="form-control form-control-sm" id="env-list-search" placeholder="Search..."
                               oninput="Environments._search(this.value)">
                    </div>
                </div>
                <div class="env-list-table-wrapper">
                    <table class="table table-hover mb-0">
                        <thead class="table-light sticky-top">
                            <tr>
                                <th>Environment</th>
                                <th class="col-type text-center">Type</th>
                                <th class="col-cloud text-center">Cloud</th>
                                <th>Groups</th>
                                <th>Total VMs</th>
                                <th>Running</th>
                                <th>Lock Status</th>
                                <th>Actions</th>
                            </tr>
                        </thead>
                        <tbody id="env-list-body">
                            ${buildEnvRows(environments, 1)}
                        </tbody>
                    </table>
                </div>
                <div class="env-list-spacer"></div>
                <div id="env-list-pagination" class="flex-shrink-0" style="border-top:1px solid #f1f5f9;padding-top:0.3rem;min-height:28px;"></div>
            </div>
            </div>
        `;
    }

    /**
     * Build paginated table rows for the list view
     */
    function buildEnvRows(envList, page) {
        const start = (page - 1) * ENV_PAGE_SIZE;
        const pageItems = envList.slice(start, start + ENV_PAGE_SIZE);

        const dataRows = pageItems.map(env => {
            const runningVms = env.runningVms || 0;
            const totalVms = env.vmCount || env.totalVms || 0;
            const statusClass = runningVms === 0 ? 'stopped' :
                               runningVms === totalVms ? 'running' : 'partial';

            let lockDisplay;
            if (env.lockStatus && env.lockStatus.isLocked) {
                const lockedBy = env.lockStatus.lockedByUserId === Auth.getUserId() ? 'you' :
                                 (env.lockStatus.lockedByDisplayName || 'another user');
                lockDisplay = `<span class="text-warning"><i class="fas fa-lock"></i> ${lockedBy}</span>`;
            } else {
                lockDisplay = `<span class="text-success"><i class="fas fa-unlock"></i> Unlocked</span>`;
            }

            const adminBtns = Auth.isEnvAdmin() ? `
                <button class="btn btn-sm btn-outline-secondary btn-action ms-1"
                        data-env-id="${env.environmentId}" data-action="edit-env"
                        data-bs-toggle="tooltip" title="Edit">
                    <i class="fas fa-edit"></i>
                </button>
                <button class="btn btn-sm btn-outline-danger btn-action ms-1"
                        data-env-id="${env.environmentId}" data-env-name="${escapeHtml(env.name)}" data-action="delete-env"
                        data-bs-toggle="tooltip" title="Delete">
                    <i class="fas fa-trash"></i>
                </button>
            ` : '';

            const descTooltip = env.description
                ? ` data-bs-toggle="tooltip" data-bs-placement="right" title="${escapeHtml(env.description)}"`
                : '';

            const serviceType = (env.serviceType || 'EC2').toUpperCase();
            const typeColor = serviceType === 'EKS' ? '#1d4ed8' : '#c2410c';
            const typeIcon = serviceType === 'EKS' ? 'fas fa-dharmachakra' : 'fas fa-server';
            const typeCell = `<i class="${typeIcon} env-type-icon" style="color:${typeColor}" data-bs-toggle="tooltip" title="${serviceType}"></i>`;

            let envMeta = {};
            try { envMeta = JSON.parse(env.metadata || '{}') || {}; } catch(e) {}
            const cloudProv = (envMeta.defaultCloudProvider || 'AWS').toUpperCase();
            const cloudCfg = (Config.CLOUD_ICONS || {})[cloudProv] || { icon: 'fab fa-aws', color: '#FF9900', label: 'AWS' };

            return `
                <tr>
                    <td><strong${descTooltip}>${escapeHtml(env.name)}</strong></td>
                    <td class="text-center">${typeCell}</td>
                    <td class="text-center"><i class="${cloudCfg.icon} env-cloud-icon" style="color:${cloudCfg.color}" data-bs-toggle="tooltip" title="${cloudCfg.label || cloudProv}"></i></td>
                    <td>${env.groupCount || 0}</td>
                    <td>${totalVms}</td>
                    <td>
                        <span class="status-badge ${statusClass}">
                            <i class="fas fa-circle"></i> ${runningVms}/${totalVms}
                        </span>
                    </td>
                    <td>${lockDisplay}</td>
                    <td>
                        <button class="btn btn-sm btn-primary btn-action"
                                data-env-id="${env.environmentId}" data-env-name="${escapeHtml(env.name)}" data-action="view"
                                data-bs-toggle="tooltip" title="View">
                            <i class="fas fa-eye"></i>
                        </button>
                        ${adminBtns}
                    </td>
                </tr>
            `;
        }).join('');

        return dataRows;
    }

    /**
     * Render pagination for the list view
     */
    function renderEnvPagination(totalItems, page) {
        const totalPages = Math.ceil(totalItems / ENV_PAGE_SIZE);

        if (totalItems <= ENV_PAGE_SIZE) {
            $('#env-list-pagination').html(
                `<div class="text-muted small mt-1">Showing ${totalItems} environment${totalItems !== 1 ? 's' : ''}</div>`
            );
            return;
        }

        const start      = (page - 1) * ENV_PAGE_SIZE + 1;
        const end        = Math.min(page * ENV_PAGE_SIZE, totalItems);
        const rangeStart = Math.max(1, page - 2);
        const rangeEnd   = Math.min(totalPages, page + 2);

        let pageButtons = '';
        for (let i = rangeStart; i <= rangeEnd; i++) {
            pageButtons += `<button class="btn btn-sm ${i === page ? 'btn-primary' : 'btn-outline-secondary'} env-list-page-btn ms-1"
                data-page="${i}">${i}</button>`;
        }

        $('#env-list-pagination').html(`
            <div class="d-flex justify-content-between align-items-center">
                <span class="text-muted small">Showing ${start}–${end} of ${totalItems} environments</span>
                <div>
                    <button class="btn btn-sm btn-outline-secondary env-list-page-btn" data-page="1"
                            ${page === 1 ? 'disabled' : ''} title="First page">
                        <i class="fas fa-angle-double-left"></i>
                    </button>
                    <button class="btn btn-sm btn-outline-secondary env-list-page-btn ms-1" data-page="${page - 1}"
                            ${page === 1 ? 'disabled' : ''} title="Previous page">
                        <i class="fas fa-chevron-left"></i>
                    </button>
                    ${pageButtons}
                    <button class="btn btn-sm btn-outline-secondary env-list-page-btn ms-1" data-page="${page + 1}"
                            ${page === totalPages ? 'disabled' : ''} title="Next page">
                        <i class="fas fa-chevron-right"></i>
                    </button>
                    <button class="btn btn-sm btn-outline-secondary env-list-page-btn ms-1" data-page="${totalPages}"
                            ${page === totalPages ? 'disabled' : ''} title="Last page">
                        <i class="fas fa-angle-double-right"></i>
                    </button>
                </div>
            </div>
        `);

        // Dispose all existing tooltips in the table first (prevent leaks on re-render)
        document.querySelectorAll('#env-list-body [data-bs-toggle="tooltip"]').forEach(el => {
            if (typeof bootstrap !== 'undefined' && bootstrap.Tooltip) {
                const existing = bootstrap.Tooltip.getInstance(el);
                if (existing) existing.dispose();
            }
        });

        // Init fresh tooltips with auto-hide delay
        document.querySelectorAll('#env-list-body [data-bs-toggle="tooltip"]').forEach(el => {
            if (typeof bootstrap !== 'undefined' && bootstrap.Tooltip) {
                new bootstrap.Tooltip(el, {
                    trigger: 'hover',
                    delay: { show: 300, hide: 100 }
                });
            }
        });
    }

    /**
     * Dispose all Bootstrap tooltips inside env-list-body to prevent leaks on re-render
     */
    function disposeEnvListTooltips() {
        if (typeof bootstrap === 'undefined' || !bootstrap.Tooltip) return;
        document.querySelectorAll('#env-list-body [data-bs-toggle="tooltip"]').forEach(el => {
            const tt = bootstrap.Tooltip.getInstance(el);
            if (tt) tt.dispose();
        });
        document.querySelectorAll('.tooltip').forEach(el => el.remove());
    }

    function disposeAllBootstrapTooltips() {
        if (typeof bootstrap !== 'undefined' && bootstrap.Tooltip) {
            document.querySelectorAll('[data-bs-toggle="tooltip"]').forEach(el => {
                const tt = bootstrap.Tooltip.getInstance(el);
                if (tt) {
                    tt.hide();
                    tt.dispose();
                }
            });
        }
        document.querySelectorAll('.tooltip').forEach(el => el.remove());
    }

    /**
     * Filter environments list and re-render
     */
    function filterAndRenderEnvList(searchTerm) {
        envFiltered = searchTerm
            ? environmentsList.filter(e =>
                (e.name || '').toLowerCase().includes(searchTerm) ||
                (e.description || '').toLowerCase().includes(searchTerm))
            : environmentsList;
        envCurrentPage = 1;
        disposeEnvListTooltips();
        $('#env-list-body').html(buildEnvRows(envFiltered, envCurrentPage));
        renderEnvPagination(envFiltered.length, envCurrentPage);
    }

    /**
     * Build environment detail HTML
     */
    function buildDetailHtml(env) {
        const totalVms = env.totalVms || 0;
        const runningVms = env.runningVms || 0;

        // Lock section
        let lockSection = '';
        try {
            const isAdmin = (typeof Auth !== 'undefined' && Auth.isEnvAdmin) ? Auth.isEnvAdmin() : false;
            lockSection = (typeof Locks !== 'undefined' && Locks.buildLockBanner) ?
                Locks.buildLockBanner(env.lockStatus, env.environmentId, isAdmin) : '';
        } catch (e) { lockSection = ''; }

        // Compact metric cards — number-only + tooltip (same style as dashboard)
        const metricsRow = `
            <div class="row g-2 mb-2">
                <div class="col-6 col-md-3">
                    <div class="metric-card text-center" data-bs-toggle="tooltip" title="Total VMs in this environment">
                        <div class="metric-value">${totalVms}</div>
                        <div class="metric-label-hint">Total VMs</div>
                    </div>
                </div>
                <div class="col-6 col-md-3">
                    <div class="metric-card text-center" data-bs-toggle="tooltip" title="Currently running VMs">
                        <div class="metric-value text-success">${runningVms}</div>
                        <div class="metric-label-hint">Running</div>
                    </div>
                </div>
                <div class="col-6 col-md-3">
                    <div class="metric-card text-center" data-bs-toggle="tooltip" title="Currently stopped VMs">
                        <div class="metric-value text-danger">${totalVms - runningVms}</div>
                        <div class="metric-label-hint">Stopped</div>
                    </div>
                </div>
                <div class="col-6 col-md-3">
                    <div class="metric-card text-center" data-bs-toggle="tooltip" title="Number of VM groups">
                        <div class="metric-value">${env.groups ? env.groups.length : 0}</div>
                        <div class="metric-label-hint">Groups</div>
                    </div>
                </div>
            </div>`;

        // Groups — first expanded, rest collapsed
        const groupsHtml = (env.groups || []).map((group, idx) =>
            buildGroupCard(group, env, idx === 0)
        ).join('');

        return `
            <div id="env-detail-view">
                <div class="d-flex justify-content-between align-items-start mb-2">
                    <div style="border-left:4px solid var(--primary-color);padding-left:0.75rem;">
                        <div style="font-size:1.25rem;font-weight:700;color:#1e293b;margin-bottom:0.05rem;">${Utils.escapeHtml(env.displayName || env.name)}</div>
                        <div style="font-size:0.78rem;color:#64748b;">${escapeHtml(env.description || '')}</div>
                    </div>
                    <div class="d-flex gap-2 align-items-center">
                        <button class="btn btn-sm btn-outline-primary" id="btn-env-insights"
                                data-bs-toggle="tooltip" title="Environment insights">
                            <i class="fas fa-info-circle"></i>
                        </button>
                        <button class="btn btn-sm btn-outline-secondary" id="btn-operation-history">
                            <i class="fas fa-history"></i> History
                        </button>
                        ${hasTransitionalVms(env) ?
                            `<button class="btn btn-sm btn-secondary" id="btn-env-action" disabled
                                     data-bs-toggle="tooltip" title="Operation already in progress">
                                <i class="fas fa-spinner fa-spin"></i> In Progress
                            </button>` :
                        runningVms === 0 ?
                            `<button class="btn btn-sm btn-success" id="btn-env-action" data-action-type="start">
                                <i class="fas fa-play-circle"></i> Start All
                            </button>` :
                        runningVms === totalVms ?
                            `<button class="btn btn-sm btn-danger" id="btn-env-action" data-action-type="stop">
                                <i class="fas fa-stop-circle"></i> Stop All
                            </button>` :
                            `<button class="btn btn-sm btn-success" id="btn-env-action" data-action-type="start">
                                <i class="fas fa-play-circle"></i> Start All
                            </button>
                            <button class="btn btn-sm btn-danger" id="btn-env-action-stop" data-action-type="stop">
                                <i class="fas fa-stop-circle"></i> Stop All
                            </button>`
                        }
                        <button class="btn btn-sm btn-outline-secondary" onclick="Environments.loadList()">
                            <i class="fas fa-arrow-left"></i> Back
                        </button>
                    </div>
                </div>

                ${lockSection}
                ${metricsRow}

                <div class="mb-1">
                    <span class="text-muted text-uppercase" style="font-size:0.68rem;letter-spacing:0.05em;">Groups &amp; VMs</span>
                </div>

                <div id="env-groups-container">
                    ${groupsHtml || '<div class="alert alert-secondary py-2 small">No groups configured for this environment.</div>'}
                </div>
            </div>`;
    }

    /**
     * Build group card — collapsible accordion.
     * @param {boolean} expanded - first group starts open, rest collapsed
     */
    function buildGroupCard(groupData, env, expanded) {
        const group = groupData.group || groupData;
        const vms   = groupData.vms || [];

        const runningCount = vms.filter(v => v.status === 'RUNNING').length || 0;
        const transitioningCount = vms.filter(v => isTransitionalStatus(v.status)).length || 0;
        const totalCount   = vms.length;
        const statusClass  = transitioningCount > 0 ? 'bg-info' :
                             runningCount === 0 ? 'bg-secondary' :
                             runningCount === totalCount ? 'bg-success' : 'bg-warning';

        let dependsText = 'None';
        if (group.dependsOnGroupIds && Array.isArray(group.dependsOnGroupIds) && group.dependsOnGroupIds.length > 0) {
            dependsText = group.dependsOnGroupIds.join(', ');
        } else if (typeof group.dependsOnGroupIds === 'string' && group.dependsOnGroupIds) {
            try {
                const parsed = JSON.parse(group.dependsOnGroupIds);
                dependsText = Array.isArray(parsed) && parsed.length > 0 ? parsed.join(', ') : 'None';
            } catch (e) { dependsText = group.dependsOnGroupIds; }
        }

        // VM rows — compact icon-only action buttons
        const vmRows = vms.map(vm => {
            const providerConfig = Config.CLOUD_ICONS[vm.provider] || { icon: 'fas fa-cloud', color: '#6b7280' };
            const statusConfig   = Config.STATUS.vm[vm.status] || Config.STATUS.vm.UNKNOWN;

            return `
                <tr>
                    <td><strong>${Utils.escapeHtml(vm.name)}</strong></td>
                    <td>
                        <i class="${providerConfig.icon}" style="color:${providerConfig.color};"></i>
                        <span class="ms-1" style="font-size:0.8rem;">${vm.region}</span>
                    </td>
                    <td>
                        <span class="status-badge ${statusConfig.class}">
                            <i class="fas ${statusConfig.icon}"></i> ${statusConfig.label}
                        </span>
                    </td>
                    <td>${vm.sequencePosition || '-'}</td>
                    <td>
                        <button class="btn btn-sm btn-outline-primary btn-action"
                                data-vm-id="${vm.vmId}" data-action="vm-details"
                                data-bs-toggle="tooltip" title="Details">
                            <i class="fas fa-info-circle"></i>
                        </button>
                        ${isTransitionalStatus(vm.status) ?
                            `<button class="btn btn-sm btn-outline-secondary btn-action ms-1" disabled
                                     data-bs-toggle="tooltip" title="Operation in progress">
                                <i class="fas fa-spinner fa-spin"></i>
                            </button>` :
                        vm.status === 'RUNNING' ?
                            `<button class="btn btn-sm btn-outline-danger btn-action ms-1"
                                     data-vm-id="${vm.vmId}" data-action="stop-vm"
                                     data-bs-toggle="tooltip" title="Stop VM">
                                <i class="fas fa-stop-circle"></i>
                            </button>` :
                            `<button class="btn btn-sm btn-outline-success btn-action ms-1"
                                     data-vm-id="${vm.vmId}" data-action="start-vm"
                                     data-bs-toggle="tooltip" title="Start VM">
                                <i class="fas fa-play-circle"></i>
                            </button>`
                        }
                    </td>
                </tr>`;
        }).join('');

        // Group action buttons
        const groupBtns = transitioningCount > 0 ?
            `<button class="btn btn-sm btn-secondary" disabled data-bs-toggle="tooltip" title="Operation in progress">
                <i class="fas fa-spinner fa-spin"></i> In Progress
            </button>` :
            runningCount === 0 ?
            `<button class="btn btn-sm btn-success" data-group-id="${group.groupId}" data-action="group-action" data-action-type="start">
                <i class="fas fa-play-circle"></i> Start
            </button>` :
            runningCount === totalCount ?
            `<button class="btn btn-sm btn-danger" data-group-id="${group.groupId}" data-action="group-action" data-action-type="stop">
                <i class="fas fa-stop-circle"></i> Stop
            </button>` :
            `<button class="btn btn-sm btn-success me-1" data-group-id="${group.groupId}" data-action="group-action" data-action-type="start">
                <i class="fas fa-play-circle"></i> Start
            </button>
            <button class="btn btn-sm btn-danger" data-group-id="${group.groupId}" data-action="group-action" data-action-type="stop">
                <i class="fas fa-stop-circle"></i> Stop
            </button>`;

        const collapseId = `group-collapse-${group.groupId}`;

        const vmTableHtml = vms.length > 0 ? `
            <div class="group-vm-table-wrapper">
                <table class="table table-sm table-hover mb-0 group-vm-table">
                    <thead class="table-light sticky-top">
                        <tr>
                            <th>VM Name</th>
                            <th>Location</th>
                            <th>Status</th>
                            <th>Seq</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody>${vmRows}</tbody>
                </table>
            </div>` :
            `<p class="px-3 py-2 mb-0 text-muted small">No VMs in this group</p>`;

        return `
            <div class="group-card mb-2">
                <div class="group-card-header d-flex justify-content-between align-items-center">
                    <div class="group-card-toggle d-flex align-items-center gap-2 flex-grow-1"
                         data-collapse-target="${collapseId}" role="button" style="min-width:0;overflow:hidden;">
                        <i class="fas fa-chevron-${expanded ? 'down' : 'right'} group-chevron flex-shrink-0"
                           style="font-size:0.68rem;color:#94a3b8;transition:transform 0.2s;"></i>
                        <strong class="flex-shrink-0" style="font-size:0.875rem;">${Utils.escapeHtml(group.displayName || group.name)}</strong>
                        <span class="badge ${statusClass} flex-shrink-0" style="font-size:0.68rem;padding:0.2em 0.45em;">${runningCount}/${totalCount}</span>
                        <small class="text-muted text-truncate" style="font-size:0.72rem;min-width:0;">Seq: ${group.sequencePosition} | Depends: ${dependsText}</small>
                    </div>
                    <div class="flex-shrink-0 ms-2" onclick="event.stopPropagation()">
                        ${groupBtns}
                    </div>
                </div>
                <div id="${collapseId}" class="group-card-body${expanded ? '' : ' group-collapsed'}">
                    ${vmTableHtml}
                </div>
            </div>`;
    }

    /**
     * Bind list view events
     */
    function bindListEvents() {
        // View environment
        $('#content-area').off('click', '[data-action="view"]').on('click', '[data-action="view"]', function() {
            disposeAllBootstrapTooltips();
            const envId = $(this).data('env-id');
            const envName = $(this).data('env-name');
            loadDetail({ environmentId: envId, environmentName: envName });
        });

        // Edit environment
        $('#content-area').off('click', '[data-action="edit-env"]').on('click', '[data-action="edit-env"]', function() {
            disposeAllBootstrapTooltips();
            const envId = $(this).data('env-id');
            const env = environmentsList.find(e => e.environmentId === envId);
            if (env) {
                Modals.showEditEnvironment(env, function() {
                    loadList();
                });
            }
        });

        // Delete environment
        $('#content-area').off('click', '[data-action="delete-env"]').on('click', '[data-action="delete-env"]', function() {
            disposeAllBootstrapTooltips();
            const envId = $(this).data('env-id');
            const envName = $(this).data('env-name');
            deleteEnvironment(envId, envName);
        });

        // Pagination
        $('#content-area').off('click', '.env-list-page-btn').on('click', '.env-list-page-btn', function() {
            const page = parseInt($(this).data('page'));
            if (!page || page < 1) return;
            const totalPages = Math.ceil(envFiltered.length / ENV_PAGE_SIZE);
            if (page > totalPages) return;
            envCurrentPage = page;
            disposeEnvListTooltips();
            $('#env-list-body').html(buildEnvRows(envFiltered, envCurrentPage));
            renderEnvPagination(envFiltered.length, envCurrentPage);
        });

        // Search — delegated binding + direct bind on rendered element (same pattern as dashboard)
        $('#content-area').off('input', '#env-list-search').on('input', '#env-list-search', function() {
            filterAndRenderEnvList($(this).val().toLowerCase().trim());
        });
        $('#env-list-search').off('input.envlist').on('input.envlist', function() {
            filterAndRenderEnvList($(this).val().toLowerCase().trim());
        });

        // Initial pagination render
        renderEnvPagination(environmentsList.length, envCurrentPage);
    }

    /**
     * Delete environment
     */
    function deleteEnvironment(envId, envName) {
        Modals.confirm(
            'Delete Environment',
            `Are you sure you want to delete "<strong>${Utils.escapeHtml(envName)}</strong>"?<br><br>
             <span class="text-danger">This action cannot be undone.</span>`,
            function() {
                ApiClient.delete(Config.API.environments.delete(envId))
                    .done(function() {
                        Notifications.success(`Environment "${envName}" deleted`);
                        loadList();
                    })
                    .fail(function(xhr) {
                        const msg = xhr.responseJSON?.message || 'Failed to delete environment';
                        Notifications.error(msg);
                    });
            },
            { confirmText: 'Delete', confirmClass: 'btn-danger' }
        );
    }

    /**
     * Bind detail view events (operations only - admin CRUD is in VM Registry)
     */
    function bindDetailEvents(env) {
        const envId = env.environmentId;
        const envName = env.displayName || env.name;
        console.log('bindDetailEvents called for environment:', envId, envName);

        // Lock events - delegate to Locks module if available
        try {
            if (typeof Locks !== 'undefined' && Locks.bindLockEvents) {
                Locks.bindLockEvents(envId, envName, function() {
                    loadDetail({ environmentId: envId });
                });
            }
        } catch (e) {
            console.error('Error binding lock events:', e);
        }

        // Environment action button (single contextual — Start All or Stop All)
        $('#btn-env-action, #btn-env-action-stop').off('click').on('click', function(e) {
            e.preventDefault();
            const actionType = $(this).data('action-type');
            if (actionType === 'start') startEnvironment(envId, envName);
            else stopEnvironment(envId, envName);
        });

        // Operation history
        $('#btn-operation-history').off('click').on('click', function(e) {
            e.preventDefault();
            showOperationHistory(envId, envName);
        });

        // Group action button (single contextual — Start or Stop)
        $('#btn-env-insights').off('click').on('click', function(e) {
            e.preventDefault();
            showEnvironmentInsights(env);
        });

        $('[data-action="group-action"]').off('click').on('click', function(e) {
            e.preventDefault();
            const groupId = $(this).data('group-id');
            const actionType = $(this).data('action-type');
            if (actionType === 'start') startGroup(envId, groupId);
            else stopGroup(envId, groupId);
        });

        // VM actions
        $('[data-action="start-vm"]').off('click').on('click', function(e) {
            e.preventDefault();
            const vmId = $(this).data('vm-id');
            console.log('Start VM button clicked:', vmId);
            startVm(envId, vmId);
        });

        $('[data-action="stop-vm"]').off('click').on('click', function(e) {
            e.preventDefault();
            const vmId = $(this).data('vm-id');
            console.log('Stop VM button clicked:', vmId);
            stopVm(envId, vmId);
        });

        $('[data-action="vm-details"]').off('click').on('click', function(e) {
            e.preventDefault();
            const vmId = $(this).data('vm-id');
            console.log('VM Details button clicked:', vmId);
            showVmDetails(envId, vmId);
        });

        // Group accordion — only one group open at a time (true accordion)
        $('#content-area').off('click', '.group-card-toggle')
            .on('click', '.group-card-toggle', function() {
                const targetId  = $(this).data('collapse-target');
                const $clicked  = $('#' + targetId);
                const isNowOpen = !$clicked.hasClass('group-collapsed');

                // Collapse ALL groups first
                $('#env-groups-container .group-card-body').addClass('group-collapsed');
                $('#env-groups-container .group-chevron')
                    .removeClass('fa-chevron-down')
                    .addClass('fa-chevron-right');

                // If it was closed → open it; if already open → leave collapsed (toggle off)
                if (!isNowOpen) {
                    $clicked.removeClass('group-collapsed');
                    $(this).find('.group-chevron')
                        .removeClass('fa-chevron-right')
                        .addClass('fa-chevron-down');
                }
            });

        // Init tooltips — ONLY on metric cards and VM action buttons, NOT on group headers
        document.querySelectorAll(
            '#env-detail-view .metric-card[data-bs-toggle="tooltip"], ' +
            '#env-groups-container [data-bs-toggle="tooltip"]'
        ).forEach(el => {
            if (typeof bootstrap !== 'undefined' && bootstrap.Tooltip) {
                const ex = bootstrap.Tooltip.getInstance(el);
                if (ex) ex.dispose();
                new bootstrap.Tooltip(el, { trigger: 'hover' });
            }
        });

        bindOperationStatusRefresh(envId);
    }

    function bindOperationStatusRefresh(envId) {
        if (operationStatusHandler) {
            window.removeEventListener('vm-operation-status', operationStatusHandler);
        }

        operationStatusHandler = function(event) {
            const detail = event.detail || {};
            if (detail.envId !== envId) {
                return;
            }

            if (operationRefreshTimer) {
                return;
            }

            operationRefreshTimer = setTimeout(async function() {
                operationRefreshTimer = null;
                try {
                    const latestEnv = await fetchEnvironmentDetails(envId);
                    currentEnvironment = latestEnv;
                    $('#content-area').html(buildDetailHtml(latestEnv));
                    bindDetailEvents(latestEnv);
                } catch (error) {
                    console.warn('Could not refresh VM operation status:', error);
                }
            }, 750);
        };

        window.addEventListener('vm-operation-status', operationStatusHandler);
    }

    // Operation functions - delegate to VmOperations module for progress tracking
    function startEnvironment(envId, envName) {
        showOperationConfirm({
            envId, opType: 'START',
            scope: { level: 'environment', label: `all VMs in <strong>${Utils.escapeHtml(envName)}</strong>` },
            onConfirm: () => VmOperations.startEnvironment(envId, envName)
                                .then(() => loadDetail({ environmentId: envId }))
        });
    }

    function stopEnvironment(envId, envName) {
        showOperationConfirm({
            envId, opType: 'STOP',
            scope: { level: 'environment', label: `all VMs in <strong>${Utils.escapeHtml(envName)}</strong>` },
            note: 'Running VMs will be gracefully stopped.',
            onConfirm: () => VmOperations.stopEnvironment(envId, envName)
                                .then(() => loadDetail({ environmentId: envId }))
        });
    }

    function startGroup(envId, groupId) {
        const group = findGroup(groupId);
        const groupName = group ? (group.group?.displayName || group.group?.name || group.displayName || group.name) : groupId;
        showOperationConfirm({
            envId, opType: 'START',
            scope: { level: 'group', label: `group <strong>${Utils.escapeHtml(groupName)}</strong>`, groupId },
            onConfirm: () => VmOperations.startGroup(envId, groupId, groupName)
                                .then(() => loadDetail({ environmentId: envId }))
        });
    }

    function stopGroup(envId, groupId) {
        const group = findGroup(groupId);
        const groupName = group ? (group.group?.displayName || group.group?.name || group.displayName || group.name) : groupId;
        showOperationConfirm({
            envId, opType: 'STOP',
            scope: { level: 'group', label: `group <strong>${Utils.escapeHtml(groupName)}</strong>`, groupId },
            note: 'Running VMs will be gracefully stopped.',
            onConfirm: () => VmOperations.stopGroup(envId, groupId, groupName)
                                .then(() => loadDetail({ environmentId: envId }))
        });
    }

    function startVm(envId, vmId) {
        const vm = findVm(vmId);
        const vmName = vm ? (vm.displayName || vm.name) : vmId;
        showOperationConfirm({
            envId, opType: 'START',
            scope: { level: 'vm', label: `VM <strong>${Utils.escapeHtml(vmName)}</strong>`, vmId },
            onConfirm: () => VmOperations.startVm(envId, vmId, vmName)
                                .then(() => loadDetail({ environmentId: envId }))
        });
    }

    function stopVm(envId, vmId) {
        const vm = findVm(vmId);
        const vmName = vm ? (vm.displayName || vm.name) : vmId;
        showOperationConfirm({
            envId, opType: 'STOP',
            scope: { level: 'vm', label: `VM <strong>${Utils.escapeHtml(vmName)}</strong>`, vmId },
            onConfirm: () => VmOperations.stopVm(envId, vmId, vmName)
                                .then(() => loadDetail({ environmentId: envId }))
        });
    }

    /**
     * Unified confirm modal with async estimate for all 3 scope levels.
     * @param {object} opts
     *   envId     - environment ID
     *   opType    - 'START' | 'STOP'
     *   scope     - { level: 'environment'|'group'|'vm', label, groupId?, vmId? }
     *   note      - optional extra text (e.g., "VMs will be gracefully stopped")
     *   onConfirm - function to call when user clicks confirm
     */
    function showOperationConfirm(opts) {
        const { envId, opType, scope, note, onConfirm } = opts;

        // Check lock status first - block if locked by another user
        Locks.getStatus(envId).then(function(lockStatus) {
            if (!Locks.canOperate(lockStatus)) {
                const lockedBy = lockStatus.lockedByDisplayName || lockStatus.lockedByUserId || 'another user';
                Notifications.warning(
                    `This environment is locked by <strong>${Utils.escapeHtml(lockedBy)}</strong>. ` +
                    `You cannot perform operations until the lock is released.`
                );
                return;
            }

            // Lock check passed - show confirmation dialog
            showOperationConfirmDialog(opts);
        }).catch(function() {
            // If we can't check lock status, proceed anyway (backend will enforce)
            showOperationConfirmDialog(opts);
        });
    }

    /**
     * Internal: Show the actual operation confirmation dialog after lock check passes
     */
    function showOperationConfirmDialog(opts) {
        const { envId, opType, scope, note, onConfirm } = opts;
        const isStart     = opType === 'START';
        const verb        = isStart ? 'Start' : 'Stop';
        const actionLabel = isStart ? `Start ${scope.level === 'vm' ? 'VM' : 'All'}` : `Stop ${scope.level === 'vm' ? 'VM' : 'All'}`;
        const actionClass = isStart ? 'btn-success' : 'btn-danger';

        // Use div instead of p to avoid double-<p> wrap from Modals.confirm
        const bodyFor = (estimateHtml) => `
            <div class="mb-2">${verb} ${scope.label}?
            ${note ? `<br><small class="text-muted">${note}</small>` : ''}
            </div>
            ${estimateHtml}
        `;

        // Show modal immediately — estimate loads async
        Modals.confirm(
            `${verb} ${scope.level === 'environment' ? 'Environment' : scope.level === 'group' ? 'Group' : 'VM'}`,
            bodyFor(`<div id="estimate-placeholder" class="text-muted small mt-2">
                <i class="fas fa-spinner fa-spin me-1"></i> Loading time estimate…
            </div>`),
            function() {
                onConfirm().catch(err => console.error(`${actionLabel} failed:`, err));
            },
            { confirmText: actionLabel, confirmClass: actionClass }
        );

        // Build scope param for API
        const scopeParam = scope.level === 'group' ? { groupId: scope.groupId }
                         : scope.level === 'vm'    ? { vmId: scope.vmId }
                         : null;

        ApiClient.get(Config.API.operations.estimates(envId, opType, scopeParam))
            .done(function(estimate) {
                console.log(`[Estimate][${scope.level}] received:`, estimate);
                const html = buildEstimateHtml(estimate, opType, scope.level);
                const $ph = $('#estimate-placeholder');
                if ($ph.length) $ph.replaceWith(html);
            })
            .fail(function(xhr) {
                console.warn(`[Estimate][${scope.level}] fetch failed:`, xhr.status, xhr.responseText);
                const $ph = $('#estimate-placeholder');
                if ($ph.length) $ph.remove();
            });
    }

    /**
     * Build the estimate info block for any scope level.
     * - ENVIRONMENT: shows avg/min/max + per-VM breakdown table
     * - GROUP: shows avg/min/max total for the group (no VM breakdown)
     * - VM: shows avg/min/max for that single VM
     */
    function buildEstimateHtml(estimate, opType, level) {
        const opLabel = opType.toLowerCase();

        if (!estimate || estimate.sampleCount === 0) {
            return `<div class="alert alert-secondary py-2 small mt-2 mb-0">
                <i class="fas fa-info-circle me-1"></i>
                No historical data yet — this will be the first recorded ${opLabel} operation${level !== 'environment' ? ' at this level' : ''}.
            </div>`;
        }

        const n   = estimate.sampleCount;
        const avg = estimate.avgEnvironmentSeconds;
        const min = estimate.minEnvironmentSeconds;
        const max = estimate.maxEnvironmentSeconds;

        const fmt = (s) => {
            if (s == null || s === 0) return '—';
            s = Math.round(s);
            if (s < 60) return `${s}s`;
            const m = Math.floor(s / 60), sec = s % 60;
            return sec > 0 ? `${m}m ${sec}s` : `${m}m`;
        };

        // Per-VM breakdown only for environment scope
        let vmBreakdown = '';
        if (level === 'environment' && estimate.vmEstimates && estimate.vmEstimates.length > 0) {
            const MAX_VISIBLE = 6;
            const rows = estimate.vmEstimates.map((vm, i) => `
                <tr class="${i >= MAX_VISIBLE ? 'vm-est-extra d-none' : ''}">
                    <td class="text-muted pe-2" style="width:24px">${vm.sequencePosition}</td>
                    <td>${Utils.escapeHtml(vm.vmName)}</td>
                    <td class="text-end text-nowrap ps-3">~${fmt(vm.avgSeconds)}
                        <small class="text-muted">(${vm.sampleCount}×)</small>
                    </td>
                </tr>`).join('');

            const extra = estimate.vmEstimates.length - MAX_VISIBLE;
            const toggleRow = extra > 0 ? `
                <tr><td colspan="3" class="pt-1">
                    <a href="#" class="small text-muted" onclick="
                        $(this).closest('table').find('.vm-est-extra').toggleClass('d-none');
                        $(this).text($(this).text().startsWith('Show') ? 'Hide' : 'Show ${extra} more…');
                        return false;">Show ${extra} more…</a>
                </td></tr>` : '';

            vmBreakdown = `
                <div class="mt-2 border-top pt-2">
                    <table class="table table-sm table-borderless mb-0" style="font-size:0.8rem">
                        <tbody>${rows}${toggleRow}</tbody>
                    </table>
                </div>`;
        }

        const levelLabel = level === 'environment' ? 'environment' : level === 'group' ? 'group' : 'VM';

        return `
            <div class="alert alert-info py-2 mt-2 mb-0" style="font-size:0.875rem">
                <div class="d-flex align-items-center gap-2 mb-1">
                    <i class="fas fa-clock text-info"></i>
                    <strong>Estimated ${opLabel} time</strong>
                    <span class="text-muted small">(based on ${n} previous ${n === 1 ? 'run' : 'runs'})</span>
                </div>
                <div class="d-flex gap-3 flex-wrap">
                    <span>Avg: <strong class="text-dark">${fmt(avg)}</strong></span>
                    <span class="text-muted">Fast: ${fmt(min)}</span>
                    <span class="text-muted">Slow: ${fmt(max)}</span>
                </div>
                ${vmBreakdown}
            </div>`;
    }


    // Helper to find group in current environment
    function findGroup(groupId) {
        if (!currentEnvironment || !currentEnvironment.groups) return null;
        return currentEnvironment.groups.find(g =>
            (g.group?.groupId || g.groupId) === groupId
        );
    }

    // Helper to find VM in current environment
    function findVm(vmId) {
        if (!currentEnvironment || !currentEnvironment.groups) return null;
        for (const group of currentEnvironment.groups) {
            const vms = group.vms || [];
            const vm = vms.find(v => v.vmId === vmId);
            if (vm) return vm;
        }
        return null;
    }

    function showEnvironmentInsights(env) {
        const envId = env.environmentId;
        ApiClient.get(Config.API.environments.insights(envId))
            .done(function(insights) {
                const title = buildEnvironmentInsightsTitle(env, insights);
                const content = buildEnvironmentInsightsPanel(insights);
                try {
                    if (typeof Slideout !== 'undefined' && Slideout.open) {
                        Slideout.open(title, content);
                    } else {
                        Notifications.info('Environment Insights: ' + (env.displayName || env.name));
                    }
                } catch (e) {
                    console.error('Error opening environment insights:', e);
                    Notifications.info('Could not display environment insights');
                }
            })
            .fail(function() {
                Notifications.error('Failed to load environment insights');
            });
    }

    function buildEnvironmentInsightsTitle(env, insights) {
        const idle = Number(insights.idleVms || 0);
        return `
            <div class="vm-slideout-title">
                <span class="vm-slideout-name">Environment: ${escapeHtml(env.displayName || env.name)}</span>
                <span class="vm-slideout-badges">
                    <span class="vm-title-pill vm-title-status running">
                        <i class="fas fa-layer-group"></i> ${insights.totalVms || 0} VMs
                    </span>
                    <span class="vm-title-pill vm-title-idle ${idle > 0 ? 'idle' : 'active'}">
                        <i class="fas ${idle > 0 ? 'fa-moon' : 'fa-bolt'}"></i> ${idle} Idle
                    </span>
                </span>
            </div>
        `;
    }

    function buildEnvironmentInsightsPanel(insights) {
        return `
            <div class="vm-insights-panel env-insights-panel">
                <div class="vm-insights-summary">
                    ${buildInsightStat('Total VMs', insights.totalVms || 0, 'fa-server')}
                    ${buildInsightStat('Running', insights.runningVms || 0, 'fa-play-circle')}
                    ${buildInsightStat('Avg CPU', formatPercent(insights.avgCpuUtilization), 'fa-gauge-high')}
                    ${buildInsightStat('Allocated EBS', insights.totalAllocatedStorageGib ? `${insights.totalAllocatedStorageGib} GiB` : '-', 'fa-hard-drive')}
                </div>

                <ul class="nav nav-tabs vm-insights-tabs" role="tablist">
                    <li class="nav-item" role="presentation">
                        <button class="nav-link active" data-bs-toggle="tab" data-bs-target="#env-overview-tab" type="button" role="tab">Overview</button>
                    </li>
                    <li class="nav-item" role="presentation">
                        <button class="nav-link" data-bs-toggle="tab" data-bs-target="#env-utilization-tab" type="button" role="tab">Utilization</button>
                    </li>
                    <li class="nav-item" role="presentation">
                        <button class="nav-link" data-bs-toggle="tab" data-bs-target="#env-storage-tab" type="button" role="tab">Storage</button>
                    </li>
                    <li class="nav-item" role="presentation">
                        <button class="nav-link" data-bs-toggle="tab" data-bs-target="#env-groups-tab" type="button" role="tab">Groups</button>
                    </li>
                    <li class="nav-item" role="presentation">
                        <button class="nav-link" data-bs-toggle="tab" data-bs-target="#env-recommendations-tab" type="button" role="tab">Checks</button>
                    </li>
                </ul>

                <div class="tab-content vm-insights-tab-content">
                    <div class="tab-pane fade show active" id="env-overview-tab" role="tabpanel">
                        ${buildEnvironmentOverviewSection(insights)}
                    </div>
                    <div class="tab-pane fade" id="env-utilization-tab" role="tabpanel">
                        ${buildEnvironmentUtilizationSection(insights)}
                    </div>
                    <div class="tab-pane fade" id="env-storage-tab" role="tabpanel">
                        ${buildEnvironmentStorageSection(insights)}
                    </div>
                    <div class="tab-pane fade" id="env-groups-tab" role="tabpanel">
                        ${buildEnvironmentGroupsSection(insights)}
                    </div>
                    <div class="tab-pane fade" id="env-recommendations-tab" role="tabpanel">
                        ${buildEnvironmentRecommendationsSection(insights)}
                    </div>
                </div>
            </div>
        `;
    }

    function buildEnvironmentOverviewSection(insights) {
        return `
            <div class="vm-insight-section">
                <div class="vm-overview-clean">
                    ${buildField('Service type', insights.serviceType || '-')}
                    ${buildField('Groups', insights.groupCount || 0)}
                    ${buildField('Running / Stopped', `${insights.runningVms || 0} / ${insights.stoppedVms || 0}`)}
                    ${buildField('Unknown / Drifted', `${insights.unknownVms || 0} / ${insights.driftedVms || 0}`)}
                    ${buildField('Last metric', formatVmInsightTime(insights.latestMetricSampleTime))}
                    ${buildField('Last inventory', formatVmInsightTime(insights.latestInventoryRefreshTime))}
                </div>
                <div class="env-chip-grid">
                    ${buildMapChips('Status', insights.statusCounts)}
                    ${buildMapChips('Region', insights.regionCounts)}
                </div>
            </div>
        `;
    }

    function buildEnvironmentUtilizationSection(insights) {
        const busiestRows = buildVmInsightRows(insights.busiestVms || [], 'cpu');
        const idleRows = buildVmInsightRows(insights.idleVmRows || [], 'idle');
        return `
            <div class="vm-insight-section">
                <div class="vm-util-grid">
                    ${buildMetricTile('Average CPU', formatPercent(insights.avgCpuUtilization), 'Top VMs, last 1h', insights.cpuSeries || [], 'cpu')}
                    ${buildMetricTile('Network In', formatBytes(insights.totalNetworkInBytes), 'Top VMs, last 1h', insights.networkInSeries || [], 'network')}
                    ${buildMetricTile('Network Out', formatBytes(insights.totalNetworkOutBytes), 'Top VMs, last 1h', insights.networkOutSeries || [], 'network')}
                    ${buildMetricTile('Disk Read', formatBytes(insights.totalDiskReadBytes), 'Top VMs, last 1h', insights.diskReadSeries || [], 'disk')}
                    ${buildMetricTile('Disk Write', formatBytes(insights.totalDiskWriteBytes), 'Top VMs, last 1h', insights.diskWriteSeries || [], 'disk')}
                    <div class="vm-idle-panel ${(insights.idleVms || 0) > 0 ? 'idle' : 'active'}">
                        <div class="vm-idle-label">Idle Detection</div>
                        <strong>${insights.idleVms || 0} idle / ${insights.activeVms || 0} active</strong>
                        <p>${insights.missingMetricVms || 0} VMs missing metric samples</p>
                        <small>Based on latest collected idle summaries</small>
                    </div>
                </div>
                <div class="env-two-column mt-2">
                    <div>
                        <div class="env-section-label">Busiest VMs</div>
                        ${busiestRows || buildEmptyState('No CPU samples collected yet.')}
                    </div>
                    <div>
                        <div class="env-section-label">Idle VMs</div>
                        ${idleRows || buildEmptyState('No idle VMs detected.')}
                    </div>
                </div>
            </div>
        `;
    }

    function buildEnvironmentStorageSection(insights) {
        return `
            <div class="vm-insight-section">
                <div class="vm-storage-summary">
                    ${buildStorageMeter('Allocated', insights.totalAllocatedStorageGib ? `${insights.totalAllocatedStorageGib} GiB` : '-', 100)}
                    ${buildStorageMeter('Volumes', insights.volumeCount || 0, 100)}
                    ${buildStorageMeter('Used', 'Not collected', 0)}
                </div>
                <div class="env-chip-grid mt-2">
                    ${buildMapChips('Volume type', insights.volumeTypeCounts)}
                </div>
                <div class="vm-storage-meter">
                    <div style="width: 0%"></div>
                </div>
            </div>
        `;
    }

    function buildEnvironmentGroupsSection(insights) {
        const rows = (insights.groups || []).slice(0, 8).map(group => `
            <div class="env-insight-row">
                <div>
                    <strong>${escapeHtml(group.name || '-')}</strong>
                    <small>Seq ${group.sequencePosition || '-'}</small>
                </div>
                <span>${group.runningVms || 0}/${group.totalVms || 0} running</span>
                <span>${formatPercent(group.avgCpuUtilization)}</span>
                <span>${group.idleVms || 0} idle</span>
            </div>
        `).join('');

        return `
            <div class="vm-insight-section">
                ${rows || buildEmptyState('No groups configured for this environment.')}
            </div>
        `;
    }

    function buildEnvironmentRecommendationsSection(insights) {
        const rows = (insights.recommendations || []).map(item => `
            <div class="vm-timeline-item env-check-${escapeHtml(item.tone || 'muted')}">
                <div class="vm-timeline-dot ${escapeHtml(item.tone || 'muted')}"></div>
                <div>
                    <strong>${escapeHtml(item.title || '-')}</strong>
                    <small>${escapeHtml(item.description || '')}</small>
                </div>
                <span class="env-check-count">${item.count || 0}</span>
            </div>
        `).join('');

        return `
            <div class="vm-insight-section">
                <div class="vm-timeline">
                    ${rows || buildEmptyState('No checks to show yet.')}
                </div>
            </div>
        `;
    }

    function buildMapChips(label, values) {
        const entries = Object.entries(values || {});
        if (entries.length === 0) return buildEmptyState(`No ${label.toLowerCase()} data collected yet.`);
        return `
            <div class="env-chip-panel">
                <span>${escapeHtml(label)}</span>
                <div>
                    ${entries.slice(0, 8).map(([key, value]) => `
                        <span class="env-chip">${escapeHtml(key)} <strong>${value}</strong></span>
                    `).join('')}
                </div>
            </div>
        `;
    }

    function buildVmInsightRows(rows, mode) {
        return rows.map(row => `
            <div class="env-insight-row">
                <div>
                    <strong>${escapeHtml(row.name || '-')}</strong>
                    <small>${escapeHtml(row.groupName || '-')}</small>
                </div>
                <span>${escapeHtml(row.status || '-')}</span>
                <span>${mode === 'idle' ? formatMinutes(row.idleDurationMinutes || 0) : formatPercent(row.cpuUtilization)}</span>
            </div>
        `).join('');
    }

    function showVmDetails(envId, vmId, window = '1h', activeTab = 'overview') {
        ApiClient.get(Config.API.vms.get(envId, vmId))
            .done(function(vm) {
                const providerConfig = Config.CLOUD_ICONS[vm.provider] || { icon: 'fas fa-cloud', label: vm.provider };
                const statusConfig = Config.STATUS.vm[vm.status] || Config.STATUS.vm.UNKNOWN;
                const inventoryRequest = resolveApiOrNull(ApiClient.get(Config.API.vms.inventory(envId, vmId), { suppressGlobalError: true }));
                const metricsRequest = resolveApiOrNull(ApiClient.get(Config.API.vms.metrics(envId, vmId, window, 300), { suppressGlobalError: true }));
                const summaryRequest = resolveApiOrNull(ApiClient.get(Config.API.vms.utilizationSummary(envId, vmId), { suppressGlobalError: true }));

                $.when(inventoryRequest, metricsRequest, summaryRequest)
                    .done(function(inventory, metrics, summary) {
                        const insights = buildLiveVmInsightsData(vm, inventory, metrics, summary);
                        const content = buildVmInsightsPanel(vm, providerConfig, statusConfig, insights, envId, window, activeTab);
                        const title = buildVmInsightsTitle(vm, statusConfig, insights);

                        try {
                            if (typeof Slideout !== 'undefined' && Slideout.open) {
                                Slideout.open(title, content);
                                bindMetricWindowInteractions();
                            } else {
                                Notifications.info('VM Details: ' + vm.name);
                            }
                        } catch (e) {
                            console.error('Error opening slideout:', e);
                            Notifications.info('Could not display VM details');
                        }
                    });
            })
            .fail(function() {
                Notifications.error('Failed to load VM details');
            });
    }

    function resolveApiOrNull(request) {
        const deferred = $.Deferred();
        request.done(function(data) {
            deferred.resolve(data);
        }).fail(function() {
            deferred.resolve(null);
        });
        return deferred.promise();
    }

    function bindMetricWindowInteractions() {
        $(document).off('click.vmMetricWindow', '.vm-window-control button')
            .on('click.vmMetricWindow', '.vm-window-control button', function() {
                const $button = $(this);
                showVmDetails($button.data('env-id'), $button.data('vm-id'), $button.data('window') || '1h', 'utilization');
            });
    }

    function buildVmInsightsTitle(vm, statusConfig, mock) {
        return `
            <div class="vm-slideout-title">
                <span class="vm-slideout-name">VM: ${escapeHtml(vm.displayName || vm.name)}</span>
                <span class="vm-slideout-badges">
                    <span class="vm-title-pill vm-title-status ${statusConfig.class}">
                        <i class="fas ${statusConfig.icon}"></i> ${statusConfig.label}
                    </span>
                    <span class="vm-title-pill vm-title-idle ${mock.idle.isIdle ? 'idle' : 'active'}">
                        <i class="fas ${mock.idle.isIdle ? 'fa-moon' : 'fa-bolt'}"></i>
                        ${mock.idle.label}
                    </span>
                </span>
            </div>
        `;
    }

    function buildVmInsightsPanel(vm, providerConfig, statusConfig, mock, envId, selectedWindow = '1h', activeTab = 'overview') {
        const providerLabel = providerConfig.label || vm.provider || 'Cloud';
        const lastSync = formatVmInsightTime(vm.lastStateSyncAt);
        const tab = ['overview', 'utilization', 'storage', 'history'].includes(activeTab) ? activeTab : 'overview';

        return `
            <div class="vm-insights-panel">
                <div class="vm-insights-summary">
                    ${buildInsightStat('Instance', mock.instanceType || '-', 'fa-microchip')}
                    ${buildInsightStat('CPU', formatPercent(mock.cpu.latest), 'fa-gauge-high')}
                    ${buildInsightStat('Storage', mock.storage.totalGiB ? `${mock.storage.totalGiB} GiB` : '-', 'fa-hard-drive')}
                    ${buildInsightStat('Last metric', mock.lastMetricSample, 'fa-clock')}
                </div>

                <ul class="nav nav-tabs vm-insights-tabs" role="tablist">
                    <li class="nav-item" role="presentation">
                        <button class="nav-link ${tab === 'overview' ? 'active' : ''}" data-bs-toggle="tab" data-bs-target="#vm-overview-tab" type="button" role="tab">Overview</button>
                    </li>
                    <li class="nav-item" role="presentation">
                        <button class="nav-link ${tab === 'utilization' ? 'active' : ''}" data-bs-toggle="tab" data-bs-target="#vm-utilization-tab" type="button" role="tab">Utilization</button>
                    </li>
                    <li class="nav-item" role="presentation">
                        <button class="nav-link ${tab === 'storage' ? 'active' : ''}" data-bs-toggle="tab" data-bs-target="#vm-storage-tab" type="button" role="tab">Storage</button>
                    </li>
                    <li class="nav-item" role="presentation">
                        <button class="nav-link ${tab === 'history' ? 'active' : ''}" data-bs-toggle="tab" data-bs-target="#vm-history-tab" type="button" role="tab">History</button>
                    </li>
                </ul>

                <div class="tab-content vm-insights-tab-content">
                    <div class="tab-pane fade ${tab === 'overview' ? 'show active' : ''}" id="vm-overview-tab" role="tabpanel">
                        ${buildOverviewSection(vm, providerLabel, providerConfig, mock, lastSync)}
                    </div>
                    <div class="tab-pane fade ${tab === 'utilization' ? 'show active' : ''}" id="vm-utilization-tab" role="tabpanel">
                        ${buildUtilizationSection(mock, envId, vm.vmId, selectedWindow)}
                    </div>
                    <div class="tab-pane fade ${tab === 'storage' ? 'show active' : ''}" id="vm-storage-tab" role="tabpanel">
                        ${buildStorageSection(mock)}
                    </div>
                    <div class="tab-pane fade ${tab === 'history' ? 'show active' : ''}" id="vm-history-tab" role="tabpanel">
                        ${buildHistorySection(mock)}
                    </div>
                </div>
            </div>
        `;
    }

    function buildInsightStat(label, value, icon) {
        return `
            <div class="vm-insight-stat">
                <i class="fas ${icon}" aria-hidden="true"></i>
                <div>
                    <span>${escapeHtml(value)}</span>
                    <small>${escapeHtml(label)}</small>
                </div>
            </div>
        `;
    }

    function buildOverviewSection(vm, providerLabel, providerConfig, mock, lastSync) {
        return `
            <div class="vm-insight-section">
                <div class="vm-overview-clean">
                    ${buildField('Private IP', mock.privateIp)}
                    ${buildField('Public IP', mock.publicIp)}
                    ${buildField('Instance type', mock.instanceType)}
                    ${buildField('vCPU / Memory', `${mock.vcpu} vCPU / ${mock.memoryGiB} GiB`)}
                    ${buildField('Region / AZ', `${vm.region || mock.region} / ${mock.availabilityZone}`)}
                    ${buildField('Last state sync', lastSync)}
                </div>
                <div class="vm-provider-line">
                    <span><i class="${providerConfig.icon}"></i> ${escapeHtml(providerLabel)}</span>
                    <code>${escapeHtml(vm.providerVmId || 'pending-provider-id')}</code>
                </div>
            </div>
        `;
    }

    function buildUtilizationSection(mock, envId, vmId, selectedWindow = '1h') {
        const windows = ['1h', '6h', '12h', '24h', '7d'];
        return `
            <div class="vm-insight-section">
                <div class="vm-window-control" aria-label="Metric window">
                    ${windows.map(window => `
                        <button class="${window === selectedWindow ? 'active' : ''}" type="button"
                                data-env-id="${escapeHtml(envId)}" data-vm-id="${escapeHtml(vmId)}" data-window="${window}">
                            ${window}
                        </button>
                    `).join('')}
                </div>

                <div class="vm-util-grid">
                    ${buildMetricTile('CPU Utilization', formatPercent(mock.cpu.latest), 'Avg 1h', mock.cpu.series, 'cpu')}
                    ${buildMetricTile('Network In', formatBytes(mock.network.inBytes), 'Last period', mock.network.inSeries, 'network')}
                    ${buildMetricTile('Network Out', formatBytes(mock.network.outBytes), 'Last period', mock.network.outSeries, 'network')}
                    ${buildMetricTile('Disk Read', formatBytes(mock.disk.readBytes), 'Last period', mock.disk.readSeries, 'disk')}
                    ${buildMetricTile('Disk Write', formatBytes(mock.disk.writeBytes), 'Last period', mock.disk.writeSeries, 'disk')}
                    <div class="vm-idle-panel ${mock.idle.isIdle ? 'idle' : 'active'}"
                         title="${escapeHtml(mock.idle.reason)} Thresholds: CPU < 5%, low network, low disk IO for 30 minutes.">
                        <div class="vm-idle-label">Idle Detection</div>
                        <strong>${mock.idle.label}</strong>
                        <p>${mock.idle.reason}</p>
                        <small>CPU &lt; 5%, low network and disk IO</small>
                    </div>
                </div>
            </div>
        `;
    }

    function buildStorageSection(mock) {
        const volumes = mock.storage.volumes.length > 0 ? mock.storage.volumes.map(volume => `
            <div class="vm-volume-pill">
                <div>
                    <strong>${escapeHtml(volume.device)}</strong>
                    <code>${escapeHtml(volume.volumeId)}</code>
                </div>
                <span>${escapeHtml(volume.type)} / ${volume.sizeGiB} GiB</span>
                <small>${volume.iops} IOPS / ${volume.throughput} MB/s</small>
            </div>
        `).join('') : buildEmptyState('No EBS volume snapshot collected yet.');

        return `
            <div class="vm-insight-section">
                <div class="vm-storage-summary">
                    ${buildStorageMeter('Allocated', mock.storage.totalGiB ? `${mock.storage.totalGiB} GiB` : '-', 100)}
                    ${buildStorageMeter('Used', 'Not collected', 0)}
                    ${buildStorageMeter('Free', 'Not collected', 0)}
                </div>
                <div class="vm-storage-meter">
                    <div style="width: 0%"></div>
                </div>
                <div class="vm-volume-list">
                    ${volumes}
                </div>
            </div>
        `;
    }

    function buildHistorySection(mock) {
        if (!mock.history || mock.history.length === 0) {
            return `
                <div class="vm-insight-section">
                    ${buildEmptyState('No inventory or metric events collected yet.')}
                </div>
            `;
        }

        return `
            <div class="vm-insight-section">
                <div class="vm-timeline">
                    ${mock.history.slice(0, 3).map(item => `
                        <div class="vm-timeline-item">
                            <div class="vm-timeline-dot ${item.tone}"></div>
                            <div>
                                <strong>${escapeHtml(item.title)}</strong>
                                <small>${escapeHtml(item.time)}</small>
                            </div>
                        </div>
                    `).join('')}
                </div>
            </div>
        `;
    }

    function buildField(label, value, isHtml = false) {
        const displayValue = value === null || value === undefined || value === '' ? '-' : value;
        return `
            <div class="vm-field">
                <span>${escapeHtml(label)}</span>
                <strong>${isHtml ? displayValue : escapeHtml(displayValue)}</strong>
            </div>
        `;
    }

    function buildMetricTile(label, value, caption, series, tone = 'default') {
        const lines = getColoredMetricLines(series, label, tone);
        const multiSeries = lines.length > 1;
        const chart = lines.length > 0
            ? buildEchartLineChart(lines, tone, label)
            : buildMetricPlaceholder();
        return `
            <div class="vm-metric-tile">
                <div class="vm-metric-head">
                    <span>${escapeHtml(label)}</span>
                    <div class="vm-metric-value-wrap">
                        ${multiSeries ? buildDotLegend(lines) : ''}
                        <strong>${escapeHtml(value)}</strong>
                    </div>
                </div>
                ${chart}
                <small>${escapeHtml(caption)}</small>
            </div>
        `;
    }

    function buildEchartLineChart(lines, tone, label) {
        const chartId = `vm-echart-${++metricChartCounter}`;
        const configId = `${chartId}-config`;
        const config = {
            label,
            tone,
            unit: tone === 'cpu' ? 'percent' : 'mb',
            series: lines
        };
        return `
            <div id="${chartId}" class="vm-echart vm-echart-${tone}"
                 data-chart-config-id="${configId}" role="img"
                 aria-label="${escapeHtml(label)} utilization chart"></div>
            <script type="application/json" id="${configId}">${safeJson(config)}</script>
        `;
    }

    function buildDotLegend(lines) {
        return `
            <div class="vm-chart-dot-legend" aria-label="VM chart legend">
                ${lines.map((line) => `
                    <span class="vm-chart-dot"
                          style="background:${line.color}"
                          title="${escapeHtml(line.name || line.vmId || 'VM')}"></span>
                `).join('')}
            </div>
        `;
    }

    function getColoredMetricLines(series, label, tone) {
        const palette = tone === 'cpu'
            ? ['#2563eb', '#059669', '#d97706', '#dc2626', '#7c3aed']
            : ['#0f766e', '#2563eb', '#d97706', '#7c3aed', '#dc2626'];

        if (Array.isArray(series) && series.length > 1 && series.every(value => typeof value !== 'object')) {
            return [{
                name: label,
                values: series,
                color: palette[0]
            }];
        }

        return (series || [])
            .filter(item => item && Array.isArray(item.values) && item.values.length > 1)
            .slice(0, palette.length)
            .map((line, index) => ({
                name: line.name || line.vmId || `VM ${index + 1}`,
                vmId: line.vmId,
                values: line.values,
                color: palette[index]
            }));
    }

    function safeJson(value) {
        return JSON.stringify(value).replace(/</g, '\\u003c');
    }

    function buildMetricPlaceholder() {
        return `
            <div class="vm-metric-placeholder">
                <span>No samples</span>
            </div>
        `;
    }

    function buildEmptyState(message) {
        return `
            <div class="vm-empty-state">
                <i class="fas fa-circle-info" aria-hidden="true"></i>
                <span>${escapeHtml(message)}</span>
            </div>
        `;
    }

    function buildStorageMeter(label, value, percent) {
        return `
            <div class="vm-storage-stat">
                <span>${escapeHtml(label)}</span>
                <strong>${escapeHtml(value)}</strong>
                <small>${Math.max(0, Math.min(100, Math.round(percent)))}%</small>
            </div>
        `;
    }

    function buildLiveVmInsightsData(vm, inventory, metrics, summary) {
        const volumes = inventory && Array.isArray(inventory.volumes) ? inventory.volumes : [];
        const totalStorage = inventory?.totalStorageGib || sumVolumeSize(volumes) || summary?.totalStorageGib;
        const samples = metrics && Array.isArray(metrics.series) ? metrics.series : [];
        const latest = metrics?.latest || samples[samples.length - 1] || null;
        const idle = metrics?.idle || null;
        const isIdle = idle?.idle === true || summary?.idle === true;
        const idleDuration = idle?.idleDurationMinutes ?? summary?.idleDurationMinutes ?? 0;

        const insights = {
            region: vm.region || '-',
            availabilityZone: inventory?.availabilityZone || '-',
            privateIp: inventory?.privateIp || vm.privateIp || '-',
            publicIp: inventory?.publicIp || vm.publicIp || '-',
            instanceType: inventory?.instanceType || summary?.instanceType || normalizeVmType(vm.vmType),
            vcpu: inventory?.vcpuCount || '-',
            memoryGiB: inventory?.memoryMib ? roundOneDecimal(inventory.memoryMib / 1024) : '-',
            architecture: inventory?.architecture || '-',
            launchTime: inventory?.launchTime ? formatVmInsightTime(inventory.launchTime) : '-',
            lastInventoryRefresh: inventory?.lastRefreshedAt ? formatVmInsightTime(inventory.lastRefreshedAt) : null,
            lastMetricSample: latest?.sampleTime ? formatVmInsightTime(latest.sampleTime) : 'No samples',
            cpu: {
                latest: latest?.cpuUtilization ?? summary?.latestCpuUtilization ?? null,
                series: sampleSeries(samples, 'cpuUtilization', 'percent')
            },
            network: {
                inBytes: latest?.networkInBytes ?? null,
                outBytes: latest?.networkOutBytes ?? null,
                inSeries: sampleSeries(samples, 'networkInBytes', 'bytes'),
                outSeries: sampleSeries(samples, 'networkOutBytes', 'bytes')
            },
            disk: {
                readBytes: latest?.diskReadBytes ?? null,
                writeBytes: latest?.diskWriteBytes ?? null,
                readSeries: sampleSeries(samples, 'diskReadBytes', 'bytes'),
                writeSeries: sampleSeries(samples, 'diskWriteBytes', 'bytes')
            },
            idle: {
                isIdle,
                label: idle || summary
                    ? (isIdle ? `Idle ${formatMinutes(idleDuration)}` : 'Active now')
                    : 'Metrics pending',
                reason: idle?.reason || (summary ? 'Latest utilization summary received.' : 'No metrics collected yet.')
            },
            storage: {
                totalGiB: totalStorage || null,
                usedGiB: null,
                freeGiB: null,
                usedPercent: 0,
                volumes: volumes.map(volume => ({
                    device: volume.deviceName || '-',
                    volumeId: volume.volumeId || '-',
                    type: volume.volumeType || '-',
                    sizeGiB: volume.sizeGib || 0,
                    iops: volume.iops || '-',
                    throughput: volume.throughputMbps || '-',
                    encrypted: volume.encrypted === true
                }))
            },
            history: []
        };

        if (inventory?.lastRefreshedAt) {
            insights.history.push({
                tone: 'success',
                title: 'Inventory refreshed',
                time: formatVmInsightTime(inventory.lastRefreshedAt)
            });
        }

        if (latest?.sampleTime) {
            insights.history.push({
                tone: isIdle ? 'warning' : 'primary',
                title: isIdle ? 'Idle summary updated' : 'Metric sample collected',
                time: formatVmInsightTime(latest.sampleTime)
            });
        }

        return insights;
    }

    function sumVolumeSize(volumes) {
        return volumes.reduce((total, volume) => total + (Number(volume.sizeGib) || 0), 0);
    }

    function sampleSeries(samples, field, unit) {
        const values = samples
            .slice(-12)
            .map(sample => sample[field])
            .filter(value => value !== null && value !== undefined)
            .map(value => {
                const numeric = Number(value);
                return unit === 'bytes' ? Math.round(numeric / (1024 * 1024)) : Math.round(numeric);
            });
        return values.length > 1 ? values : [];
    }

    function normalizeVmType(vmType) {
        return vmType && vmType !== 'UNKNOWN' ? vmType : '-';
    }

    function formatPercent(value) {
        if (value === null || value === undefined || value === '') return '-';
        const numeric = Number(value);
        if (Number.isNaN(numeric)) return '-';
        return `${Math.round(numeric)}%`;
    }

    function roundOneDecimal(value) {
        return Math.round(value * 10) / 10;
    }

    function formatMinutes(minutes) {
        const safeMinutes = Math.max(0, Number(minutes) || 0);
        const hours = Math.floor(safeMinutes / 60);
        const mins = safeMinutes % 60;
        if (hours === 0) return `${mins}m`;
        if (mins === 0) return `${hours}h`;
        return `${hours}h ${mins}m`;
    }

    function formatBytes(bytes) {
        if (!bytes && bytes !== 0) return '-';
        const units = ['B', 'KB', 'MB', 'GB', 'TB'];
        let value = Number(bytes);
        let unitIndex = 0;
        while (value >= 1024 && unitIndex < units.length - 1) {
            value = value / 1024;
            unitIndex++;
        }
        return `${value >= 10 ? value.toFixed(0) : value.toFixed(1)} ${units[unitIndex]}`;
    }

    function formatVmInsightTime(time) {
        if (!time) return 'Never';
        try {
            return typeof Utils !== 'undefined' && Utils.formatRelativeTime ?
                Utils.formatRelativeTime(time) : new Date(time).toLocaleString();
        } catch (e) {
            return new Date(time).toLocaleString();
        }
    }

    function showOperationHistory(envId, envName) {
        VmOperations.showHistoryModal(envId, envName);
    }

    return {
        loadList,
        loadDetail,
        _search: function(val) {
            filterAndRenderEnvList((val || '').toLowerCase().trim());
        }
    };
})();
