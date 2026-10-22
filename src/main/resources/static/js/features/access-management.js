/**
 * Access management (U10-T04, AccessManagement.dc.html): who can use which environment, at what
 * level, and until when. One paged list from the server (GET /access-grants, 10 a page) with
 * search, environment and status filters (Active, Ending within 7 days), the header counts from
 * /access-grants/summary (the "end within 7 days" count picks the Ending filter), a History tab
 * of ended grants, and Change / Revoke per row (not on your own access), which open the Change
 * access and Remove access dialogs (U10-T05). Waiting access requests are a link to Access
 * requests; Grant access is its own page (U10-T06, grant-access.js).
 */
const AccessManagement = (function() {
    'use strict';

    const PAGE_SIZE = 10;
    const NS = '.accessMgmt';

    // State
    let environments = [];
    let rows = [];
    let total = 0;
    let summary = null;
    let pendingCount = 0;
    let tab = 'grants';
    let selectedEnvironmentId = '';
    let currentSearch = '';
    let currentStatus = 'ACTIVE';
    let currentPage = 0;


    // Navigation token of the current visit (see the page contract in core/router.js).
    let pageToken = null;

    function isActive() {
        return pageToken !== null && ContentRouter.isCurrent(pageToken);
    }

    function call(url) {
        return new Promise((resolve, reject) => {
            ApiClient.get(url, { suppressGlobalError: true }).done(resolve).fail(reject);
        });
    }

    /** Router loader. */
    async function load() {
        pageToken = ContentRouter.token();
        ContentRouter.onLeave(teardown);
        if (!Auth.isEnvAdmin()) {
            $('#content-area').html('<div class="alert alert-danger m-3">Access denied. Admin or Env Admin only.</div>');
            return;
        }
        tab = 'grants';
        currentPage = 0;
        Loading.showPage('Loading access…');
        try {
            const [envList, pending] = await Promise.all([
                call(Config.API.environments.list),
                call(Config.API.access.pendingRequests).catch(() => [])
            ]);
            if (!isActive()) return;
            environments = Array.isArray(envList) ? envList : [];
            pendingCount = Array.isArray(pending) ? pending.length : 0;
            if (selectedEnvironmentId && !environments.some(e => e.environmentId === selectedEnvironmentId)) {
                selectedEnvironmentId = '';
            }
            render();
            await refreshList();
        } catch (error) {
            if (!isActive()) return;
            showError(error);
        }
    }

    /** Runs when the user leaves Access management. */
    function teardown() {
        $(document).off(NS);
    }

    function showError(xhr) {
        $('#content-area').html(UiStates.failedPage({ title: 'Access management', what: 'Access', xhr }));
    }

    /** Grant, change and revoke follow the server's flag for each environment (U02-T02). */
    function canManageAccessTo(environmentId) {
        const env = environments.find(e => e.environmentId === environmentId);
        return !!env && env.viewerCanManageAccess === true;
    }

    function isMe(row) {
        return typeof Auth !== 'undefined' && Auth.getUserId && row.userId === Auth.getUserId();
    }

    // ---- page ----

    function render() {
        const canGrant = environments.some(e => e.viewerCanManageAccess === true);
        const waiting = pendingCount > 0
            ? `<a class="am-waiting" href="#/pending-requests" id="am-pending-link">${pendingCount} access request${pendingCount === 1 ? '' : 's'} waiting</a>` : '';
        const grantBtn = canGrant ? `<a class="btn btn-primary" id="btn-grant-access" href="#/access-management/grant">
                <svg width="14" height="14" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M10 4v12M4 10h12"></path></svg>
                Grant access</a>` : '';
        const envOptions = ['<option value="">All environments</option>'].concat(environments.map(env =>
            Utils.html`<option value="${env.environmentId}" ${env.environmentId === selectedEnvironmentId ? 'selected' : ''}>${env.displayName || env.name}</option>`)).join('');
        $('#content-area').html(`
            <div class="content-view am-page" id="access-management-view">
                ${PageHeader.markup({ title: 'Access management',
                    summaryHtml: 'Who can use which environment, at what level, and until when.',
                    actionsHtml: waiting + grantBtn, helpPage: 'access-management', helpLabel: 'Access management' })}
                <div class="am-stats" id="am-stats" aria-live="polite"></div>
                <div class="am-tabs" role="tablist" aria-label="Access">
                    <button type="button" role="tab" class="am-tab" id="access-tab-grants" aria-selected="${tab === 'grants'}" aria-controls="am-panel" data-am-tab="grants">Grants</button>
                    <button type="button" role="tab" class="am-tab" id="access-tab-history" aria-selected="${tab === 'history'}" aria-controls="am-panel" data-am-tab="history">History</button>
                </div>
                <div class="am-filters">
                    <label class="am-search">
                        <svg width="15" height="15" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><circle cx="9" cy="9" r="5.5"></circle><path d="m13.5 13.5 3.5 3.5"></path></svg>
                        <span class="visually-hidden">Search people</span>
                        <input type="search" id="access-search" placeholder="Name or email" autocomplete="off" value="${Utils.escapeHtml(currentSearch)}">
                    </label>
                    <select class="form-select" id="filter-environment" aria-label="Environment">${envOptions}</select>
                    <select class="form-select" id="filter-status" aria-label="Status" ${tab === 'history' ? 'hidden' : ''}>
                        <option value="ACTIVE" ${currentStatus === 'ACTIVE' ? 'selected' : ''}>Active</option>
                        <option value="ENDING" ${currentStatus === 'ENDING' ? 'selected' : ''}>Ending within 7 days</option>
                    </select>
                </div>
                <div class="am-card" id="am-panel" role="tabpanel" aria-labelledby="access-tab-${tab}">
                    <div class="am-table-wrap">
                        <table class="am-table" id="access-table">
                            <thead id="access-table-head"></thead>
                            <tbody id="access-table-body"></tbody>
                        </table>
                    </div>
                    <div class="pagination-bar-wrap" id="access-pagination"></div>
                </div>
            </div>
        `);
        bindEvents();
    }

    function drawStats() {
        const s = summary || { total: 0, admins: 0, operators: 0, viewers: 0, endingWithin7Days: 0 };
        const n = (value, label) => `<span class="am-stat"><b>${value}</b> ${label}</span>`;
        const ending = s.endingWithin7Days > 0
            ? `<button type="button" class="am-stat am-stat-soon" id="am-ending" aria-label="Show the ${s.endingWithin7Days} grants that end within 7 days"><b>${s.endingWithin7Days}</b> end within 7 days</button>`
            : n(0, 'end within 7 days');
        $('#am-stats').html(n(s.total, s.total === 1 ? 'grant' : 'grants') + n(s.admins, s.admins === 1 ? 'admin' : 'admins')
            + n(s.operators, s.operators === 1 ? 'operator' : 'operators') + n(s.viewers, 'view only') + ending);
    }

    /** Fetch the current page and the counts from the server, then draw them. */
    async function refreshList() {
        const token = pageToken;
        const params = { page: currentPage, size: PAGE_SIZE, status: tab === 'history' ? 'ENDED' : currentStatus };
        if (selectedEnvironmentId) params.environmentId = selectedEnvironmentId;
        if (currentSearch.trim()) params.search = currentSearch.trim();
        const summaryParams = selectedEnvironmentId ? { environmentId: selectedEnvironmentId } : null;
        $('#access-table-body').attr('aria-busy', 'true');
        try {
            const [page, counts] = await Promise.all([
                call(Config.API.access.grants(params)),
                call(Config.API.access.grantsSummary(summaryParams))
            ]);
            if (!ContentRouter.isCurrent(token)) return;
            rows = (page && page.content) || [];
            total = page && page.page ? page.page.totalElements : (page && page.totalElements) || rows.length;
            summary = counts;
            drawStats();
            drawTable();
        } catch (error) {
            if (!ContentRouter.isCurrent(token)) return;
            rows = [];
            total = 0;
            $('#access-table-body').html(`<tr><td colspan="7">${UiStates.failed({ what: 'The grants', xhr: error, onRetry: () => refreshList() })}</td></tr>`);
        } finally {
            $('#access-table-body').attr('aria-busy', 'false');
        }
    }

    function partText(row) {
        return row.scopeType === 'GROUP' ? `${row.scopeName || row.scopeId} group` : 'Whole';
    }

    function drawTable() {
        const history = tab === 'history';
        $('#access-table-head').html(`<tr><th scope="col">Person</th><th scope="col">Environment</th><th scope="col">Level</th>
            <th scope="col">Part</th><th scope="col">Granted by</th><th scope="col">${history ? 'Ended' : 'Ends'}</th>
            ${history ? '' : '<th scope="col" class="am-actions"><span class="visually-hidden">Actions</span></th>'}</tr>`);
        if (!rows.length) {
            const filtered = currentSearch.trim() || selectedEnvironmentId || (!history && currentStatus !== 'ACTIVE');
            $('#access-table-body').html(`<tr><td colspan="${history ? 6 : 7}" class="am-empty">${history
                ? 'No grants ended in the last 90 days.' : filtered ? 'No grants match these filters.' : 'Nobody has access yet.'}</td></tr>`);
        } else {
            $('#access-table-body').html(rows.map(history ? historyRow : grantRow).join(''));
        }
        Pagination.renderNumbered('#access-pagination', {
            page: currentPage, totalItems: total, pageSize: PAGE_SIZE, itemLabel: 'grants', zeroIndexed: true,
            bindAncestor: '#content-area',
            onPageChange: (p) => { currentPage = p; refreshList(); }
        });
    }

    function personCell(row) {
        return Utils.html`<td class="am-cell-person"><div class="am-person">${row.userDisplayName || row.userEmail || 'Unknown'}</div>
            <div class="am-email">${row.userEmail || ''}</div></td>`;
    }

    function levelCell(row) {
        return Utils.html`<td data-label="Level"><span class="access-level-badge ${AccessLevels.chipClass(row.accessLevel)}">${AccessLevels.label(row.accessLevel)}</span></td>`;
    }

    function grantRow(row) {
        const ends = AccessLevels.endsText(row.expiresAt);
        let actions = '';
        if (isMe(row)) {
            actions = '<span class="am-own" title="You can\'t change your own access">Your access</span>';
        } else if (canManageAccessTo(row.environmentId)) {
            actions = Utils.html`<button type="button" class="am-link" data-action="edit" data-access-id="${row.accessId}"
                    aria-label="Change ${row.userDisplayName || row.userEmail}'s access to ${row.environmentName}">Change</button>
                <button type="button" class="am-link am-danger" data-action="revoke" data-access-id="${row.accessId}"
                    aria-label="Revoke ${row.userDisplayName || row.userEmail}'s access to ${row.environmentName}">Revoke</button>`;
        }
        return Utils.html`<tr data-access-id="${row.accessId}">
            ${Utils.raw(personCell(row))}
            <td data-label="Environment">${row.environmentName || row.environmentId || ''}</td>
            ${Utils.raw(levelCell(row))}
            <td data-label="Part">${partText(row)}</td>
            <td data-label="Granted by">${row.grantedByUserName || 'System'}</td>
            <td class="am-ends${ends.soon ? ' soon' : ''}" data-label="Ends">${ends.text}</td>
            <td class="am-actions">${Utils.raw(actions)}</td>
        </tr>`;
    }

    function historyRow(row) {
        const when = row.endedAt ? AccessLevels.endsText(row.endedAt).text.replace(/^Ended /, '') : '';
        const how = row.status === 'REVOKED' ? 'Removed' : 'Ended';
        return Utils.html`<tr data-access-id="${row.accessId}">
            ${Utils.raw(personCell(row))}
            <td data-label="Environment">${row.environmentName || row.environmentId || ''}</td>
            ${Utils.raw(levelCell(row))}
            <td data-label="Part">${partText(row)}</td>
            <td data-label="Granted by">${row.grantedByUserName || 'System'}</td>
            <td class="am-ends" data-label="Ended">${how} ${when}</td>
        </tr>`;
    }

    function setTab(next) {
        if (tab === next) return;
        tab = next;
        currentPage = 0;
        $('[data-am-tab]').each(function() { this.setAttribute('aria-selected', String(this.getAttribute('data-am-tab') === tab)); });
        $('#am-panel').attr('aria-labelledby', `access-tab-${tab}`);
        $('#filter-status').prop('hidden', tab === 'history');
        refreshList();
    }

    /**
     * Bind the page's events; delegated on the document under one namespace, removed on leave.
     */
    function bindEvents() {
        $(document).off(NS)
            .on('click' + NS, '[data-am-tab]', function() { setTab(this.getAttribute('data-am-tab')); })
            .on('keydown' + NS, '[data-am-tab]', function(e) {
                if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
                e.preventDefault();
                const next = tab === 'grants' ? 'history' : 'grants';
                setTab(next);
                $(`#access-tab-${next}`).trigger('focus');
            })
            .on('input' + NS, '#access-search', Utils.debounce(function() {
                currentSearch = this.value;
                currentPage = 0;
                refreshList();
            }, 300))
            .on('change' + NS, '#filter-environment', function() {
                selectedEnvironmentId = this.value;
                currentPage = 0;
                refreshList();
            })
            .on('change' + NS, '#filter-status', function() {
                currentStatus = this.value;
                currentPage = 0;
                refreshList();
            })
            .on('click' + NS, '#am-ending', function() {
                if (tab !== 'grants') setTab('grants');
                currentStatus = 'ENDING';
                $('#filter-status').val('ENDING');
                currentPage = 0;
                refreshList();
            })
            .on('click' + NS, '#access-table-body [data-action="revoke"]', function() {
                const access = rows.find(a => a.accessId === $(this).data('access-id'));
                if (access) AccessChangeDialogs.openRemove(access, refreshAccessData);
            })
            .on('click' + NS, '#access-table-body [data-action="edit"]', function() {
                const access = rows.find(a => a.accessId === $(this).data('access-id'));
                if (access) AccessChangeDialogs.openChange(access, refreshAccessData, { mine: isMe(access) });
            });
    }

    /**
     * Public entry point: open Grant access with one environment chosen. Used by "Manage access"
     * on the environment page.
     */
    function openForEnvironment(envId) {
        window.location.hash = '#/access-management/grant?environmentId=' + encodeURIComponent(envId);
    }

    /** Reload the list and the counts after a grant, change or revoke. */
    async function refreshAccessData() {
        await refreshList();
    }

    // Public API
    return {
        load: load,
        openForEnvironment: openForEnvironment
    };

})();

// Make AccessManagement available globally
window.AccessManagement = AccessManagement;
