/**
 * VM Registry Feature
 * Admin > VM registry (U11-T03, VmRegistry.dc.html): the environments on the left, the selected
 * one's groups and VMs on the right, with what needs a look. The group, VM, move and register
 * dialogs live in index.html.
 */
const VmRegistry = (function() {
    'use strict';

    // Cache for fetched EC2 instances (used for client-side filtering)
    let _ec2FetchedInstances = [];
    // What the import panel browses (E33-T02): 'EC2' instances or 'RDS' databases, and the databases fetched
    let _importSource = 'EC2';
    /** The reference of the EC2 instance picked from the list (U01-T02); registering sends it instead of the id. */
    let _selectedInstanceRef = null;
    let _rdsFetched = [];

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

    /** Environments per page in the list on the left (VmRegistry.dc.html). */
    const ENV_PAGE_SIZE = 8;

    const PLUS_ICON = '<svg width="14" height="14" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M10 4v12M4 10h12"></path></svg>';
    const SEARCH_ICON = '<svg width="14" height="14" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><circle cx="9" cy="9" r="5.5"></circle><path d="m13.5 13.5 3.5 3.5"></path></svg>';

    function isActive() {
        return pageToken !== null && ContentRouter.isCurrent(pageToken);
    }

    /**
     * Router loader. U11-T03: one screen, the environments on the left and the selected one's
     * groups and VMs on the right; the selection is kept in the address (#/vm-registry?env=<id>).
     */
    function load() {
        pageToken = ContentRouter.token();
        const q = ContentRouter.query ? ContentRouter.query() : {};
        window.VmRegistryState = {
            environments: [],
            filtered: [],
            currentEnvironment: null,
            currentGroups: [],
            currentGroupsWithVms: [],
            groupVmPages: {},
            currentPage: 1,
            PAGE_SIZE: ENV_PAGE_SIZE,
            show: 'active',
            search: '',
            selectedId: q.env || null,
            reviewSummary: []
        };

        renderVmRegistry();
        loadEnvironmentsData();
        loadReviewSummary();
    }

    // =========================================================================
    // Model (pure; node tests use VmRegistry.model)
    // =========================================================================

    function count(n, word) {
        const v = n || 0;
        return `${v} ${word}${v === 1 ? '' : 's'}`;
    }

    /** "payments-dev · 3 groups · 7 VMs", or "hr-portal-stg · EKS · 6 nodes". */
    function envMeta(env) {
        if ((env.serviceType || 'EC2') === 'EKS') {
            return `${env.name} · EKS · ${count(env.vmCount, 'node')}`;
        }
        return `${env.name} · ${count(env.groupCount, 'group')} · ${count(env.vmCount, 'VM')}`;
    }

    /** 1st, 2nd, 3rd, 4th … 11th, 12th, 13th … 21st. */
    function ordinal(n) {
        const mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) return `${n}th`;
        return `${n}${{ 1: 'st', 2: 'nd', 3: 'rd' }[n % 10] || 'th'}`;
    }

    /** "1st", "2nd · after Database": the group's place in the start order and what it waits for. */
    function startOrderText(group, groups) {
        const ordered = (groups || []).slice().sort((a, b) => (a.sequencePosition ?? 0) - (b.sequencePosition ?? 0));
        const place = ordered.findIndex(g => g.groupId === group.groupId) + 1;
        const after = (group.dependsOnGroupIds || [])
            .map(id => ordered.find(g => g.groupId === id))
            .filter(Boolean)
            .map(g => g.displayName || g.name);
        return `${ordinal(place || 1)}${after.length ? ` · after ${after.join(', ')}` : ''}`;
    }

    /** The note on a VM row: why it needs a look, else its purpose. {text, warn} */
    function vmNote(vm) {
        if (vm.isActive === false) {
            return { text: vm.deletedAt ? 'Removed from the platform' : 'Not found in AWS; inactive', warn: false };
        }
        if (vm.stateDriftDetected) return { text: 'State changed outside the platform', warn: true };
        if (vm.discoveryPending) return { text: 'Found by discovery', warn: true };
        return { text: vm.purpose || '', warn: false };
    }

    /** The banner: "3 VMs need a look: 1 changed outside the platform, 2 found in AWS and not in a group." */
    function bannerText(rows) {
        const drift = (rows || []).reduce((n, r) => n + (r.drift || 0), 0);
        const pending = (rows || []).reduce((n, r) => n + (r.pending || 0), 0);
        const total = drift + pending;
        if (!total) return null;
        const parts = [];
        if (drift) parts.push(`${drift} changed outside the platform`);
        if (pending) parts.push(`${pending} found in AWS and not in a group`);
        return { lead: `${total} ${total === 1 ? 'VM needs' : 'VMs need'} a look:`, rest: `${parts.join(', ')}.` };
    }

    /** The environments matching the search (name, display name or description). */
    function matching(environments, search) {
        const term = (search || '').trim().toLowerCase();
        if (!term) return environments || [];
        return (environments || []).filter(env =>
            (env.name || '').toLowerCase().includes(term)
            || (env.displayName || '').toLowerCase().includes(term)
            || (env.description || '').toLowerCase().includes(term));
    }

    // =========================================================================
    // Template
    // =========================================================================

    function renderVmRegistry() {
        $('#content-area').html(`
            <div class="content-view vr-page" id="vm-registry-view">
                ${PageHeader.markup({ title: 'VM registry',
                    summaryHtml: 'Set up environments: their groups, start order and the AWS VMs in them.',
                    actionsHtml: `<button type="button" class="btn btn-primary" data-action="vr-create-environment">${PLUS_ICON} New environment</button>`,
                    helpPage: 'vm-registry', helpLabel: 'VM registry' })}
                <div id="vr-banner"></div>
                <div class="vr-grid">
                    <section class="vr-master" aria-label="Environments">
                        <div class="vr-master-tools">
                            <label class="vr-search">${SEARCH_ICON}<span class="visually-hidden">Search environments</span>
                                <input type="search" id="searchEnvironments" placeholder="Search" autocomplete="off" data-action-input="vr-search"></label>
                            <select class="form-select" id="filterStatus" aria-label="Show" data-action-change="vr-reload">
                                <option value="active">Active</option>
                                <option value="inactive">Deactivated</option>
                            </select>
                        </div>
                        <div class="vr-env-list" id="vr-env-list"><div class="vr-empty">Loading environments…</div></div>
                        <div class="vr-master-pager" id="vm-registry-pagination"></div>
                    </section>
                    <section class="vr-detail" id="vr-detail" aria-labelledby="vr-sel-title"></section>
                </div>
            </div>
        `);
    }

    // =========================================================================
    // Data Loading
    // =========================================================================

    async function loadEnvironmentsData() {
        const state = window.VmRegistryState;
        const show = $('#filterStatus').val() || 'active';
        try {
            const environments = await ApiClient.get(
                `/api/v1/environments?includeInactive=${show === 'inactive'}&includeGroups=true`);
            if (!isActive()) return;
            state.environments = (environments || []).filter(env =>
                show === 'inactive' ? env.isActive === false : env.isActive !== false);
            state.show = show;
            applyFilter();
        } catch (error) {
            if (!isActive()) return;
            console.error('Failed to load environments:', error);
            $('#vr-env-list').html(`<div class="vr-empty">Could not load the environments.
                <button type="button" class="vr-link" data-action="vr-reload">Try again</button></div>`);
        }
    }

    /**
     * Filters the list, keeps the selection when it is still there (on the page that holds it),
     * else selects the first environment shown, and loads its groups when the selection changed.
     */
    function applyFilter() {
        const state = window.VmRegistryState;
        state.filtered = matching(state.environments, state.search);
        let index = state.filtered.findIndex(env => env.environmentId === state.selectedId);
        if (index < 0) {
            const pages = Math.max(1, Math.ceil(state.filtered.length / ENV_PAGE_SIZE));
            state.currentPage = Math.min(Math.max(1, state.currentPage), pages);
            index = (state.currentPage - 1) * ENV_PAGE_SIZE;
        } else {
            state.currentPage = Math.floor(index / ENV_PAGE_SIZE) + 1;
        }
        const env = state.filtered[index];
        renderEnvironmentsList();
        if (!env) {
            state.selectedId = null;
            state.currentEnvironment = null;
            $('#vr-detail').html(`<div class="vr-empty">${state.filtered.length || !state.environments.length
                ? 'Pick an environment to see its groups and VMs.' : 'No environment matches the search.'}</div>`);
            return;
        }
        const current = state.currentEnvironment;
        if (current && current.environmentId === env.environmentId) {
            // Same environment: refresh what its header says (counts, name, active) and keep the tree.
            current.isActive = env.isActive !== false;
            current.canAdminister = env.viewerCanAdminister === true;
            $('#vr-detail-head').replaceWith(detailHeadHtml(env));
            return;
        }
        selectEnvironment(env.environmentId);
    }

    // =========================================================================
    // Environment list (left)
    // =========================================================================

    function renderEnvironmentsList() {
        const state = window.VmRegistryState;
        const list = state.filtered || [];
        const items = Pagination.slice(list, state.currentPage, ENV_PAGE_SIZE);
        const empty = state.search
            ? Utils.html`No environments match "${state.search}".`
            : state.show === 'inactive' ? 'No deactivated environments.' : 'No environments yet. Add one with New environment.';
        $('#vr-env-list').html(items.length ? items.map(buildEnvironmentRow).join('') : `<div class="vr-empty">${empty}</div>`);
        $('#vm-registry-pagination').html(Pagination.buildPager({ page: state.currentPage, pageSize: ENV_PAGE_SIZE,
            totalItems: list.length, action: 'vr-env-page', key: 'environments', emptyText: '0 environments' }));
    }

    function buildEnvironmentRow(env) {
        const current = env.environmentId === window.VmRegistryState.selectedId;
        const strip = env.groupStates && env.groupStates.length
            ? PowerStrip.render(PowerStrip.fromGroupStates(env.groupStates), { showCount: false }) : '';
        return Utils.html`<button type="button" class="vr-env" data-action="vr-select-env" data-env-id="${env.environmentId}"${Utils.raw(current ? ' aria-current="true"' : '')}>
                <span class="vr-env-text"><span class="vr-env-name">${env.displayName || env.name}</span><span class="vr-env-meta">${envMeta(env)}</span></span>${Utils.raw(strip)}
            </button>`;
    }

    function changeEnvironmentPage(page) {
        const state = window.VmRegistryState;
        const pages = Math.max(1, Math.ceil((state.filtered || []).length / ENV_PAGE_SIZE));
        if (page < 1 || page > pages) return;
        state.currentPage = page;
        renderEnvironmentsList();
    }

    function filterEnvironments(searchTerm) {
        const state = window.VmRegistryState;
        state.search = searchTerm || '';
        state.currentPage = 1;
        state.filtered = matching(state.environments, state.search);
        renderEnvironmentsList();
        if (state.filtered.length && !state.filtered.some(env => env.environmentId === state.selectedId)) {
            selectEnvironment(state.filtered[0].environmentId);
        }
    }

    /** Keeps the selection in the address, so a reload or a shared link opens the same environment. */
    function writeUrl(environmentId) {
        if (window.history && window.history.replaceState) {
            window.history.replaceState(null, '', `#/vm-registry?env=${encodeURIComponent(environmentId)}`);
        }
    }

    // =========================================================================
    // Needs a look (banner)
    // =========================================================================

    async function loadReviewSummary() {
        try {
            const rows = await ApiClient.get(Config.API.environments.reviewSummary, { suppressGlobalError: true });
            if (!isActive()) return;
            window.VmRegistryState.reviewSummary = rows || [];
            const text = bannerText(rows);
            $('#vr-banner').html(text ? Utils.html`
                <button type="button" class="vr-banner" data-action="vr-review-them">
                    <span class="vr-banner-dot" aria-hidden="true"></span>
                    <span class="vr-banner-text"><b>${text.lead}</b> ${text.rest}</span>
                    <b>Review them</b>
                </button>` : '');
        } catch (error) {
            $('#vr-banner').empty();
        }
    }

    /** "Review them": the first environment with something to look at, opened on that list. */
    async function reviewThem() {
        const state = window.VmRegistryState;
        const first = (state.reviewSummary || [])[0];
        if (!first) return;
        if (state.show !== 'active' || state.search) {
            state.search = '';
            $('#searchEnvironments').val('');
            $('#filterStatus').val('active');
            state.selectedId = first.environmentId;
            await loadEnvironmentsData();
        }
        state.selectedId = first.environmentId;
        const index = (state.filtered || []).findIndex(env => env.environmentId === first.environmentId);
        if (index >= 0) state.currentPage = Math.floor(index / ENV_PAGE_SIZE) + 1;
        await selectEnvironment(first.environmentId, { reviewState: first.drift ? 'DRIFT' : 'PENDING' });
        document.getElementById('vrReviewArea')?.scrollIntoView({ block: 'nearest' });
    }

    // =========================================================================
    // Selected environment (right)
    // =========================================================================

    /**
     * Shows one environment's groups and VMs on the right. {@code manageGroups} is the old name
     * (the groups were in a dialog before U11-T03).
     */
    async function selectEnvironment(environmentId, opts = {}) {
        const state = window.VmRegistryState;
        let env = (state.environments || []).find(e => e.environmentId === environmentId);
        if (!env) {
            try {
                env = await ApiClient.get(`/api/v1/environments/${environmentId}`);
            } catch (error) {
                return;
            }
            if (!isActive()) return;
        }
        state.selectedId = environmentId;
        state.currentEnvironment = {
            environmentId,
            environmentName: env.displayName || env.name || environmentId,
            serviceType: env.serviceType || 'EC2',
            awsAccountRef: env.awsAccountRef,
            isActive: env.isActive !== false,
            // U02-T02: change controls only where the server says this person administers the environment.
            canAdminister: env.viewerCanAdminister === true
        };
        state.groupVmPages = {};
        state.reviewState = opts.reviewState || null;
        state.reviewPage = 0;
        renderEnvironmentsList();
        writeUrl(environmentId);
        $('#vr-detail').html(`${detailHeadHtml(env)}
            <div id="vrReviewArea" class="vr-review"></div>
            <div class="vr-tree-wrap" id="vr-tree-wrap"><div class="vr-empty">Loading groups and VMs…</div></div>`);
        await refreshDetail();
    }

    function manageGroups(environmentId) {
        return selectEnvironment(environmentId);
    }

    function detailHeadHtml(env) {
        const admin = env.viewerCanAdminister === true;
        const isEks = (env.serviceType || 'EC2') === 'EKS';
        const id = Utils.escapeHtml(env.environmentId);
        const actions = [];
        if (admin) {
            actions.push(`<button type="button" class="vr-link" data-action="vr-edit-environment" data-env-id="${id}">Settings</button>`);
            if (env.isActive !== false) {
                if (isEks) {
                    actions.push('<button type="button" class="vr-link" id="eks-sync-now-btn" data-action="vr-sync-eks">Sync EKS now</button>');
                } else {
                    actions.push('<button type="button" class="vr-link" data-action="vr-open-group-form">Add group</button>');
                    actions.push('<button type="button" class="btn btn-outline-primary btn-sm vr-add-vms" data-action="vr-add-vms">Add VMs from AWS</button>');
                }
                actions.push(`<button type="button" class="vr-link vr-danger" data-action="vr-delete-environment" data-env-id="${id}">Deactivate</button>`);
            } else {
                actions.push(`<button type="button" class="vr-link" data-action="vr-reactivate-environment" data-env-id="${id}">Reactivate</button>`);
            }
        }
        return Utils.html`<div class="vr-detail-head" id="vr-detail-head">
                <div class="vr-detail-title">
                    <h2 id="vr-sel-title">${env.displayName || env.name}</h2>
                    <div class="vr-detail-meta">${envMeta(env)}${env.isActive === false ? ' · deactivated' : ''}</div>
                </div>
                ${Utils.raw(actions.join(''))}
            </div>`;
    }

    /** Reloads the selected environment's groups and VMs (after any change in them). */
    async function refreshDetail() {
        const state = window.VmRegistryState;
        const env = state.currentEnvironment;
        if (!env) return;
        try {
            const groupsWithVms = await ApiClient.get(`/api/v1/environments/${env.environmentId}/vms`);
            if (!isActive() || state.currentEnvironment !== env) return;
            state.currentGroupsWithVms = groupsWithVms || [];
            state.currentGroups = state.currentGroupsWithVms.map(gv => gv.group);
            state.groupVmPages = {};
            renderTree(state.currentGroupsWithVms);
            loadReviewCounts();
            loadReviewSummary();
        } catch (error) {
            if (!isActive() || state.currentEnvironment !== env) return;
            console.error('Failed to load groups:', error);
            $('#vr-tree-wrap').html('<div class="vr-empty">Could not load the groups and VMs.</div>');
        }
    }

    /** The old name: the groups were in a dialog before U11-T03. */
    function refreshGroupsModal() {
        return refreshDetail();
    }

    /**
     * Manually re-syncs the selected EKS environment's node groups from AWS right now, so an
     * admin picks up a newly-created node group (or retries one that failed on the last scheduled
     * cycle) without waiting for the next 5-minute run.
     */
    async function syncEksNow() {
        const env = window.VmRegistryState.currentEnvironment;
        if (!env) return;

        const $btn = $('#eks-sync-now-btn');
        $btn.prop('disabled', true).text('Syncing…');
        try {
            const result = await ApiClient.post(Config.API.monitoring.triggerEksSyncForEnvironment(env.environmentId), {});
            Notifications.success(`EKS sync complete — ${result.nodeGroupsSynced} node group(s) processed`);
            await refreshDetail();
            await loadEnvironmentsData();
        } catch (error) {
            console.error('EKS sync failed:', error);
            Notifications.error(error?.responseJSON?.message || 'EKS sync failed');
        } finally {
            $('#eks-sync-now-btn').prop('disabled', false).text('Sync EKS now');
        }
    }

    /** "Add VMs from AWS": the register dialog for the first group until U11-T04 brings its own page. */
    function addVmsFromAws() {
        const groups = (window.VmRegistryState.currentGroups || []).slice()
            .sort((a, b) => (a.sequencePosition ?? 0) - (b.sequencePosition ?? 0));
        if (!groups.length) {
            Notifications.error('Add a group first, then add VMs to it.');
            return;
        }
        openVmForm(groups[0].groupId);
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
    // Groups and VMs (the tree)
    // =========================================================================

    /** VMs per page within a group (VmRegistry.dc.html); the server sends the first 25 of each group. */
    const VM_PAGE_SIZE = 10;

    function renderTree(groupsWithVms) {
        const isEks = (window.VmRegistryState.currentEnvironment?.serviceType || 'EC2') === 'EKS';
        const ordered = groupsWithVms.slice().sort((a, b) => (a.group.sequencePosition ?? 0) - (b.group.sequencePosition ?? 0));
        const empty = ordered.length ? '' : `<tbody><tr><td colspan="5" class="vr-name vr-muted">${isEks
            ? 'No node groups synced yet. Sync EKS now to bring them in.'
            : `No groups yet.${canAdministerCurrent() ? ' Add a group, then add VMs from AWS to it.' : ''}`}</td></tr></tbody>`;
        $('#vr-tree-wrap').html(`
            <table class="vr-tree" id="vr-tree">
                <thead><tr>
                    <th scope="col">Group / VM</th><th scope="col">Size</th><th scope="col">Start order</th>
                    <th scope="col">Notes</th><th scope="col"><span class="visually-hidden">Actions</span></th>
                </tr></thead>
                ${ordered.map(gv => groupBodyHtml(gv.group, (gv.vms || []).slice(0, VM_PAGE_SIZE), 0)).join('')}${empty}
            </table>`);
    }

    function groupBodyHtml(group, vms, page) {
        const total = group.vmCount || 0;
        const pager = total > VM_PAGE_SIZE
            ? `<tr class="vr-page-row"><td colspan="5">${Pagination.buildPager({ page: page + 1, pageSize: VM_PAGE_SIZE,
                totalItems: total, action: 'vr-group-page', key: group.groupId })}</td></tr>` : '';
        return `<tbody class="vr-group" id="vr-group-${Utils.escapeHtml(group.groupId)}" data-group-id="${Utils.escapeHtml(group.groupId)}">
                ${groupRowHtml(group)}
                ${vms.map(vmRowHtml).join('')}
                ${total === 0 ? '<tr class="vr-vm-row"><td colspan="5" class="vr-name vr-indent vr-muted">No VMs in this group yet.</td></tr>' : ''}
                ${pager}
            </tbody>`;
    }

    function groupRowHtml(group) {
        const isEks = (window.VmRegistryState.currentEnvironment?.serviceType || 'EC2') === 'EKS';
        const name = group.displayName || group.name;
        const id = group.groupId;
        const actions = !canAdministerCurrent() ? '' : isEks
            ? Utils.html`<button type="button" class="vr-act" data-action="vr-edit-group" data-group-id="${id}" aria-label="Edit group ${name}">Edit</button>`
            : Utils.html`<button type="button" class="vr-act" data-action="vr-edit-group" data-group-id="${id}" aria-label="Edit group ${name}">Edit</button>
                <button type="button" class="vr-act" data-action="vr-open-vm-form" data-group-id="${id}" aria-label="Add a VM to ${name}">Add VM</button>
                <button type="button" class="vr-act vr-danger" data-action="vr-delete-group" data-group-id="${id}" aria-label="Remove group ${name}"
                    ${Utils.raw(group.vmCount > 0 ? 'disabled title="Move or remove its VMs first"' : '')}>Remove</button>`;
        return Utils.html`<tr class="vr-group-row">
                <th scope="rowgroup" class="vr-name">${name} <span class="vr-count">${count(group.vmCount, isEks ? 'node' : 'VM')}</span></th>
                <td></td>
                <td class="vr-order">${startOrderText(group, window.VmRegistryState.currentGroups)}</td>
                <td class="vr-note">${group.description || ''}</td>
                <td class="vr-actions">${Utils.raw(actions)}</td>
            </tr>`;
    }

    function vmRowHtml(vm) {
        const isEks = (window.VmRegistryState.currentEnvironment?.serviceType || 'EC2') === 'EKS';
        const note = vmNote(vm);
        const name = vm.displayName || vm.name;
        const id = vm.vmId;
        let actions = '';
        if (canAdministerCurrent()) {
            actions = vm.isActive === false
                ? Utils.html`<button type="button" class="vr-act" data-action="vr-reactivate-vm" data-vm-id="${id}" aria-label="Reactivate ${name}">Reactivate</button>`
                : Utils.html`${Utils.raw(vm.discoveryPending ? Utils.html`<button type="button" class="vr-act" data-action="vr-ack-vm" data-vm-id="${id}" aria-label="Mark ${name} as reviewed">Mark reviewed</button>` : '')}
                    <button type="button" class="vr-act" data-action="vr-edit-vm" data-vm-id="${id}" aria-label="Edit ${name}">Edit</button>
                    ${Utils.raw(!isEks && vm.provider !== 'AWS_EKS' ? Utils.html`<button type="button" class="vr-act" data-action="vr-move-vm" data-vm-id="${id}" aria-label="Move ${name}">Move</button>
                    <button type="button" class="vr-act vr-danger" data-action="vr-delete-vm" data-vm-id="${id}" aria-label="Remove ${name}">Remove</button>` : '')}`;
        }
        return Utils.html`<tr class="vr-vm-row${vm.isActive === false ? ' vr-removed' : ''}" data-vm-id="${id}">
                <td class="vr-name vr-indent">${name}${Utils.raw(accountNote(vm))}</td>
                <td class="vr-size">${vm.instanceType || '—'}</td>
                <td></td>
                <td class="vr-note${note.warn ? ' vr-warn' : ''}">${note.text}</td>
                <td class="vr-actions">${Utils.raw(actions)}</td>
            </tr>`;
    }

    /** Pages through a large group's VMs, replacing just that group's rows. */
    async function changeGroupVmPage(groupId, page) {
        const state = window.VmRegistryState;
        const env = state.currentEnvironment;
        if (!env) return;
        try {
            const result = normalizePage(await ApiClient.get(Config.API.vms.groupPage(env.environmentId, groupId, page, VM_PAGE_SIZE)));
            if (!isActive() || state.currentEnvironment !== env) return;
            state.groupVmPages[groupId] = page;
            const group = state.currentGroups.find(g => g.groupId === groupId);
            if (!group) return;
            $(document.getElementById(`vr-group-${groupId}`)).replaceWith(groupBodyHtml(group, result.content || [], page));
        } catch (error) {
            console.error('Failed to load VM page:', error);
            Notifications.error('Failed to load VMs');
        }
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
                                <th>VM Name</th><th>Purpose</th><th>Provider</th><th>Region</th>${Utils.raw(showInstanceIds() ? '<th>Instance ID</th>' : '')}
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
            <button type="button" class="btn btn-sm btn-outline-success btn-action" data-action="vr-ack-vm" data-vm-id="${vm.vmId}" title="Mark as reviewed" aria-label="Mark as reviewed">
                <i class="fas fa-check" aria-hidden="true"></i>
            </button>`) : ''}
            ${vm.provider !== 'AWS_EKS' ? Utils.raw(Utils.html`
            <button type="button" class="btn btn-sm btn-outline-primary btn-action" data-action="vr-move-vm" data-vm-id="${vm.vmId}" title="Move to another group" aria-label="Move to another group">
                <i class="fas fa-arrow-right" aria-hidden="true"></i>
            </button>`) : ''}
            <button type="button" class="btn btn-sm btn-outline-warning btn-action" data-action="vr-edit-vm" data-vm-id="${vm.vmId}" title="Edit VM" aria-label="Edit VM">
                <i class="fas fa-edit" aria-hidden="true"></i>
            </button>
            ${!isEks ? Utils.raw(Utils.html`
            <button type="button" class="btn btn-sm btn-outline-danger btn-action" data-action="vr-delete-vm" data-vm-id="${vm.vmId}" title="Remove VM" aria-label="Remove VM">
                <i class="fas fa-trash" aria-hidden="true"></i>
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

    /** The VM's account under its region, only when there is more than one account (E28-T10). */
    function accountNote(vm) {
        if (typeof AwsAccountOptions === 'undefined' || !AwsAccountOptions.isMulti(AwsAccountOptions.cached()) || !vm.awsAccountRef) return '';
        return Utils.html`<div class="small text-muted vm-account">${AwsAccountOptions.nameOf(vm.awsAccountRef) || vm.awsAccountRef}</div>`;
    }

    function buildVmRows(vms, group, opts = {}) {
        const isEks = (window.VmRegistryState.currentEnvironment?.serviceType || 'EC2') === 'EKS';
        const providerLabels = { AWS: 'AWS', AZURE: 'Azure', GCP: 'GCP', OCI: 'OCI', AWS_EKS: 'EKS', AWS_RDS: 'RDS' };
        const providerIcons = { AWS: 'fab fa-aws', AZURE: 'fab fa-microsoft', GCP: 'fab fa-google', OCI: 'fas fa-cloud', AWS_EKS: 'fas fa-dharmachakra', AWS_RDS: 'fas fa-database' };

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
                    <td>${Utils.escapeHtml(vm.region)}${accountNote(vm)}</td>
                    ${showInstanceIds() ? `<td>${vm.providerVmId ? `<code class="small">${Utils.escapeHtml(vm.providerVmId)}</code>` : '<span class="text-muted">&mdash;</span>'}</td>` : ''}
                    <td><code class="small">${Utils.escapeHtml(vm.privateIp || '-')}</code></td>
                    <td>
                        <span class="status-badge ${statusConfig.class}">
                            <i class="fas ${statusConfig.icon}"></i> ${statusConfig.label}
                        </span>
                        ${buildVmMarkers(vm)}
                    </td>
                    <td class="text-center">${vm.sequencePosition || '-'}</td>
                    <td class="text-end text-nowrap td-actions">
                        ${canAdministerCurrent() ? buildVmActions(vm, isEks) : ''}
                    </td>
                </tr>
            `;
        }).join('');
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
        bootstrap.Modal.getOrCreateInstance(document.getElementById('createGroupModal')).show();
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
        _rdsFetched = [];
        _selectedInstanceRef = null;
        $('#importSourceEc2').prop('checked', true);
        setImportSource('EC2');
        probeRds();

        fillEc2Regions(null);
        // Another account to browse and register into, when there is more than one (E28-T10).
        if (typeof AwsAccountOptions !== 'undefined') {
            const env = window.VmRegistryState.currentEnvironment || {};
            AwsAccountOptions.fill($('#ec2AccountCol'), $('#ec2Account'), env.awsAccountRef).then(shown => {
                if (shown) fillEc2Regions($('#ec2Account').val());
            });
        }

        $('#vmName').prop('readonly', false);
        $('#vmProvider').prop('disabled', false);
        $('#vmRegion').prop('readonly', false);
        $('#vmProviderVmId').prop('readonly', false);
        // Only administrators may type an instance ID; everyone else picks from the AWS list (U01-T02).
        showProviderVmIdField(showInstanceIds());

        bootstrap.Modal.getOrCreateInstance(document.getElementById('registerVmModal')).show();
    }

    /**
     * Instance IDs reach administrators only (U01-T01): when editing a VM whose ID the server left out,
     * hide the field so it is neither shown nor required; the server keeps the stored ID.
     */
    function showProviderVmIdField(show) {
        $('#vmProviderVmId').prop('required', show).closest('.col-md-4').toggle(show);
    }

    /** Whether the person administers the environment open in the registry (U02-T02). */
    function canAdministerCurrent() {
        return window.VmRegistryState?.currentEnvironment?.canAdminister === true;
    }

    /** Instance IDs are for administrators only (U01-T05); the server leaves them out for everyone else. */
    function showInstanceIds() {
        return typeof Auth !== 'undefined' && typeof Auth.isAdmin === 'function' && Auth.isAdmin();
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
        $('#vmProviderVmId').val(vm.providerVmId || '');
        showProviderVmIdField(!!vm.providerVmId);
        $('#vmSequencePosition').val(vm.sequencePosition);
        $('#registerVmModalLabel').text(`Edit VM "${vm.displayName}"`);
        $('#btnSubmitVm').html('<i class="fas fa-save"></i> Save Changes');

        // Importing from AWS only makes sense when registering a brand-new VM
        $('#ec2ImportCard').hide();

        $('#vmName').prop('readonly', isEks);
        $('#vmProvider').prop('disabled', isEks);
        $('#vmRegion').prop('readonly', isEks);
        $('#vmProviderVmId').prop('readonly', isEks);

        bootstrap.Modal.getOrCreateInstance(document.getElementById('registerVmModal')).show();
    }

    /** The region list: the account's own regions when it has some, else every known region. */
    function fillEc2Regions(accountRef) {
        const account = accountRef && typeof AwsAccountOptions !== 'undefined'
            ? AwsAccountOptions.cached().find(a => a.ref === accountRef) : null;
        const regions = account && account.regions && account.regions.length
            ? account.regions.map(value => ({ value, label: value }))
            : Config.AWS_REGIONS;
        const regionSelect = $('#ec2Region');
        regionSelect.empty();
        regions.forEach(r => {
            regionSelect.append(Utils.html`<option value="${r.value}">${r.label} (${r.value})</option>`);
        });
    }

    function selectedAccountRef() {
        return $('#ec2AccountCol').prop('hidden') ? null : ($('#ec2Account').val() || null);
    }

    /** The EC2 / RDS toggle is shown only while RDS browsing is on (GET /rds answers enabled). */
    function probeRds() {
        $('#importSourceToggle').prop('hidden', true);
        ApiClient.request('GET', Config.API.rds.status, null, { suppressGlobalError: true })
            .done(r => $('#importSourceToggle').prop('hidden', !(r && r.enabled)))
            .fail(() => $('#importSourceToggle').prop('hidden', true));
    }

    function setImportSource(source) {
        _importSource = source === 'RDS' ? 'RDS' : 'EC2';
        const rds = _importSource === 'RDS';
        $('#btnFetchEc2').html(rds ? '<i class="fas fa-database me-1"></i> Fetch Databases' : '<i class="fab fa-aws me-1"></i> Fetch Instances');
        $('#ec2SearchFilter').val('').hide();
        $('#ec2InstancesList').html(rds ? `
            <div class="text-center text-muted py-3">
                <i class="fas fa-database fa-2x mb-2 d-block opacity-50"></i>
                Select a region and click "Fetch Databases" to browse RDS DB instances and Aurora clusters.
            </div>` : `
            <div class="text-center text-muted py-3">
                <i class="fab fa-aws fa-2x mb-2 d-block text-warning opacity-50"></i>
                Select a region and click "Fetch Instances" to browse available EC2 instances.
            </div>`);
    }

    async function fetchRdsDatabases(region) {
        const button = $('#btnFetchEc2');
        try {
            button.prop('disabled', true).html('<i class="fas fa-spinner fa-spin me-1"></i> Fetching...');
            $('#ec2InstancesList').html(Utils.html`
                <div class="text-center py-3">
                    <div class="spinner-border spinner-border-sm text-primary me-2" role="status"></div>
                    Fetching RDS databases from <strong>${region}</strong>...
                </div>`);
            _rdsFetched = await ApiClient.get(Config.API.rds.databases(region, selectedAccountRef())) || [];
            renderRdsDatabases(_rdsFetched);
            $('#ec2SearchFilter').show();
        } catch (error) {
            console.error('Failed to fetch RDS databases:', error);
            $('#ec2InstancesList').html(`
                <div class="text-center text-danger py-3">
                    <i class="fas fa-exclamation-triangle me-1"></i> Failed to fetch databases. Check AWS credentials and region.
                </div>`);
        } finally {
            button.prop('disabled', false).html('<i class="fas fa-database me-1"></i> Fetch Databases');
        }
    }

    /** Databases with Select for unregistered ones; those RDS cannot stop say why. */
    function renderRdsDatabases(list) {
        const container = $('#ec2InstancesList');
        if (!list || list.length === 0) {
            container.html(`
                <div class="text-center text-muted py-3">
                    <i class="fas fa-inbox fa-2x mb-2 d-block"></i>
                    No RDS databases found in this region.
                </div>`);
            return;
        }
        const rows = list.map(db => Utils.html`
            <tr class="${db.registered ? '' : 'rds-selectable-row'}" data-provider-vm-id="${db.providerVmId}"
                ${Utils.raw(db.registered ? '' : Utils.html`data-action="vr-select-rds" data-id="${db.providerVmId}" style="cursor:pointer;"`)}>
                <td><strong>${db.identifier}</strong>
                    <div class="small text-muted">${db.kind === 'CLUSTER' ? 'Aurora cluster' : 'DB instance'}${db.multiAz ? ' · Multi-AZ' : ''}</div></td>
                <td>${db.engine || '-'} ${db.engineVersion || ''}</td>
                <td>${db.instanceClass || '-'}</td>
                <td>${db.status || '-'}</td>
                <td class="td-actions">${Utils.raw(db.registered
                    ? '<span class="badge bg-warning text-dark"><i class="fas fa-check me-1"></i>In Use</span>'
                    : '<button type="button" class="btn btn-sm btn-outline-success">Select</button>')}
                    ${Utils.raw(db.stoppable ? '' : Utils.html`<div class="small text-danger mt-1 rds-not-stoppable">Can't be stopped: ${db.notStoppableReason || ''}</div>`)}</td>
            </tr>`).join('');
        container.html(`
            <div class="small text-muted mb-2"><strong>${list.length}</strong> databases found</div>
            <div class="table-responsive" style="max-height: 250px; overflow-y: auto;">
                <table class="table table-sm mb-0 table-cards" id="rdsDatabaseTable">
                    <thead class="table-light sticky-top">
                        <tr><th>Identifier</th><th>Engine</th><th>Class</th><th>Status</th><th style="width:140px;"></th></tr>
                    </thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>`);
    }

    function selectRdsDatabase(providerVmId) {
        const db = _rdsFetched.find(d => d.providerVmId === providerVmId);
        if (!db || db.registered) return;
        _selectedInstanceRef = null;
        showProviderVmIdField(true);
        $('#vmProvider').val('AWS_RDS').prop('disabled', true);
        $('#vmRegion').val($('#ec2Region').val()).prop('readonly', true);
        $('#vmProviderVmId').val(db.providerVmId).prop('readonly', true);
        $('#vmName').val(db.identifier.toLowerCase().replace(/[^a-z0-9-]/g, '-').replace(/-+/g, '-'));
        $('#vmDisplayName').val(db.identifier);
        $('#vmDescription').val([db.engine && (db.engine + ' ' + (db.engineVersion || '')).trim(), db.instanceClass, db.status]
            .filter(Boolean).join(' | '));
        $('#ec2InstancesList .rds-selectable-row').removeClass('table-success');
        $('#ec2InstancesList tr').filter(function() { return this.dataset.providerVmId === providerVmId; }).addClass('table-success');
        document.getElementById('registerVmForm').scrollIntoView({ behavior: 'smooth', block: 'start' });
        Notifications.success(`Selected: ${db.identifier}`);
    }

    async function fetchEc2Instances() {
        const region = $('#ec2Region').val();
        if (!region) {
            Notifications.error('Please select a region');
            return;
        }
        if (_importSource === 'RDS') {
            return fetchRdsDatabases(region);
        }
        try {
            $('#btnFetchEc2').prop('disabled', true).html('<i class="fas fa-spinner fa-spin me-1"></i> Fetching...');
            $('#ec2InstancesList').html(`
                <div class="text-center py-3">
                    <div class="spinner-border spinner-border-sm text-warning me-2" role="status"></div>
                    Fetching EC2 instances from <strong>${region}</strong>...
                </div>
            `);

            // Each instance comes with its registration and a reference to register it by; the instance id
            // is only in the list for administrators (U01-T02).
            const instances = await ApiClient.get(Config.API.ec2.listInstances(region, selectedAccountRef()));

            _ec2FetchedInstances = instances.map(inst => ({
                ...inst,
                _registered: !!inst.registered,
                _stale: !!inst.stale,
                _status: inst.stale ? 'stale' : inst.registered ? 'registered' : 'available',
                _name: inst.name || ''
            }));

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
                    data-ref="${Utils.escapeHtml(inst.ref)}"
                    data-instance-name="${Utils.escapeHtml(inst._name || '')}">
                    <td>
                        <strong>${name}</strong>
                        ${inst.instanceId ? `<div class="small text-muted">${Utils.escapeHtml(inst.instanceId)}</div>` : ''}
                        ${inst.registered && inst.registeredEnvironmentName ? `<div class="small text-muted">In ${Utils.escapeHtml(inst.registeredEnvironmentName)}</div>` : ''}
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
                            <th>Name</th>
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
        if (_importSource === 'RDS') {
            const term = (searchText || '').toLowerCase();
            renderRdsDatabases(term ? _rdsFetched.filter(db => db.identifier.toLowerCase().includes(term)
                || (db.engine || '').toLowerCase().includes(term)) : _rdsFetched);
            return;
        }
        if (!_ec2FetchedInstances || _ec2FetchedInstances.length === 0) return;
        if (!searchText) {
            renderEc2Instances(_ec2FetchedInstances);
            return;
        }
        const term = searchText.toLowerCase();
        const filtered = _ec2FetchedInstances.filter(inst =>
            (inst._name && inst._name.toLowerCase().includes(term)) ||
            (inst.instanceId && inst.instanceId.toLowerCase().includes(term)) ||
            (inst.privateIpAddress && inst.privateIpAddress.includes(term))
        );
        renderEc2Instances(filtered);
    }

    function selectEc2Instance(ref) {
        const inst = _ec2FetchedInstances.find(i => i.ref === ref);
        if (!inst || inst._registered) return;

        const region = $('#ec2Region').val();
        const name = inst._name || inst.instanceId || inst.privateIpAddress || 'ec2-instance';
        _selectedInstanceRef = inst.ref;

        $('#vmProvider').val('AWS').prop('disabled', true);
        $('#vmRegion').val(region).prop('readonly', true);
        // Administrators see the id; everyone else registers by the reference alone.
        $('#vmProviderVmId').val(inst.instanceId || '').prop('readonly', true);
        showProviderVmIdField(!!inst.instanceId);
        $('#vmName').val(name.toLowerCase().replace(/[^a-z0-9-]/g, '-').replace(/-+/g, '-'));
        $('#vmDisplayName').val(name);
        $('#vmDescription').val(`${inst.instanceType || ''} | ${inst.privateIpAddress || ''} | ${inst.state || ''}`.trim());

        $('#ec2InstancesList .ec2-selectable-row').removeClass('table-success');
        $('#ec2InstancesList tr').filter(function () { return this.dataset.ref === ref; }).addClass('table-success');
        document.getElementById('registerVmForm').scrollIntoView({ behavior: 'smooth', block: 'start' });
        Notifications.success(`Selected: ${name}`);
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
            instanceRef: isEdit ? null : _selectedInstanceRef,
            awsAccountRef: isEdit ? null : selectedAccountRef(),
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
        fillEc2Regions,
        loadEnvironmentsData,
        renderEnvironmentsList,
        buildEnvironmentRow,
        filterEnvironments,
        changeEnvironmentPage,
        selectEnvironment,
        reviewThem,
        addVmsFromAws,
        openCreateEnvironmentModal,
        editEnvironment,
        deleteEnvironment,
        reactivateEnvironment,
        manageGroups,
        syncEksNow,
        changeGroupVmPage,
        refreshDetail,
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
        setImportSource,
        selectRdsDatabase,
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
        },
        model: { envMeta, ordinal, startOrderText, vmNote, bannerText, matching }
    };
})();

window.VmRegistry = VmRegistry;

if (typeof Actions !== 'undefined') {
    Actions.registerAll({
        'vr-create-environment': () => VmRegistry.openCreateEnvironmentModal(),
        'vr-reload': () => VmRegistry.loadEnvironmentsData(),
        'vr-select-env': el => VmRegistry.selectEnvironment(el.dataset.envId),
        'vr-manage-groups': el => VmRegistry.selectEnvironment(el.dataset.envId),
        'vr-env-page': el => VmRegistry.changeEnvironmentPage(Number(el.dataset.page)),
        'vr-review-them': () => VmRegistry.reviewThem(),
        'vr-add-vms': () => VmRegistry.addVmsFromAws(),
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
        'vr-group-page': el => VmRegistry.changeGroupVmPage(el.dataset.pageKey, Number(el.dataset.page) - 1),
        'vr-edit-group': el => VmRegistry.editGroup(el.dataset.groupId),
        'vr-open-vm-form': el => VmRegistry.openVmForm(el.dataset.groupId),
        'vr-delete-group': el => VmRegistry.deleteGroup(el.dataset.groupId),
        'vr-select-ec2': el => VmRegistry.selectEc2Instance(el.dataset.ref),
        'vr-select-rds': el => VmRegistry.selectRdsDatabase(el.dataset.id),
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

    Actions.register('vr-ec2-account', el => VmRegistry.fillEc2Regions(el.value), 'change');
    Actions.register('vr-import-source', el => VmRegistry.setImportSource(el.value), 'change');
}
