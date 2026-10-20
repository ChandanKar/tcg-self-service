/**
 * VM Self-Service Platform - Environments Feature
 * Handles environment list and detail views with real API integration
 */

const Environments = (function() {
    'use strict';

    // Cache for current environment data
    let currentEnvironment = null;
    let environmentsList = []; // holds only the currently-displayed server page, not the full set

    // Pagination state for list view — server-side paginated, 0-indexed to match the backend
    // 15 rows fit 1440x900 with the header and the filter (Environments15.dc.html); the pager
    // appears only past 15 (U06-T05).
    const ENV_PAGE_SIZE = 15;
    let envCurrentPage = 0;
    let envSearchTerm = null;
    let envTotalElements = 0;
    let envStateFilter = 'all';            // all | running | stopped (U06-T05)
    let envTotals = null;                  // {vms, running} of the filtered set
    let operationRefreshTimer = null;
    let operationStatusHandler = null;
    let metricChartCounter = 0;

    // Navigation token of the current list/detail visit (see the page contract in core/router.js).
    let pageToken = null;

    /**
     * Start (or continue) a visit: called by loadList/loadDetail. Registers the teardown once per
     * router visit; internal reloads within the same visit keep the token.
     */
    function beginVisit() {
        const t = ContentRouter.token();
        if (pageToken !== t) {
            pageToken = t;
            ContentRouter.onLeave(teardown);
        }
        return t;
    }

    function isActive() {
        return pageToken !== null && ContentRouter.isCurrent(pageToken);
    }

    /** Reload the detail view only if the user is still on this environment's detail page. */
    function reloadDetailIfCurrent(envId) {
        if (isActive() && currentEnvironment && currentEnvironment.environmentId === envId) {
            loadDetail({ environmentId: envId });
        }
    }

    function reloadListIfCurrent() {
        if (isActive() && $('#env-list-pagination').length) {
            loadList();
        }
    }

    /** Runs when the user leaves the Environments list or detail (ContentRouter.onLeave). */
    function teardown() {
        pageToken = null;
        if (operationStatusHandler) {
            window.removeEventListener('vm-operation-status', operationStatusHandler);
            operationStatusHandler = null;
        }
        clearTimeout(operationRefreshTimer);
        operationRefreshTimer = null;
        $('#content-area').off('.envList .envDetail');
        $(document).off('click.vmMetricWindow');
        disposeAllBootstrapTooltips();
        currentEnvironment = null;
    }

    function isTransitionalStatus(status) {
        return ['STARTING', 'STOPPING'].includes((status || '').toUpperCase());
    }

    // E33-T04: AWS starts a stopped RDS database again after seven days; whether the platform stops
    // it again (rds.keep-stopped.enabled) comes from the capabilities (U02-T03).
    let rdsKeepStopped = null;

    function rdsAutoStartText(at) {
        const when = Utils.formatDate(at);
        return rdsKeepStopped
            ? `AWS will restart it on ${when}; the platform will stop it again`
            : `AWS will restart it on ${when} and it will keep running until stopped`;
    }

    /** Whether the server has switched the feature on (U02-T03); false while capabilities are unknown. */
    function hasFeature(feature) {
        return typeof Capabilities !== 'undefined' && Capabilities.has(feature);
    }

    function rdsAutoStartNote(vm) {
        if (vm.provider !== 'AWS_RDS' || !vm.awsAutoStartAt || vm.status !== 'STOPPED') return '';
        // Whether the platform stops it again comes from the capabilities, not a request per page (U02-T03).
        rdsKeepStopped = hasFeature('rdsKeepStopped');
        return Utils.html`<div class="small mt-1 rds-auto-start ${rdsKeepStopped ? 'text-muted' : 'text-warning-emphasis'}"
            data-at="${vm.awsAutoStartAt}">${rdsAutoStartText(vm.awsAutoStartAt)}</div>`;
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
        const t = beginVisit();
        showLoading('Loading environments...');

        try {
            envCurrentPage = 0;
            envSearchTerm = null;
            envStateFilter = 'all';
            const pageResult = await fetchEnvironmentsPage(envCurrentPage, envSearchTerm, envStateFilter);
            if (!ContentRouter.isCurrent(t)) return;
            environmentsList = pageResult.content;
            envTotalElements = pageResult.totalElements;
            envTotals = pageResult.totals;
            const html = buildListHtml(environmentsList, envTotalElements);
            $('#content-area').html(html);
            bindListEvents();
        } catch (error) {
            if (!ContentRouter.isCurrent(t)) return;
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
        const t = beginVisit();
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
                if (!ContentRouter.isCurrent(t)) return;
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
            if (!ContentRouter.isCurrent(t)) return;
            currentEnvironment = env;

            const html = buildDetailHtml(env);
            $('#content-area').html(html);
            bindDetailEvents(env);
            openVmFromRoute(env);
        } catch (error) {
            if (!ContentRouter.isCurrent(t)) return;
            console.error('Failed to load environment:', error);
            if (error.status === 403 || error.status === 404) {
                // Same page for both, so it does not reveal whether the environment exists (U02-T01).
                ContentRouter.showNoAccess('environment');
            } else {
                showError('Failed to load environment details.');
            }
        }
    }

    /** Home's "Review" links here with vm=<id> (U06-T03): open that VM's details once, then drop the parameter. */
    function openVmFromRoute(env) {
        const q = ContentRouter.query ? ContentRouter.query() : {};
        if (!q.vm) return;
        if (window.history && window.history.replaceState) {
            const [path, search = ''] = window.location.hash.split('?');
            const rest = search.split('&').filter(part => part && !part.startsWith('vm='));
            window.history.replaceState(null, '', rest.length ? `${path}?${rest.join('&')}` : path);
        }
        showVmDetails(env.environmentId, q.vm);
    }

    /**
     * Fetch environments list
     */
    function fetchEnvironments() {
        return new Promise((resolve, reject) => {
            ApiClient.get(Config.API.environments.list)
                .done(async function(environments) {
                    const enriched = await Promise.all(environments.map(enrichWithLockStatus));
                    resolve(enriched);
                })
                .fail(reject);
        });
    }

    /**
     * Environment counts (vmCount/runningVmCount) already come from the list endpoint itself —
     * only the lock status still needs a per-row fetch. Shared by fetchEnvironments() (unpaged,
     * used only for the name-lookup path) and fetchEnvironmentsPage() (server-paginated).
     */
    /** The list payload carries the lock holder (E10-T05): no per-row lock request. */
    function enrichWithLockStatus(env) {
        return {
            ...env,
            totalVms: env.vmCount || 0,
            runningVms: env.runningVmCount || 0,
            lockStatus: env.locked
                ? { isLocked: true, lockedByUserId: env.lockedByUserId, lockedByDisplayName: env.lockedByDisplayName, lockedAt: env.lockedAt }
                : { isLocked: false }
        };
    }

    /**
     * Spring Data serializes Page<T> as { content: [...], page: { totalElements, totalPages,
     * number, size } } — flatten that back onto the response, same pattern already used for VM
     * group pagination in vm-registry.js.
     */
    function normalizeEnvPage(data) {
        if (!data || !data.page) return data;
        return {
            ...data,
            totalElements: data.page.totalElements ?? data.totalElements ?? 0,
            totalPages: data.page.totalPages ?? data.totalPages ?? 0,
            number: data.page.number ?? data.number ?? 0,
            size: data.page.size ?? data.size
        };
    }

    /**
     * One server page of the Environments list: search, the All / Running / Stopped filter, the
     * per-group state counts for the power strips, and the VM totals of the whole filtered set
     * from the X-Total-Vms / X-Running-Vms headers (U06-T04).
     */
    function fetchEnvironmentsPage(page, search, state) {
        return new Promise((resolve, reject) => {
            ApiClient.get(Config.API.environments.page(page, ENV_PAGE_SIZE, search || '',
                { includeGroups: true, state: state && state !== 'all' ? state.toUpperCase() : '' }))
                .done(function(data, _status, xhr) {
                    const normalized = normalizeEnvPage(data);
                    const header = name => {
                        const value = xhr && xhr.getResponseHeader ? xhr.getResponseHeader(name) : null;
                        return value === null || value === '' ? null : Number(value);
                    };
                    resolve({
                        content: (normalized.content || []).map(enrichWithLockStatus),
                        totalElements: normalized.totalElements || 0,
                        totalPages: normalized.totalPages || 0,
                        number: normalized.number || 0,
                        totals: { vms: header('X-Total-Vms'), running: header('X-Running-Vms') }
                    });
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
                    <p>${Utils.escapeHtml(message || 'Loading...')}</p>
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
                    <p>${Utils.escapeHtml(message)}</p>
                    <button class="btn btn-primary" data-action="go-dashboard">
                        <i class="fas fa-arrow-left"></i> Back to Dashboard
                    </button>
                </div>
            </div>
        `);
    }

    const STATE_FILTERS = [
        { key: 'all', label: 'All' },
        { key: 'running', label: 'Running' },
        { key: 'stopped', label: 'Stopped' },
    ];

    function money(value) {
        if (value === null || value === undefined || value === '') return '—';
        const amount = Number(value);
        return Number.isFinite(amount)
            ? '$' + amount.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '—';
    }

    /** "15 environments · 7 of 26 VMs running" for the whole filtered set. */
    function countsHtml(totalElements, totals) {
        const count = Utils.html`<span class="mono">${totalElements}</span> environment${totalElements === 1 ? '' : 's'}`;
        const vms = totals && totals.vms !== null && totals.vms !== undefined
            ? Utils.html` · <span class="mono">${totals.running || 0}</span> of <span class="mono">${totals.vms}</span> VMs running`
            : '';
        return count + vms;
    }

    function listSummaryHtml(totalElements, totals) {
        return `<span id="env-list-summary">${countsHtml(totalElements, totals)}</span>. Open one to start, stop or lock it.`;
    }

    /** The list's page header (Environments15.dc.html, U06-T05): search and the filter sit on its right. */
    function listHeader(totalElements, totals, toolbarHtml = '') {
        return PageHeader.markup({
            title: 'Environments',
            summaryHtml: totalElements === null
                ? 'The environments you can see. Open one to start, stop or lock it.'
                : listSummaryHtml(totalElements, totals),
            actionsHtml: toolbarHtml,
            actionsClass: toolbarHtml ? 'env-list-toolbar' : '',
            helpPage: 'my-environments',
            helpLabel: 'Environments',
        });
    }

    function listToolbarHtml() {
        return `
            <label class="env-list-search">
                <i class="fas fa-search" aria-hidden="true"></i>
                <span class="visually-hidden">Search environments</span>
                <input type="search" id="env-list-search" placeholder="Search by name" autocomplete="off"
                       data-action-input="env-search">
            </label>
            <span id="env-list-filter">${FilterGroup.markup({ label: 'Show', options: STATE_FILTERS, active: envStateFilter, action: 'env-list-filter' })}</span>`;
    }

    /**
     * The Environments list (Environments.dc.html, Environments15.dc.html): search, the
     * All / Running / Stopped filter, one 38 px row per environment, "N environments" below and a
     * pager only past 15.
     */
    function buildListHtml(environments, totalElements) {
        if ((!environments || environments.length === 0) && !envSearchTerm && envStateFilter === 'all') {
            return `
                <div id="environments-list-view" class="page env-list-page">
                ${listHeader(null)}
                <div class="empty-state">
                    <i class="fas fa-server fa-3x text-muted" aria-hidden="true"></i>
                    <p class="mt-3 mb-1">You have no environments yet.</p>
                    <p class="text-muted small">${Auth.isEnvAdmin()
                        ? 'Create one in <strong>VM Registry</strong>.'
                        : 'Ask for access and an environment admin will review your request.'}</p>
                    ${Auth.isEnvAdmin() ? '' : '<a class="btn btn-primary" href="#/request-access">Request access to an environment</a>'}
                </div>
                </div>
            `;
        }

        return `
            <div id="environments-list-view" class="page env-list-page">
            ${listHeader(totalElements, envTotals, listToolbarHtml())}
            <div class="panel env-list-card">
                <div class="env-list-table-wrapper table-cards-wrap">
                    <table class="table mb-0 table-cards env-list-table">
                        <thead>
                            <tr>
                                <th scope="col">Environment</th>
                                <th scope="col" class="col-type">Type</th>
                                <th scope="col" class="col-account" hidden>Account</th>
                                <th scope="col" class="col-region">Region</th>
                                <th scope="col" class="col-running">VMs</th>
                                <th scope="col" class="col-lock">Lock</th>
                                <th scope="col" class="col-access">Your access</th>
                                <th scope="col" class="col-cost">This month</th>
                                <th scope="col" class="col-actions"><span class="visually-hidden">Open</span></th>
                            </tr>
                        </thead>
                        <tbody id="env-list-body">
                            ${environments.length ? buildEnvRows(environments) : emptyFilterRow()}
                        </tbody>
                    </table>
                </div>
                <div id="env-list-pagination" class="panel-foot env-list-foot"></div>
            </div>
            </div>
        `;
    }

    function emptyFilterRow() {
        const text = envSearchTerm ? 'No environments match your search.' : 'No environments match this filter.';
        return `<tr class="env-list-empty"><td colspan="9">${text}</td></tr>`;
    }

    /** "Not locked", "Locked by you" or the holder's name. */
    function lockText(env) {
        if (!env.lockStatus || !env.lockStatus.isLocked) return 'Not locked';
        if (env.lockStatus.lockedByUserId === Auth.getUserId()) return 'Locked by you';
        return env.lockStatus.lockedByDisplayName || 'Locked';
    }

    /**
     * The rows of one server page: name with its slug, type, region, the grouped power strip,
     * lock, the viewer's access, this month's cost and Open. Editing and deactivating live in
     * VM Registry (U06-T05).
     */
    function buildEnvRows(envList) {
        // The Account column only when the listed environments span more than one AWS account (E28-T10);
        // toggled once the caller has inserted the rows.
        const multiAccount = new Set(envList.map(e => e.awsAccountRef).filter(Boolean)).size > 1;
        queueMicrotask(() => $('#environments-list-view .col-account').prop('hidden', !multiAccount));
        return envList.map(env => {
            const name = env.displayName || env.name;
            const href = `#/environments/${encodeURIComponent(env.environmentId)}`;
            const runningVms = env.runningVms || 0;
            const totalVms = env.vmCount || env.totalVms || 0;
            const strip = Array.isArray(env.groupStates) && env.groupStates.length
                ? PowerStrip.render(PowerStrip.fromGroupStates(env.groupStates))
                : PowerStrip.counts(runningVms, totalVms);

            let envMeta = {};
            try { envMeta = JSON.parse(env.metadata || '{}') || {}; } catch (e) { /* no metadata */ }
            const type = `${(envMeta.defaultCloudProvider || 'AWS').toUpperCase()} ${(env.serviceType || 'EC2').toUpperCase()}`;

            // From the environment's own VMs (EC2 and EKS alike), not a declared value.
            const regions = env.regions || [];
            const regionCell = regions.length === 0
                ? '<span class="text-muted">&mdash;</span>'
                : Utils.escapeHtml(regions[0]) + (regions.length > 1
                    ? Utils.html` <span class="text-muted" title="${regions.join(', ')}">+${regions.length - 1}</span>` : '');

            const slug = env.displayName && env.displayName !== env.name
                ? Utils.html` <span class="env-slug mono">${env.name}</span>` : '';

            return Utils.html`
                <tr>
                    <td class="td-primary col-name" data-label="Environment">
                        <a class="env-name-link" href="${href}">${name}</a>` + slug + Utils.html`
                    </td>
                    <td class="col-type" data-label="Type">${type}</td>
                    <td class="col-account" data-label="Account" hidden>${env.awsAccountName || ''}</td>
                    <td class="col-region" data-label="Region"><span class="mono">` + regionCell + `</span></td>
                    <td class="col-running" data-label="VMs">` + strip + Utils.html`</td>
                    <td class="col-lock" data-label="Lock" title="${lockText(env)}">${lockText(env)}</td>
                    <td class="col-access" data-label="Your access">${HomeModel.accessLabel(env.myAccess)}</td>
                    <td class="col-cost" data-label="This month"><span class="mono">${money(env.monthToDateCost)}</span></td>
                    <td class="col-actions td-actions">
                        <a class="env-open-link" href="${href}" aria-label="Open ${name}">Open</a>
                    </td>
                </tr>`;
        }).join('');
    }

    /** "N environments" below the table; Previous / Next only past one page of 15. */
    function renderEnvPagination(totalItems, page) {
        const $foot = $('#env-list-pagination');
        if (!$foot.length) return;
        if (totalItems <= ENV_PAGE_SIZE) {
            $foot.html(`<div class="pager"><span class="pager-range">${totalItems} environment${totalItems === 1 ? '' : 's'}</span></div>`);
            return;
        }
        $foot.html(Pagination.buildPager({
            totalItems, page: page + 1, pageSize: ENV_PAGE_SIZE, action: 'env-list-page', key: 'environments',
        }));
    }

    /** Load another page, filter or search into the mounted list, keeping the toolbar. */
    async function refreshEnvList(page) {
        const t = ContentRouter.token();
        try {
            const pageResult = await fetchEnvironmentsPage(page, envSearchTerm, envStateFilter);
            if (!ContentRouter.isCurrent(t)) return;
            envCurrentPage = page;
            environmentsList = pageResult.content;
            envTotalElements = pageResult.totalElements;
            envTotals = pageResult.totals;
            $('#env-list-body').html(environmentsList.length ? buildEnvRows(environmentsList) : emptyFilterRow());
            $('#env-list-summary').html(countsHtml(envTotalElements, envTotals));
            $('#env-list-filter').html(FilterGroup.markup({ label: 'Show', options: STATE_FILTERS, active: envStateFilter, action: 'env-list-filter' }));
            renderEnvPagination(envTotalElements, envCurrentPage);
        } catch (error) {
            console.error('Failed to load environments page:', error);
        }
    }

    /** The filter buttons (Actions): page 0 with the new state. */
    function setStateFilter(key) {
        if (!STATE_FILTERS.some(f => f.key === key) || key === envStateFilter) return;
        envStateFilter = key;
        refreshEnvList(0);
    }

    /** Previous / Next (Actions): 1-indexed page from the pager. */
    function goToEnvPage(page) {
        const target = Math.max(0, (Number(page) || 1) - 1);
        refreshEnvList(target);
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

    /** Search on the server; the page resets to 0 and the filter stays. */
    async function doSearchEnvList(searchTerm) {
        envSearchTerm = searchTerm || null;
        await refreshEnvList(0);
    }

    // Debounced so a fresh server request doesn't fire on every keystroke.
    const filterAndRenderEnvList = Utils.debounce(doSearchEnvList, 300);

    /** An EKS environment: node groups instead of VMs (U07-T08, EksEnv.dc.html). */
    function isEks(env) {
        return String(env && env.serviceType || '').toUpperCase() === 'EKS';
    }

    /**
     * "payments-dev · AWS EC2 · ap-south-1 · Payments team" under the name (EnvDetail.dc.html);
     * "AWS EKS · cluster partner-qa-eks · ap-south-1" for EKS (EksEnv.dc.html).
     */
    function detailMetaHtml(env) {
        let envMeta = {};
        try { envMeta = JSON.parse(env.metadata || '{}') || {}; } catch (e) { /* no metadata */ }
        const type = `${(envMeta.defaultCloudProvider || 'AWS').toUpperCase()} ${(env.serviceType || 'EC2').toUpperCase()}`;
        const regions = env.regions || [];
        const parts = isEks(env)
            ? [Utils.escapeHtml(type) + (env.eksClusterName ? Utils.html` · cluster <span class="mono">${env.eksClusterName}</span>` : '')]
            : [Utils.html`<span class="mono">${env.name}</span>`, Utils.escapeHtml(type)];
        if (regions.length) {
            parts.push(Utils.html`<span class="mono">${regions[0]}</span>` + (regions.length > 1
                ? Utils.html` <span title="${regions.join(', ')}">+${regions.length - 1}</span>` : ''));
        }
        if (env.teamName) parts.push(Utils.escapeHtml(env.teamName));
        return `<p class="env-meta">${parts.join(' · ')}</p>`;
    }

    /**
     * Start and stop in the header: "Stop all" while anything runs, and "Start all" (nothing runs)
     * or "Start the N stopped" (some run); nothing for people who cannot operate here.
     */
    function headerOperationButtons(env) {
        if (env.viewerCanOperate !== true) return '';
        if (hasTransitionalVms(env)) {
            return `<button type="button" class="btn btn-secondary" id="btn-env-action" disabled title="An operation is already in progress">
                    <i class="fas fa-spinner fa-spin" aria-hidden="true"></i> In progress</button>`;
        }
        const running = env.runningVms || 0;
        const stopped = countVmsWithStatus(env, 'STOPPED');
        const stop = running > 0
            ? `<button type="button" class="btn btn-danger" id="btn-env-action-stop" data-action-type="stop">${isEks(env) ? 'Scale all to 0' : 'Stop all'}</button>`
            : '';
        const startLabel = running === 0 ? 'Start all' : `Start the ${stopped} stopped`;
        const start = running === 0 || stopped > 0
            ? `<button type="button" class="btn btn-success" id="btn-env-action" data-action-type="start">
                    <i class="fas fa-play" aria-hidden="true"></i> ${Utils.escapeHtml(startLabel)}</button>`
            : '';
        return stop + start;
    }

    /** The groups in start order, each with its lights, arrows between (EnvDetail.dc.html "Start order"). */
    function buildStartOrder(env) {
        const groups = PowerStrip.fromVmGroups(env.groups || []);
        if (!groups.length) return '';
        const arrow = `<svg class="env-stage-arrow" width="28" height="14" viewBox="0 0 28 14" fill="none" stroke="currentColor"
                stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 7h20M19 3l4 4-4 4"/></svg>`;
        const stages = groups.map((g, i) => (i ? arrow : '')
            + Utils.html`<span class="env-stage"><b>${g.name || 'Group ' + (i + 1)}</b>`
            + PowerStrip.render([g], { large: true, showCount: false }) + '</span>').join('');
        return `<section class="panel env-start-order" aria-label="Start order">
                <span class="env-start-order-label">Start order</span>${stages}</section>`;
    }

    /**
     * The environment page (EnvDetail.dc.html): the header with the meta line and actions, then a
     * main column (notes and banners, start order, groups and VMs) and the "About this environment"
     * column (running count, cost, budget, idle auto-stop). Spot, Teams, webhooks and Notify open
     * from Settings (EnvSettingsPanel).
     */
    function buildDetailHtml(env) {
        const totalVms = env.totalVms || 0;
        const runningVms = env.runningVms || 0;
        // The server sets the viewer flags on every environment it returns (U02-T02): no flag, no controls.
        const canOperate = env.viewerCanOperate === true;
        const isEnvAdmin = typeof Auth !== 'undefined' && Auth.isEnvAdmin && Auth.isEnvAdmin();
        const envName = env.displayName || env.name;
        // More than 12 VMs and groups: the group list with one group's VMs (EnvDetailLarge.dc.html).
        const eks = isEks(env);
        const large = !eks && useLargeLayout(env);
        const nodes = eks ? EnvVmTable.nodeTotals(env) : null;

        // The Lock fact; its actions follow the server's per-viewer flags on lockStatus (E07-T05).
        let lockFact = '';
        try {
            lockFact = (typeof Locks !== 'undefined' && Locks.buildLockFact) ?
                Locks.buildLockFact(env.lockStatus, env.environmentId) : '';
        } catch (e) { lockFact = ''; }

        const unknownVms = countVmsWithStatus(env, 'UNKNOWN');
        // "17 Nov", as on Home (HomeModel.day).
        const ends = env.viewerAccessEndsAt && typeof HomeModel !== 'undefined' ? HomeModel.day(env.viewerAccessEndsAt) : '';
        const viewOnly = canOperate ? '' : `
            <div class="env-viewonly-note" role="note">
                <i class="fas fa-eye" aria-hidden="true"></i>
                <span>You can see this environment but not start or stop it.${ends ? Utils.html` Your view access ends on ${ends}.` : ''}</span>
                <a class="env-viewonly-ask" href="#/request-access?environmentId=${encodeURIComponent(env.environmentId)}">Ask for operator access</a>
            </div>`;

        const settings = typeof EnvSettingsPanel !== 'undefined' && EnvSettingsPanel.isAvailable(env)
            ? '<button type="button" class="btn btn-ghost" id="btn-env-settings">Settings</button>' : '';

        return `
            <div id="env-detail-view" class="page env-detail-page">
                ${PageHeader.markup({
                    title: envName,
                    breadcrumb: [{ label: 'Environments', href: '#/my-environments' }, { label: envName }],
                    summaryHtml: detailMetaHtml(env),
                    actionsClass: 'env-detail-actions',
                    actionsHtml: `
                        <button type="button" class="btn btn-ghost" id="btn-env-insights" aria-label="Environment insights"
                                title="Use, cost and recommendations">Insights</button>
                        <button type="button" class="btn btn-ghost" id="btn-operation-history" title="Past starts and stops">History</button>
                        ${isEnvAdmin
                            ? '<button type="button" class="btn btn-ghost" id="btn-env-manage-access" title="Who has access to this environment">Access</button>'
                            : ''}
                        ${settings}
                        ${headerOperationButtons(env)}`
                })}

                <div id="env-alerts" class="env-alerts"></div>

                <div class="env-body${large ? ' env-body-wide' : ''}">
                    <div id="env-main" class="env-main">
                        ${viewOnly}
                        ${large || eks ? '' : buildStartOrder(env)}
                        <section id="env-vms" aria-labelledby="env-vms-title">
                            <h2 class="visually-hidden" id="env-vms-title">Groups and VMs</h2>
                            <div id="env-vms-body">${eks
                                ? EnvVmTable.renderEks(env, vmTableOptions(env))
                                : large
                                ? EnvVmTable.renderLarge(env, largeStateFor(env), vmTableOptions(env))
                                : EnvVmTable.render(env, vmTableOptions(env))}</div>
                        </section>
                        <section class="panel env-eks-namespaces" id="env-eks-namespaces-section" aria-labelledby="env-eks-namespaces-title" hidden>
                            <h2 id="env-eks-namespaces-title">Cost by namespace</h2>
                            <div id="env-eks-namespaces"></div>
                        </section>
                    </div>
                    <aside id="env-aside" class="env-aside" aria-label="About this environment">
                        <div class="panel env-about">
                            <div class="env-aside-run">
                                ${env.description ? Utils.html`<p class="env-aside-description">${env.description}</p>` : ''}
                                <div>${eks
                                    ? `<b class="mono">${nodes.running}</b> of <b class="mono">${nodes.total}</b> nodes running`
                                    : `<b class="mono">${runningVms}</b> of <b class="mono">${totalVms}</b> VMs running`}</div>
                                ${unknownVms > 0 ? `<div class="env-aside-note" title="Not yet read from the cloud; it updates on the next sync">${unknownVms} with unknown status</div>` : ''}
                            </div>
                            <div id="env-fact-lock">${lockFact}</div>
                            <div id="env-fact-lease"></div>
                            <div id="env-fact-schedule"></div>
                            <div id="env-fact-idle"></div>
                            <div class="env-fact env-cost-fact">
                                <section id="env-cost-card" aria-label="Environment cost"></section>
                                <div id="env-budget-panel"></div>
                            </div>
                        </div>
                    </aside>
                </div>
            </div>`;
    }

    /**
     * Whether the environment is large enough for the group-list layout (U07-T04): more than 12 VMs
     * and groups together. Until that layout lands every environment uses the one table.
     */
    function useLargeLayout(env) {
        return getEnvironmentVms(env).length + (env.groups || []).length > 12;
    }

    // The large layout's group, filter and page per environment; kept across the refreshes that
    // follow an operation (U07-T04).
    const largeState = {};

    function largeStateFor(env) {
        return largeState[env.environmentId] || (largeState[env.environmentId] = { groupId: null, filter: 'all', page: 0 });
    }

    function vmTableOptions(env) {
        return { canOperate: env.viewerCanOperate === true, rdsNote: rdsAutoStartNote };
    }

    function renderLargeVms() {
        const env = currentEnvironment;
        if (!env) return;
        $('#env-vms-body').html(EnvVmTable.renderLarge(env, largeStateFor(env), vmTableOptions(env)));
    }

    function countVmsWithStatus(env, status) {
        return (env.groups || []).reduce((n, g) => n + (g.vms || []).filter(v => v.status === status).length, 0);
    }

    /**
     * Bind list view events
     */
    function bindListEvents() {
        // Search — delegated binding + direct bind on rendered element (same pattern as dashboard)
        $('#content-area').off('input.envList', '#env-list-search').on('input.envList', '#env-list-search', function() {
            filterAndRenderEnvList($(this).val().toLowerCase().trim());
        });
        $('#env-list-search').off('input.envlist').on('input.envlist', function() {
            filterAndRenderEnvList($(this).val().toLowerCase().trim());
        });

        // Initial pagination render
        renderEnvPagination(envTotalElements, envCurrentPage);
    }

    /**
     * Bind detail view events (operations only - admin CRUD is in VM Registry)
     */
    function bindDetailEvents(env) {
        const envId = env.environmentId;
        const envName = env.displayName || env.name;
        console.log('bindDetailEvents called for environment:', envId, envName);

        // One alert stack for the page (U07-T07): each source below sets or clears its own alert.
        if (typeof EnvAlerts !== 'undefined') {
            EnvAlerts.reset(envId);
            EnvAlerts.set('lock', Locks.alertFor ? Locks.alertFor(env.lockStatus, envId, envName) : null, envId);
            const vms = getEnvironmentVms(env);
            EnvAlerts.set('drift', EnvAlerts.model.driftAlert(vms), envId);
            EnvAlerts.set('sync', EnvAlerts.model.syncAlert(vms, Auth.isAdmin && Auth.isAdmin()), envId);
        }

        // Cost card (E18): loads after the page, never blocks it.
        if (typeof EnvCost !== 'undefined') {
            EnvCost.render(env);
        }

        // A cost anomaly of the last 2 days becomes the Cost alert (E21-T04, U07-T07).
        if (typeof CostAnomalies !== 'undefined') {
            CostAnomalies.renderEnvPanel(env);
        }

        // Budget bar, freeze banner and owner approvals (E20-T05).
        if (typeof Budgets !== 'undefined') {
            Budgets.renderEnvPanel(env);
        }

        // Idle auto-stop fact (E16): not even asked for while the feature is off (U02-T03).
        if (typeof IdleStop !== 'undefined' && hasFeature('idleStop')) {
            IdleStop.renderPanel(env);
        }

        // A start or extension of mine waiting for approval (E25-T06): the Approval alert.
        if (typeof Approvals !== 'undefined') {
            Approvals.renderEnvBanner(env);
        }

        // Spot, Teams channels and webhooks render only when Settings is opened (U07-T02).

        // Lease banner (E15-T06): renders nothing while leases are off.
        if (typeof Leases !== 'undefined') {
            Leases.render(env);
        }

        // Lock events - delegate to Locks module if available
        try {
            if (typeof Locks !== 'undefined' && Locks.bindLockEvents) {
                Locks.bindLockEvents(envId, envName, function() {
                    reloadDetailIfCurrent(envId);
                });
                // Countdown to auto-release; page-scoped, so it stops when the user leaves.
                Locks.startCountdown(function() {
                    reloadDetailIfCurrent(envId);
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

        $('#btn-env-manage-access').off('click').on('click', function(e) {
            e.preventDefault();
            if (typeof AccessManagement !== 'undefined' && AccessManagement.openForEnvironment) {
                AccessManagement.openForEnvironment(env.environmentId);
            }
        });

        $('#btn-env-settings').off('click').on('click', function(e) {
            e.preventDefault();
            EnvSettingsPanel.open(env);
        });

        // Group and VM actions in the Groups and VMs table (U07-T03): delegated, so a re-rendered table
        // needs no new bindings; the environment is read from the page at click time.
        const $area = $('#content-area');
        $area.off('click.envDetail', '#env-vms [data-action]')
            .on('click.envDetail', '#env-vms [data-action]', function(e) {
                const action = this.getAttribute('data-action');
                const id = currentEnvironment && currentEnvironment.environmentId;
                if (!id) return;
                e.preventDefault();
                if (action === 'group-action') {
                    const groupId = this.getAttribute('data-group-id');
                    if (this.getAttribute('data-action-type') === 'start') startGroup(id, groupId);
                    else stopGroup(id, groupId);
                } else if (action === 'start-vm') {
                    startVm(id, this.getAttribute('data-vm-id'));
                } else if (action === 'stop-vm') {
                    stopVm(id, this.getAttribute('data-vm-id'));
                } else if (action === 'vm-details') {
                    showVmDetails(id, this.getAttribute('data-vm-id'));
                } else if (action === 'env-large-group') {
                    Object.assign(largeStateFor(currentEnvironment),
                        { groupId: this.getAttribute('data-group-id'), filter: 'all', page: 0 });
                    renderLargeVms();
                } else if (action === 'env-large-filter') {
                    Object.assign(largeStateFor(currentEnvironment), { filter: this.getAttribute('data-filter'), page: 0 });
                    renderLargeVms();
                } else if (action === 'env-large-page') {
                    largeStateFor(currentEnvironment).page = Math.max(0, (Number(this.getAttribute('data-page')) || 1) - 1);
                    renderLargeVms();
                }
            });

        // Tooltips on the header actions
        document.querySelectorAll(
            '#env-detail-view .env-detail-actions [data-bs-toggle="tooltip"]'
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
        const stillOnDetail = () =>
            isActive() && currentEnvironment && currentEnvironment.environmentId === envId;

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
                if (!stillOnDetail()) return;
                try {
                    const latestEnv = await fetchEnvironmentDetails(envId);
                    if (!stillOnDetail()) return;
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

    // Operation functions - confirm in OperationConfirm (U08-T02), run through VmOperations for progress tracking
    function confirmOperation(envId, opType, scope, run) {
        const env = currentEnvironment && currentEnvironment.environmentId === envId ? currentEnvironment : { environmentId: envId, groups: [] };
        OperationConfirm.open({
            env, opType, scope,
            onConfirm: leaseMinutes => run(leaseMinutes).then(() => reloadDetailIfCurrent(envId))
        });
    }

    function startEnvironment(envId, envName) {
        confirmOperation(envId, 'START', { level: 'environment' },
            minutes => VmOperations.startEnvironment(envId, envName, minutes));
    }

    function stopEnvironment(envId, envName) {
        confirmOperation(envId, 'STOP', { level: 'environment' }, () => VmOperations.stopEnvironment(envId, envName));
    }

    /**
     * True when a group's VMs are EKS node-group representations (one Vm per node group in
     * this model) rather than a regular multi-VM group — used to say "node group" instead of
     * the more alarming-sounding bare "Group" in operation messages.
     */
    function isEksGroup(group) {
        const vms = group ? (group.vms || []) : [];
        return vms.length > 0 && vms.every(v => v.provider === 'AWS_EKS');
    }

    function groupLabel(groupId) {
        const group = findGroup(groupId);
        return {
            name: group ? (group.group?.displayName || group.group?.name || group.displayName || group.name) : groupId,
            noun: isEksGroup(group) ? 'Node Group' : 'Group'
        };
    }

    function startGroup(envId, groupId) {
        const { name, noun } = groupLabel(groupId);
        confirmOperation(envId, 'START', { level: 'group', groupId },
            minutes => VmOperations.startGroup(envId, groupId, name, noun, minutes));
    }

    function stopGroup(envId, groupId) {
        const { name, noun } = groupLabel(groupId);
        confirmOperation(envId, 'STOP', { level: 'group', groupId }, () => VmOperations.stopGroup(envId, groupId, name, noun));
    }

    function vmLabel(vmId) {
        const vm = findVm(vmId);
        return vm ? (vm.displayName || vm.name) : vmId;
    }

    function startVm(envId, vmId) {
        const name = vmLabel(vmId);
        confirmOperation(envId, 'START', { level: 'vm', vmId }, minutes => VmOperations.startVm(envId, vmId, name, minutes));
    }

    function stopVm(envId, vmId) {
        const name = vmLabel(vmId);
        confirmOperation(envId, 'STOP', { level: 'vm', vmId }, () => VmOperations.stopVm(envId, vmId, name));
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
                        Slideout.open(title, content, { html: true });
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
                <span class="vm-slideout-name">Environment: ${Utils.escapeHtml(env.displayName || env.name)}</span>
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
                    <strong>${Utils.escapeHtml(group.name || '-')}</strong>
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
            <div class="vm-timeline-item env-check-${Utils.escapeHtml(item.tone || 'muted')}">
                <div class="vm-timeline-dot ${Utils.escapeHtml(item.tone || 'muted')}"></div>
                <div>
                    <strong>${Utils.escapeHtml(item.title || '-')}</strong>
                    <small>${Utils.escapeHtml(item.description || '')}</small>
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
                <span>${Utils.escapeHtml(label)}</span>
                <div>
                    ${entries.slice(0, 8).map(([key, value]) => `
                        <span class="env-chip">${Utils.escapeHtml(key)} <strong>${value}</strong></span>
                    `).join('')}
                </div>
            </div>
        `;
    }

    function buildVmInsightRows(rows, mode) {
        return rows.map(row => `
            <div class="env-insight-row">
                <div>
                    <strong>${Utils.escapeHtml(row.name || '-')}</strong>
                    <small>${Utils.escapeHtml(row.groupName || '-')}</small>
                </div>
                <span>${Utils.escapeHtml(row.status || '-')}</span>
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
                                Slideout.open(title, content, { html: true });
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
                <span class="vm-slideout-name">VM: ${Utils.escapeHtml(vm.displayName || vm.name)}</span>
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
                    <span>${Utils.escapeHtml(value)}</span>
                    <small>${Utils.escapeHtml(label)}</small>
                </div>
            </div>
        `;
    }

    function buildOverviewSection(vm, providerLabel, providerConfig, mock, lastSync) {
        const purposeValue = vm.purpose
            ? `${Utils.escapeHtml(vm.purpose)}${vm.remarks ? `<div class="small text-muted">${Utils.escapeHtml(vm.remarks)}</div>` : ''}`
            : null;
        return `
            <div class="vm-insight-section">
                <div class="vm-overview-clean">
                    ${buildField('Purpose', purposeValue, true)}
                    ${buildField('Private IP', mock.privateIp)}
                    ${buildField('Public IP', mock.publicIp)}
                    ${buildField('Instance type', mock.instanceType)}
                    ${buildField('vCPU / Memory', `${mock.vcpu} vCPU / ${mock.memoryGiB} GiB`)}
                    ${buildField('Region / AZ', `${vm.region || mock.region} / ${mock.availabilityZone}`)}
                    ${buildField('Last state sync', lastSync)}
                </div>
                <div class="vm-provider-line">
                    <span><i class="${providerConfig.icon}"></i> ${Utils.escapeHtml(providerLabel)}</span>
                    ${vm.providerVmId ? `<span class="vm-instance-id">Instance ID <code>${Utils.escapeHtml(vm.providerVmId)}</code> <small class="text-muted">· Administrators only</small></span>` : ''}
                </div>
            </div>
        `;
    }

    function buildUtilizationSection(mock, envId, vmId, selectedWindow = '1h') {
        const windows = ['1h', '6h', '12h', '24h', '7d'];
        return `
            <div class="vm-insight-section">
                <div class="vm-window-control" role="group" aria-label="Metric window">
                    ${windows.map(window => `
                        <button class="${window === selectedWindow ? 'active' : ''}" type="button"
                                data-env-id="${Utils.escapeHtml(envId)}" data-vm-id="${Utils.escapeHtml(vmId)}" data-window="${window}">
                            ${window}
                        </button>
                    `).join('')}
                </div>

                <div class="vm-util-grid">
                    ${buildMetricTile('CPU Utilization', formatPercent(mock.cpu.latest), 'Avg 1h', mock.cpu.series, 'cpu')}
                    ${mock.memory && mock.memory.available
                        ? buildMetricTile('Memory', formatPercent(mock.memory.latest), 'Avg 1h', mock.memory.series, 'cpu')
                        : buildMetricTile('Memory', '-', 'Memory needs the CloudWatch agent', [], 'cpu')}
                    ${buildMetricTile('Network In', formatBytes(mock.network.inBytes), 'Last period', mock.network.inSeries, 'network')}
                    ${buildMetricTile('Network Out', formatBytes(mock.network.outBytes), 'Last period', mock.network.outSeries, 'network')}
                    ${buildMetricTile('Disk Read', formatBytes(mock.disk.readBytes), 'Last period', mock.disk.readSeries, 'disk')}
                    ${buildMetricTile('Disk Write', formatBytes(mock.disk.writeBytes), 'Last period', mock.disk.writeSeries, 'disk')}
                    <div class="vm-idle-panel ${mock.idle.isIdle ? 'idle' : 'active'}"
                         title="${Utils.escapeHtml(mock.idle.reason)} Thresholds: CPU < 5%, low network, low disk IO for 30 minutes.">
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
                    <strong>${Utils.escapeHtml(volume.device)}</strong>
                    <code>${Utils.escapeHtml(volume.volumeId)}</code>
                </div>
                <span>${Utils.escapeHtml(volume.type)} / ${volume.sizeGiB} GiB</span>
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
                                <strong>${Utils.escapeHtml(item.title)}</strong>
                                <small>${Utils.escapeHtml(item.time)}</small>
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
                <span>${Utils.escapeHtml(label)}</span>
                <strong>${isHtml ? displayValue : Utils.escapeHtml(displayValue)}</strong>
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
                    <span>${Utils.escapeHtml(label)}</span>
                    <div class="vm-metric-value-wrap">
                        ${multiSeries ? buildDotLegend(lines) : ''}
                        <strong>${Utils.escapeHtml(value)}</strong>
                    </div>
                </div>
                ${chart}
                <small>${Utils.escapeHtml(caption)}</small>
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
                 aria-label="${Utils.escapeHtml(label)} utilization chart"></div>
            <script type="application/json" id="${configId}">${safeJson(config)}</script>
        `;
    }

    function buildDotLegend(lines) {
        return `
            <div class="vm-chart-dot-legend" role="group" aria-label="VM chart legend">
                ${lines.map((line) => `
                    <span class="vm-chart-dot"
                          style="background:${line.color}"
                          title="${Utils.escapeHtml(line.name || line.vmId || 'VM')}"></span>
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
                <span>${Utils.escapeHtml(message)}</span>
            </div>
        `;
    }

    function buildStorageMeter(label, value, percent) {
        return `
            <div class="vm-storage-stat">
                <span>${Utils.escapeHtml(label)}</span>
                <strong>${Utils.escapeHtml(value)}</strong>
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
            // Memory only exists when the CloudWatch agent publishes it (E12-T04).
            memory: {
                available: samples.some(s => s.memoryUtilization !== null && s.memoryUtilization !== undefined),
                latest: latest?.memoryUtilization ?? null,
                series: sampleSeries(samples, 'memoryUtilization', 'percent')
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
        setStateFilter,
        goToEnvPage,
        /** VM details from the drift alert (U07-T07), on its History tab. */
        openVm: vmId => currentEnvironment && showVmDetails(currentEnvironment.environmentId, vmId, '1h', 'history'),
        _search: function(val) {
            filterAndRenderEnvList((val || '').toLowerCase().trim());
        }
    };
})();

Actions.register('env-search', el => Environments._search(el.value), 'input');
// The list's All / Running / Stopped switch and its pager past 15 (U06-T05).
Actions.register('env-list-filter', el => Environments.setStateFilter(el.getAttribute('data-filter')));
Actions.register('env-list-page', el => Environments.goToEnvPage(el.getAttribute('data-page')));
Actions.register('env-alert-vm', el => Environments.openVm(el.getAttribute('data-vm-id')));
