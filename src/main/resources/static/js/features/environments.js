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
            showError('Failed to load environments. Please try again.');
        }
    }

    /**
     * Load environment detail view
     */
    async function loadDetail(params) {
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
            showError('Failed to load environment details.');
        }
    }

    /**
     * Fetch environments list
     */
    function fetchEnvironments() {
        return new Promise((resolve, reject) => {
            ApiClient.get(Config.API.environments.list)
                .done(async function(environments) {
                    // Enrich with lock status
                    const enriched = await Promise.all(
                        environments.map(async (env) => {
                            try {
                                const lock = await fetchLockStatus(env.environmentId);
                                return { ...env, lockStatus: lock };
                            } catch (e) {
                                return { ...env, lockStatus: { isLocked: false } };
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
        const vmsData = await new Promise((resolve) => {
            ApiClient.get(Config.API.vms.list(envId))
                .done(resolve)
                .fail(() => resolve([]));
        });

        // Fetch lock status
        const lockStatus = await fetchLockStatus(envId);

        // Calculate counts
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

        return {
            ...env,
            groups: vmsData || [],
            totalVms: env.vmCount || totalVms,
            runningVms,
            lockStatus
        };
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
     * Build environment list HTML
     */
    function buildListHtml(environments) {
        // Admin actions button
        const adminActions = Auth.isEnvAdmin() ? `
            <button class="btn btn-success" id="btn-create-env">
                <i class="fas fa-plus"></i> Create Environment
            </button>
        ` : '';

        if (!environments || environments.length === 0) {
            return `
                <div class="content-header d-flex justify-content-between align-items-start">
                    <div>
                        <h1>My Environments</h1>
                        <p>Manage and monitor your accessible environments</p>
                    </div>
                    ${adminActions}
                </div>
                <div class="empty-state">
                    <i class="fas fa-folder-open fa-3x text-muted"></i>
                    <p class="mt-3">No environments available</p>
                    ${Auth.isEnvAdmin() ?
                        '<button class="btn btn-primary" id="btn-create-env-empty"><i class="fas fa-plus"></i> Create First Environment</button>' :
                        '<a href="#" class="btn btn-primary" data-content="request-access">Request Access</a>'
                    }
                </div>
            `;
        }

        const rows = environments.map(env => {
            const runningVms = env.runningVms || 0;
            const totalVms = env.vmCount || env.totalVms || 0;
            const statusClass = runningVms === 0 ? 'stopped' :
                               runningVms === totalVms ? 'running' : 'partial';

            // Lock status
            let lockDisplay;
            if (env.lockStatus && env.lockStatus.isLocked) {
                const lockedBy = env.lockStatus.lockedByUserId === Auth.getUserId() ? 'you' :
                                 (env.lockStatus.lockedByDisplayName || 'another user');
                lockDisplay = `<span class="text-warning"><i class="fas fa-lock"></i> ${lockedBy}</span>`;
            } else {
                lockDisplay = `<span class="text-success"><i class="fas fa-unlock"></i> Unlocked</span>`;
            }

            // Admin action buttons
            const adminBtns = Auth.isEnvAdmin() ? `
                <button class="btn btn-sm btn-outline-secondary" data-env-id="${env.environmentId}" data-action="edit-env" title="Edit">
                    <i class="fas fa-edit"></i>
                </button>
                <button class="btn btn-sm btn-outline-danger" data-env-id="${env.environmentId}" data-env-name="${env.name}" data-action="delete-env" title="Delete">
                    <i class="fas fa-trash"></i>
                </button>
            ` : '';

            return `
                <tr>
                    <td>
                        <strong>${escapeHtml(env.name)}</strong>
                        ${env.description ? `<br><small class="text-muted">${escapeHtml(env.description)}</small>` : ''}
                    </td>
                    <td>${env.groupCount || 0}</td>
                    <td>${totalVms}</td>
                    <td>
                        <span class="status-badge ${statusClass}">
                            <i class="fas fa-circle"></i> ${runningVms}/${totalVms}
                        </span>
                    </td>
                    <td>${lockDisplay}</td>
                    <td>
                        <button class="btn btn-sm btn-primary" data-env-id="${env.environmentId}" data-env-name="${env.name}" data-action="view">
                            <i class="fas fa-eye"></i> View
                        </button>
                        ${adminBtns}
                    </td>
                </tr>
            `;
        }).join('');

        return `
            <div class="content-header d-flex justify-content-between align-items-start">
                <div>
                    <h1>My Environments</h1>
                    <p>Manage and monitor your accessible environments</p>
                </div>
                ${adminActions}
            </div>
            <div class="mb-3">
                <input type="text" class="form-control" id="env-search" placeholder="Search environments...">
            </div>
            <div class="custom-table">
                <table class="table table-hover">
                    <thead>
                        <tr>
                            <th>Environment</th>
                            <th>Groups</th>
                            <th>Total VMs</th>
                            <th>Running</th>
                            <th>Lock Status</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody id="env-table-body">
                        ${rows}
                    </tbody>
                </table>
            </div>
        `;
    }

    /**
     * Build environment detail HTML
     */
    function buildDetailHtml(env) {
        const totalVms = env.totalVms || 0;
        const runningVms = env.runningVms || 0;

        // Lock section - use Locks module if available
        let lockSection = '';
        try {
            const isAdmin = (typeof Auth !== 'undefined' && Auth.isEnvAdmin) ? Auth.isEnvAdmin() : false;
            lockSection = (typeof Locks !== 'undefined' && Locks.buildLockBanner) ?
                Locks.buildLockBanner(env.lockStatus, env.environmentId, isAdmin) : '';
        } catch (e) {
            console.error('Error building lock banner:', e);
            lockSection = '';
        }

        // Metrics row
        const metricsRow = `
            <div class="row mb-4">
                <div class="col-md-3">
                    <div class="metric-card">
                        <div class="metric-title">Total VMs</div>
                        <div class="metric-value">${totalVms}</div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="metric-card">
                        <div class="metric-title">Running</div>
                        <div class="metric-value text-success">${runningVms}</div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="metric-card">
                        <div class="metric-title">Stopped</div>
                        <div class="metric-value text-danger">${totalVms - runningVms}</div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="metric-card">
                        <div class="metric-title">Groups</div>
                        <div class="metric-value">${env.groups ? env.groups.length : 0}</div>
                    </div>
                </div>
            </div>
        `;

        // Groups HTML
        const groupsHtml = (env.groups || []).map(group => buildGroupCard(group, env)).join('');

        return `
            <div class="content-header d-flex justify-content-between align-items-start">
                <div>
                    <h1>${Utils.escapeHtml(env.displayName || env.name)}</h1>
                    <p>${env.description || ''}</p>
                </div>
                <button class="btn btn-outline-secondary" onclick="Dashboard.load()">
                    <i class="fas fa-arrow-left"></i> Back
                </button>
            </div>

            ${lockSection}
            ${metricsRow}

            <div class="d-flex justify-content-between align-items-center mb-3">
                <h5>Groups and VMs</h5>
                <div>
                    <button class="btn btn-outline-secondary me-2" id="btn-operation-history">
                        <i class="fas fa-history"></i> History
                    </button>
                    <button class="btn btn-success" id="btn-start-all" ${runningVms === totalVms ? 'disabled' : ''}>
                        <i class="fas fa-play"></i> Start All
                    </button>
                    <button class="btn btn-danger ms-2" id="btn-stop-all" ${runningVms === 0 ? 'disabled' : ''}>
                        <i class="fas fa-stop"></i> Stop All
                    </button>
                </div>
            </div>

            ${groupsHtml || '<div class="alert alert-secondary">No groups configured for this environment.</div>'}
        `;
    }

    /**
     * Build group card HTML
     */
    function buildGroupCard(groupData, env) {
        const group = groupData.group || groupData;
        const vms = groupData.vms || [];

        const runningCount = vms.filter(v => v.status === 'RUNNING').length || 0;
        const totalCount = vms.length;
        const statusClass = runningCount === 0 ? 'bg-secondary' :
                           runningCount === totalCount ? 'bg-success' : 'bg-warning';

        // dependsOnGroupIds comes as a JS array from the API (already parsed), not a JSON string
        let dependsText = 'None';
        if (group.dependsOnGroupIds && Array.isArray(group.dependsOnGroupIds) && group.dependsOnGroupIds.length > 0) {
            dependsText = group.dependsOnGroupIds.join(', ');
        } else if (typeof group.dependsOnGroupIds === 'string' && group.dependsOnGroupIds) {
            try {
                const parsed = JSON.parse(group.dependsOnGroupIds);
                dependsText = Array.isArray(parsed) && parsed.length > 0 ? parsed.join(', ') : 'None';
            } catch (e) {
                dependsText = group.dependsOnGroupIds;
            }
        }

        const vmRows = vms.map(vm => {
            const providerConfig = Config.CLOUD_ICONS[vm.provider] || { icon: 'fas fa-cloud', color: '#6b7280' };
            const statusConfig = Config.STATUS.vm[vm.status] || Config.STATUS.vm.UNKNOWN;

            return `
                <tr>
                    <td>
                        <strong>${Utils.escapeHtml(vm.name)}</strong>
                        ${vm.displayName && vm.displayName !== vm.name ?
                            `<br><small class="text-muted">${Utils.escapeHtml(vm.displayName)}</small>` : ''}
                    </td>
                    <td>
                        <i class="${providerConfig.icon}" style="color: ${providerConfig.color};"></i>
                        ${vm.region}
                    </td>
                    <td>
                        <span class="status-badge ${statusConfig.class}">
                            <i class="fas ${statusConfig.icon}"></i> ${statusConfig.label}
                        </span>
                    </td>
                    <td>${vm.sequencePosition || '-'}</td>
                    <td>
                        <button class="btn btn-sm btn-outline-primary" data-vm-id="${vm.vmId}" data-action="vm-details">
                            <i class="fas fa-info-circle"></i>
                        </button>
                        ${vm.status === 'RUNNING' ?
                            `<button class="btn btn-sm btn-outline-danger" data-vm-id="${vm.vmId}" data-action="stop-vm">
                                <i class="fas fa-stop"></i>
                            </button>` :
                            `<button class="btn btn-sm btn-outline-success" data-vm-id="${vm.vmId}" data-action="start-vm">
                                <i class="fas fa-play"></i>
                            </button>`
                        }
                    </td>
                </tr>
            `;
        }).join('');

        return `
            <div class="card mb-3">
                <div class="card-header bg-light">
                    <div class="d-flex justify-content-between align-items-center">
                        <div>
                            <strong>${Utils.escapeHtml(group.displayName || group.name)}</strong>
                            <span class="badge ${statusClass} ms-2">${runningCount}/${totalCount}</span>
                            <small class="text-muted ms-2">Seq: ${group.sequencePosition} | Depends: ${dependsText}</small>
                        </div>
                        <div>
                            <button class="btn btn-sm btn-success" data-group-id="${group.groupId}" data-action="start-group"
                                    ${runningCount === totalCount ? 'disabled' : ''}>
                                <i class="fas fa-play"></i> Start
                            </button>
                            <button class="btn btn-sm btn-danger ms-1" data-group-id="${group.groupId}" data-action="stop-group"
                                    ${runningCount === 0 ? 'disabled' : ''}>
                                <i class="fas fa-stop"></i> Stop
                            </button>
                        </div>
                    </div>
                </div>
                <div class="card-body p-0">
                    ${vms.length > 0 ? `
                        <table class="table table-sm mb-0">
                            <thead class="table-light">
                                <tr>
                                    <th>VM Name</th>
                                    <th>Location</th>
                                    <th>Status</th>
                                    <th>Sequence</th>
                                    <th>Actions</th>
                                </tr>
                            </thead>
                            <tbody>
                                ${vmRows}
                            </tbody>
                        </table>
                    ` : '<p class="p-3 mb-0 text-muted">No VMs in this group</p>'}
                </div>
            </div>
        `;
    }

    /**
     * Bind list view events
     */
    function bindListEvents() {
        // View environment
        $('#content-area').off('click', '[data-action="view"]').on('click', '[data-action="view"]', function() {
            const envId = $(this).data('env-id');
            const envName = $(this).data('env-name');
            loadDetail({ environmentId: envId, environmentName: envName });
        });

        // Create environment
        $('#btn-create-env, #btn-create-env-empty').off('click').on('click', function() {
            Modals.showCreateEnvironment(function(env) {
                loadList(); // Refresh list
            });
        });

        // Edit environment
        $('[data-action="edit-env"]').off('click').on('click', function() {
            const envId = $(this).data('env-id');
            const env = environmentsList.find(e => e.environmentId === envId);
            if (env) {
                Modals.showEditEnvironment(env, function() {
                    loadList(); // Refresh list
                });
            }
        });

        // Delete environment
        $('[data-action="delete-env"]').off('click').on('click', function() {
            const envId = $(this).data('env-id');
            const envName = $(this).data('env-name');
            deleteEnvironment(envId, envName);
        });

        // Search
        $('#env-search').off('input').on('input', Utils.debounce(function() {
            const query = $(this).val().toLowerCase();
            $('#env-table-body tr').each(function() {
                const text = $(this).text().toLowerCase();
                $(this).toggle(text.includes(query));
            });
        }, 300));
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

        // Start all
        $('#btn-start-all').off('click').on('click', function(e) {
            e.preventDefault();
            console.log('Start All button clicked');
            startEnvironment(envId, envName);
        });

        // Stop all
        $('#btn-stop-all').off('click').on('click', function(e) {
            e.preventDefault();
            console.log('Stop All button clicked');
            stopEnvironment(envId, envName);
        });

        // Operation history
        $('#btn-operation-history').off('click').on('click', function(e) {
            e.preventDefault();
            console.log('Operation History button clicked');
            showOperationHistory(envId, envName);
        });

        // Group actions
        $('[data-action="start-group"]').off('click').on('click', function(e) {
            e.preventDefault();
            const groupId = $(this).data('group-id');
            console.log('Start Group button clicked:', groupId);
            startGroup(envId, groupId);
        });

        $('[data-action="stop-group"]').off('click').on('click', function(e) {
            e.preventDefault();
            const groupId = $(this).data('group-id');
            console.log('Stop Group button clicked:', groupId);
            stopGroup(envId, groupId);
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
    }

    // Operation functions - delegate to VmOperations module for progress tracking
    function startEnvironment(envId, envName) {
        console.log('startEnvironment called:', envId, envName);
        Modals.confirm(
            'Start Environment',
            `Start all VMs in "<strong>${Utils.escapeHtml(envName)}</strong>"?`,
            function() {
                console.log('Confirmed start environment');
                VmOperations.startEnvironment(envId, envName)
                    .then(() => {
                        console.log('Start environment completed');
                        loadDetail({ environmentId: envId });
                    })
                    .catch((err) => {
                        console.error('Start environment failed:', err);
                    });
            },
            { confirmText: 'Start All', confirmClass: 'btn-success' }
        );
    }

    function stopEnvironment(envId, envName) {
        console.log('stopEnvironment called:', envId, envName);
        Modals.confirm(
            'Stop Environment',
            `Stop all VMs in "<strong>${Utils.escapeHtml(envName)}</strong>"?<br>
             <small class="text-muted">Running VMs will be gracefully stopped.</small>`,
            function() {
                console.log('Confirmed stop environment');
                VmOperations.stopEnvironment(envId, envName)
                    .then(() => {
                        console.log('Stop environment completed');
                        loadDetail({ environmentId: envId });
                    })
                    .catch((err) => {
                        console.error('Stop environment failed:', err);
                    });
            },
            { confirmText: 'Stop All', confirmClass: 'btn-danger' }
        );
    }

    function startGroup(envId, groupId) {
        console.log('startGroup called:', envId, groupId);
        const group = findGroup(groupId);
        const groupName = group ? (group.group?.name || group.name) : groupId;

        VmOperations.startGroup(envId, groupId, groupName)
            .then(() => {
                console.log('Start group completed');
                loadDetail({ environmentId: envId });
            })
            .catch((err) => {
                console.error('Start group failed:', err);
            });
    }

    function stopGroup(envId, groupId) {
        console.log('stopGroup called:', envId, groupId);
        const group = findGroup(groupId);
        const groupName = group ? (group.group?.name || group.name) : groupId;

        VmOperations.stopGroup(envId, groupId, groupName)
            .then(() => {
                console.log('Stop group completed');
                loadDetail({ environmentId: envId });
            })
            .catch((err) => {
                console.error('Stop group failed:', err);
            });
    }

    function startVm(envId, vmId) {
        console.log('startVm called:', envId, vmId);
        const vm = findVm(vmId);
        const vmName = vm ? vm.name : vmId;

        VmOperations.startVm(envId, vmId, vmName)
            .then(() => {
                console.log('Start VM completed');
                loadDetail({ environmentId: envId });
            })
            .catch((err) => {
                console.error('Start VM failed:', err);
            });
    }

    function stopVm(envId, vmId) {
        console.log('stopVm called:', envId, vmId);
        const vm = findVm(vmId);
        const vmName = vm ? vm.name : vmId;

        VmOperations.stopVm(envId, vmId, vmName)
            .then(() => {
                console.log('Stop VM completed');
                loadDetail({ environmentId: envId });
            })
            .catch((err) => {
                console.error('Stop VM failed:', err);
            });
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

    function showVmDetails(envId, vmId) {
        ApiClient.get(Config.API.vms.get(envId, vmId))
            .done(function(vm) {
                const providerConfig = Config.CLOUD_ICONS[vm.provider] || { icon: 'fas fa-cloud', label: vm.provider };
                const statusConfig = Config.STATUS.vm[vm.status] || Config.STATUS.vm.UNKNOWN;

                const formatTime = (time) => {
                    if (!time) return 'Never';
                    try {
                        return typeof Utils !== 'undefined' && Utils.formatRelativeTime ?
                            Utils.formatRelativeTime(time) : new Date(time).toLocaleString();
                    } catch (e) {
                        return new Date(time).toLocaleString();
                    }
                };

                const content = `
                    <div class="vm-details">
                        <div class="mb-3">
                            <span class="status-badge ${statusConfig.class} fs-6">
                                <i class="fas ${statusConfig.icon}"></i> ${statusConfig.label}
                            </span>
                        </div>
                        <table class="table table-sm">
                            <tr><th>Name</th><td>${escapeHtml(vm.name)}</td></tr>
                            <tr><th>Display Name</th><td>${escapeHtml(vm.displayName || vm.name)}</td></tr>
                            <tr><th>Provider</th><td><i class="${providerConfig.icon}"></i> ${providerConfig.label}</td></tr>
                            <tr><th>Region</th><td>${vm.region}</td></tr>
                            <tr><th>Provider VM ID</th><td><code>${vm.providerVmId}</code></td></tr>
                            <tr><th>Type</th><td>${vm.vmType || '-'}</td></tr>
                            <tr><th>Sequence</th><td>${vm.sequencePosition}</td></tr>
                            <tr><th>Last Sync</th><td>${formatTime(vm.lastStateSyncAt)}</td></tr>
                        </table>
                    </div>
                `;

                try {
                    if (typeof Slideout !== 'undefined' && Slideout.open) {
                        Slideout.open(`VM: ${escapeHtml(vm.name)}`, content);
                    } else {
                        Notifications.info('VM Details: ' + vm.name);
                    }
                } catch (e) {
                    console.error('Error opening slideout:', e);
                    Notifications.info('Could not display VM details');
                }
            })
            .fail(function() {
                Notifications.error('Failed to load VM details');
            });
    }

    function showOperationHistory(envId, envName) {
        VmOperations.showHistoryModal(envId, envName);
    }

    return {
        loadList,
        loadDetail
    };
})();
