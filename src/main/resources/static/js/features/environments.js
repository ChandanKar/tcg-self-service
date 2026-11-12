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
    /** Rows a page: as many 38 px rows as fit the window, at most the board's 15 (U18-T02). */
    let envPageSize = ENV_PAGE_SIZE;
    let envListPanel = null;
    let envCurrentPage = 0;
    let envSearchTerm = null;
    let envTotalElements = 0;
    let envStateFilter = 'all';            // all | running | stopped (U06-T05)
    let envRegionFilter = '';              // '' (every region) or a region code (U19-T08)
    let envSort = 'name';                  // name | running | cost (U22-T13)
    let envTotals = null;                  // {vms, running} of the filtered set
    let operationRefreshTimer = null;
    let operationStatusHandler = null;

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
        envListPanel = null; // ListPanel stops itself on leave
        if (operationStatusHandler) {
            window.removeEventListener('vm-operation-status', operationStatusHandler);
            operationStatusHandler = null;
        }
        clearTimeout(operationRefreshTimer);
        operationRefreshTimer = null;
        $('#content-area').off('.envList .envDetail');
        $(document).off('click.vmMetricWindow');
        disposeAllBootstrapTooltips();
        document.body.classList.remove('phone-drilldown');
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
            envRegionFilter = '';
            const pageResult = await fetchEnvironmentsPage(envCurrentPage, envSearchTerm, envStateFilter);
            if (!ContentRouter.isCurrent(t)) return;
            environmentsList = pageResult.content;
            envTotalElements = pageResult.totalElements;
            envTotals = pageResult.totals;
            const html = buildListHtml(environmentsList, envTotalElements);
            $('#content-area').html(html);
            bindListEvents();
            loadRegionFilter(t);
        } catch (error) {
            if (!ContentRouter.isCurrent(t)) return;
            console.error('Failed to load environments:', error);
            showError(error, 'Environments', 'Your environments');
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
                    ContentRouter.showNoAccess('environment');
                    return;
                }
            }

            if (!envId) {
                ContentRouter.showNoAccess('environment');
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
                showError(error, 'Environment', 'This environment');
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
        openVmDetails(env.environmentId, q.vm);
    }

    /** The VM details side panel (U08-T05); a start or stop from it reloads this page. */
    function openVmDetails(envId, vmId, tab) {
        const env = currentEnvironment && currentEnvironment.environmentId === envId ? currentEnvironment : null;
        VmDetails.open(envId, vmId, { env, tab, onDone: () => reloadDetailIfCurrent(envId) });
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
            ApiClient.get(Config.API.environments.page(page, envPageSize, search || '',
                { includeGroups: true, state: state && state !== 'all' ? state.toUpperCase() : '', region: envRegionFilter, sort: envSort }))
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
    /** The page couldn't load (U17-T02): its heading and the shared panel with Try again. */
    function showError(xhr, title, what) {
        $('#content-area').html(UiStates.failedPage({ title, what, xhr }));
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
            <span id="env-list-filter">${FilterGroup.markup({ label: 'Show', options: STATE_FILTERS, active: envStateFilter, action: 'env-list-filter' })}</span>
            <span id="env-list-region"></span>
            <label class="env-list-sort"><span class="visually-hidden">Order</span>
                <select class="form-select form-select-sm" id="env-sort-select" data-action-change="env-list-sort">
                    ${SORTS.map(([v, l]) => `<option value="${v}"${v === envSort ? ' selected' : ''}>${l}</option>`).join('')}
                </select></label>
            <button type="button" class="btn btn-ghost btn-sm env-list-export" data-action="env-list-export">Export CSV</button>`;
    }

    // U22-T13 (staging review): order a 30-environment fleet by what is running or costing most.
    const SORTS = [['name', 'By name'], ['running', 'Most running'], ['cost', 'Highest cost']];

    function setSort(sort) {
        const next = SORTS.some(([v]) => v === sort) ? sort : 'name';
        if (next === envSort) return;
        envSort = next;
        refreshEnvList(0);
    }

    /** The CSV of every environment with the current search, Show and region filters. */
    function exportCsv() {
        const link = document.createElement('a');
        link.href = Config.API.environments.exportCsv({
            search: envSearchTerm || '',
            state: envStateFilter && envStateFilter !== 'all' ? envStateFilter.toUpperCase() : '',
            region: envRegionFilter
        });
        link.download = `environments-${new Date().toISOString().split('T')[0]}.csv`;
        document.body.appendChild(link);
        link.click();
        link.remove();
    }

    /**
     * The Schedule cell (U22-T13): when the next scheduled stop is ("Stops 19:00" today, "Stops Fri 19:00"
     * later), "Scheduled" when rules exist without a stop ahead, otherwise "No schedule".
     */
    function scheduleText(env, now = new Date()) {
        if (env && env.nextScheduledStop) {
            const d = new Date(env.nextScheduledStop);
            const pad = n => String(n).padStart(2, '0');
            const clock = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
            const sameDay = d.toDateString() === now.toDateString();
            return `Stops ${sameDay ? '' : d.toLocaleDateString('en-GB', { weekday: 'short' }) + ' '}${clock}`;
        }
        return env && env.scheduleRuleCount > 0 ? 'Scheduled' : 'No schedule';
    }

    /** The region filter (U19-T08): shown once the VMs are in more than one region; names from Config. */
    function regionFilterHtml(regions, active) {
        if (!regions || regions.length < 2) return '';
        const label = code => (typeof Config.regionLabel === 'function' ? Config.regionLabel(code) : code);
        return Utils.html`<label class="env-list-region"><span class="visually-hidden">Region</span>
            <select class="form-select form-select-sm" id="env-region-select" data-action-change="env-list-region">
                <option value="">Every region</option>${Utils.raw(regions.map(r => Utils.html`<option value="${r}"${Utils.raw(r === active ? ' selected' : '')}>${label(r)}</option>`).join(''))}
            </select></label>`;
    }

    async function loadRegionFilter(token) {
        try {
            const regions = await ApiClient.get(Config.API.environments.regionsInUse, { suppressGlobalError: true });
            if (token && !ContentRouter.isCurrent(token)) return;
            envRegions = Array.isArray(regions) ? regions : [];
            $('#env-list-region').html(regionFilterHtml(envRegions, envRegionFilter));
        } catch (e) {
            $('#env-list-region').empty(); // the list works without the filter
        }
    }

    let envRegions = [];

    /** The region select (Actions): page 0 with the new region. */
    function setRegionFilter(region) {
        const next = region || '';
        if (next === envRegionFilter) return;
        envRegionFilter = next;
        refreshEnvList(0);
    }

    /**
     * The Environments list (Environments.dc.html, Environments15.dc.html): search, the
     * All / Running / Stopped filter, one 38 px row per environment, "N environments" below and a
     * pager only past 15.
     */
    function buildListHtml(environments, totalElements) {
        if ((!environments || environments.length === 0) && !envSearchTerm && envStateFilter === 'all' && !envRegionFilter) {
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

        // On the page frame (U18-T02): the header stays, the table scrolls inside its panel, the pager stays.
        return `
            <div id="environments-list-view" class="page page-frame env-list-page">
            ${listHeader(totalElements, envTotals, listToolbarHtml())}
            <div class="page-fill">
            <section class="list-panel env-list-card" id="env-list-panel" aria-label="Environments">
                <div class="list-panel-body env-list-table-wrapper table-cards-wrap" tabindex="0" role="region" aria-label="Environments, scrolls">
                    <table class="table mb-0 table-cards env-list-table">
                        <thead>
                            <tr>
                                <th scope="col">Environment</th>
                                <th scope="col" class="col-schedule">Schedule</th>
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
                <div id="env-list-pagination" class="list-panel-foot env-list-foot"></div>
            </section>
            </div>
            </div>
        `;
    }

    /**
     * Rows a page follow the window (U18-T02): after the list mounts and whenever a resize settles,
     * the panel says how many rows fit; a new count reloads the page that keeps the first row in view.
     */
    function attachListFit() {
        const panel = document.getElementById('env-list-panel');
        if (!panel || typeof ListPanel === 'undefined') return;
        if (envListPanel) envListPanel.destroy();
        envListPanel = ListPanel.attach(panel, {
            rowSelector: '#env-list-body tr:not(.env-list-empty)', rowHeight: 38, min: 5, max: ENV_PAGE_SIZE,
            onFit: applyListFit
        });
        applyListFit(envListPanel.fitCount());
    }

    function applyListFit(count) {
        if (!count || count === envPageSize) return;
        const first = envCurrentPage * envPageSize;
        envPageSize = count;
        refreshEnvList(ListPanel.pageKeepingFirst(first, count) - 1);
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
            // U22-T13: the type and team move to the name's hover; the column shows the schedule.
            const about = [type, env.teamName].filter(Boolean).join(' · ');
            const schedule = scheduleText(env);

            return Utils.html`
                <tr>
                    <td class="td-primary col-name" data-label="Environment" title="${about}">
                        <a class="env-name-link" href="${href}">${name}</a>` + slug + Utils.html`
                    </td>
                    <td class="col-schedule${schedule === 'No schedule' ? ' text-muted' : ''}" data-label="Schedule">${schedule}</td>
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
        if (totalItems <= envPageSize) {
            $foot.html(`<div class="pager"><span class="pager-range">${totalItems} environment${totalItems === 1 ? '' : 's'}</span></div>`);
            return;
        }
        $foot.html(Pagination.buildPager({
            totalItems, page: page + 1, pageSize: envPageSize, action: 'env-list-page', key: 'environments',
        }));
    }

    /** Load another page, filter or search into the mounted list, keeping the toolbar. */
    // Only the newest list request draws (U22): the window-fit request sent on mount, without the search,
    // could return after a search typed meanwhile and put back the unfiltered rows.
    let envListRequest = 0;

    async function refreshEnvList(page) {
        const t = ContentRouter.token();
        const mine = ++envListRequest;
        try {
            const pageResult = await fetchEnvironmentsPage(page, envSearchTerm, envStateFilter);
            if (!ContentRouter.isCurrent(t) || mine !== envListRequest) return;
            envCurrentPage = page;
            environmentsList = pageResult.content;
            envTotalElements = pageResult.totalElements;
            envTotals = pageResult.totals;
            $('#env-list-body').html(environmentsList.length ? buildEnvRows(environmentsList) : emptyFilterRow());
            $('#env-list-summary').html(countsHtml(envTotalElements, envTotals));
            $('#env-list-filter').html(FilterGroup.markup({ label: 'Show', options: STATE_FILTERS, active: envStateFilter, action: 'env-list-filter' }));
            if (!$('#env-region-select').length) $('#env-list-region').html(regionFilterHtml(envRegions, envRegionFilter));
            renderEnvPagination(envTotalElements, envCurrentPage);
            if (envListPanel) envListPanel.refresh();
        } catch (error) {
            if (mine !== envListRequest) return;
            console.error('Failed to load environments page:', error);
            $('#env-list-body').html(`<tr><td colspan="9">${UiStates.failed({ what: 'This page of environments', xhr: error,
                onRetry: () => refreshEnvList(envCurrentPage) })}</td></tr>`);
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
    function detailMetaHtml(env, large) {
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
        if (!large) return `<p class="env-meta">${parts.join(' · ')}</p>`;
        // A large environment has no About column (EnvDetailLarge.dc.html): the lease and the lock are
        // part of this line ("Lease ends 20:00 · Not locked"), the lease filled in by Leases.render.
        return `<p class="env-meta">${parts.join(' · ')}<span id="env-meta-lease"></span> · ${metaLockHtml(env)}</p>`;
    }

    /** "Not locked · Lock" or "Locked by Rahul Verma" for the meta line of a large environment. */
    function metaLockHtml(env) {
        const lock = env.lockStatus;
        if (!lock) return '';
        if (lock.isLocked) return Utils.html`Locked by ${lock.lockedByDisplayName || 'someone'}`;
        const acquire = lock.canAcquire
            ? Utils.html` <button type="button" class="btn btn-link env-meta-action" data-lock-action="acquire" data-env-id="${env.environmentId}"
                data-expiry-enabled="${lock.lockExpiryEnabled ? 'true' : 'false'}">Lock</button>` : '';
        return 'Not locked' + acquire;
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
     * column (running count, cost, budget, idle auto-stop). Settings opens the environment's
     * settings page for its admins (U11-T07: groups, leases, Spot, Teams, webhooks, the stop notice);
     * others who may see its Spot state get the Spot side panel (EnvSettingsPanel).
     */
    function buildDetailHtml(env) {
        if (isPhone() && !isEks(env)) return buildPhoneDetailHtml(env);
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
                <a class="btn btn-primary btn-sm env-viewonly-ask" href="#/request-access?environmentId=${encodeURIComponent(env.environmentId)}">Ask for operator access</a>
            </div>`;

        const settings = typeof EnvSettingsPanel !== 'undefined' && EnvSettingsPanel.isAvailable(env)
            ? '<button type="button" class="btn btn-ghost" id="btn-env-settings">Settings</button>' : '';

        return `
            <div id="env-detail-view" class="page env-detail-page">
                ${PageHeader.markup({
                    title: envName,
                    breadcrumb: [{ label: 'Environments', href: '#/my-environments' }, { label: envName }],
                    summaryHtml: detailMetaHtml(env, large),
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

    // ---------------------------------------------------------------- the environment on a phone (U16-T03)

    const PHONE_QUERY = '(max-width: 768px)';
    const PHONE_GROUP_PAGE = 10;
    /** Per environment: the groups opened, and how many VMs each shows. */
    const phoneState = {};

    function isPhone() {
        return typeof window !== 'undefined' && window.matchMedia && window.matchMedia(PHONE_QUERY).matches;
    }

    function phoneStateFor(envId) {
        return phoneState[envId] || (phoneState[envId] = { open: new Set(), shown: {} });
    }

    function groupsInOrder(env) {
        return (env.groups || []).slice().sort((a, b) =>
            ((a.group || a).sequencePosition ?? 0) - ((b.group || b).sequencePosition ?? 0));
    }

    function groupIdOf(g) {
        return (g.group && g.group.groupId) || g.groupId;
    }

    function groupNameOf(g, i) {
        const x = g.group || g;
        return x.displayName || x.name || 'Group ' + (i + 1);
    }

    /** One VM in an opened group: name, state, its start or stop, and Details. */
    function phoneVmRow(vm, canOperate) {
        const st = EnvVmTable.model.state(vm);
        const act = EnvVmTable.model.vmAction(vm, canOperate);
        const name = vm.displayName || vm.name;
        const button = act === 'stop'
            ? Utils.html`<button type="button" class="btn btn-outline-secondary btn-sm" data-action="stop-vm" data-vm-id="${vm.vmId}" aria-label="Stop ${name}">Stop</button>`
            : act === 'start' || act === 'retry'
            ? Utils.html`<button type="button" class="btn btn-outline-secondary btn-sm" data-action="start-vm" data-vm-id="${vm.vmId}" aria-label="Start ${name}">${act === 'retry' ? 'Retry' : 'Start'}</button>`
            : '';
        return Utils.html`<li class="phone-vm">
                <span class="phone-vm-main"><b>${name}</b><span class="phone-vm-state state-${st.kind}">${st.text}</span></span>
                <span class="phone-vm-actions">${Utils.raw(button)}<button type="button" class="btn btn-link btn-sm" data-action="vm-details"
                    data-vm-id="${vm.vmId}" aria-label="Details of ${name}">Details</button></span></li>`;
    }

    function phoneGroupHtml(env, g, i, canOperate) {
        const id = groupIdOf(g);
        const ps = phoneStateFor(env.environmentId);
        const open = ps.open.has(id);
        const vms = g.vms || [];
        const shown = Math.min(vms.length, ps.shown[id] || PHONE_GROUP_PAGE);
        const strip = PowerStrip.render(PowerStrip.fromVmGroups([g]), { showCount: true });
        const listId = `phone-group-${String(id).replace(/[^A-Za-z0-9_-]/g, '')}`;
        const rows = vms.slice(0, shown).map(vm => phoneVmRow(vm, canOperate)).join('');
        const more = shown < vms.length ? Utils.html`<button type="button" class="btn btn-link phone-group-more" data-action="env-phone-more"
                data-group-id="${id}">Show ${String(Math.min(PHONE_GROUP_PAGE, vms.length - shown))} more</button>` : '';
        return Utils.html`<div class="phone-group">
                <button type="button" class="phone-group-row" data-action="env-phone-group" data-group-id="${id}"
                    aria-expanded="${open ? 'true' : 'false'}" aria-controls="${listId}">
                    <span class="phone-group-name">${groupNameOf(g, i)}</span>${Utils.raw(strip)}
                    <svg class="phone-group-chevron" width="14" height="14" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="2"
                        stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m8 5 5 5-5 5"/></svg></button>
                <div class="phone-group-vms" id="${listId}"${Utils.raw(open ? '' : ' hidden')}><ul>${Utils.raw(rows)}</ul>${Utils.raw(more)}</div>
            </div>`;
    }

    /**
     * The environment on a phone (PhoneEnv.dc.html): back, name and History; the state with a large strip
     * labelled by group and the two big actions; Lease, Lock and this month's cost; then one row per group
     * that opens to its VMs. The same ids as the desktop page, so leases, locks, cost, alerts and the
     * operations all work as they do there.
     */
    function buildPhoneDetailHtml(env) {
        const canOperate = env.viewerCanOperate === true;
        const envName = env.displayName || env.name;
        const groups = groupsInOrder(env);
        let lockFact = '';
        try {
            lockFact = (typeof Locks !== 'undefined' && Locks.buildLockFact) ? Locks.buildLockFact(env.lockStatus, env.environmentId) : '';
        } catch (e) { lockFact = ''; }
        const strips = groups.map((g, i) => Utils.html`<span class="phone-stage">`
            + PowerStrip.render(PowerStrip.fromVmGroups([g]), { large: true, showCount: false })
            + Utils.html`<span class="phone-stage-name">${groupNameOf(g, i)}</span></span>`).join('');
        const isEnvAdmin = typeof Auth !== 'undefined' && Auth.isEnvAdmin && Auth.isEnvAdmin();
        const settings = typeof EnvSettingsPanel !== 'undefined' && EnvSettingsPanel.isAvailable(env)
            ? '<button type="button" class="btn btn-link" id="btn-env-settings">Settings</button>' : '';
        const viewOnly = canOperate ? '' : Utils.html`<p class="env-viewonly-note phone-viewonly" role="note">You can see this environment but not
            start or stop it. <a class="env-viewonly-ask" href="#/request-access?environmentId=${encodeURIComponent(env.environmentId)}">Ask for operator access</a></p>`;
        setTitle(envName);
        return `
            <div id="env-detail-view" class="page env-detail-page phone-env">
                <header class="phone-page-head">
                    <a class="phone-back" href="#/my-environments" id="phone-back" aria-label="Back to environments">
                        <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"
                            stroke-linejoin="round" aria-hidden="true"><path d="m12 4-6 6 6 6"/></svg></a>
                    <h1 class="phone-page-title">${Utils.escapeHtml(envName)}</h1>
                    <button type="button" class="btn btn-link phone-head-link" id="btn-operation-history">History</button>
                </header>
                <div id="env-alerts" class="env-alerts"></div>
                ${viewOnly}
                <section class="phone-env-state" aria-label="State">
                    <div class="phone-env-count"><b class="mono">${env.runningVms || 0}</b> of <b class="mono">${env.totalVms || 0}</b> VMs running</div>
                    <div class="phone-stages">${strips}</div>
                    <div class="phone-env-actions env-detail-actions">${headerOperationButtons(env)}</div>
                </section>
                <section class="phone-env-details" aria-label="Details">
                    <div id="env-fact-lease"></div>
                    <div id="env-fact-lock">${lockFact}</div>
                    <div id="env-fact-schedule"></div>
                    <div id="env-fact-idle"></div>
                    <div class="env-cost-fact"><section id="env-cost-card" aria-label="Environment cost"></section><div id="env-budget-panel"></div></div>
                </section>
                <section id="env-vms" class="phone-groups" aria-label="Groups and VMs">
                    ${groups.map((g, i) => phoneGroupHtml(env, g, i, canOperate)).join('')}
                </section>
                <nav class="phone-env-more" aria-label="More for this environment">
                    <button type="button" class="btn btn-link" id="btn-env-insights">Insights</button>
                    ${isEnvAdmin ? '<button type="button" class="btn btn-link" id="btn-env-manage-access">Access</button>' : ''}
                    ${settings}
                </nav>
            </div>`;
    }

    function setTitle(name) {
        if (typeof PageHeader !== 'undefined' && PageHeader.setDocumentTitle) PageHeader.setDocumentTitle(name);
    }

    /** Opens or closes a group's VMs in place (aria-expanded), without redrawing the page. */
    function togglePhoneGroup(button) {
        const env = currentEnvironment;
        if (!env) return;
        const id = button.getAttribute('data-group-id');
        const ps = phoneStateFor(env.environmentId);
        const open = button.getAttribute('aria-expanded') !== 'true';
        if (open) ps.open.add(id); else ps.open.delete(id);
        button.setAttribute('aria-expanded', String(open));
        const list = document.getElementById(button.getAttribute('aria-controls'));
        if (list) list.hidden = !open;
    }

    function showMorePhoneVms(button) {
        const env = currentEnvironment;
        if (!env) return;
        const id = button.getAttribute('data-group-id');
        const ps = phoneStateFor(env.environmentId);
        ps.shown[id] = (ps.shown[id] || PHONE_GROUP_PAGE) + PHONE_GROUP_PAGE;
        const groups = groupsInOrder(env);
        const i = groups.findIndex(g => String(groupIdOf(g)) === id);
        if (i < 0) return;
        const holder = button.closest('.phone-group');
        holder.outerHTML = phoneGroupHtml(env, groups[i], i, env.viewerCanOperate === true);
    }

    /** Crossing the phone width redraws the page in the other form, from what it already loaded. */
    if (typeof window !== 'undefined' && window.matchMedia) {
        const media = window.matchMedia(PHONE_QUERY);
        const redraw = () => {
            if (!currentEnvironment || !document.getElementById('env-detail-view') || !isActive()) return;
            $('#content-area').html(buildDetailHtml(currentEnvironment));
            bindDetailEvents(currentEnvironment);
        };
        if (media.addEventListener) media.addEventListener('change', redraw); else if (media.addListener) media.addListener(redraw);
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
        attachListFit();
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
            EnvAlerts.set('region', EnvAlerts.model.regionAlert(env, env.viewerCanAdminister === true), envId);
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

        // On a phone the page's own back header replaces the top bar (PhoneEnv board, U17-T10).
        document.body.classList.toggle('phone-drilldown', $('#phone-back').length > 0);

        // Phone back (U16-T03): the previous page when it was in this app, else Environments.
        $('#phone-back').off('click').on('click', function(e) {
            if (window.history.length > 1) {
                e.preventDefault();
                window.history.back();
            }
        });

        // Operation history
        $('#btn-operation-history').off('click').on('click', function(e) {
            e.preventDefault();
            showOperationHistory(envId, envName);
        });

        // Group action button (single contextual — Start or Stop)
        $('#btn-env-insights').off('click').on('click', function(e) {
            e.preventDefault();
            EnvInsights.open(env);
        });

        $('#btn-env-manage-access').off('click').on('click', function(e) {
            e.preventDefault();
            if (typeof AccessManagement !== 'undefined' && AccessManagement.openForEnvironment) {
                AccessManagement.openForEnvironment(env.environmentId);
            }
        });

        $('#btn-env-settings').off('click').on('click', function(e) {
            e.preventDefault();
            if (env.viewerCanAdminister === true) {
                window.location.hash = `#/environments/${encodeURIComponent(env.environmentId)}/settings`;
            } else {
                EnvSettingsPanel.open(env);
            }
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
                    openVmDetails(id, this.getAttribute('data-vm-id'));
                } else if (action === 'env-phone-group') {
                    togglePhoneGroup(this);
                } else if (action === 'env-phone-more') {
                    showMorePhoneVms(this);
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

    /** The history side panel (U08-T04); Retry in it needs the environment's VMs. */
    function showOperationHistory(envId, envName) {
        const env = currentEnvironment && currentEnvironment.environmentId === envId ? currentEnvironment
            : { environmentId: envId, displayName: envName, groups: [] };
        OperationHistory.open(env);
    }

    return {
        loadList,
        loadDetail,
        setStateFilter,
        setRegionFilter,
        setSort,
        exportCsv,
        goToEnvPage,
        /** VM details from the drift alert (U07-T07), on its History tab. */
        openVm: vmId => currentEnvironment && openVmDetails(currentEnvironment.environmentId, vmId, 'history'),
        _search: function(val) {
            filterAndRenderEnvList((val || '').toLowerCase().trim());
        },
        model: { regionFilterHtml, scheduleText }
    };
})();

Actions.register('env-search', el => Environments._search(el.value), 'input');
// The list's All / Running / Stopped switch and its pager past 15 (U06-T05).
Actions.register('env-list-filter', el => Environments.setStateFilter(el.getAttribute('data-filter')));
Actions.register('env-list-region', el => Environments.setRegionFilter(el.value), 'change');
Actions.register('env-list-sort', el => Environments.setSort(el.value), 'change');
Actions.register('env-list-export', () => Environments.exportCsv());
Actions.register('env-list-page', el => Environments.goToEnvPage(el.getAttribute('data-page')));
Actions.register('env-alert-vm', el => Environments.openVm(el.getAttribute('data-vm-id')));
