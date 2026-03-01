/**
 * VM Self-Service Platform - Additional Features
 * Placeholder implementations for various features
 * These use mock data and will be connected to APIs later
 */

const Features = (function() {
    'use strict';


    /**
     * Load Access Management view (Admin)
     */
    function loadAccessManagement() {
        const html = `
            <div class="content-header">
                <h1>Access Management</h1>
                <p>Grant or revoke user access to environments</p>
            </div>

            <div class="row mb-3">
                <div class="col-md-6">
                    <input type="text" class="form-control" placeholder="Search users by name or email...">
                </div>
                <div class="col-md-4">
                    <select class="form-select">
                        <option>All Environments</option>
                        <option>mcube-demo-env</option>
                        <option>analytics-sandbox</option>
                    </select>
                </div>
                <div class="col-md-2">
                    <button class="btn btn-primary w-100"><i class="fas fa-plus"></i> Grant Access</button>
                </div>
            </div>

            <h5 class="mb-3">Environment: mcube-demo-env</h5>
            <div class="custom-table mb-4">
                <table class="table">
                    <thead>
                        <tr>
                            <th>User</th>
                            <th>Role</th>
                            <th>Granted By</th>
                            <th>Granted Date</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr>
                            <td>john.doe@company.com</td>
                            <td><span class="badge bg-primary">Owner</span></td>
                            <td>System</td>
                            <td>3 months ago</td>
                            <td>-</td>
                        </tr>
                        <tr>
                            <td>jane.smith@company.com</td>
                            <td><span class="badge bg-secondary">User</span></td>
                            <td>admin</td>
                            <td>1 month ago</td>
                            <td><button class="btn btn-sm btn-danger">Revoke</button></td>
                        </tr>
                        <tr>
                            <td>bob.jones@company.com</td>
                            <td><span class="badge bg-secondary">User</span></td>
                            <td>john.doe</td>
                            <td>2 weeks ago</td>
                            <td><button class="btn btn-sm btn-danger">Revoke</button></td>
                        </tr>
                    </tbody>
                </table>
            </div>

            <h5 class="mb-3">Pending Access Requests (2)</h5>
            <div class="custom-table">
                <table class="table">
                    <thead>
                        <tr>
                            <th>User</th>
                            <th>Environment</th>
                            <th>Requested</th>
                            <th>Justification</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr>
                            <td>alice.wang@company.com</td>
                            <td>mcube-demo-env</td>
                            <td>2 days ago</td>
                            <td>Need access for feature development</td>
                            <td>
                                <button class="btn btn-sm btn-success me-1">Approve</button>
                                <button class="btn btn-sm btn-danger">Deny</button>
                            </td>
                        </tr>
                        <tr>
                            <td>chris.lee@company.com</td>
                            <td>analytics-sandbox</td>
                            <td>1 day ago</td>
                            <td>Testing new analytics pipeline</td>
                            <td>
                                <button class="btn btn-sm btn-success me-1">Approve</button>
                                <button class="btn btn-sm btn-danger">Deny</button>
                            </td>
                        </tr>
                    </tbody>
                </table>
            </div>
        `;
        $('#content-area').html(html);
    }

    /**
     * Load VM Registry view (Admin)
     */
    function loadVmRegistry() {
        window.VmRegistryState = {
            environments: [],
            currentEnvironment: null,
            currentGroups: []
        };

        renderVmRegistry();
        loadEnvironmentsData();
    }

    function renderVmRegistry() {
        const html = `
            <div class="vm-registry-container">
                <div class="content-header">
                    <div class="d-flex justify-content-between align-items-center">
                        <div>
                            <h1><i class="fas fa-database"></i> VM Registry</h1>
                            <p class="text-muted">Create and manage environments, groups, and VMs</p>
                        </div>
                        <div>
                            <button class="btn btn-primary" onclick="Features.openCreateEnvironmentModal()">
                                <i class="fas fa-plus"></i> Create Environment
                            </button>
                        </div>
                    </div>
                </div>

                <div class="row mb-3">
                    <div class="col-md-6">
                        <div class="input-group">
                            <span class="input-group-text"><i class="fas fa-search"></i></span>
                            <input type="text" class="form-control" id="searchEnvironments" placeholder="Search environments..." onkeyup="Features.filterEnvironments(this.value)">
                        </div>
                    </div>
                    <div class="col-md-3">
                        <select class="form-select" id="filterStatus" onchange="Features.loadEnvironmentsData()">
                            <option value="active">Active Only</option>
                            <option value="all">All Environments</option>
                        </select>
                    </div>
                    <div class="col-md-3">
                        <button class="btn btn-outline-secondary w-100" onclick="Features.loadEnvironmentsData()">
                            <i class="fas fa-sync-alt"></i> Refresh
                        </button>
                    </div>
                </div>

                <div class="card">
                    <div class="card-body">
                        <div id="environmentsTableContainer">
                            <div class="text-center py-5" id="environmentsLoading">
                                <div class="spinner-border text-primary" role="status"></div>
                                <p class="text-muted mt-2">Loading environments...</p>
                            </div>
                            <div class="text-center py-5 d-none" id="environmentsEmpty">
                                <i class="fas fa-folder-open fa-4x text-muted mb-3"></i>
                                <h5>No Environments Yet</h5>
                                <p class="text-muted">Create your first environment to get started</p>
                                <button class="btn btn-primary" onclick="Features.openCreateEnvironmentModal()">
                                    <i class="fas fa-plus"></i> Create Environment
                                </button>
                            </div>
                            <div class="table-responsive d-none" id="environmentsTableWrapper">
                                <table class="table table-hover align-middle" id="environmentsTable">
                                    <thead>
                                        <tr>
                                            <th>Environment</th>
                                            <th>Description</th>
                                            <th class="text-center">Groups</th>
                                            <th class="text-center">VMs</th>
                                            <th class="text-center">Status</th>
                                            <th>Created</th>
                                            <th class="text-end">Actions</th>
                                        </tr>
                                    </thead>
                                    <tbody id="environmentsTableBody"></tbody>
                                </table>
                            </div>
                        </div>
                    </div>
                </div>

                <div class="row mt-4">
                    <div class="col-md-3">
                        <div class="card text-center">
                            <div class="card-body">
                                <h3 class="mb-0" id="statTotalEnvironments">0</h3>
                                <p class="text-muted mb-0">Total Environments</p>
                            </div>
                        </div>
                    </div>
                    <div class="col-md-3">
                        <div class="card text-center">
                            <div class="card-body">
                                <h3 class="mb-0" id="statTotalGroups">0</h3>
                                <p class="text-muted mb-0">Total Groups</p>
                            </div>
                        </div>
                    </div>
                    <div class="col-md-3">
                        <div class="card text-center">
                            <div class="card-body">
                                <h3 class="mb-0" id="statTotalVMs">0</h3>
                                <p class="text-muted mb-0">Total VMs</p>
                            </div>
                        </div>
                    </div>
                    <div class="col-md-3">
                        <div class="card text-center">
                            <div class="card-body">
                                <h3 class="mb-0" id="statActiveEnvironments">0</h3>
                                <p class="text-muted mb-0">Active Environments</p>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        `;
        $('#content-area').html(html);
    }

    async function loadEnvironmentsData() {
        try {
            $('#environmentsLoading').removeClass('d-none');
            $('#environmentsEmpty').addClass('d-none');
            $('#environmentsTableWrapper').addClass('d-none');

            const includeInactive = $('#filterStatus').val() === 'all';
            const environments = await ApiClient.get(`/api/v1/environments?includeInactive=${includeInactive}`);

            window.VmRegistryState.environments = environments;
            renderEnvironmentsList(environments);
            updateVmRegistryStats(environments);
        } catch (error) {
            console.error('Failed to load environments:', error);
            Notifications.error('Failed to load environments');
            $('#environmentsLoading').addClass('d-none');
            $('#environmentsEmpty').removeClass('d-none');
        }
    }

    function renderEnvironmentsList(envs) {
        $('#environmentsLoading').addClass('d-none');
        if (!envs || envs.length === 0) {
            $('#environmentsEmpty').removeClass('d-none');
            $('#environmentsTableWrapper').addClass('d-none');
            return;
        }
        $('#environmentsEmpty').addClass('d-none');
        $('#environmentsTableWrapper').removeClass('d-none');

        const tbody = $('#environmentsTableBody');
        tbody.empty();
        envs.forEach(env => tbody.append(buildEnvironmentRow(env)));
    }

    function buildEnvironmentRow(env) {
        const statusBadge = env.isActive ? '<span class="badge bg-success">Active</span>' : '<span class="badge bg-secondary">Inactive</span>';
        const createdDate = env.createdAt ? new Date(env.createdAt).toLocaleDateString() : 'N/A';
        const description = env.description || '<span class="text-muted">No description</span>';
        const displayName = Utils.escapeHtml(env.displayName);
        const name = Utils.escapeHtml(env.name);

        return `
            <tr data-env-id="${env.environmentId}">
                <td>
                    <div><strong>${displayName}</strong></div>
                    <div class="small text-muted">${name}</div>
                </td>
                <td>${description}</td>
                <td class="text-center"><span class="badge bg-primary">${env.groupCount || 0}</span></td>
                <td class="text-center"><span class="badge bg-info">${env.vmCount || 0}</span></td>
                <td class="text-center">${statusBadge}</td>
                <td>${createdDate}</td>
                <td class="text-end">
                    <button class="btn btn-sm btn-primary" onclick="Features.manageGroups('${env.environmentId}', '${displayName.replace(/'/g, "\\'")}')">
                        <i class="fas fa-layer-group"></i> Groups
                    </button>
                    <button class="btn btn-sm btn-warning" onclick="Features.editEnvironment('${env.environmentId}')">
                        <i class="fas fa-edit"></i>
                    </button>
                    <button class="btn btn-sm btn-danger" onclick="Features.deleteEnvironment('${env.environmentId}')">
                        <i class="fas fa-trash"></i>
                    </button>
                </td>
            </tr>
        `;
    }

    function updateVmRegistryStats(envs) {
        const total = envs.length;
        const active = envs.filter(e => e.isActive).length;
        const totalGroups = envs.reduce((sum, e) => sum + (e.groupCount || 0), 0);
        const totalVMs = envs.reduce((sum, e) => sum + (e.vmCount || 0), 0);
        $('#statTotalEnvironments').text(total);
        $('#statActiveEnvironments').text(active);
        $('#statTotalGroups').text(totalGroups);
        $('#statTotalVMs').text(totalVMs);
    }

    function filterEnvironments(searchTerm) {
        if (!searchTerm) {
            renderEnvironmentsList(window.VmRegistryState.environments);
            return;
        }
        const term = searchTerm.toLowerCase();
        const filtered = window.VmRegistryState.environments.filter(env =>
            env.name.toLowerCase().includes(term) ||
            env.displayName.toLowerCase().includes(term) ||
            (env.description && env.description.toLowerCase().includes(term))
        );
        renderEnvironmentsList(filtered);
    }

    function openCreateEnvironmentModal() {
        $('#createEnvironmentForm')[0].reset();
        $('#createEnvironmentForm').removeClass('was-validated');
        $('#environmentId').val('');
        $('#createEnvironmentModalLabel').text('Create Environment');
        $('#btnSubmitEnvironment').html('<i class="fas fa-save"></i> Create Environment');
        new bootstrap.Modal(document.getElementById('createEnvironmentModal')).show();
    }

    async function editEnvironment(environmentId) {
        try {
            Loading.show('Loading environment...');
            const env = await ApiClient.get(`/api/v1/environments/${environmentId}`);
            $('#environmentId').val(env.environmentId);
            $('#envName').val(env.name);
            $('#envDisplayName').val(env.displayName);
            $('#envDescription').val(env.description || '');
            $('#envMetadata').val(env.metadata || '');
            $('#createEnvironmentModalLabel').text('Edit Environment');
            $('#btnSubmitEnvironment').html('<i class="fas fa-save"></i> Update Environment');
            new bootstrap.Modal(document.getElementById('createEnvironmentModal')).show();
            Loading.hide();
        } catch (error) {
            console.error('Failed to load environment:', error);
            Notifications.error('Failed to load environment');
            Loading.hide();
        }
    }

    async function submitEnvironment() {
        const form = $('#createEnvironmentForm')[0];
        if (!form.checkValidity()) {
            form.classList.add('was-validated');
            return;
        }
        const environmentId = $('#environmentId').val();
        const isEdit = !!environmentId;
        const name = $('#envName').val().trim();
        const displayName = $('#envDisplayName').val().trim();
        const data = {
            name: name,
            displayName: displayName || name,
            description: $('#envDescription').val().trim() || null,
            metadata: $('#envMetadata').val().trim() || null
        };
        try {
            Loading.show(isEdit ? 'Updating...' : 'Creating...');
            if (isEdit) {
                await ApiClient.put(`/api/v1/environments/${environmentId}`, data);
            } else {
                await ApiClient.post('/api/v1/environments', data);
            }
            Notifications.success(isEdit ? 'Environment updated' : 'Environment created');
            bootstrap.Modal.getInstance(document.getElementById('createEnvironmentModal')).hide();
            await loadEnvironmentsData();
            Loading.hide();
        } catch (error) {
            console.error('Failed to submit environment:', error);
            // error is the xhr object from jQuery deferred rejection
            // ApiClient.handleApiError already shows a notification, so only log here
            const msg = error?.responseJSON?.message || error?.responseJSON?.error;
            if (!msg) {
                // Only show fallback if ApiClient didn't already handle it
                if (error?.status === 403) {
                    // Already notified by ApiClient
                } else if (error?.status === 400) {
                    Notifications.error('Validation failed. Please check your input.');
                } else if (!error?.status) {
                    Notifications.error('Failed to save environment');
                }
            }
            Loading.hide();
        }
    }

    async function deleteEnvironment(environmentId) {
        const env = window.VmRegistryState.environments.find(e => e.environmentId === environmentId);
        if (!env) return;
        const confirmed = confirm(`Are you sure you want to delete "${env.displayName}"? This action cannot be undone.`);
        if (!confirmed) return;
        try {
            Loading.show('Deleting...');
            await ApiClient.delete(`/api/v1/environments/${environmentId}`);
            Notifications.success('Environment deleted');
            await loadEnvironmentsData();
            Loading.hide();
        } catch (error) {
            console.error('Failed to delete environment:', error);
            Notifications.error(error.responseJSON?.message || 'Failed to delete');
            Loading.hide();
        }
    }

    async function manageGroups(environmentId, environmentName) {
        window.VmRegistryState.currentEnvironment = { environmentId, environmentName };
        try {
            Loading.show('Loading groups and VMs...');
            // Fetch groups WITH their VMs using the /vms endpoint
            const groupsWithVms = await ApiClient.get(`/api/v1/environments/${environmentId}/vms`);
            // Also fetch group-only data for group operations (edit, dependency dropdown)
            window.VmRegistryState.currentGroupsWithVms = groupsWithVms;
            window.VmRegistryState.currentGroups = groupsWithVms.map(gv => gv.group);
            renderGroupsModal(environmentName, groupsWithVms);
            new bootstrap.Modal(document.getElementById('manageGroupsModal')).show();
            Loading.hide();
        } catch (error) {
            console.error('Failed to load groups:', error);
            Notifications.error('Failed to load groups');
            Loading.hide();
        }
    }

    function renderGroupsModal(environmentName, groupsWithVms) {
        $('#manageGroupsModalLabel').text(`Manage Groups & VMs - ${environmentName}`);
        const container = $('#groupsContentArea');
        container.empty();
        if (!groupsWithVms || groupsWithVms.length === 0) {
            container.html(`
                <div class="text-center text-muted py-5">
                    <i class="fas fa-inbox fa-3x mb-3 d-block"></i>
                    <p>No groups yet. Add a group to get started.</p>
                </div>
            `);
            return;
        }
        groupsWithVms.forEach(gv => container.append(buildGroupCard(gv)));
    }

    function buildGroupCard(groupWithVms) {
        const group = groupWithVms.group;
        const vms = groupWithVms.vms || [];
        const vmCount = vms.length;
        const runningCount = vms.filter(v => v.status === 'RUNNING').length;
        const statusClass = vmCount === 0 ? 'bg-secondary' :
                           runningCount === vmCount ? 'bg-success' :
                           runningCount > 0 ? 'bg-warning' : 'bg-secondary';

        const dependencies = group.dependsOnGroupIds && group.dependsOnGroupIds.length > 0
            ? group.dependsOnGroupIds.map(id => {
                const depGroup = window.VmRegistryState.currentGroups.find(g => g.groupId === id);
                return depGroup ? `<span class="badge bg-secondary me-1">${Utils.escapeHtml(depGroup.displayName)}</span>` : '';
              }).join('')
            : '<span class="text-muted small">None</span>';

        const vmRows = vms.map(vm => {
            const providerLabels = { AWS: 'AWS', AZURE: 'Azure', GCP: 'GCP', OCI: 'OCI' };
            const providerIcons = { AWS: 'fab fa-aws', AZURE: 'fab fa-microsoft', GCP: 'fab fa-google', OCI: 'fas fa-cloud' };
            const statusConfig = Config.STATUS.vm[vm.status] || Config.STATUS.vm.UNKNOWN;
            return `
                <tr>
                    <td>
                        <strong>${Utils.escapeHtml(vm.name)}</strong>
                        ${vm.displayName && vm.displayName !== vm.name ? `<div class="small text-muted">${Utils.escapeHtml(vm.displayName)}</div>` : ''}
                    </td>
                    <td><i class="${providerIcons[vm.provider] || 'fas fa-cloud'}"></i> ${providerLabels[vm.provider] || vm.provider}</td>
                    <td>${Utils.escapeHtml(vm.region)}</td>
                    <td><code class="small">${Utils.escapeHtml(vm.providerVmId)}</code></td>
                    <td>
                        <span class="status-badge ${statusConfig.class}">
                            <i class="fas ${statusConfig.icon}"></i> ${statusConfig.label}
                        </span>
                    </td>
                    <td class="text-center">${vm.sequencePosition || '-'}</td>
                    <td class="text-end">
                        <button class="btn btn-sm btn-outline-danger" onclick="Features.deleteVm('${vm.vmId}', '${Utils.escapeHtml(vm.name)}')" title="Remove VM">
                            <i class="fas fa-trash"></i>
                        </button>
                    </td>
                </tr>
            `;
        }).join('');

        const collapseId = `collapse-${group.groupId}`;

        return `
            <div class="card mb-3" data-group-id="${group.groupId}">
                <div class="card-header bg-light">
                    <div class="d-flex justify-content-between align-items-center">
                        <div class="d-flex align-items-center">
                            <a class="text-decoration-none text-dark" data-bs-toggle="collapse" href="#${collapseId}" role="button" aria-expanded="true">
                                <i class="fas fa-chevron-down me-2"></i>
                                <strong>${Utils.escapeHtml(group.displayName)}</strong>
                            </a>
                            <span class="badge ${statusClass} ms-2">${runningCount}/${vmCount} VMs</span>
                            <small class="text-muted ms-3">Seq: ${group.sequencePosition}</small>
                            <small class="text-muted ms-3">Depends: ${dependencies}</small>
                        </div>
                        <div>
                            <button class="btn btn-sm btn-success me-1" onclick="Features.openVmForm('${group.groupId}')" title="Register VM">
                                <i class="fas fa-plus"></i> VM
                            </button>
                            <button class="btn btn-sm btn-warning me-1" onclick="Features.editGroup('${group.groupId}')" title="Edit Group">
                                <i class="fas fa-edit"></i>
                            </button>
                            <button class="btn btn-sm btn-danger" onclick="Features.deleteGroup('${group.groupId}')" title="Delete Group"
                                    ${vmCount > 0 ? 'disabled' : ''}>
                                <i class="fas fa-trash"></i>
                            </button>
                        </div>
                    </div>
                </div>
                <div class="collapse show" id="${collapseId}">
                    <div class="card-body p-0">
                        ${vmCount > 0 ? `
                            <table class="table table-sm table-hover mb-0">
                                <thead class="table-light">
                                    <tr>
                                        <th>VM Name</th>
                                        <th>Provider</th>
                                        <th>Region</th>
                                        <th>Instance ID</th>
                                        <th>Status</th>
                                        <th class="text-center">Seq</th>
                                        <th class="text-end">Actions</th>
                                    </tr>
                                </thead>
                                <tbody>${vmRows}</tbody>
                            </table>
                        ` : `
                            <p class="text-muted text-center py-3 mb-0">
                                <i class="fas fa-server me-1"></i> No VMs registered.
                                <a href="#" onclick="Features.openVmForm('${group.groupId}'); return false;">Add one</a>
                            </p>
                        `}
                    </div>
                </div>
            </div>
        `;
    }

    function openGroupForm(groupId = null) {
        $('#createGroupForm')[0].reset();
        $('#createGroupForm').removeClass('was-validated');
        $('#groupId').val('');
        if (groupId) {
            const group = window.VmRegistryState.currentGroups.find(g => g.groupId === groupId);
            if (!group) return;
            $('#groupId').val(group.groupId);
            $('#groupName').val(group.name);
            $('#groupDisplayName').val(group.displayName);
            $('#groupDescription').val(group.description || '');
            $('#groupSequencePosition').val(group.sequencePosition);
            $('#groupMetadata').val(group.metadata || '');
            $('#createGroupModalLabel').text('Edit Group');
            $('#btnSubmitGroup').html('<i class="fas fa-save"></i> Update');
        } else {
            const maxSeq = window.VmRegistryState.currentGroups.length > 0
                ? Math.max(...window.VmRegistryState.currentGroups.map(g => g.sequencePosition)) : 0;
            $('#groupSequencePosition').val(maxSeq + 1);
            $('#createGroupModalLabel').text('Create Group');
            $('#btnSubmitGroup').html('<i class="fas fa-save"></i> Create');
        }
        populateDependencyDropdown(groupId);
        new bootstrap.Modal(document.getElementById('createGroupModal')).show();
    }

    function populateDependencyDropdown(excludeGroupId) {
        const select = $('#groupDependsOn');
        select.empty();
        window.VmRegistryState.currentGroups.forEach(group => {
            if (group.groupId !== excludeGroupId) {
                select.append(new Option(group.displayName, group.groupId));
            }
        });
        if (excludeGroupId) {
            const group = window.VmRegistryState.currentGroups.find(g => g.groupId === excludeGroupId);
            if (group && group.dependsOnGroupIds) select.val(group.dependsOnGroupIds);
        }
    }

    async function submitGroup() {
        const form = $('#createGroupForm')[0];
        if (!form.checkValidity()) {
            form.classList.add('was-validated');
            return;
        }
        const groupId = $('#groupId').val();
        const isEdit = !!groupId;
        const data = {
            name: $('#groupName').val().trim(),
            displayName: $('#groupDisplayName').val().trim(),
            description: $('#groupDescription').val().trim() || null,
            sequencePosition: parseInt($('#groupSequencePosition').val()),
            dependsOnGroupIds: $('#groupDependsOn').val() || [],
            metadata: $('#groupMetadata').val().trim() || null
        };
        try {
            Loading.show(isEdit ? 'Updating...' : 'Creating...');
            const envId = window.VmRegistryState.currentEnvironment.environmentId;
            if (isEdit) {
                await ApiClient.put(`/api/v1/environments/${envId}/groups/${groupId}`, data);
            } else {
                await ApiClient.post(`/api/v1/environments/${envId}/groups`, data);
            }
            Notifications.success(isEdit ? 'Group updated' : 'Group created');
            bootstrap.Modal.getInstance(document.getElementById('createGroupModal')).hide();
            await refreshGroupsModal();
            await loadEnvironmentsData();
            Loading.hide();
        } catch (error) {
            console.error('Failed to submit group:', error);
            Notifications.error(error.responseJSON?.message || 'Failed to save group');
            Loading.hide();
        }
    }

    function editGroup(groupId) {
        openGroupForm(groupId);
    }

    async function deleteGroup(groupId) {
        const group = window.VmRegistryState.currentGroups.find(g => g.groupId === groupId);
        if (!group) return;
        if (group.vmCount > 0) {
            Notifications.error('Cannot delete group with VMs');
            return;
        }
        const confirmed = confirm(`Delete "${group.displayName}"?`);
        if (!confirmed) return;
        try {
            Loading.show('Deleting...');
            await ApiClient.delete(`/api/v1/environments/${window.VmRegistryState.currentEnvironment.environmentId}/groups/${groupId}`);
            Notifications.success('Group deleted');
            await refreshGroupsModal();
            await loadEnvironmentsData();
            Loading.hide();
        } catch (error) {
            console.error('Failed to delete group:', error);
            Notifications.error(error.responseJSON?.message || 'Failed to delete');
            Loading.hide();
        }
    }

    // =========================================================================
    // VM Registration within VM Registry
    // =========================================================================

    // Cache for fetched EC2 instances (for filtering)
    let _ec2FetchedInstances = [];

    /**
     * Open the Register VM form for a specific group
     */
    function openVmForm(groupId) {
        const group = window.VmRegistryState.currentGroups.find(g => g.groupId === groupId);
        if (!group) {
            Notifications.error('Group not found');
            return;
        }
        // Reset form
        $('#registerVmForm')[0].reset();
        $('#registerVmForm').removeClass('was-validated');
        $('#vmId').val('');
        $('#vmGroupId').val(groupId);
        $('#vmGroupLabel').text(group.displayName);
        $('#vmSequencePosition').val(1);
        $('#registerVmModalLabel').text(`Register VM in "${group.displayName}"`);
        $('#btnSubmitVm').html('<i class="fas fa-save"></i> Register VM');

        // Reset EC2 picker
        _ec2FetchedInstances = [];
        $('#ec2InstancesList').html(`
            <div class="text-center text-muted py-3">
                <i class="fab fa-aws fa-2x mb-2 d-block text-warning opacity-50"></i>
                Select a region and click "Fetch Instances" to browse available EC2 instances.
            </div>
        `);
        $('#ec2SearchFilter').val('').hide();

        // Populate region dropdown from Config
        const regionSelect = $('#ec2Region');
        regionSelect.empty();
        Config.AWS_REGIONS.forEach(r => {
            regionSelect.append(`<option value="${r.value}">${r.label} (${r.value})</option>`);
        });

        // Re-enable fields in case they were set readonly from a previous import
        $('#vmProvider').prop('disabled', false);
        $('#vmRegion').prop('readonly', false);
        $('#vmProviderVmId').prop('readonly', false);

        new bootstrap.Modal(document.getElementById('registerVmModal')).show();
    }

    /**
     * Fetch EC2 instances from AWS for the selected region
     */
    async function fetchEc2Instances() {
        const region = $('#ec2Region').val();
        if (!region) {
            Notifications.error('Please select a region');
            return;
        }

        try {
            $('#btnFetchEc2').prop('disabled', true).html('<i class="fas fa-spinner fa-spin me-1"></i> Fetching...');
            $('#ec2InstancesList').html(`
                <div class="text-center py-3">
                    <div class="spinner-border spinner-border-sm text-warning me-2" role="status"></div>
                    Fetching EC2 instances from <strong>${region}</strong>...
                </div>
            `);

            // Fetch EC2 instances and globally registered IDs in parallel
            const [instances, registeredIdsArray] = await Promise.all([
                ApiClient.get(Config.API.ec2.listInstances(region)),
                ApiClient.get(Config.API.ec2.registeredIds)
            ]);

            const registeredVmIds = new Set(registeredIdsArray);
            const STALE_STATES = new Set(['terminated', 'shutting-down']);

            _ec2FetchedInstances = instances.map(inst => {
                const isRegistered = registeredVmIds.has(inst.instanceId);
                const isStale = isRegistered && STALE_STATES.has(inst.state);
                return {
                    ...inst,
                    _registered: isRegistered,
                    _stale: isStale,
                    _status: isStale ? 'stale' : isRegistered ? 'registered' : 'available',
                    _name: (inst.tags && inst.tags.Name) || ''
                };
            });

            renderEc2Instances(_ec2FetchedInstances);
            $('#ec2SearchFilter').show();
            $('#btnFetchEc2').prop('disabled', false).html('<i class="fab fa-aws me-1"></i> Fetch Instances');
        } catch (error) {
            console.error('Failed to fetch EC2 instances:', error);
            $('#ec2InstancesList').html(`
                <div class="text-center text-danger py-3">
                    <i class="fas fa-exclamation-triangle me-1"></i> Failed to fetch instances. Check AWS credentials and region.
                </div>
            `);
            $('#btnFetchEc2').prop('disabled', false).html('<i class="fab fa-aws me-1"></i> Fetch Instances');
        }
    }

    /**
     * Render the fetched EC2 instances list with color-coded status:
     *   - Available (light green): not registered anywhere
     *   - In Use (light yellow): registered and active in the platform
     *   - Stale (light red): registered but EC2 instance is terminated/shutting-down
     */
    function renderEc2Instances(instances) {
        const container = $('#ec2InstancesList');

        if (!instances || instances.length === 0) {
            container.html(`
                <div class="text-center text-muted py-3">
                    <i class="fas fa-inbox fa-2x mb-2 d-block"></i>
                    No EC2 instances found in this region.
                </div>
            `);
            return;
        }

        const available = instances.filter(i => i._status === 'available');
        const registered = instances.filter(i => i._status === 'registered');
        const stale = instances.filter(i => i._status === 'stale');

        const rows = instances.map(inst => {
            const name = Utils.escapeHtml(inst._name || '-');
            const stateClass = inst.state === 'running' ? 'text-success' :
                              inst.state === 'stopped' ? 'text-danger' :
                              inst.state === 'terminated' ? 'text-decoration-line-through text-muted' : 'text-muted';

            // Background colors and badges based on 3-state status
            let rowBg, rowClass, badge, clickable;
            if (inst._status === 'stale') {
                rowBg = 'background-color: #fde8e8;';
                rowClass = '';
                badge = '<span class="badge bg-danger"><i class="fas fa-exclamation-triangle me-1"></i>Stale</span>';
                clickable = false;
            } else if (inst._status === 'registered') {
                rowBg = 'background-color: #fef9e7;';
                rowClass = '';
                badge = '<span class="badge bg-warning text-dark"><i class="fas fa-check me-1"></i>In Use</span>';
                clickable = false;
            } else {
                rowBg = 'background-color: #eafaf1;';
                rowClass = 'ec2-selectable-row';
                badge = '<button class="btn btn-sm btn-outline-success">Select</button>';
                clickable = true;
            }

            return `
                <tr class="${rowClass}"
                    style="${rowBg} ${clickable ? 'cursor:pointer;' : ''}"
                    ${clickable ? `onclick="Features.selectEc2Instance('${inst.instanceId}')"` : ''}
                    data-instance-id="${inst.instanceId}"
                    data-instance-name="${Utils.escapeHtml(inst._name || '')}">
                    <td>
                        <strong>${name}</strong>
                        <div class="small text-muted">${Utils.escapeHtml(inst.instanceId)}</div>
                    </td>
                    <td>${Utils.escapeHtml(inst.instanceType || '-')}</td>
                    <td><span class="${stateClass}"><i class="fas fa-circle fa-xs me-1"></i>${inst.state}</span></td>
                    <td>${Utils.escapeHtml(inst.privateIpAddress || '-')}</td>
                    <td>${badge}</td>
                </tr>
            `;
        }).join('');

        container.html(`
            <div class="d-flex justify-content-between align-items-center mb-2">
                <div class="small text-muted">
                    <strong>${instances.length}</strong> instances found
                </div>
                <div class="d-flex gap-3 small">
                    <span><i class="fas fa-square" style="color: #b7f0cd;"></i> Available (${available.length})</span>
                    <span><i class="fas fa-square" style="color: #f9e79f;"></i> In Use (${registered.length})</span>
                    <span><i class="fas fa-square" style="color: #f5b7b1;"></i> Stale (${stale.length})</span>
                </div>
            </div>
            <div class="table-responsive" style="max-height: 250px; overflow-y: auto;">
                <table class="table table-sm mb-0">
                    <thead class="table-light sticky-top">
                        <tr>
                            <th>Name / Instance ID</th>
                            <th>Type</th>
                            <th>State</th>
                            <th>Private IP</th>
                            <th style="width:100px;"></th>
                        </tr>
                    </thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
        `);
    }

    /**
     * Filter the fetched EC2 instances by search text
     */
    function filterEc2Instances(searchText) {
        if (!_ec2FetchedInstances || _ec2FetchedInstances.length === 0) return;
        if (!searchText) {
            renderEc2Instances(_ec2FetchedInstances);
            return;
        }
        const term = searchText.toLowerCase();
        const filtered = _ec2FetchedInstances.filter(inst =>
            (inst._name && inst._name.toLowerCase().includes(term)) ||
            inst.instanceId.toLowerCase().includes(term) ||
            (inst.privateIpAddress && inst.privateIpAddress.includes(term))
        );
        renderEc2Instances(filtered);
    }

    /**
     * Select an EC2 instance and auto-fill the VM form
     */
    function selectEc2Instance(instanceId) {
        const inst = _ec2FetchedInstances.find(i => i.instanceId === instanceId);
        if (!inst || inst._registered) return;

        const region = $('#ec2Region').val();
        const name = inst._name || inst.instanceId;

        // Auto-fill form fields
        $('#vmProvider').val('AWS').prop('disabled', true);
        $('#vmRegion').val(region).prop('readonly', true);
        $('#vmProviderVmId').val(inst.instanceId).prop('readonly', true);
        $('#vmName').val(name.toLowerCase().replace(/[^a-z0-9-]/g, '-').replace(/-+/g, '-'));
        $('#vmDisplayName').val(name);
        $('#vmDescription').val(`${inst.instanceType || ''} | ${inst.privateIpAddress || ''} | ${inst.state || ''}`.trim());

        // Highlight selected row
        $('#ec2InstancesList .ec2-selectable-row').removeClass('table-success');
        $(`#ec2InstancesList tr[data-instance-id="${instanceId}"]`).addClass('table-success');

        // Scroll to form
        document.getElementById('registerVmForm').scrollIntoView({ behavior: 'smooth', block: 'start' });

        Notifications.success(`Selected: ${name} (${inst.instanceId})`);
    }

    /**
     * Submit the Register VM form
     */
    async function submitVm() {
        // Re-enable disabled/readonly fields so values are collected
        const providerDisabled = $('#vmProvider').prop('disabled');
        const regionReadonly = $('#vmRegion').prop('readonly');
        const providerVmIdReadonly = $('#vmProviderVmId').prop('readonly');
        $('#vmProvider').prop('disabled', false);
        $('#vmRegion').prop('readonly', false);
        $('#vmProviderVmId').prop('readonly', false);

        const form = $('#registerVmForm')[0];
        if (!form.checkValidity()) {
            form.classList.add('was-validated');
            // Restore states
            $('#vmProvider').prop('disabled', providerDisabled);
            $('#vmRegion').prop('readonly', regionReadonly);
            $('#vmProviderVmId').prop('readonly', providerVmIdReadonly);
            return;
        }
        const groupId = $('#vmGroupId').val();
        const vmName = $('#vmName').val().trim();
        const vmDisplayName = $('#vmDisplayName').val().trim();
        const data = {
            groupId: groupId,
            name: vmName,
            displayName: vmDisplayName || vmName,
            description: $('#vmDescription').val().trim() || null,
            provider: $('#vmProvider').val(),
            region: $('#vmRegion').val().trim(),
            providerVmId: $('#vmProviderVmId').val().trim(),
            sequencePosition: parseInt($('#vmSequencePosition').val()) || 1
        };
        try {
            Loading.show('Registering VM...');
            const envId = window.VmRegistryState.currentEnvironment.environmentId;
            await ApiClient.post(`/api/v1/environments/${envId}/vms`, data);
            Notifications.success(`VM "${vmName}" registered successfully`);
            bootstrap.Modal.getInstance(document.getElementById('registerVmModal')).hide();
            // Refresh groups content in the already-open modal
            await refreshGroupsModal();
            // Also refresh the environments list to update VM counts
            await loadEnvironmentsData();
            Loading.hide();
        } catch (error) {
            console.error('Failed to register VM:', error);
            // Restore states
            $('#vmProvider').prop('disabled', providerDisabled);
            $('#vmRegion').prop('readonly', regionReadonly);
            $('#vmProviderVmId').prop('readonly', providerVmIdReadonly);
            const msg = error?.responseJSON?.message;
            if (!msg) {
                if (error?.status === 403) {
                    // Already notified by ApiClient
                } else if (error?.status === 400) {
                    Notifications.error('Validation failed. Please check your input.');
                }
            }
            Loading.hide();
        }
    }

    /**
     * Refresh the Manage Groups modal content without re-opening it
     */
    async function refreshGroupsModal() {
        const envId = window.VmRegistryState.currentEnvironment.environmentId;
        const envName = window.VmRegistryState.currentEnvironment.environmentName;
        const groupsWithVms = await ApiClient.get(`/api/v1/environments/${envId}/vms`);
        window.VmRegistryState.currentGroupsWithVms = groupsWithVms;
        window.VmRegistryState.currentGroups = groupsWithVms.map(gv => gv.group);
        renderGroupsModal(envName, groupsWithVms);
    }

    /**
     * Delete a VM from a group
     */
    async function deleteVm(vmId, vmName) {
        if (!confirm(`Are you sure you want to remove VM "${vmName}"? This only unregisters it from the platform.`)) {
            return;
        }
        try {
            Loading.show('Removing VM...');
            const envId = window.VmRegistryState.currentEnvironment.environmentId;
            await ApiClient.delete(`/api/v1/environments/${envId}/vms/${vmId}`);
            Notifications.success(`VM "${vmName}" removed`);
            await refreshGroupsModal();
            await loadEnvironmentsData();
            Loading.hide();
        } catch (error) {
            console.error('Failed to delete VM:', error);
            const msg = error?.responseJSON?.message;
            if (!msg) {
                Notifications.error('Failed to remove VM');
            }
            Loading.hide();
        }
    }

    /**
     * Load Automation Rules view
     */
    function loadAutomationRules() {
        const html = `
            <div class="content-header">
                <h1>Automation Rules</h1>
                <p>Schedule automatic start/stop operations</p>
            </div>

            <div class="mb-3">
                <button class="btn btn-primary"><i class="fas fa-plus"></i> Create Rule</button>
            </div>

            <h5 class="mb-3">Active Rules (3)</h5>

            <div class="card mb-3">
                <div class="card-body">
                    <div class="d-flex justify-content-between align-items-start">
                        <div>
                            <h5 class="card-title">Nightly Shutdown</h5>
                            <p class="mb-1"><strong>Environment:</strong> mcube-demo-env</p>
                            <p class="mb-1"><strong>Action:</strong> <span class="badge bg-danger">STOP_ALL</span></p>
                            <p class="mb-1"><strong>Schedule:</strong> Every day at 7:00 PM EST</p>
                            <p class="mb-1"><strong>Status:</strong> <span class="badge bg-success">Active</span></p>
                            <p class="mb-1 text-muted">Last Run: Today at 7:00 PM (Success)</p>
                        </div>
                        <div>
                            <button class="btn btn-sm btn-primary me-1">Edit</button>
                            <button class="btn btn-sm btn-warning me-1">Disable</button>
                            <button class="btn btn-sm btn-danger">Delete</button>
                        </div>
                    </div>
                </div>
            </div>

            <div class="card mb-3">
                <div class="card-body">
                    <div class="d-flex justify-content-between align-items-start">
                        <div>
                            <h5 class="card-title">Morning Startup</h5>
                            <p class="mb-1"><strong>Environment:</strong> mcube-demo-env</p>
                            <p class="mb-1"><strong>Action:</strong> <span class="badge bg-success">START_ALL</span></p>
                            <p class="mb-1"><strong>Schedule:</strong> Weekdays at 8:00 AM EST</p>
                            <p class="mb-1"><strong>Status:</strong> <span class="badge bg-success">Active</span></p>
                            <p class="mb-1 text-muted">Last Run: Today at 8:00 AM (Success)</p>
                        </div>
                        <div>
                            <button class="btn btn-sm btn-primary me-1">Edit</button>
                            <button class="btn btn-sm btn-warning me-1">Disable</button>
                            <button class="btn btn-sm btn-danger">Delete</button>
                        </div>
                    </div>
                </div>
            </div>
        `;
        $('#content-area').html(html);
    }

    /**
     * Load System Health view
     */
    function loadSystemHealth() {
        const html = `
            <div class="content-header">
                <h1>System Health</h1>
                <p>Monitor platform health and state synchronization</p>
            </div>

            <div class="row mb-4">
                <div class="col-md-3">
                    <div class="metric-card">
                        <div class="metric-title">API Status</div>
                        <div class="metric-value text-success">
                            <i class="fas fa-check-circle"></i>
                        </div>
                        <div class="metric-subtitle">All services healthy</div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="metric-card">
                        <div class="metric-title">Last State Sync</div>
                        <div class="metric-value">2m ago</div>
                        <div class="metric-subtitle">47 VMs synced</div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="metric-card">
                        <div class="metric-title">Drift Detected</div>
                        <div class="metric-value text-warning">2</div>
                        <div class="metric-subtitle">In last 24 hours</div>
                    </div>
                </div>
                <div class="col-md-3">
                    <div class="metric-card">
                        <div class="metric-title">Pending Operations</div>
                        <div class="metric-value">0</div>
                        <div class="metric-subtitle">All operations complete</div>
                    </div>
                </div>
            </div>

            <h5 class="mb-3">Cloud Provider Connectivity</h5>
            <div class="custom-table mb-4">
                <table class="table">
                    <thead>
                        <tr>
                            <th>Provider</th>
                            <th>Region</th>
                            <th>Status</th>
                            <th>Last Check</th>
                            <th>Response Time</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr>
                            <td><i class="fab fa-aws text-warning"></i> AWS</td>
                            <td>us-east-1</td>
                            <td><span class="badge bg-success">Connected</span></td>
                            <td>30s ago</td>
                            <td>45ms</td>
                        </tr>
                        <tr>
                            <td><i class="fab fa-aws text-warning"></i> AWS</td>
                            <td>eu-west-1</td>
                            <td><span class="badge bg-success">Connected</span></td>
                            <td>30s ago</td>
                            <td>120ms</td>
                        </tr>
                        <tr>
                            <td><i class="fab fa-microsoft text-primary"></i> Azure</td>
                            <td>East US</td>
                            <td><span class="badge bg-success">Connected</span></td>
                            <td>30s ago</td>
                            <td>55ms</td>
                        </tr>
                        <tr>
                            <td><i class="fab fa-google text-info"></i> GCP</td>
                            <td>us-central1</td>
                            <td><span class="badge bg-warning">Degraded</span></td>
                            <td>30s ago</td>
                            <td>350ms</td>
                        </tr>
                    </tbody>
                </table>
            </div>

            <div class="d-flex gap-2">
                <button class="btn btn-primary"><i class="fas fa-sync"></i> Trigger State Sync</button>
                <button class="btn btn-secondary"><i class="fas fa-download"></i> Download Health Report</button>
            </div>
        `;
        $('#content-area').html(html);
    }

    function showLoading() {
        $('#content-area').html(`
            <div class="loading-state">
                <div class="spinner-border text-primary" role="status"></div>
                <p>Loading...</p>
            </div>
        `);
    }

    /**
     * Load User Management view (Admin only)
     */
    function loadUserManagement() {
        if (!Auth.isAdmin()) {
            $('#content-area').html('<div class="alert alert-danger">Access denied. Admin only.</div>');
            return;
        }

        showLoading();

        ApiClient.get(Config.API.users.list)
            .done(function(users) {
                const html = buildUserManagementHtml(users);
                $('#content-area').html(html);
                bindUserManagementEvents();
            })
            .fail(function() {
                $('#content-area').html(`
                    <div class="content-header">
                        <h1>User Management</h1>
                    </div>
                    <div class="alert alert-danger">Failed to load users</div>
                `);
            });
    }

    function buildUserManagementHtml(users) {
        const rows = users.map(user => {
            const roleClass = user.admin ? 'role-badge-admin' :
                             user.envAdmin ? 'role-badge-env-admin' : 'role-badge-user';
            const roleLabel = user.admin ? 'Admin' :
                             user.envAdmin ? 'Env Admin' : 'User';

            return `
                <tr>
                    <td>
                        <strong>${Utils.escapeHtml(user.displayName)}</strong>
                        <br><small class="text-muted">${Utils.escapeHtml(user.email)}</small>
                    </td>
                    <td><span class="role-badge ${roleClass}">${roleLabel}</span></td>
                    <td>
                        <span class="badge ${user.active ? 'bg-success' : 'bg-secondary'}">
                            ${user.active ? 'Active' : 'Inactive'}
                        </span>
                    </td>
                    <td>${user.lastLoginAt ? Utils.formatRelativeTime(user.lastLoginAt) : 'Never'}</td>
                    <td>
                        <div class="btn-group btn-group-sm">
                            <button class="btn btn-outline-primary dropdown-toggle" data-bs-toggle="dropdown">
                                Actions
                            </button>
                            <ul class="dropdown-menu">
                                <li>
                                    <a class="dropdown-item" href="#" data-user-id="${user.userId}" data-action="toggle-admin">
                                        ${user.admin ? 'Remove Admin' : 'Make Admin'}
                                    </a>
                                </li>
                                <li>
                                    <a class="dropdown-item" href="#" data-user-id="${user.userId}" data-action="toggle-env-admin">
                                        ${user.envAdmin ? 'Remove Env Admin' : 'Make Env Admin'}
                                    </a>
                                </li>
                                <li><hr class="dropdown-divider"></li>
                                <li>
                                    <a class="dropdown-item ${user.active ? 'text-danger' : 'text-success'}" href="#"
                                       data-user-id="${user.userId}" data-action="${user.active ? 'deactivate' : 'reactivate'}">
                                        ${user.active ? 'Deactivate' : 'Reactivate'}
                                    </a>
                                </li>
                            </ul>
                        </div>
                    </td>
                </tr>
            `;
        }).join('');

        return `
            <div class="content-header">
                <h1>User Management</h1>
                <p>Manage users and their roles</p>
            </div>
            <div class="mb-3">
                <input type="text" class="form-control" id="user-search" placeholder="Search users...">
            </div>
            <div class="custom-table">
                <table class="table table-hover">
                    <thead>
                        <tr>
                            <th>User</th>
                            <th>Role</th>
                            <th>Status</th>
                            <th>Last Login</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody id="users-table-body">
                        ${rows}
                    </tbody>
                </table>
            </div>
        `;
    }

    function bindUserManagementEvents() {
        $('#user-search').off('input').on('input', Utils.debounce(function() {
            const query = $(this).val().toLowerCase();
            $('#users-table-body tr').each(function() {
                const text = $(this).text().toLowerCase();
                $(this).toggle(text.includes(query));
            });
        }, 300));

        $('[data-action="toggle-admin"]').off('click').on('click', function(e) {
            e.preventDefault();
            const userId = $(this).data('user-id');
            toggleAdmin(userId);
        });

        $('[data-action="toggle-env-admin"]').off('click').on('click', function(e) {
            e.preventDefault();
            const userId = $(this).data('user-id');
            toggleEnvAdmin(userId);
        });

        $('[data-action="deactivate"]').off('click').on('click', function(e) {
            e.preventDefault();
            const userId = $(this).data('user-id');
            if (confirm('Deactivate this user?')) {
                deactivateUser(userId);
            }
        });

        $('[data-action="reactivate"]').off('click').on('click', function(e) {
            e.preventDefault();
            const userId = $(this).data('user-id');
            reactivateUser(userId);
        });
    }

    function toggleAdmin(userId) {
        ApiClient.post(Config.API.users.setAdmin(userId))
            .done(function() {
                Notifications.success('Admin status updated');
                loadUserManagement();
            })
            .fail(function(xhr) {
                Notifications.error(xhr.responseJSON?.message || 'Failed to update admin status');
            });
    }

    function toggleEnvAdmin(userId) {
        ApiClient.post(Config.API.users.setEnvAdmin(userId))
            .done(function() {
                Notifications.success('Env admin status updated');
                loadUserManagement();
            })
            .fail(function(xhr) {
                Notifications.error(xhr.responseJSON?.message || 'Failed to update env admin status');
            });
    }

    function deactivateUser(userId) {
        ApiClient.post(Config.API.users.deactivate(userId))
            .done(function() {
                Notifications.success('User deactivated');
                loadUserManagement();
            })
            .fail(function(xhr) {
                Notifications.error(xhr.responseJSON?.message || 'Failed to deactivate user');
            });
    }

    function reactivateUser(userId) {
        ApiClient.post(Config.API.users.reactivate(userId))
            .done(function() {
                Notifications.success('User reactivated');
                loadUserManagement();
            })
            .fail(function(xhr) {
                Notifications.error(xhr.responseJSON?.message || 'Failed to reactivate user');
            });
    }

    return {
        loadAccessManagement,
        loadVmRegistry,
        loadEnvironmentsData,
        renderEnvironmentsList,
        buildEnvironmentRow,
        updateVmRegistryStats,
        filterEnvironments,
        openCreateEnvironmentModal,
        editEnvironment,
        submitEnvironment,
        deleteEnvironment,
        manageGroups,
        renderGroupsModal,
        buildGroupCard,
        openGroupForm,
        populateDependencyDropdown,
        submitGroup,
        editGroup,
        deleteGroup,
        openVmForm,
        submitVm,
        deleteVm,
        refreshGroupsModal,
        fetchEc2Instances,
        filterEc2Instances,
        selectEc2Instance,
        loadAutomationRules,
        loadSystemHealth,
        loadUserManagement
    };
})();

