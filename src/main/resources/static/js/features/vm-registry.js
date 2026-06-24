/**
 * VM Registry Feature
 * Admin > VM Registry — manage environments, groups, and VMs.
 * Extracted from features.js as a dedicated module.
 */

const VmRegistry = (function() {
    'use strict';

    // Cache for fetched EC2 instances (used for client-side filtering)
    let _ec2FetchedInstances = [];

    // =========================================================================
    // Entry Point
    // =========================================================================

    function load() {
        window.VmRegistryState = {
            environments: [],
            filtered: [],
            currentEnvironment: null,
            currentGroups: [],
            currentPage: 1,
            PAGE_SIZE: 8
        };

        renderVmRegistry();
        loadEnvironmentsData();
    }

    // =========================================================================
    // Template
    // =========================================================================

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
                            <button class="btn btn-primary" onclick="VmRegistry.openCreateEnvironmentModal()">
                                <i class="fas fa-plus"></i> Create Environment
                            </button>
                        </div>
                    </div>
                </div>

                <div class="row mb-3">
                    <div class="col-md-6">
                        <div class="input-group">
                            <span class="input-group-text"><i class="fas fa-search"></i></span>
                            <input type="text" class="form-control" id="searchEnvironments" placeholder="Search environments..." oninput="VmRegistry._searchRegistry(this.value)">
                        </div>
                    </div>
                    <div class="col-md-3">
                        <select class="form-select" id="filterStatus" onchange="VmRegistry.loadEnvironmentsData()">
                            <option value="active">Active Only</option>
                            <option value="all">All Environments</option>
                        </select>
                    </div>
                    <div class="col-md-3">
                        <button class="btn btn-outline-secondary w-100" onclick="VmRegistry.loadEnvironmentsData()">
                            <i class="fas fa-sync-alt"></i> Refresh
                        </button>
                    </div>
                </div>

                <div class="card">
                    <div class="card-body vm-registry-card-body">
                        <div id="environmentsTableContainer" class="vm-registry-table-wrapper">
                            <div class="text-center py-5" id="environmentsLoading">
                                <div class="spinner-border text-primary" role="status"></div>
                                <p class="text-muted mt-2">Loading environments...</p>
                            </div>
                            <div class="text-center py-5 d-none" id="environmentsEmpty">
                                <i class="fas fa-folder-open fa-4x text-muted mb-3"></i>
                                <h5>No Environments Yet</h5>
                                <p class="text-muted">Create your first environment to get started</p>
                                <button class="btn btn-primary" onclick="VmRegistry.openCreateEnvironmentModal()">
                                    <i class="fas fa-plus"></i> Create Environment
                                </button>
                            </div>
                            <div class="table-responsive d-none" id="environmentsTableWrapper">
                                <table class="table table-hover mb-0" id="environmentsTable">
                                    <thead class="table-light sticky-top">
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
                        <div id="vm-registry-pagination" class="vm-registry-pagination"></div>
                    </div>
                </div>
            </div>
        `;
        $('#content-area').html(html);
    }

    // =========================================================================
    // Data Loading
    // =========================================================================

    async function loadEnvironmentsData() {
        try {
            $('#environmentsLoading').removeClass('d-none');
            $('#environmentsEmpty').addClass('d-none');
            $('#environmentsTableWrapper').addClass('d-none');

            const includeInactive = $('#filterStatus').val() === 'all';
            const environments = await ApiClient.get(`/api/v1/environments?includeInactive=${includeInactive}`);

            window.VmRegistryState.environments = environments;
            window.VmRegistryState.filtered = environments;
            window.VmRegistryState.currentPage = 1;
            renderEnvironmentsList();
            updateVmRegistryStats(environments);
        } catch (error) {
            console.error('Failed to load environments:', error);
            Notifications.error('Failed to load environments');
            $('#environmentsLoading').addClass('d-none');
            $('#environmentsEmpty').removeClass('d-none');
        }
    }

    // =========================================================================
    // Table Rendering
    // =========================================================================

    function renderEnvironmentsList() {
        const state = window.VmRegistryState;
        const list = state.filtered || [];
        const total = list.length;
        const pageSize = state.PAGE_SIZE || 10;
        const totalPages = Math.max(1, Math.ceil(total / pageSize));
        if (state.currentPage > totalPages) state.currentPage = totalPages;
        if (state.currentPage < 1) state.currentPage = 1;

        $('#environmentsLoading').addClass('d-none');
        if (!total) {
            $('#environmentsEmpty').removeClass('d-none');
            $('#environmentsTableWrapper').addClass('d-none');
            $('#vm-registry-pagination').html(`<div class="text-muted small">Showing 0 environments</div>`);
            return;
        }

        $('#environmentsEmpty').addClass('d-none');
        $('#environmentsTableWrapper').removeClass('d-none');

        const tbody = $('#environmentsTableBody');
        tbody.empty();
        const start = (state.currentPage - 1) * pageSize;
        const slice = list.slice(start, start + pageSize);
        slice.forEach(env => tbody.append(buildEnvironmentRow(env)));

        // Filler row expands via CSS height:100% to fill leftover space (same as Dashboard)
        if (slice.length < pageSize) {
            tbody.append('<tr class="vm-registry-filler-row"><td colspan="7"></td></tr>');
        }

        renderVmRegistryPagination(total, state.currentPage, pageSize);
        $('#vm-registry-pagination').off('click', '.vm-reg-page').on('click', '.vm-reg-page', function() {
            const target = parseInt($(this).data('page'));
            if (!target || target < 1) return;
            const maxPage = Math.ceil(total / pageSize) || 1;
            if (target > maxPage) return;
            state.currentPage = target;
            renderEnvironmentsList();
        });
    }

    function buildEnvironmentRow(env) {
        const statusBadge = env.isActive
            ? '<span class="badge bg-success">Active</span>'
            : '<span class="badge bg-secondary">Inactive</span>';
        const createdDate = env.createdAt ? new Date(env.createdAt).toLocaleDateString() : 'N/A';
        const description = env.description || '<span class="text-muted">No description</span>';
        const displayName = Utils.escapeHtml(env.displayName);
        const name = Utils.escapeHtml(env.name);
        const tooltip = `Environment: ${displayName}${name && displayName !== name ? ` (${name})` : ''}`;

        return `
            <tr data-env-id="${env.environmentId}" title="${tooltip}">
                <td>
                    <div><strong>${displayName}</strong></div>
                </td>
                <td>${description}</td>
                <td class="text-center"><span class="badge bg-primary">${env.groupCount || 0}</span></td>
                <td class="text-center"><span class="badge bg-info">${env.vmCount || 0}</span></td>
                <td class="text-center">${statusBadge}</td>
                <td>${createdDate}</td>
                <td class="text-end">
                    <button class="btn btn-sm btn-primary" title="${tooltip}" onclick="VmRegistry.manageGroups('${env.environmentId}', '${displayName.replace(/'/g, "\\'")}')">
                        <i class="fas fa-layer-group"></i> Groups
                    </button>
                    <button class="btn btn-sm btn-warning" onclick="VmRegistry.editEnvironment('${env.environmentId}')" title="Edit ${tooltip}">
                        <i class="fas fa-edit"></i>
                    </button>
                    <button class="btn btn-sm btn-danger" onclick="VmRegistry.deleteEnvironment('${env.environmentId}')" title="Delete ${tooltip}">
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

    function renderVmRegistryPagination(totalItems, page, pageSize) {
        const totalPages = Math.max(1, Math.ceil(totalItems / pageSize));
        if (totalItems <= pageSize) {
            $('#vm-registry-pagination').html(`<div class="text-muted small mt-1">Showing ${totalItems} environment${totalItems !== 1 ? 's' : ''}</div>`);
            return;
        }

        const start = (page - 1) * pageSize + 1;
        const end = Math.min(page * pageSize, totalItems);
        const rangeStart = Math.max(1, page - 2);
        const rangeEnd = Math.min(totalPages, page + 2);

        let pageButtons = '';
        for (let i = rangeStart; i <= rangeEnd; i++) {
            pageButtons += `<button class="btn btn-sm ${i === page ? 'btn-primary' : 'btn-outline-secondary'} vm-reg-page ms-1" data-page="${i}">${i}</button>`;
        }

        $('#vm-registry-pagination').html(`
            <div class="d-flex justify-content-between align-items-center">
                <span class="text-muted small">Showing ${start}–${end} of ${totalItems} environments</span>
                <div>
                    <button class="btn btn-sm btn-outline-secondary vm-reg-page" data-page="1" ${page === 1 ? 'disabled' : ''} title="First page">
                        <i class="fas fa-angle-double-left"></i>
                    </button>
                    <button class="btn btn-sm btn-outline-secondary vm-reg-page ms-1" data-page="${page - 1}" ${page === 1 ? 'disabled' : ''} title="Previous page">
                        <i class="fas fa-chevron-left"></i>
                    </button>
                    ${pageButtons}
                    <button class="btn btn-sm btn-outline-secondary vm-reg-page ms-1" data-page="${page + 1}" ${page === totalPages ? 'disabled' : ''} title="Next page">
                        <i class="fas fa-chevron-right"></i>
                    </button>
                    <button class="btn btn-sm btn-outline-secondary vm-reg-page ms-1" data-page="${totalPages}" ${page === totalPages ? 'disabled' : ''} title="Last page">
                        <i class="fas fa-angle-double-right"></i>
                    </button>
                </div>
            </div>
        `);
    }

    // =========================================================================
    // Search & Filter
    // =========================================================================

    function filterEnvironments(searchTerm) {
        const state = window.VmRegistryState;
        if (!searchTerm) {
            state.filtered = state.environments;
            state.currentPage = 1;
            renderEnvironmentsList();
            return;
        }
        const term = searchTerm.toLowerCase();
        state.filtered = state.environments.filter(env =>
            (env.name || '').toLowerCase().includes(term) ||
            (env.displayName || '').toLowerCase().includes(term) ||
            (env.description && env.description.toLowerCase().includes(term))
        );
        state.currentPage = 1;
        renderEnvironmentsList();
    }

    // =========================================================================
    // Environment CRUD
    // =========================================================================

    function openCreateEnvironmentModal() {
        Modals.showCreateEnvironment(function() { loadEnvironmentsData(); });
    }

    async function editEnvironment(environmentId) {
        const cached = window.VmRegistryState.environments.find(e => e.environmentId === environmentId);
        if (cached) {
            Modals.showEditEnvironment(cached, function() { loadEnvironmentsData(); });
            return;
        }
        try {
            Loading.show('Loading environment...');
            const env = await ApiClient.get(`/api/v1/environments/${environmentId}`);
            Loading.hide();
            Modals.showEditEnvironment(env, function() { loadEnvironmentsData(); });
        } catch (error) {
            console.error('Failed to load environment:', error);
            Notifications.error('Failed to load environment');
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

    // =========================================================================
    // Group Management
    // =========================================================================

    async function manageGroups(environmentId, environmentName) {
        const envRecord = window.VmRegistryState.environments.find(e => e.environmentId === environmentId);
        window.VmRegistryState.currentEnvironment = {
            environmentId,
            environmentName,
            serviceType: envRecord?.serviceType || 'EC2'
        };
        try {
            Loading.show('Loading groups and VMs...');
            const groupsWithVms = await ApiClient.get(`/api/v1/environments/${environmentId}/vms`);
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
        const isEks = (window.VmRegistryState.currentEnvironment?.serviceType || 'EC2') === 'EKS';
        $('#manageGroupsModalLabel').text(`Manage Groups & VMs - ${environmentName}`);

        // Update the Add Group button in the modal header (defined in index.html)
        const $addGroupBtn = $('#manageGroupsModal .modal-body .d-flex button');
        if (isEks) {
            $addGroupBtn.hide();
            // Show EKS info banner above groups
            $('#cem-eks-groups-banner').remove();
            $('#groupsContentArea').before(`
                <div id="cem-eks-groups-banner" class="alert alert-info d-flex align-items-center gap-2 py-2 mb-3" style="font-size:0.875rem;">
                    <i class="fas fa-info-circle"></i>
                    <span>Node groups are auto-synced from AWS EKS. You can edit the sequence order but cannot add or delete groups manually.</span>
                </div>
            `);
        } else {
            $addGroupBtn.show();
            $('#cem-eks-groups-banner').remove();
        }

        const container = $('#groupsContentArea');
        container.empty();
        if (!groupsWithVms || groupsWithVms.length === 0) {
            container.html(`
                <div class="text-center text-muted py-5">
                    <i class="fas fa-inbox fa-3x mb-3 d-block"></i>
                    <p>${isEks ? 'No node groups synced yet. Run an EKS sync to populate groups.' : 'No groups yet. Add a group to get started.'}</p>
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

        const isEks = (window.VmRegistryState.currentEnvironment?.serviceType || 'EC2') === 'EKS';

        const vmRows = vms.map(vm => {
            const providerLabels = { AWS: 'AWS', AZURE: 'Azure', GCP: 'GCP', OCI: 'OCI', AWS_EKS: 'EKS' };
            const providerIcons = { AWS: 'fab fa-aws', AZURE: 'fab fa-microsoft', GCP: 'fab fa-google', OCI: 'fas fa-cloud', AWS_EKS: 'fas fa-dharmachakra' };
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
                        ${!isEks ? `
                        <button class="btn btn-sm btn-outline-danger" onclick="VmRegistry.deleteVm('${vm.vmId}', '${Utils.escapeHtml(vm.name)}')" title="Remove VM">
                            <i class="fas fa-trash"></i>
                        </button>` : `
                        <span class="text-muted small" title="EKS node groups are managed by sync">
                            <i class="fas fa-sync-alt"></i>
                        </span>`}
                    </td>
                </tr>
            `;
        }).join('');

        const collapseId = `collapse-${group.groupId}`;

        const actionBtns = isEks
            ? `<button class="btn btn-sm btn-warning" onclick="VmRegistry.editGroup('${group.groupId}')" title="Edit sequence / display name">
                   <i class="fas fa-edit"></i>
               </button>`
            : `<button class="btn btn-sm btn-success me-1" onclick="VmRegistry.openVmForm('${group.groupId}')" title="Register VM">
                   <i class="fas fa-plus"></i> VM
               </button>
               <button class="btn btn-sm btn-warning me-1" onclick="VmRegistry.editGroup('${group.groupId}')" title="Edit Group">
                   <i class="fas fa-edit"></i>
               </button>
               <button class="btn btn-sm btn-danger" onclick="VmRegistry.deleteGroup('${group.groupId}')" title="Delete Group"
                       ${vmCount > 0 ? 'disabled' : ''}>
                   <i class="fas fa-trash"></i>
               </button>`;

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
                            ${actionBtns}
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
                                <a href="#" onclick="VmRegistry.openVmForm('${group.groupId}'); return false;">Add one</a>
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
    // VM Registration
    // =========================================================================

    function openVmForm(groupId) {
        const group = window.VmRegistryState.currentGroups.find(g => g.groupId === groupId);
        if (!group) {
            Notifications.error('Group not found');
            return;
        }
        $('#registerVmForm')[0].reset();
        $('#registerVmForm').removeClass('was-validated');
        $('#vmId').val('');
        $('#vmGroupId').val(groupId);
        $('#vmGroupLabel').text(group.displayName);
        $('#vmSequencePosition').val(1);
        $('#registerVmModalLabel').text(`Register VM in "${group.displayName}"`);
        $('#btnSubmitVm').html('<i class="fas fa-save"></i> Register VM');

        _ec2FetchedInstances = [];
        $('#ec2InstancesList').html(`
            <div class="text-center text-muted py-3">
                <i class="fab fa-aws fa-2x mb-2 d-block text-warning opacity-50"></i>
                Select a region and click "Fetch Instances" to browse available EC2 instances.
            </div>
        `);
        $('#ec2SearchFilter').val('').hide();

        const regionSelect = $('#ec2Region');
        regionSelect.empty();
        Config.AWS_REGIONS.forEach(r => {
            regionSelect.append(`<option value="${r.value}">${r.label} (${r.value})</option>`);
        });

        $('#vmProvider').prop('disabled', false);
        $('#vmRegion').prop('readonly', false);
        $('#vmProviderVmId').prop('readonly', false);

        new bootstrap.Modal(document.getElementById('registerVmModal')).show();
    }

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
     * Render EC2 instances with 3-state colour coding:
     *   Available (green)  — not registered anywhere
     *   In Use   (yellow)  — registered and active
     *   Stale    (red)     — registered but instance is terminated/shutting-down
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
                    ${clickable ? `onclick="VmRegistry.selectEc2Instance('${inst.instanceId}')"` : ''}
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

    function selectEc2Instance(instanceId) {
        const inst = _ec2FetchedInstances.find(i => i.instanceId === instanceId);
        if (!inst || inst._registered) return;

        const region = $('#ec2Region').val();
        const name = inst._name || inst.instanceId;

        $('#vmProvider').val('AWS').prop('disabled', true);
        $('#vmRegion').val(region).prop('readonly', true);
        $('#vmProviderVmId').val(inst.instanceId).prop('readonly', true);
        $('#vmName').val(name.toLowerCase().replace(/[^a-z0-9-]/g, '-').replace(/-+/g, '-'));
        $('#vmDisplayName').val(name);
        $('#vmDescription').val(`${inst.instanceType || ''} | ${inst.privateIpAddress || ''} | ${inst.state || ''}`.trim());

        $('#ec2InstancesList .ec2-selectable-row').removeClass('table-success');
        $(`#ec2InstancesList tr[data-instance-id="${instanceId}"]`).addClass('table-success');
        document.getElementById('registerVmForm').scrollIntoView({ behavior: 'smooth', block: 'start' });
        Notifications.success(`Selected: ${name} (${inst.instanceId})`);
    }

    async function submitVm() {
        const providerDisabled = $('#vmProvider').prop('disabled');
        const regionReadonly = $('#vmRegion').prop('readonly');
        const providerVmIdReadonly = $('#vmProviderVmId').prop('readonly');
        $('#vmProvider').prop('disabled', false);
        $('#vmRegion').prop('readonly', false);
        $('#vmProviderVmId').prop('readonly', false);

        const form = $('#registerVmForm')[0];
        if (!form.checkValidity()) {
            form.classList.add('was-validated');
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
            await refreshGroupsModal();
            await loadEnvironmentsData();
            Loading.hide();
        } catch (error) {
            console.error('Failed to register VM:', error);
            $('#vmProvider').prop('disabled', providerDisabled);
            $('#vmRegion').prop('readonly', regionReadonly);
            $('#vmProviderVmId').prop('readonly', providerVmIdReadonly);
            const msg = error?.responseJSON?.message;
            if (!msg) {
                if (error?.status === 400) {
                    Notifications.error('Validation failed. Please check your input.');
                }
            }
            Loading.hide();
        }
    }

    async function refreshGroupsModal() {
        const envId = window.VmRegistryState.currentEnvironment.environmentId;
        const envName = window.VmRegistryState.currentEnvironment.environmentName;
        const groupsWithVms = await ApiClient.get(`/api/v1/environments/${envId}/vms`);
        window.VmRegistryState.currentGroupsWithVms = groupsWithVms;
        window.VmRegistryState.currentGroups = groupsWithVms.map(gv => gv.group);
        renderGroupsModal(envName, groupsWithVms);
    }

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
            if (!error?.responseJSON?.message) {
                Notifications.error('Failed to remove VM');
            }
            Loading.hide();
        }
    }

    // =========================================================================
    // Public API
    // =========================================================================

    return {
        load,
        loadEnvironmentsData,
        renderEnvironmentsList,
        buildEnvironmentRow,
        updateVmRegistryStats,
        filterEnvironments,
        openCreateEnvironmentModal,
        editEnvironment,
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
        fetchEc2Instances,
        filterEc2Instances,
        selectEc2Instance,
        submitVm,
        deleteVm,
        refreshGroupsModal,
        _searchRegistry: function(val) {
            filterEnvironments((val || '').trim());
        }
    };
})();

window.VmRegistry = VmRegistry;
