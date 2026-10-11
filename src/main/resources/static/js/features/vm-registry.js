/**
 * VM Registry Feature
 * Admin > VM Registry — manage environments, groups, and VMs.
 * Extracted from features.js as a dedicated module.
 */

const VmRegistry = (function() {
    'use strict';

    // Cache for fetched EC2 instances (used for client-side filtering)
    let _ec2FetchedInstances = [];

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

    // =========================================================================
    // Entry Point
    // =========================================================================

    // Navigation token of the current visit (see the page contract in core/router.js).
    let pageToken = null;

    function isActive() {
        return pageToken !== null && ContentRouter.isCurrent(pageToken);
    }

    /** Router loader. */
    function load() {
        pageToken = ContentRouter.token();
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
                            <button class="btn btn-primary" data-action="vr-create-environment">
                                <i class="fas fa-plus"></i> Create Environment
                            </button>
                        </div>
                    </div>
                </div>

                <div class="row mb-3">
                    <div class="col-md-6">
                        <div class="input-group">
                            <span class="input-group-text"><i class="fas fa-search"></i></span>
                            <input type="text" class="form-control" id="searchEnvironments" placeholder="Search environments..." data-action-input="vr-search">
                        </div>
                    </div>
                    <div class="col-md-3">
                        <select class="form-select" id="filterStatus" data-action-change="vr-reload">
                            <option value="active">Active Only</option>
                            <option value="all">All Environments</option>
                        </select>
                    </div>
                    <div class="col-md-3">
                        <button class="btn btn-ghost w-100" data-action="vr-reload">
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
                                <button class="btn btn-primary" data-action="vr-create-environment">
                                    <i class="fas fa-plus"></i> Create Environment
                                </button>
                            </div>
                            <div class="table-responsive d-none" id="environmentsTableWrapper">
                                <table class="table table-hover mb-0 table-cards" id="environmentsTable">
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
                        <div id="vm-registry-pagination" class="pagination-bar-wrap"></div>
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
            if (!isActive()) return;

            window.VmRegistryState.environments = environments;
            window.VmRegistryState.filtered = environments;
            window.VmRegistryState.currentPage = 1;
            renderEnvironmentsList();
            updateVmRegistryStats(environments);
        } catch (error) {
            if (!isActive()) return;
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
    }

    function buildEnvironmentRow(env) {
        const statusBadge = env.isActive
            ? '<span class="badge bg-success">Active</span>'
            : '<span class="badge bg-secondary">Inactive</span>';
        const createdDate = env.createdAt ? new Date(env.createdAt).toLocaleDateString() : 'N/A';
        const description = env.description ? Utils.escapeHtml(env.description) : '<span class="text-muted">No description</span>';
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
                <td class="text-end td-actions">
                    <button class="btn btn-sm btn-primary btn-action" title="${tooltip}" data-action="vr-manage-groups" data-env-id="${Utils.escapeHtml(env.environmentId)}">
                        <i class="fas fa-layer-group"></i> Groups
                    </button>
                    <button class="btn btn-sm btn-warning btn-action" data-action="vr-edit-environment" data-env-id="${Utils.escapeHtml(env.environmentId)}" title="Edit ${tooltip}">
                        <i class="fas fa-edit"></i>
                    </button>
                    ${env.isActive
                        ? `<button class="btn btn-sm btn-danger btn-action" data-action="vr-delete-environment" data-env-id="${Utils.escapeHtml(env.environmentId)}" title="Deactivate ${tooltip}">
                        <i class="fas fa-trash"></i>
                    </button>`
                        : `<button class="btn btn-sm btn-success btn-action" data-action="vr-reactivate-environment" data-env-id="${Utils.escapeHtml(env.environmentId)}" title="Reactivate ${tooltip}">
                        <i class="fas fa-rotate-left"></i>
                    </button>`}
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
        Pagination.renderNumbered('#vm-registry-pagination', {
            page,
            totalItems,
            pageSize,
            itemLabel: 'environments',
            onPageChange: (target) => {
                const maxPage = Math.ceil(totalItems / pageSize) || 1;
                if (target < 1 || target > maxPage) return;
                window.VmRegistryState.currentPage = target;
                renderEnvironmentsList();
            }
        });
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
        DestructiveConfirm.confirmDeleteEnvironment(env.displayName, env.vmCount || 0, async function() {
            try {
                Loading.show('Deactivating...');
                await ApiClient.delete(`/api/v1/environments/${environmentId}`);
                Notifications.success('Environment deactivated');
                await loadEnvironmentsData();
                Loading.hide();
            } catch (error) {
                console.error('Failed to deactivate environment:', error);
                Notifications.error(error.responseJSON?.message || 'Failed to deactivate');
                Loading.hide();
            }
        });
    }

    async function reactivateEnvironment(environmentId) {
        const env = window.VmRegistryState.environments.find(e => e.environmentId === environmentId);
        if (!env) return;
        try {
            Loading.show('Reactivating...');
            await ApiClient.post(Config.API.environments.reactivate(environmentId));
            Notifications.success('Environment reactivated');
            await loadEnvironmentsData();
        } catch (error) {
            console.error('Failed to reactivate environment:', error);
            Notifications.error(error.responseJSON?.message || 'Failed to reactivate');
        } finally {
            Loading.hide();
        }
    }

    // =========================================================================
    // Group Management
    // =========================================================================

    const VM_PAGE_SIZE = 25;

    async function manageGroups(environmentId, environmentName) {
        const envRecord = window.VmRegistryState.environments.find(e => e.environmentId === environmentId);
        // Names are looked up from state, never passed through inline handler markup.
        environmentName = environmentName || envRecord?.displayName || envRecord?.name || environmentId;
        window.VmRegistryState.currentEnvironment = {
            environmentId,
            environmentName,
            serviceType: envRecord?.serviceType || 'EC2'
        };
        window.VmRegistryState.groupVmPages = {};
        window.VmRegistryState.reviewState = null;
        window.VmRegistryState.reviewPage = 0;
        $('#vrReviewArea').empty();
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

    /**
     * Manually re-syncs the currently-open EKS environment's node groups from AWS right now,
     * then reloads the modal — lets an admin pick up a newly-created node group (or retry one
     * that failed on the last scheduled cycle) without waiting for the next 5-minute run.
     */
    async function syncEksNow() {
        const env = window.VmRegistryState.currentEnvironment;
        if (!env) return;

        const $btn = $('#eks-sync-now-btn');
        $btn.prop('disabled', true).html('<i class="fas fa-spinner fa-spin me-1"></i>Syncing...');
        try {
            const result = await ApiClient.post(Config.API.monitoring.triggerEksSyncForEnvironment(env.environmentId), {});
            Notifications.success(`EKS sync complete — ${result.nodeGroupsSynced} node group(s) processed`);
            await manageGroups(env.environmentId, env.environmentName);
        } catch (error) {
            console.error('EKS sync failed:', error);
            Notifications.error(error?.responseJSON?.message || 'EKS sync failed');
            $btn.prop('disabled', false).html('<i class="fas fa-sync me-1"></i>Sync Now');
        }
    }

    /**
     * Fetches one page of a group's VMs and re-renders just that group's table + pager,
     * so paging through a large group doesn't require reloading the whole modal.
     */
    async function changeGroupVmPage(groupId, page) {
        const environmentId = window.VmRegistryState.currentEnvironment.environmentId;
        try {
            const result = normalizePage(await ApiClient.get(Config.API.vms.groupPage(environmentId, groupId, page, VM_PAGE_SIZE)));
            window.VmRegistryState.groupVmPages[groupId] = page;

            const group = window.VmRegistryState.currentGroups.find(g => g.groupId === groupId);
            $(`#vm-rows-${groupId}`).html(buildVmRows(result.content || [], group));
            $(`#vm-page-footer-${groupId}`).replaceWith(
                buildVmPageFooter(groupId, group.vmCount, page, result.totalPages || 1)
            );
        } catch (error) {
            console.error('Failed to load VM page:', error);
            Notifications.error('Failed to load VMs');
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
                <div id="cem-eks-groups-banner" class="alert alert-info d-flex align-items-center justify-content-between gap-2 py-2 mb-3" style="font-size:0.875rem;">
                    <span><i class="fas fa-info-circle me-1"></i>Node groups are auto-synced from AWS EKS every few minutes. You can edit the sequence order but cannot add or delete groups manually.</span>
                    <button class="btn btn-sm btn-outline-primary flex-shrink-0" id="eks-sync-now-btn"
                            title="Sync this environment's node groups from AWS right now" data-action="vr-sync-eks">
                        <i class="fas fa-sync me-1"></i>Sync Now
                    </button>
                </div>
            `);
        } else {
            $addGroupBtn.show();
            $('#cem-eks-groups-banner').remove();
        }

        loadReviewCounts();

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

    // =========================================================================
    // Needs attention: drift / pending review / removed or inactive (E09-T10, M34)
    // =========================================================================

    const REVIEW_STATES = [
        { state: 'DRIFT', key: 'drift', label: 'Drift', icon: 'fa-exclamation-triangle' },
        { state: 'PENDING', key: 'pending', label: 'Pending review', icon: 'fa-inbox' },
        { state: 'INACTIVE', key: 'inactive', label: 'Removed or inactive', icon: 'fa-ban' }
    ];

    async function loadReviewCounts() {
        const env = window.VmRegistryState.currentEnvironment;
        if (!env) return;
        const $area = $('#vrReviewArea');
        try {
            const counts = await ApiClient.get(Config.API.vms.reviewCounts(env.environmentId), { suppressGlobalError: true });
            if (window.VmRegistryState.currentEnvironment !== env) return; // another environment opened meanwhile
            window.VmRegistryState.reviewCounts = counts;
            const any = REVIEW_STATES.some(s => (counts[s.key] || 0) > 0);
            if (!any) {
                window.VmRegistryState.reviewState = null;
                $area.empty();
                return;
            }
            const selected = window.VmRegistryState.reviewState;
            const chips = REVIEW_STATES.filter(s => (counts[s.key] || 0) > 0).map(s => Utils.html`
                <button type="button" class="btn btn-sm ${selected === s.state ? 'btn-primary' : 'btn-outline-secondary'} me-2 mb-1"
                        data-action="vr-review-state" data-state="${s.state}" aria-pressed="${String(selected === s.state)}">
                    <i class="fas ${s.icon} me-1"></i>${s.label} (${counts[s.key]})
                </button>`).join('');
            $area.html(Utils.html`
                <div class="card mb-3" id="vrReviewCard">
                    <div class="card-body py-2">
                        <div class="d-flex flex-wrap align-items-center">
                            <strong class="me-3 mb-1 small text-uppercase text-muted">Needs attention</strong>
                            ${Utils.raw(chips)}
                        </div>
                        <div id="vrReviewList"></div>
                    </div>
                </div>`);
            if (selected && (counts[REVIEW_STATES.find(s => s.state === selected).key] || 0) > 0) {
                await loadReviewList(selected, window.VmRegistryState.reviewPage || 0);
            } else {
                window.VmRegistryState.reviewState = null;
            }
        } catch (error) {
            console.error('Failed to load review counts:', error);
            $area.html('<div class="text-muted small mb-3"><i class="fas fa-exclamation-circle me-1"></i>Could not load VMs that need attention.</div>');
        }
    }

    async function selectReviewState(state) {
        const current = window.VmRegistryState.reviewState;
        window.VmRegistryState.reviewState = current === state ? null : state; // a second click closes the list
        window.VmRegistryState.reviewPage = 0;
        await loadReviewCounts();
    }

    async function loadReviewList(state, page) {
        const env = window.VmRegistryState.currentEnvironment;
        const $list = $('#vrReviewList');
        $list.html('<div class="text-muted small py-2"><i class="fas fa-spinner fa-spin me-1"></i>Loading...</div>');
        try {
            const result = normalizePage(await ApiClient.get(Config.API.vms.review(env.environmentId, state, page, VM_PAGE_SIZE),
                { suppressGlobalError: true }));
            if (window.VmRegistryState.currentEnvironment !== env || window.VmRegistryState.reviewState !== state) return;
            window.VmRegistryState.reviewPage = page;
            const vms = result.content || [];
            const totalPages = result.totalPages || 1;
            if (vms.length === 0) {
                $list.html('<div class="text-muted small py-2">Nothing here any more.</div>');
                return;
            }
            $list.html(Utils.html`
                <div class="table-responsive mt-2">
                    <table class="table table-sm table-hover mb-0 table-cards">
                        <thead class="table-light">
                            <tr>
                                <th>VM Name</th><th>Purpose</th><th>Provider</th><th>Region</th><th>Instance ID</th>
                                <th>Private IP</th><th>Status</th><th class="text-center">Seq</th><th class="text-end">Actions</th>
                            </tr>
                        </thead>
                        <tbody>${Utils.raw(buildVmRows(vms, null, { showGroup: true }))}</tbody>
                    </table>
                </div>
                ${totalPages > 1 ? Utils.raw(Utils.html`
                <div class="d-flex justify-content-between align-items-center pt-2">
                    <span class="text-muted small">Page ${page + 1} of ${totalPages}</span>
                    <div>
                        <button class="btn btn-sm btn-ghost" ${page === 0 ? 'disabled' : ''} data-action="vr-review-page" data-page="${page - 1}">
                            <i class="fas fa-chevron-left"></i> Prev
                        </button>
                        <button class="btn btn-sm btn-ghost ms-1" ${page >= totalPages - 1 ? 'disabled' : ''} data-action="vr-review-page" data-page="${page + 1}">
                            Next <i class="fas fa-chevron-right"></i>
                        </button>
                    </div>
                </div>`) : ''}`);
        } catch (error) {
            console.error('Failed to load review list:', error);
            $list.html('<div class="text-muted small py-2"><i class="fas fa-exclamation-circle me-1"></i>Could not load this list.</div>');
        }
    }

    function changeReviewPage(page) {
        const state = window.VmRegistryState.reviewState;
        if (state) loadReviewList(state, page);
    }

    async function fetchVm(vmId) {
        const envId = window.VmRegistryState.currentEnvironment.environmentId;
        return ApiClient.get(Config.API.vms.get(envId, vmId));
    }

    async function acknowledgeVm(vmId) {
        try {
            Loading.show('Marking as reviewed...');
            await ApiClient.put(Config.API.vms.acknowledge(window.VmRegistryState.currentEnvironment.environmentId, vmId), {});
            Notifications.success('VM marked as reviewed');
            await refreshGroupsModal();
        } catch (error) {
            console.error('Failed to acknowledge VM:', error);
            if (!error?.responseJSON?.message) Notifications.error('Failed to mark the VM as reviewed');
        } finally {
            Loading.hide();
        }
    }

    async function openMoveVm(vmId) {
        let vm;
        try {
            vm = await fetchVm(vmId);
        } catch (error) {
            console.error('Failed to load VM:', error);
            return;
        }
        const targets = (window.VmRegistryState.currentGroups || []).filter(g => g.groupId !== vm.groupId);
        if (targets.length === 0) {
            Notifications.error('There is no other group to move this VM to. Add a group first.');
            return;
        }
        $('#moveVmId').val(vm.vmId);
        $('#moveVmModalLabel').text(`Move VM "${vm.displayName || vm.name}"`);
        const $select = $('#moveVmTargetGroup').empty();
        targets.forEach(g => $select.append(new Option(g.displayName || g.name, g.groupId)));
        $('#btnSubmitMoveVm').prop('disabled', false);
        bootstrap.Modal.getOrCreateInstance(document.getElementById('moveVmModal')).show();
    }

    async function submitMoveVm() {
        const vmId = $('#moveVmId').val();
        const targetGroupId = $('#moveVmTargetGroup').val();
        if (!vmId || !targetGroupId) return;
        const $btn = $('#btnSubmitMoveVm').prop('disabled', true);
        try {
            await ApiClient.put(Config.API.vms.move(window.VmRegistryState.currentEnvironment.environmentId, vmId),
                { targetGroupId });
            bootstrap.Modal.getInstance(document.getElementById('moveVmModal'))?.hide();
            Notifications.success('VM moved');
            await refreshGroupsModal();
            await loadEnvironmentsData();
        } catch (error) {
            console.error('Failed to move VM:', error);
            if (!error?.responseJSON?.message) Notifications.error('Failed to move VM');
        } finally {
            $btn.prop('disabled', false);
        }
    }

    async function reactivateVm(vmId) {
        try {
            Loading.show('Reactivating VM...');
            await ApiClient.post(Config.API.vms.reactivate(window.VmRegistryState.currentEnvironment.environmentId, vmId), {});
            Notifications.success('VM reactivated');
            await refreshGroupsModal();
            await loadEnvironmentsData();
        } catch (error) {
            // e.g. "Instance not found in ap-south-1 - fix the region first" (shown by ApiClient)
            console.error('Failed to reactivate VM:', error);
            if (!error?.responseJSON?.message) Notifications.error('Failed to reactivate VM');
        } finally {
            Loading.hide();
        }
    }

    /** Action buttons for one VM row: inactive VMs can only be reactivated (E09-T10). */
    function buildVmActions(vm, isEks) {
        if (vm.isActive === false) {
            return Utils.html`
                <button class="btn btn-sm btn-outline-success btn-action" data-action="vr-reactivate-vm" data-vm-id="${vm.vmId}" title="Reactivate VM">
                    <i class="fas fa-undo"></i> Reactivate
                </button>`;
        }
        return Utils.html`
            ${vm.discoveryPending ? Utils.raw(Utils.html`
            <button class="btn btn-sm btn-outline-success btn-action" data-action="vr-ack-vm" data-vm-id="${vm.vmId}" title="Mark as reviewed">
                <i class="fas fa-check"></i>
            </button>`) : ''}
            ${vm.provider !== 'AWS_EKS' ? Utils.raw(Utils.html`
            <button class="btn btn-sm btn-outline-primary btn-action" data-action="vr-move-vm" data-vm-id="${vm.vmId}" title="Move to another group">
                <i class="fas fa-arrow-right"></i>
            </button>`) : ''}
            <button class="btn btn-sm btn-outline-warning btn-action" data-action="vr-edit-vm" data-vm-id="${vm.vmId}" title="Edit VM">
                <i class="fas fa-edit"></i>
            </button>
            ${!isEks ? Utils.raw(Utils.html`
            <button class="btn btn-sm btn-outline-danger btn-action" data-action="vr-delete-vm" data-vm-id="${vm.vmId}" title="Remove VM">
                <i class="fas fa-trash"></i>
            </button>`) : Utils.raw(`
            <span class="text-muted small ms-1" title="EKS node groups are managed by sync">
                <i class="fas fa-sync-alt"></i>
            </span>`)}`;
    }

    /** Drift / pending-review / removed markers shown after the status badge. */
    function buildVmMarkers(vm) {
        return Utils.html`
            ${vm.stateDriftDetected && vm.isActive !== false ? Utils.raw(
                '<span class="status-badge drift ms-1" title="Cloud state differs from the recorded state"><i class="fas fa-exclamation-triangle"></i> Drift</span>') : ''}
            ${vm.discoveryPending && vm.isActive !== false ? Utils.raw(
                '<span class="status-badge review ms-1" title="Discovered automatically; not yet reviewed"><i class="fas fa-inbox"></i> Pending review</span>') : ''}
            ${vm.isActive === false ? Utils.raw(vm.deletedAt
                ? '<span class="status-badge unknown ms-1" title="Removed from the platform; discovery ignores it"><i class="fas fa-trash"></i> Removed</span>'
                : '<span class="status-badge unknown ms-1" title="Deactivated by state sync"><i class="fas fa-ban"></i> Inactive</span>') : ''}`;
    }

    function buildVmRows(vms, group, opts = {}) {
        const isEks = (window.VmRegistryState.currentEnvironment?.serviceType || 'EC2') === 'EKS';
        const providerLabels = { AWS: 'AWS', AZURE: 'Azure', GCP: 'GCP', OCI: 'OCI', AWS_EKS: 'EKS' };
        const providerIcons = { AWS: 'fab fa-aws', AZURE: 'fab fa-microsoft', GCP: 'fab fa-google', OCI: 'fas fa-cloud', AWS_EKS: 'fas fa-dharmachakra' };

        return vms.map(vm => {
            const statusConfig = Config.STATUS.vm[vm.status] || Config.STATUS.vm.UNKNOWN;
            const purposeCell = vm.purpose
                ? `${Utils.escapeHtml(vm.purpose)}${vm.remarks ? `<div class="small text-muted" title="${Utils.escapeHtml(vm.remarks)}">${Utils.escapeHtml(vm.remarks)}</div>` : ''}`
                : '<span class="text-muted small">&mdash;</span>';
            return `
                <tr>
                    <td>
                        <strong>${Utils.escapeHtml(vm.name)}</strong>
                        ${vm.displayName && vm.displayName !== vm.name ? `<div class="small text-muted">${Utils.escapeHtml(vm.displayName)}</div>` : ''}
                        ${opts.showGroup && vm.groupName ? Utils.html`<div class="small text-muted">in ${vm.groupName}</div>` : ''}
                    </td>
                    <td>${purposeCell}</td>
                    <td><i class="${providerIcons[vm.provider] || 'fas fa-cloud'}"></i> ${providerLabels[vm.provider] || vm.provider}</td>
                    <td>${Utils.escapeHtml(vm.region)}</td>
                    <td><code class="small">${Utils.escapeHtml(vm.providerVmId)}</code></td>
                    <td><code class="small">${Utils.escapeHtml(vm.privateIp || '-')}</code></td>
                    <td>
                        <span class="status-badge ${statusConfig.class}">
                            <i class="fas ${statusConfig.icon}"></i> ${statusConfig.label}
                        </span>
                        ${buildVmMarkers(vm)}
                    </td>
                    <td class="text-center">${vm.sequencePosition || '-'}</td>
                    <td class="text-end text-nowrap td-actions">
                        ${buildVmActions(vm, isEks)}
                    </td>
                </tr>
            `;
        }).join('');
    }

    function buildVmPageFooter(groupId, totalVmCount, currentPage, totalPages) {
        if (totalPages <= 1) {
            return `<div id="vm-page-footer-${groupId}"></div>`;
        }
        return `
            <div id="vm-page-footer-${groupId}" class="d-flex justify-content-between align-items-center px-3 py-2 border-top">
                <span class="text-muted small">Page ${currentPage + 1} of ${totalPages} (${totalVmCount} VMs)</span>
                <div>
                    <button class="btn btn-sm btn-ghost" ${currentPage === 0 ? 'disabled' : ''}
                            data-action="vr-group-vm-page" data-group-id="${Utils.escapeHtml(groupId)}" data-page="${currentPage - 1}">
                        <i class="fas fa-chevron-left"></i> Prev
                    </button>
                    <button class="btn btn-sm btn-ghost ms-1" ${currentPage >= totalPages - 1 ? 'disabled' : ''}
                            data-action="vr-group-vm-page" data-group-id="${Utils.escapeHtml(groupId)}" data-page="${currentPage + 1}">
                        Next <i class="fas fa-chevron-right"></i>
                    </button>
                </div>
            </div>
        `;
    }

    function buildGroupCard(groupWithVms) {
        const group = groupWithVms.group;
        const vms = groupWithVms.vms || [];
        const vmCount = group.vmCount || 0;
        const runningCount = group.runningVmCount || 0;
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
        const vmRows = buildVmRows(vms, group);
        const totalPages = Math.max(1, Math.ceil(vmCount / VM_PAGE_SIZE));
        const vmPageFooter = buildVmPageFooter(group.groupId, vmCount, 0, totalPages);

        const collapseId = `collapse-${group.groupId}`;

        const actionBtns = isEks
            ? `<button class="btn btn-sm btn-warning btn-action" data-action="vr-edit-group" data-group-id="${Utils.escapeHtml(group.groupId)}" title="Edit sequence / display name">
                   <i class="fas fa-edit"></i>
               </button>`
            : `<button class="btn btn-sm btn-success btn-action me-1" data-action="vr-open-vm-form" data-group-id="${Utils.escapeHtml(group.groupId)}" title="Register VM">
                   <i class="fas fa-plus"></i> VM
               </button>
               <button class="btn btn-sm btn-warning btn-action me-1" data-action="vr-edit-group" data-group-id="${Utils.escapeHtml(group.groupId)}" title="Edit Group">
                   <i class="fas fa-edit"></i>
               </button>
               <button class="btn btn-sm btn-danger btn-action" data-action="vr-delete-group" data-group-id="${Utils.escapeHtml(group.groupId)}" title="Delete Group"
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
                            <div class="table-responsive">
                            <table class="table table-sm table-hover mb-0 table-cards">
                                <thead class="table-light">
                                    <tr>
                                        <th>VM Name</th>
                                        <th>Purpose</th>
                                        <th>Provider</th>
                                        <th>Region</th>
                                        <th>Instance ID</th>
                                        <th>Private IP</th>
                                        <th>Status</th>
                                        <th class="text-center">Seq</th>
                                        <th class="text-end">Actions</th>
                                    </tr>
                                </thead>
                                <tbody id="vm-rows-${group.groupId}">${vmRows}</tbody>
                            </table>
                            </div>
                            ${vmPageFooter}
                        ` : `
                            <p class="text-muted text-center py-3 mb-0">
                                <i class="fas fa-server me-1"></i> No VMs registered.
                                <a href="#" data-action="vr-open-vm-form" data-group-id="${Utils.escapeHtml(group.groupId)}">Add one</a>
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
            $('#groupName').prop('readonly', true); // the name is the group's identity
            $('#createGroupModalLabel').text('Edit Group');
            $('#btnSubmitGroup').html('<i class="fas fa-save"></i> Update');
        } else {
            $('#groupName').prop('readonly', false);
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
        const loaded = isEdit ? window.VmRegistryState.currentGroups.find(g => g.groupId === groupId) : null;
        const metadata = $('#groupMetadata').val().trim();
        const data = {
            name: $('#groupName').val().trim(),
            displayName: $('#groupDisplayName').val().trim(),
            // On edit '' clears the description; metadata is sent only when it was changed (it is merged)
            description: isEdit ? $('#groupDescription').val().trim() : ($('#groupDescription').val().trim() || null),
            sequencePosition: parseInt($('#groupSequencePosition').val()),
            dependsOnGroupIds: $('#groupDependsOn').val() || [],
            metadata: isEdit
                ? (metadata !== (loaded?.metadata || '').trim() ? (metadata || '{}') : null)
                : (metadata || null)
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
        Modals.confirm('Delete Group', `Delete "${group.displayName}"? This cannot be undone.`, async function() {
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
        }, { confirmText: 'Delete', confirmClass: 'btn-danger' });
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
        // Next free position among the loaded VMs of the group (was always 1, which collided).
        const loaded = (window.VmRegistryState.currentGroupsWithVms || [])
            .filter(gv => gv.group.groupId === groupId).flatMap(gv => gv.vms || []);
        const maxSeq = loaded.reduce((max, v) => Math.max(max, v.sequencePosition || 0), 0);
        $('#vmSequencePosition').val(Math.max(maxSeq, group.vmCount || 0) + 1);
        $('#registerVmModalLabel').text(`Register VM in "${group.displayName}"`);
        $('#btnSubmitVm').html('<i class="fas fa-save"></i> Register VM');
        $('#ec2ImportCard').show();

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

        $('#vmName').prop('readonly', false);
        $('#vmProvider').prop('disabled', false);
        $('#vmRegion').prop('readonly', false);
        $('#vmProviderVmId').prop('readonly', false);

        new bootstrap.Modal(document.getElementById('registerVmModal')).show();
    }

    /**
     * Opens the same Register VM modal pre-filled for editing an existing VM.
     * EKS-managed VMs lock identity fields that the sync job owns (name, provider,
     * region, instance ID) — the next sync would otherwise re-diverge them anyway —
     * but Purpose/Remarks and other business metadata stay editable.
     */
    async function editVm(vmId) {
        // Fetched by id, so Edit works for a VM on any page (only page 1 was cached).
        let vm;
        try {
            vm = await fetchVm(vmId);
        } catch (error) {
            console.error('Failed to load VM:', error);
            return;
        }
        const group = (window.VmRegistryState.currentGroups || []).find(g => g.groupId === vm.groupId);
        if (!group) {
            Notifications.error('VM group not found');
            return;
        }
        const isEks = (window.VmRegistryState.currentEnvironment?.serviceType || 'EC2') === 'EKS';

        $('#registerVmForm')[0].reset();
        $('#registerVmForm').removeClass('was-validated');
        $('#vmId').val(vm.vmId);
        $('#vmGroupId').val(group.groupId);
        $('#vmGroupLabel').text(group.displayName);
        $('#vmName').val(vm.name);
        $('#vmDisplayName').val(vm.displayName);
        $('#vmDescription').val(vm.description || '');
        $('#vmPurpose').val(vm.purpose || '');
        $('#vmRemarks').val(vm.remarks || '');
        $('#vmProvider').val(vm.provider);
        $('#vmRegion').val(vm.region);
        $('#vmProviderVmId').val(vm.providerVmId);
        $('#vmSequencePosition').val(vm.sequencePosition);
        $('#registerVmModalLabel').text(`Edit VM "${vm.displayName}"`);
        $('#btnSubmitVm').html('<i class="fas fa-save"></i> Save Changes');

        // Importing from AWS only makes sense when registering a brand-new VM
        $('#ec2ImportCard').hide();

        $('#vmName').prop('readonly', isEks);
        $('#vmProvider').prop('disabled', isEks);
        $('#vmRegion').prop('readonly', isEks);
        $('#vmProviderVmId').prop('readonly', isEks);

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
                    ${clickable ? 'data-action="vr-select-ec2"' : ''}
                    data-instance-id="${Utils.escapeHtml(inst.instanceId)}"
                    data-instance-name="${Utils.escapeHtml(inst._name || '')}">
                    <td>
                        <strong>${name}</strong>
                        <div class="small text-muted">${Utils.escapeHtml(inst.instanceId)}</div>
                    </td>
                    <td>${Utils.escapeHtml(inst.instanceType || '-')}</td>
                    <td><span class="${stateClass}"><i class="fas fa-circle fa-xs me-1"></i>${inst.state}</span></td>
                    <td>${Utils.escapeHtml(inst.privateIpAddress || '-')}</td>
                    <td class="td-actions">${badge}</td>
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
                <table class="table table-sm mb-0 table-cards">
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
        const vmId = $('#vmId').val();
        const isEdit = !!vmId;
        const groupId = $('#vmGroupId').val();
        const vmName = $('#vmName').val().trim();
        const vmDisplayName = $('#vmDisplayName').val().trim();
        const data = {
            groupId: groupId,
            name: vmName,
            displayName: vmDisplayName || vmName,
            description: $('#vmDescription').val().trim() || null,
            purpose: $('#vmPurpose').val().trim() || null,
            remarks: $('#vmRemarks').val().trim() || null,
            provider: $('#vmProvider').val(),
            region: $('#vmRegion').val().trim(),
            providerVmId: $('#vmProviderVmId').val().trim(),
            sequencePosition: parseInt($('#vmSequencePosition').val()) || 1
        };
        try {
            Loading.show(isEdit ? 'Saving VM...' : 'Registering VM...');
            const envId = window.VmRegistryState.currentEnvironment.environmentId;
            if (isEdit) {
                await ApiClient.put(`/api/v1/environments/${envId}/vms/${vmId}`, data);
                Notifications.success(`VM "${vmName}" updated successfully`);
            } else {
                await ApiClient.post(`/api/v1/environments/${envId}/vms`, data);
                Notifications.success(`VM "${vmName}" registered successfully`);
            }
            bootstrap.Modal.getInstance(document.getElementById('registerVmModal')).hide();
            await refreshGroupsModal();
            await loadEnvironmentsData();
            Loading.hide();
        } catch (error) {
            console.error(isEdit ? 'Failed to update VM:' : 'Failed to register VM:', error);
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
        window.VmRegistryState.groupVmPages = {};
        const groupsWithVms = await ApiClient.get(`/api/v1/environments/${envId}/vms`);
        window.VmRegistryState.currentGroupsWithVms = groupsWithVms;
        window.VmRegistryState.currentGroups = groupsWithVms.map(gv => gv.group);
        renderGroupsModal(envName, groupsWithVms);
    }

    async function deleteVm(vmId, vmName) {
        if (!vmName) {
            const vm = (window.VmRegistryState.currentGroupsWithVms || [])
                .flatMap(gv => gv.vms || []).find(v => v.vmId === vmId);
            vmName = vm?.displayName || vm?.name;
        }
        if (!vmName) {
            try {
                const vm = await fetchVm(vmId); // a VM on a later page or in a review list
                vmName = vm.displayName || vm.name;
            } catch (error) {
                vmName = vmId;
            }
        }
        Modals.confirm('Remove VM', `Remove "${vmName}" from the platform? Its history is kept and discovery will ignore this instance. It can be reactivated from Removed or inactive.`, async function() {
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
        }, { confirmText: 'Remove', confirmClass: 'btn-danger' });
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
        reactivateEnvironment,
        manageGroups,
        syncEksNow,
        changeGroupVmPage,
        renderGroupsModal,
        buildGroupCard,
        openGroupForm,
        populateDependencyDropdown,
        submitGroup,
        editGroup,
        deleteGroup,
        openVmForm,
        editVm,
        fetchEc2Instances,
        filterEc2Instances,
        selectEc2Instance,
        submitVm,
        deleteVm,
        refreshGroupsModal,
        selectReviewState,
        changeReviewPage,
        acknowledgeVm,
        openMoveVm,
        submitMoveVm,
        reactivateVm,
        _searchRegistry: function(val) {
            filterEnvironments((val || '').trim());
        }
    };
})();

window.VmRegistry = VmRegistry;

Actions.registerAll({
    'vr-create-environment': () => VmRegistry.openCreateEnvironmentModal(),
    'vr-reload': () => VmRegistry.loadEnvironmentsData(),
    'vr-manage-groups': el => VmRegistry.manageGroups(el.dataset.envId),
    'vr-edit-environment': el => VmRegistry.editEnvironment(el.dataset.envId),
    'vr-delete-environment': el => VmRegistry.deleteEnvironment(el.dataset.envId),
    'vr-reactivate-environment': el => VmRegistry.reactivateEnvironment(el.dataset.envId),
    'vr-sync-eks': () => VmRegistry.syncEksNow(),
    'vr-edit-vm': el => VmRegistry.editVm(el.dataset.vmId),
    'vr-delete-vm': el => VmRegistry.deleteVm(el.dataset.vmId),
    'vr-ack-vm': el => VmRegistry.acknowledgeVm(el.dataset.vmId),
    'vr-move-vm': el => VmRegistry.openMoveVm(el.dataset.vmId),
    'vr-submit-move-vm': () => VmRegistry.submitMoveVm(),
    'vr-reactivate-vm': el => VmRegistry.reactivateVm(el.dataset.vmId),
    'vr-review-state': el => VmRegistry.selectReviewState(el.dataset.state),
    'vr-review-page': el => VmRegistry.changeReviewPage(Number(el.dataset.page)),
    'vr-group-vm-page': el => VmRegistry.changeGroupVmPage(el.dataset.groupId, Number(el.dataset.page)),
    'vr-edit-group': el => VmRegistry.editGroup(el.dataset.groupId),
    'vr-open-vm-form': el => VmRegistry.openVmForm(el.dataset.groupId),
    'vr-delete-group': el => VmRegistry.deleteGroup(el.dataset.groupId),
    'vr-select-ec2': el => VmRegistry.selectEc2Instance(el.dataset.instanceId),
    'vr-open-group-form': () => VmRegistry.openGroupForm(),
    'vr-submit-group': () => Features.submitGroup(),
    'vr-fetch-ec2': () => Features.fetchEc2Instances(),
    'vr-submit-vm': () => Features.submitVm()
});
Actions.registerAll({
    'vr-search': el => VmRegistry._searchRegistry(el.value),
    'vr-filter-ec2': el => Features.filterEc2Instances(el.value)
}, 'input');
Actions.register('vr-reload', () => VmRegistry.loadEnvironmentsData(), 'change');
