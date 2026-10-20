/**
 * Access management (U10-T04, AccessManagement.dc.html): who can use which environment, at what
 * level, and until when. One paged list from the server (GET /access-grants, 10 a page) with
 * search, environment and status filters (Active, Ending within 7 days), the header counts from
 * /access-grants/summary (the "end within 7 days" count picks the Ending filter), a History tab
 * of ended grants, and Change / Revoke per row (not on your own access), which open the Change
 * access and Remove access dialogs (U10-T05). Waiting access requests are a link to Access
 * requests. The Grant access modal is unchanged here (U10-T06 replaces it).
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

    // Autocomplete instances for the Grant Access modal (initialized in bindModalEvents)
    let grantEnvironmentAutocomplete = null;
    let grantUserAutocomplete = null;

    // The user chosen in the Grant Access modal, once picked from the typeahead.
    // { source: 'app', email } for an existing user, or
    // { source: 'directory', directoryObjectId, email, displayName } for an Entra directory
    // match that will be onboarded as a normal user on submit. Null until a pick is made;
    // cleared whenever the user edits the search box.
    let grantUserPick = null;

    // Set by openForEnvironment() before navigating here; consumed once the view renders.
    let pendingOpenEnvId = null;

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
            applyPendingOpen();
        } catch (error) {
            if (!isActive()) return;
            showError(error && error.status === 403
                ? 'You do not have permission to manage access.' : "Access didn't load. Try again in a moment.");
        }
    }

    /** Runs when the user leaves Access management. */
    function teardown() {
        $(document).off('.accessAutocomplete');
        $(document).off(NS);
    }

    function showError(message) {
        $('#content-area').html(Utils.html`<div class="content-view"><div class="content-header"><h1>Access management</h1></div>
            <div class="alert alert-danger" role="alert">${message}</div></div>`);
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
        const grantBtn = canGrant ? `<button type="button" class="btn btn-primary" id="btn-grant-access">
                <svg width="14" height="14" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M10 4v12M4 10h12"></path></svg>
                Grant access</button>` : '';
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
            <!-- Grant Access Modal -->
            <div class="modal fade" id="grantAccessModal" tabindex="-1" aria-hidden="true">
                <div class="modal-dialog">
                    <div class="modal-content">
                        <div class="modal-header">
                            <h5 class="modal-title" id="grant-modal-title"><i class="fas fa-user-plus me-2"></i>Grant Access</h5>
                            <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
                        </div>
                        <div class="modal-body">
                            <form id="grant-access-form">
                                <input type="hidden" id="grant-access-id">
                                <div class="mb-3 access-autocomplete-field">
                                    <label class="form-label" for="grant-environment-search">Environment</label>
                                    <input type="text" class="form-control" id="grant-environment-search" autocomplete="off"
                                           placeholder="Type at least 2 characters to search environments...">
                                    <input type="hidden" id="grant-environment">
                                    <div class="access-autocomplete-menu" id="grant-environment-menu"></div>
                                </div>
                                <div class="mb-3 access-autocomplete-field">
                                    <label class="form-label" for="grant-user-search">User</label>
                                    <input type="text" class="form-control" id="grant-user-search" autocomplete="off"
                                           placeholder="Type at least 2 characters to search by name or email...">
                                    <input type="hidden" id="grant-user-id">
                                    <div class="access-autocomplete-menu" id="grant-user-menu"></div>
                                    <div class="form-text">Searches app users first, then the Microsoft Entra directory (admin only). Picking a directory match onboards that person as a normal user, then grants.</div>
                                </div>
                                <div class="mb-3" id="grant-scope-block">
                                    <label class="form-label d-block">Scope</label>
                                    <div class="form-check">
                                        <input class="form-check-input" type="radio" name="grant-scope" id="grant-scope-env" value="ENVIRONMENT" checked>
                                        <label class="form-check-label" for="grant-scope-env">Whole environment <span class="text-muted">— every group, now and future</span></label>
                                    </div>
                                    <div class="form-check">
                                        <input class="form-check-input" type="radio" name="grant-scope" id="grant-scope-group" value="GROUP">
                                        <label class="form-check-label" for="grant-scope-group">Specific groups</label>
                                    </div>
                                </div>
                                <div class="mb-3 grant-group-checklist" id="grant-group-checklist" hidden>
                                    <div class="grant-checklist-head">
                                        <input type="text" class="form-control form-control-sm" id="grant-group-filter" placeholder="Filter groups">
                                        <label class="grant-selall mb-0"><input type="checkbox" id="grant-group-selall"> Select all</label>
                                    </div>
                                    <ul id="grant-group-list" class="grant-checklist-items">
                                        <li class="text-muted small p-2">Pick an environment first.</li>
                                    </ul>
                                </div>
                                <div class="mb-3">
                                    <label class="form-label">Access Level</label>
                                    <select class="form-select" id="grant-access-level" required>
                                        <option value="VIEWER">View only - See state, cost, history</option>
                                        <option value="USER" selected>Operator - Start, stop and lock</option>
                                        <option value="ADMIN">Admin - Also grant access and edit</option>
                                    </select>
                                </div>
                                <div class="mb-3">
                                    <label class="form-label" for="grant-duration">Expiry</label>
                                    <select class="form-select" id="grant-duration">
                                        <option value="permanent">Permanent (no expiry)</option>
                                        <option value="7">7 days from today</option>
                                        <option value="30">30 days from today</option>
                                        <option value="90">90 days from today</option>
                                        <option value="180">180 days from today</option>
                                        <option value="365">1 year from today</option>
                                    </select>
                                </div>
                                <div class="mb-3">
                                    <label class="form-label">Notes (optional)</label>
                                    <textarea class="form-control" id="grant-notes" rows="2"
                                              placeholder="Reason for access grant..."></textarea>
                                </div>
                            </form>
                        </div>
                        <div class="modal-footer">
                            <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">Cancel</button>
                            <button type="button" class="btn btn-primary" id="btn-confirm-grant">
                                <i class="fas fa-check me-1"></i><span id="btn-confirm-grant-label">Grant Access</span>
                            </button>
                        </div>
                    </div>
                </div>
            </div>
        `);
        bindEvents();
        bindModalEvents();
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
            $('#access-table-body').html('<tr><td colspan="7" class="am-empty" role="alert">The grants didn\'t load. <button type="button" class="am-link" id="am-retry">Try again</button></td></tr>');
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
            .on('click' + NS, '#am-retry', () => refreshList())
            .on('click' + NS, '#access-table-body [data-action="revoke"]', function() {
                const access = rows.find(a => a.accessId === $(this).data('access-id'));
                if (access) AccessChangeDialogs.openRemove(access, refreshAccessData);
            })
            .on('click' + NS, '#access-table-body [data-action="edit"]', function() {
                const access = rows.find(a => a.accessId === $(this).data('access-id'));
                if (access) AccessChangeDialogs.openChange(access, refreshAccessData, { mine: isMe(access) });
            });
    }

    /** The grant / change dialog's controls (kept from E04 until U10-T05 / U10-T06). */
    function bindModalEvents() {
        // Grant Access button
        $('#btn-grant-access').off('click').on('click', showGrantAccessModal);

        // Confirm grant
        $('#btn-confirm-grant').off('click').on('click', handleGrantAccess);

        // Environment / User autocompletes in the Grant Access modal
        grantEnvironmentAutocomplete = initAutocomplete({
            inputSelector: '#grant-environment-search',
            hiddenSelector: '#grant-environment',
            menuSelector: '#grant-environment-menu',
            minChars: 2,
            fetchSuggestions: (query) => {
                const q = query.toLowerCase();
                return Promise.resolve(
                    environments
                        .filter(env => (env.displayName || env.name || '').toLowerCase().includes(q))
                        .slice(0, 20)
                        .map(env => ({ id: env.environmentId, label: env.displayName || env.name }))
                );
            },
            onSelect: (envId) => { if (grantScope() === 'GROUP') loadGroupsForGrant(envId); }
        });

        // Scope toggle + group checklist wiring
        $('input[name="grant-scope"]').off('change').on('change', function() {
            const isGroup = grantScope() === 'GROUP';
            document.getElementById('grant-group-checklist').hidden = !isGroup;
            if (isGroup) {
                const envId = $('#grant-environment').val();
                if (envId) loadGroupsForGrant(envId);
            }
        });
        $('#grant-group-selall').off('change').on('change', function() {
            $('#grant-group-list .grant-group-cb:visible').prop('checked', this.checked);
        });
        $('#grant-group-filter').off('input').on('input', function() {
            const q = this.value.trim().toLowerCase();
            $('#grant-group-list li').each(function() {
                const name = ($(this).data('name') || '').toLowerCase();
                $(this).toggle(!q || name.includes(q));
            });
        });

        grantUserAutocomplete = initAutocomplete({
            inputSelector: '#grant-user-search',
            hiddenSelector: '#grant-user-id',
            menuSelector: '#grant-user-menu',
            minChars: 2,
            fetchSuggestions: searchUsersThenDirectory,
            onInput: () => { grantUserPick = null; setGrantButtonMode('grant'); },
            onSelect: (id, label, item) => {
                grantUserPick = (item && item.data) || null;
                setGrantButtonMode(grantUserPick && grantUserPick.source === 'directory' ? 'onboard' : 'grant');
            }
        });

    }

    /**
     * Show grant access modal
     */
    function grantScope() {
        return $('input[name="grant-scope"]:checked').val() || 'ENVIRONMENT';
    }

    /**
     * Load an environment's groups into the modal checklist (name · N VMs · M running).
     */
    function loadGroupsForGrant(envId, preCheckedGroupIds, readOnly) {
        const $list = $('#grant-group-list');
        if (!envId) { $list.html('<li class="text-muted small p-2">Pick an environment first.</li>'); return; }
        $list.html('<li class="text-muted small p-2"><i class="fas fa-spinner fa-spin me-1"></i>Loading groups…</li>');
        ApiClient.get(Config.API.groups.list(envId))
            .done(groups => {
                const checked = new Set(preCheckedGroupIds || []);
                if (!groups || groups.length === 0) {
                    $list.html('<li class="text-muted small p-2">This environment has no groups.</li>');
                    return;
                }
                const rows = readOnly ? groups.filter(g => checked.has(g.groupId)) : groups;
                $list.html(rows.map(g => `
                    <li data-name="${Utils.escapeHtml(g.displayName || g.name)}">
                        <label>
                            <input type="checkbox" class="grant-group-cb" value="${g.groupId}"
                                   ${checked.has(g.groupId) ? 'checked' : ''} ${readOnly ? 'disabled' : ''}>
                            <span class="grant-group-name">${Utils.escapeHtml(g.displayName || g.name)}</span>
                        </label>
                        <span class="grant-group-meta">${g.vmCount || 0} VMs · ${g.runningVmCount || 0} running</span>
                    </li>`).join(''));
            })
            .fail(() => $list.html('<li class="text-danger small p-2">Failed to load groups.</li>'));
    }

    /**
     * The expiry choice as request fields: a number of days from today, or permanent (clear the
     * expiry).
     */
    function expiryChoice(value) {
        return {
            durationDays: /^\d+$/.test(value) ? parseInt(value, 10) : null,
            clearExpiry: value === 'permanent'
        };
    }

    /** Open the Grant access modal for a new grant (changing one is AccessChangeDialogs, U10-T05). */
    function showGrantAccessModal() {
        $('#grant-access-id').val('');
        $('#grant-modal-title').html('<i class="fas fa-user-plus me-2"></i>Grant Access');
        $('#btn-confirm-grant-label').text('Grant Access');
        $('#grant-scope-block, #grant-group-filter').show();
        $('#grant-user-search, #grant-environment-search').prop('disabled', false);
        grantUserPick = null;
        grantUserAutocomplete && grantUserAutocomplete.reset();
        const preselected = selectedEnvironmentId && environments.find(env => env.environmentId === selectedEnvironmentId);
        if (grantEnvironmentAutocomplete) {
            preselected
                ? grantEnvironmentAutocomplete.setValue(preselected.displayName || preselected.name, preselected.environmentId)
                : grantEnvironmentAutocomplete.reset();
        }
        $('#grant-scope-env').prop('checked', true);
        $('#grant-scope-group').prop('checked', false);
        document.getElementById('grant-group-checklist').hidden = true;
        $('#grant-group-list').html('<li class="text-muted small p-2">Pick an environment first.</li>');
        $('#grant-group-selall').prop('checked', false);
        $('#grant-group-filter').val('');
        $('#grant-access-level').val('USER');
        $('#grant-duration').val('permanent');
        $('#grant-notes').val('');
        bootstrap.Modal.getOrCreateInstance(document.getElementById('grantAccessModal')).show();
    }

    /**
     * Public entry point: jump to Access Management scoped to one environment and open the
     * grant modal. Used by the "Manage access" button on the environment detail view.
     */
    function openForEnvironment(envId) {
        pendingOpenEnvId = envId;
        if ($('#access-table-body').length) {
            applyPendingOpen();
        } else {
            ContentRouter.navigate('access-management');
        }
    }

    function applyPendingOpen() {
        if (!pendingOpenEnvId) return;
        const envId = pendingOpenEnvId;
        pendingOpenEnvId = null;
        if (environments.find(e => e.environmentId === envId)) {
            selectedEnvironmentId = envId;
            $('#filter-environment').val(envId).trigger('change');
        }
        setTimeout(() => showGrantAccessModal(), 150);
    }

    /**
     * Wires a text input to a suggestion dropdown backed by a hidden id field — used for both
     * the Environment and User fields in the Grant Access modal. Typing invalidates any
     * previously selected value until a suggestion is clicked again.
     */
    function initAutocomplete({ inputSelector, hiddenSelector, menuSelector, minChars, fetchSuggestions, onSelect, onInput }) {
        const $input = $(inputSelector);
        const $hidden = $(hiddenSelector);
        const $menu = $(menuSelector);
        // Bumped on every keystroke and on reset/setValue — a resolved fetch whose seq is
        // stale is dropped so a slow response can't overwrite a newer one.
        let reqSeq = 0;

        function closeMenu() {
            $menu.empty().hide();
        }

        function renderSuggestions(items) {
            if (!items || items.length === 0) {
                $menu.html('<div class="access-autocomplete-empty">No matches</div>').show();
                return;
            }
            $menu.data('items', items);
            let lastGroup = null;
            const rows = items.map((item, idx) => {
                let html = '';
                if (item.group && item.group !== lastGroup) {
                    lastGroup = item.group;
                    html += `<div class="access-autocomplete-group">${Utils.escapeHtml(item.group)}</div>`;
                }
                const badge = item.badge
                    ? `<span class="access-autocomplete-badge">${Utils.escapeHtml(item.badge)}</span>` : '';
                html += `
                    <button type="button" class="access-autocomplete-item${item.disabled ? ' is-disabled' : ''}"
                            data-idx="${idx}"${item.disabled ? ' disabled' : ''}>
                        <span>${Utils.escapeHtml(item.label)}</span>${badge}
                    </button>`;
                return html;
            });
            $menu.html(rows.join('')).show();
        }

        async function handleInput() {
            const query = $input.val().trim();
            $hidden.val('');
            if (typeof onInput === 'function') onInput();
            if (query.length < minChars) {
                closeMenu();
                return;
            }
            const mySeq = ++reqSeq;
            try {
                const items = await fetchSuggestions(query);
                if (mySeq !== reqSeq) return;   // a newer keystroke already answered
                renderSuggestions(items);
            } catch (error) {
                if (mySeq !== reqSeq) return;
                console.error('Autocomplete search failed:', error);
                $menu.html('<div class="access-autocomplete-empty">Search failed</div>').show();
            }
        }

        $input.off('input.autocomplete').on('input.autocomplete', Utils.debounce(handleInput, 300));

        $menu.off('click').on('click', '.access-autocomplete-item', function() {
            if (this.disabled) return;
            const item = ($menu.data('items') || [])[$(this).data('idx')];
            if (!item || item.disabled) return;
            $input.val(item.label);
            $hidden.val(item.id);
            closeMenu();
            if (typeof onSelect === 'function') onSelect(item.id, item.label, item);
        });

        // Second namespace .accessAutocomplete lets teardown remove every autocomplete's handler.
        const outsideClickNamespace = 'click.autocomplete-' + inputSelector.replace(/[^a-zA-Z0-9]/g, '') + '.accessAutocomplete';
        $(document).off(outsideClickNamespace).on(outsideClickNamespace, function(e) {
            if (!$(e.target).closest(inputSelector).length && !$(e.target).closest(menuSelector).length) {
                closeMenu();
            }
        });

        return {
            reset() {
                reqSeq++;
                $input.val('');
                $hidden.val('');
                closeMenu();
            },
            setValue(label, id) {
                reqSeq++;
                $input.val(label || '');
                $hidden.val(id || '');
                closeMenu();
            }
        };
    }

    /**
     * User typeahead for the Grant Access modal: the app database first, and only when it
     * has no match — and the caller is an admin and the query is 3+ chars — the Microsoft
     * Entra directory. Directory rows carry a `directoryObjectId`; picking one onboards the
     * person as a normal user on submit. Directory rows for someone already in the app are
     * shown disabled.
     */
    async function searchUsersThenDirectory(query) {
        const q = (query || '').trim();

        const localUsers = await new Promise((resolve) => {
            ApiClient.get(Config.API.users.search(q))
                .done(users => resolve(users || []))
                .fail(() => resolve([]));
        });

        const items = localUsers.map(user => ({
            id: user.email,
            label: `${user.displayName || user.email} (${user.email})`,
            group: 'In this app',
            data: { source: 'app', email: user.email, displayName: user.displayName }
        }));

        if (items.length > 0 || q.length < 3 || !Auth.isAdmin()) {
            return items;
        }

        let dirResult = null;
        let dirErrorStatus = null;
        try {
            dirResult = await new Promise((resolve, reject) => {
                ApiClient.get(Config.API.directory.search(q, 10), { suppressGlobalError: true })
                    .done(resolve)
                    .fail(reject);
            });
        } catch (xhr) {
            dirErrorStatus = xhr && xhr.status;
        }

        if (dirErrorStatus === 409) {
            return [{ label: 'No app users match — directory search is off. Onboard in User Management first.', disabled: true }];
        }
        if (dirErrorStatus === 502) {
            return [{ label: 'No app users match — directory lookup is unavailable right now.', disabled: true }];
        }
        if (dirErrorStatus) {
            return [{ label: 'No app users match — directory search failed.', disabled: true }];
        }

        return (dirResult || []).map(d => {
            const email = d.email || d.userPrincipalName || '';
            return {
                id: d.directoryObjectId,
                label: `${d.displayName || email} (${email})`,
                group: 'Microsoft Entra directory',
                disabled: !!d.alreadyInApp,
                badge: d.alreadyInApp ? 'already a user' : null,
                data: {
                    source: 'directory',
                    directoryObjectId: d.directoryObjectId,
                    email: email,
                    displayName: d.displayName,
                    alreadyInApp: !!d.alreadyInApp
                }
            };
        });
    }

    /** Swap the confirm button label between a plain grant and an onboard-then-grant. */
    function setGrantButtonMode(mode) {
        if ($('#grant-access-id').val()) return;   // editing — keep "Save changes"
        $('#btn-confirm-grant-label').text(mode === 'onboard' ? 'Onboard & grant' : 'Grant Access');
    }

    /**
     * Handle grant / edit access form submission.
     */
    async function handleGrantAccess() {
        const editing = false; // changing a grant is AccessChangeDialogs (U10-T05)
        const accessId = null;
        const envId = $('#grant-environment').val();
        const accessLevel = $('#grant-access-level').val();
        const { durationDays, clearExpiry } = expiryChoice($('#grant-duration').val());
        const notes = $('#grant-notes').val().trim() || null;
        const scopeType = grantScope();
        const groupIds = scopeType === 'GROUP'
            ? $('#grant-group-list .grant-group-cb:checked').map((_, el) => el.value).get()
            : [];
        const pick = grantUserPick;

        if (!editing && (!envId || !accessLevel || !pick)) {
            showToast('Pick a user, an environment and an access level', 'warning');
            return;
        }
        if (!editing && pick.source === 'directory' && pick.alreadyInApp) {
            showToast('That person is already an app user — search for them again', 'warning');
            return;
        }
        if (!editing && scopeType === 'GROUP' && groupIds.length === 0) {
            showToast('Tick at least one group', 'warning');
            return;
        }

        const viaDirectory = !editing && pick.source === 'directory';
        const $btn = $('#btn-confirm-grant');
        const origLabel = $('#btn-confirm-grant-label').text();
        $btn.prop('disabled', true).find('#btn-confirm-grant-label')
            .text(editing ? 'Saving…' : (viaDirectory ? 'Onboarding…' : 'Granting…'));

        try {
            await new Promise((resolve, reject) => {
                let req;
                if (editing) {
                    req = ApiClient.patch(Config.API.access.updateGrant(accessId), {
                        accessLevel, durationDays,
                        clearExpiry: clearExpiry || null,
                        notes
                    });
                } else {
                    const body = { environmentId: envId, accessLevel, scopeType, groupIds, durationDays, notes };
                    if (viaDirectory) {
                        body.directoryObjectId = pick.directoryObjectId;
                    } else {
                        body.userEmail = pick.email;
                    }
                    req = ApiClient.post(Config.API.access.accessGrants, body);
                }
                req.done(resolve).fail(reject);
            });

            bootstrap.Modal.getInstance(document.getElementById('grantAccessModal')).hide();
            showToast(
                editing ? 'Grant updated'
                    : viaDirectory ? `Onboarded ${pick.displayName || pick.email} and granted ${accessLevel}`
                        : 'Access granted',
                'success');
            await refreshAccessData();
        } catch (error) {
            console.error('Grant/edit access failed:', error);
            showToast(error.responseJSON?.message || 'Failed to save access', 'danger');
        } finally {
            $btn.prop('disabled', false).find('#btn-confirm-grant-label').text(origLabel);
        }
    }

    /** Reload the list and the counts after a grant, change or revoke. */
    async function refreshAccessData() {
        await refreshList();
    }

    function getInitials(name) {
        if (!name) return '?';
        const parts = name.split(/[\s@]+/);
        if (parts.length >= 2) {
            return (parts[0][0] + parts[1][0]).toUpperCase();
        }
        return name.substring(0, 2).toUpperCase();
    }

    function showToast(message, type = 'info') {
        // Delegate to the app-wide toast system (js/ui/notifications.js).
        // 'danger' (Bootstrap naming, used by callers in this file) maps to
        // Notifications' 'error' type.
        if (window.Notifications && typeof Notifications.show === 'function') {
            const notifType = (type === 'danger') ? 'error' : type;
            Notifications.show(message, notifType);
        } else {
            console.log(`[${type.toUpperCase()}] ${message}`);
        }
    }

    // Public API
    return {
        load: load,
        openForEnvironment: openForEnvironment
    };

})();

// Make AccessManagement available globally
window.AccessManagement = AccessManagement;
