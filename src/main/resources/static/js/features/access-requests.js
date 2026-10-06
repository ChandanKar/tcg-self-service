/**
 * VM Self-Service Platform - Access Requests Module
 * Handles access request workflow for users and admins
 */

const AccessRequests = (function() {
    'use strict';

    // ── Env table state (client-side pagination + search) ──────────────────
    let allEnvs       = [];       // full list loaded from API
    let pendingEnvIds = new Set(); // env IDs that already have a pending request
    let envQuery      = '';        // current search string
    let envPage       = 0;         // 0-indexed current page
    const ENV_PAGE_SIZE = 7;

    /**
     * Load Request Access page (user view)
     */
    async function loadRequestAccessPage() {
        showLoading();

        try {
            // Load available environments and user's pending requests
            const [environments, myRequests] = await Promise.all([
                fetchAvailableEnvironments(),
                fetchMyRequests()
            ]);

            // Reset env table state on fresh load
            allEnvs       = environments || [];
            envQuery      = '';
            envPage       = 0;
            const pendingRequests = (myRequests || []).filter(r => r.status === 'PENDING');
            pendingEnvIds = new Set(pendingRequests.map(r => r.environmentId));

            const html = buildRequestAccessPageHtml(myRequests || []);
            $('#content-area').html(html);
            bindRequestAccessEvents();
        } catch (error) {
            console.error('Failed to load request access page:', error);
            showError('Failed to load page. Please try again.');
        }
    }

    /**
     * Load Pending Requests page (admin view)
     */
    async function loadPendingRequestsPage() {
        if (!Auth.isEnvAdmin()) {
            $('#content-area').html('<div class="alert alert-danger">Access denied</div>');
            return;
        }

        showLoading();

        try {
            const requests = await fetchPendingRequests();
            const html = buildPendingRequestsPageHtml(requests);
            $('#content-area').html(html);
            bindPendingRequestsEvents();
        } catch (error) {
            console.error('Failed to load pending requests:', error);
            showError('Failed to load pending requests.');
        }
    }

    /**
     * Fetch available environments (ones user doesn't have access to)
     */
    function fetchAvailableEnvironments() {
        return new Promise((resolve, reject) => {
            // Use the endpoint that returns environments user doesn't have access to
            ApiClient.get(Config.API.environments.available)
                .done(function(environments) {
                    resolve(environments || []);
                })
                .fail(function(xhr) {
                    // Fallback: if endpoint doesn't exist, return empty
                    if (xhr.status === 404) {
                        console.warn('Available environments endpoint not found');
                        resolve([]);
                    } else {
                        reject(xhr);
                    }
                });
        });
    }

    /**
     * Fetch user's access requests
     */
    function fetchMyRequests() {
        return new Promise((resolve, reject) => {
            ApiClient.get(Config.API.access.myRequests)
                .done(resolve)
                .fail(function(xhr) {
                    // If endpoint doesn't exist yet, return empty
                    if (xhr.status === 404) {
                        resolve([]);
                    } else {
                        reject(xhr);
                    }
                });
        });
    }

    /**
     * Fetch pending requests (admin)
     */
    function fetchPendingRequests() {
        return new Promise((resolve, reject) => {
            ApiClient.get(Config.API.access.pendingRequests)
                .done(resolve)
                .fail(reject);
        });
    }

    /**
     * Show loading state
     */
    function showLoading() {
        $('#content-area').html(`
            <div class="loading-state">
                <div class="spinner-border text-primary" role="status"></div>
                <p>Loading...</p>
            </div>
        `);
    }

    /**
     * Show error state
     */
    function showError(message) {
        $('#content-area').html(`
            <div class="error-state">
                <i class="fas fa-exclamation-triangle fa-3x text-warning"></i>
                <h4 class="mt-3">Error</h4>
                <p>${message}</p>
                <button class="btn btn-primary" onclick="Dashboard.load()">Back to Dashboard</button>
            </div>
        `);
    }

    /**
     * Build Request Access page HTML
     */
    function buildRequestAccessPageHtml(myRequests) {
        const pendingRequests   = myRequests.filter(r => r.status === 'PENDING');
        const completedRequests = myRequests.filter(r => r.status !== 'PENDING');

        return `
            <div class="request-access-container">
                <!-- Header -->
                <div class="content-header">
                    <h1><i class="fas fa-paper-plane"></i> Request Environment Access</h1>
                    <p class="text-muted">Request access to environments you need for your work</p>
                </div>

                <!-- Compact Search Bar -->
                <div class="ra-search-bar mb-2">
                    <div class="input-group input-group-sm">
                        <span class="input-group-text"><i class="fas fa-search"></i></span>
                        <input type="text" class="form-control" id="env-search"
                               placeholder="Search environments..." value="${Utils.escapeHtml(envQuery)}">
                    </div>
                </div>

                <!-- Available Environments -->
                <div class="card mb-3">
                    <div class="card-header d-flex justify-content-between align-items-center">
                        <h5><i class="fas fa-folder-open me-2"></i>Available Environments</h5>
                        <span class="badge bg-secondary" id="env-count-badge">${allEnvs.length}</span>
                    </div>
                    <div class="card-body ra-env-card-body">
                        ${buildEnvironmentsList()}
                    </div>
                </div>

                <!-- My Pending Requests -->
                ${pendingRequests.length > 0 ? `
                    <div class="card mb-3">
                        <div class="card-header d-flex justify-content-between align-items-center">
                            <h5><i class="fas fa-clock me-2"></i>My Pending Requests</h5>
                            <span class="badge bg-warning text-dark">${pendingRequests.length}</span>
                        </div>
                        <div class="card-body p-0">
                            ${buildMyRequestsTable(pendingRequests, true, 'my-requests-table')}
                        </div>
                    </div>
                ` : ''}

                <!-- Request History -->
                ${completedRequests.length > 0 ? `
                    <div class="card mb-3">
                        <div class="card-header d-flex justify-content-between align-items-center">
                            <h5><i class="fas fa-history me-2"></i>Request History</h5>
                            <button class="btn btn-sm btn-ghost" id="toggle-history">
                                <i class="fas fa-chevron-down"></i>
                            </button>
                        </div>
                        <div class="card-body p-0" id="request-history" style="display:none;">
                            ${buildMyRequestsTable(completedRequests, false, 'history-table')}
                        </div>
                    </div>
                ` : ''}
            </div>
        `;
    }

    /**
     * Build environments list (uses module state: allEnvs, envQuery, envPage)
     */
    function buildEnvironmentsList() {
        const filtered   = getFilteredEnvs();
        const pageEnvs   = filtered.slice(envPage * ENV_PAGE_SIZE, (envPage + 1) * ENV_PAGE_SIZE);

        return `
            <div class="ra-env-table-wrapper">
                <table class="table table-hover mb-0" id="env-list-table">
                    <thead class="table-light">
                        <tr>
                            <th>Environment</th>
                            <th>Size</th>
                            <th class="text-end">Action</th>
                        </tr>
                    </thead>
                    <tbody id="env-list">
                        ${buildEnvRows(pageEnvs, filtered.length)}
                    </tbody>
                </table>
            </div>
            <div class="pagination-bar-wrap" id="env-pagination"></div>
        `;
    }

    /**
     * Return envs filtered by current search query
     */
    function getFilteredEnvs() {
        if (!envQuery) return allEnvs;
        const q = envQuery.toLowerCase();
        return allEnvs.filter(env => [
            env.displayName,
            env.name,
            env.description,
            env.serviceType,
            env.cloudProvider,
            env.provider,
            env.environmentId
        ].some(value => String(value || '').toLowerCase().includes(q)));
    }

    /**
     * Build env table rows for a given page slice
     */
    function buildEnvRows(pageEnvs, filteredTotal) {
        if (pageEnvs.length === 0) {
            const msg = envQuery
                ? `No environments match "<strong>${Utils.escapeHtml(envQuery)}</strong>"`
                : 'You have access to all environments!';
            return `
                <tr class="ra-empty-row">
                    <td colspan="3">
                        <div class="ra-empty-state">
                            <i class="fas ${envQuery ? 'fa-search' : 'fa-check-circle'} fa-2x ${envQuery ? 'text-muted' : 'text-success'} d-block"></i>
                            <p>${msg}</p>
                        </div>
                    </td>
                </tr>
            `;
        }

        const rows = pageEnvs.map(env => {
            const hasPending = pendingEnvIds.has(env.environmentId);
            return `
                <tr>
                    <td class="ra-wrap">
                        <strong>${Utils.escapeHtml(env.displayName || env.name)}</strong>
                        ${env.description ? `<br><small class="text-muted">${Utils.escapeHtml(env.description)}</small>` : ''}
                    </td>
                    <td>${env.vmCount || 0} VMs</td>
                    <td class="text-end">
                        ${hasPending
                            ? `<span class="badge bg-warning text-dark">Request Pending</span>`
                            : `<button class="btn btn-sm btn-primary" data-env-id="${env.environmentId}"
                                       data-env-name="${Utils.escapeHtml(env.displayName || env.name)}" data-action="request-access">
                                   <i class="fas fa-paper-plane"></i> Request Access
                               </button>`
                        }
                    </td>
                </tr>
            `;
        }).join('');

        // Filler row: expands to fill remaining card height so pagination stays pinned
        return rows + `<tr class="ra-env-filler-row"><td colspan="3"></td></tr>`;
    }

    /**
     * Re-render only the env tbody + pagination (used by search and page clicks)
     */
    function renderEnvTable() {
        const filtered   = getFilteredEnvs();
        const totalPages = Math.ceil(filtered.length / ENV_PAGE_SIZE);
        if (envPage >= totalPages && totalPages > 0) envPage = totalPages - 1;
        if (envPage < 0 || totalPages === 0) envPage = 0;
        const pageEnvs   = filtered.slice(envPage * ENV_PAGE_SIZE, (envPage + 1) * ENV_PAGE_SIZE);

        if (!$('#env-list').length || !$('#env-pagination').length) {
            $('.ra-env-card-body').html(buildEnvironmentsList());
        } else {
            $('#env-list').html(buildEnvRows(pageEnvs, filtered.length));
        }

        Pagination.renderNumbered('#env-pagination', {
            page: envPage,
            totalItems: filtered.length,
            pageSize: ENV_PAGE_SIZE,
            itemLabel: envQuery ? `of ${allEnvs.length} environments` : 'environments',
            zeroIndexed: true,
            bindAncestor: '#content-area',
            onPageChange: (page) => {
                envPage = page;
                renderEnvTable();
            }
        });

        // Update header badge: show filtered / total when searching
        $('#env-count-badge').text(envQuery ? `${filtered.length} / ${allEnvs.length}` : allEnvs.length);
    }

    /**
     * Build my requests table
     */
    function buildMyRequestsTable(requests, showCancel, tableId) {
        const rows = requests.map(req => {
            const statusBadge = getStatusBadge(req.status);
            const cancelBtn = showCancel && req.status === 'PENDING' ?
                `<button class="btn btn-sm btn-outline-danger" data-request-id="${req.requestId}" data-action="cancel">
                    <i class="fas fa-times"></i> Cancel
                </button>` : '-';

            const scopeCell = req.scopeType === 'GROUP'
                ? `<span class="badge bg-primary-subtle text-primary">Group: ${Utils.escapeHtml(req.scopeName || req.scopeId)}</span>`
                : `<span class="text-muted small">Environment</span>`;

            return `
                <tr>
                    <td class="ra-wrap"><strong>${Utils.escapeHtml(req.environmentName || 'Unknown')}</strong></td>
                    <td>${scopeCell}</td>
                    <td>${statusBadge}</td>
                    <td>${Utils.escapeHtml(req.requestedAccessLevel || 'USER')}</td>
                    <td>${Utils.formatRelativeTime(req.createdAt)}</td>
                    <td>${req.reviewedAt ? Utils.formatRelativeTime(req.reviewedAt) : '-'}</td>
                    <td>${cancelBtn}</td>
                </tr>
            `;
        }).join('');

        return `
            <table class="table table-hover mb-0" id="${tableId || 'my-requests-table'}">
                <thead class="table-light">
                    <tr>
                        <th>Environment</th>
                        <th>Scope</th>
                        <th>Status</th>
                        <th>Access Level</th>
                        <th>Requested</th>
                        <th>Processed</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    ${rows}
                </tbody>
            </table>
        `;
    }

    /**
     * Build Pending Requests page HTML (admin)
     */
    function buildPendingRequestsPageHtml(requests) {
        if (!requests || requests.length === 0) {
            return `
                <div class="request-access-container">
                    <div class="content-header">
                        <h1><i class="fas fa-user-check"></i> Pending Access Requests</h1>
                        <p class="text-muted">Review and approve user access requests</p>
                    </div>
                    <div class="card">
                        <div class="card-body">
                            <div class="ra-empty-state">
                                <i class="fas fa-inbox fa-2x d-block"></i>
                                <p>No pending requests — all access requests have been processed</p>
                            </div>
                        </div>
                    </div>
                </div>
            `;
        }

        const rows = requests.map(req => `
            <tr>
                <td class="ra-wrap">
                    <strong>${Utils.escapeHtml(req.requesterDisplayName || 'Unknown')}</strong>
                    <br><small class="text-muted">${Utils.escapeHtml(req.requesterEmail || '')}</small>
                </td>
                <td class="ra-wrap">
                    <strong>${Utils.escapeHtml(req.environmentName || 'Unknown')}</strong>
                </td>
                <td>
                    <span class="badge bg-${Config.ACCESS_LEVELS[req.requestedAccessLevel]?.color || 'secondary'}">
                        ${req.requestedAccessLevel}
                    </span>
                </td>
                <td>${Utils.formatRelativeTime(req.createdAt)}</td>
                <td title="${Utils.escapeHtml(req.businessJustification || '')}">
                    <span class="reason-text">${Utils.escapeHtml(req.businessJustification || '-')}</span>
                </td>
                <td class="text-end ra-actions-cell">
                    <div class="ra-action-buttons">
                        <button class="btn btn-sm btn-tonal btn-success" data-request-id="${req.requestId}" data-action="approve">
                            <i class="fas fa-check"></i> Approve
                        </button>
                        <button class="btn btn-sm btn-tonal btn-danger" data-request-id="${req.requestId}" data-action="deny">
                            <i class="fas fa-times"></i> Deny
                        </button>
                    </div>
                </td>
            </tr>
        `).join('');

        return `
            <div class="request-access-container">
                <div class="content-header">
                    <h1><i class="fas fa-user-check"></i> Pending Access Requests</h1>
                    <p class="text-muted">Review and approve user access requests
                        <span class="badge bg-warning text-dark ms-2">${requests.length} pending</span>
                    </p>
                </div>
                <div class="card">
                    <div class="card-body p-0">
                        <table class="table table-hover mb-0" id="pending-requests-table">
                            <thead class="table-light">
                                <tr>
                                    <th>Requester</th>
                                    <th>Environment</th>
                                    <th>Access Level</th>
                                    <th>Requested</th>
                                    <th>Reason</th>
                                    <th class="text-end">Actions</th>
                                </tr>
                            </thead>
                            <tbody>
                                ${rows}
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>
        `;
    }

    /**
     * Get status badge HTML
     */
    function getStatusBadge(status) {
        const config = Config.STATUS.accessRequest[status] || { class: 'bg-secondary', label: status };
        return `<span class="badge ${config.class}">${config.label || status}</span>`;
    }

    /**
     * Bind events for Request Access page
     */
    function bindRequestAccessEvents() {
        // Search: filter in-memory list, reset to page 0, re-render table
        $('#content-area').off('input', '#env-search').on('input', '#env-search', Utils.debounce(function(event) {
            envQuery = $(event.currentTarget).val().trim();
            envPage  = 0;
            renderEnvTable();
        }, 300));

        // Request access button — delegated so it survives tbody re-render
        $('#content-area').off('click', '[data-action="request-access"]').on('click', '[data-action="request-access"]', function() {
            const envId   = $(this).data('env-id');
            const envName = $(this).data('env-name');
            showRequestAccessModal(envId, envName);
        });

        // Cancel request — delegated
        $('#content-area').off('click', '[data-action="cancel"]').on('click', '[data-action="cancel"]', function() {
            const requestId = $(this).data('request-id');
            cancelRequest(requestId);
        });

        // Toggle history
        $('#toggle-history').off('click').on('click', function() {
            const $history = $('#request-history');
            const $icon    = $(this).find('i');
            $history.slideToggle(200);
            $icon.toggleClass('fa-chevron-down fa-chevron-up');
        });
    }

    /**
     * Bind events for Pending Requests page
     */
    function bindPendingRequestsEvents() {
        // Approve request
        $('[data-action="approve"]').off('click').on('click', function() {
            const requestId = $(this).data('request-id');
            showApproveModal(requestId);
        });

        // Deny request
        $('[data-action="deny"]').off('click').on('click', function() {
            const requestId = $(this).data('request-id');
            showDenyModal(requestId);
        });
    }

    /**
     * Show request access modal
     */
    function showRequestAccessModal(envId, envName) {
        Modals.show({
            id: 'requestAccessModal',
            title: `Request Access: ${envName}`,
            body: `
                <form id="requestAccessForm">
                    <div class="mb-3">
                        <label class="form-label d-block">Scope</label>
                        <div class="form-check form-check-inline">
                            <input class="form-check-input" type="radio" name="reqScope" id="reqScopeEnv" value="ENVIRONMENT" checked>
                            <label class="form-check-label" for="reqScopeEnv">Whole environment</label>
                        </div>
                        <div class="form-check form-check-inline">
                            <input class="form-check-input" type="radio" name="reqScope" id="reqScopeGroup" value="GROUP">
                            <label class="form-check-label" for="reqScopeGroup">One group</label>
                        </div>
                    </div>
                    <div class="mb-3" id="reqGroupField" hidden>
                        <label class="form-label" for="reqGroupId">Group</label>
                        <select class="form-select" id="reqGroupId"></select>
                        <div class="form-text" id="reqGroupHint"></div>
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Access Level</label>
                        <select class="form-select" id="accessLevel">
                            <option value="VIEWER">Viewer - Can view details</option>
                            <option value="USER" selected>User - Can start/stop VMs</option>
                            <option value="ADMIN">Admin - Full control</option>
                        </select>
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Reason for Request <span class="text-danger">*</span></label>
                        <textarea class="form-control" id="requestReason" rows="3" required minlength="10" maxlength="1000"
                                  placeholder="Explain why you need access..."></textarea>
                        <div class="invalid-feedback">Reason must be between 10 and 1000 characters.</div>
                    </div>
                </form>
            `,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Submit Request', class: 'btn-primary', id: 'submitRequest' }
            ],
            onShow: function() {
                $('#requestReason').off('input').on('input', function() {
                    $(this).removeClass('is-invalid');
                });

                $('input[name="reqScope"]').off('change').on('change', function() {
                    const isGroup = $('input[name="reqScope"]:checked').val() === 'GROUP';
                    document.getElementById('reqGroupField').hidden = !isGroup;
                    if (isGroup && !$('#reqGroupId option').length) {
                        $('#reqGroupHint').text('Loading groups…');
                        ApiClient.get(Config.API.groups.list(envId))
                            .done(groups => {
                                if (!groups || !groups.length) { $('#reqGroupHint').text('This environment has no groups.'); return; }
                                $('#reqGroupId').html(groups.map(g =>
                                    `<option value="${g.groupId}">${Utils.escapeHtml(g.displayName || g.name)} — ${g.vmCount || 0} VMs</option>`).join(''));
                                $('#reqGroupHint').text('');
                            })
                            .fail(() => $('#reqGroupHint').text('You need view access to this environment before you can request a specific group.'));
                    }
                });

                $('#submitRequest').off('click').on('click', function() {
                    const accessLevel = $('#accessLevel').val();
                    const reason = $('#requestReason').val().trim();
                    const scopeType = $('input[name="reqScope"]:checked').val();
                    const groupId = scopeType === 'GROUP' ? $('#reqGroupId').val() : null;

                    if (reason.length < 10 || reason.length > 1000) {
                        $('#requestReason').addClass('is-invalid');
                        return;
                    }
                    if (scopeType === 'GROUP' && !groupId) {
                        $('#reqGroupHint').text('Pick a group.');
                        return;
                    }

                    $(this).prop('disabled', true).html('<i class="fas fa-spinner fa-spin"></i> Submitting...');
                    submitAccessRequest(envId, accessLevel, reason, scopeType, groupId);
                });
            }
        });
    }

    /**
     * Submit access request
     */
    function submitAccessRequest(envId, accessLevel, reason, scopeType, groupId) {
        ApiClient.post(Config.API.access.requestAccess(envId), {
            accessLevel: accessLevel,
            businessJustification: reason,
            scopeType: scopeType || 'ENVIRONMENT',
            groupId: groupId || null
        }, { suppressGlobalError: true })
        .done(function() {
            Modals.hide('requestAccessModal');
            Notifications.success('Access request submitted successfully');
            loadRequestAccessPage(); // Refresh page
        })
        .fail(function(xhr) {
            $('#submitRequest').prop('disabled', false).html('Submit Request');
            const msg = getAccessRequestErrorMessage(xhr);
            Notifications.error(msg);
        });
    }

    function getAccessRequestErrorMessage(xhr) {
        const response = xhr.responseJSON;
        if (response?.message) {
            return response.message;
        }
        if (Array.isArray(response?.errors) && response.errors.length > 0) {
            return response.errors
                .map(error => error.message)
                .filter(Boolean)
                .join('; ') || 'Failed to submit request';
        }
        return 'Failed to submit request';
    }

    /**
     * Cancel access request
     */
    function cancelRequest(requestId) {
        Modals.confirm(
            'Cancel Request',
            'Are you sure you want to cancel this access request?',
            function() {
                ApiClient.delete(Config.API.access.cancelRequest(requestId))
                    .done(function() {
                        Notifications.success('Request cancelled');
                        loadRequestAccessPage();
                    })
                    .fail(function(xhr) {
                        Notifications.error(xhr.responseJSON?.message || 'Failed to cancel request');
                    });
            }
        );
    }

    /**
     * Show approve modal
     */
    function showApproveModal(requestId) {
        Modals.show({
            id: 'approveRequestModal',
            title: 'Approve Access Request',
            body: `
                <form id="approveRequestForm">
                    <div class="mb-3">
                        <label class="form-label">Access Expiration</label>
                        <select class="form-select" id="expirationDays">
                            <option value="">Never expires</option>
                            <option value="7">1 week</option>
                            <option value="30">30 days</option>
                            <option value="90">90 days</option>
                            <option value="180">6 months</option>
                            <option value="365">1 year</option>
                        </select>
                        <div class="form-text">Optionally set when access should expire</div>
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Comments (optional)</label>
                        <textarea class="form-control" id="approveComments" rows="2"
                                  placeholder="Any notes for the requester..."></textarea>
                    </div>
                </form>
            `,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Approve', class: 'btn-success', id: 'confirmApprove' }
            ],
            onShow: function() {
                $('#confirmApprove').off('click').on('click', function() {
                    const days = $('#expirationDays').val();
                    const notes = $('#approveComments').val().trim();
                    const durationDays = days ? parseInt(days) : null;

                    $(this).prop('disabled', true).html('<i class="fas fa-spinner fa-spin"></i> Approving...');

                    ApiClient.post(Config.API.access.approveRequest(requestId), {
                        notes,
                        durationDays
                    })
                    .done(function() {
                        Modals.hide('approveRequestModal');
                        Notifications.success('Request approved');
                        loadPendingRequestsPage();
                        updatePendingBadge();
                    })
                    .fail(function(xhr) {
                        $('#confirmApprove').prop('disabled', false).html('Approve');
                        Notifications.error(xhr.responseJSON?.message || 'Failed to approve request');
                    });
                });
            }
        });
    }

    /**
     * Show deny modal
     */
    function showDenyModal(requestId) {
        Modals.show({
            id: 'denyRequestModal',
            title: 'Deny Access Request',
            body: `
                <form id="denyRequestForm">
                    <div class="mb-3">
                        <label class="form-label">Reason for Denial <span class="text-danger">*</span></label>
                        <textarea class="form-control" id="denyReason" rows="3" required
                                  placeholder="Explain why this request is being denied..."></textarea>
                        <div class="form-text">This will be visible to the requester</div>
                    </div>
                </form>
            `,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Deny Request', class: 'btn-danger', id: 'confirmDeny' }
            ],
            onShow: function() {
                $('#confirmDeny').off('click').on('click', function() {
                    const reason = $('#denyReason').val().trim();

                    if (!reason) {
                        $('#denyReason').addClass('is-invalid');
                        return;
                    }

                    $(this).prop('disabled', true).html('<i class="fas fa-spinner fa-spin"></i> Denying...');

                    ApiClient.post(Config.API.access.denyRequest(requestId), { notes: reason })
                        .done(function() {
                            Modals.hide('denyRequestModal');
                            Notifications.success('Request denied');
                            loadPendingRequestsPage();
                            updatePendingBadge();
                        })
                        .fail(function(xhr) {
                            $('#confirmDeny').prop('disabled', false).html('Deny Request');
                            Notifications.error(xhr.responseJSON?.message || 'Failed to deny request');
                        });
                });
            }
        });
    }

    /**
     * Update pending requests badge in sidebar
     */
    function updatePendingBadge() {
        if (!Auth.isEnvAdmin()) return;

        ApiClient.get(Config.API.access.pendingRequests)
            .done(function(requests) {
                const count = requests ? requests.length : 0;
                if (count > 0) {
                    $('.pending-count').text(count).show();
                } else {
                    $('.pending-count').hide();
                }
            });
    }

    /**
     * Show environment access management modal
     */
    function showManageAccessModal(envId, envName) {
        Modals.show({
            id: 'manageAccessModal',
            title: `Manage Access: ${envName}`,
            size: 'lg',
            body: `
                <div class="manage-access-content">
                    <div class="text-center py-4">
                        <i class="fas fa-spinner fa-spin"></i> Loading access list...
                    </div>
                </div>
            `,
            buttons: [
                { text: 'Close', class: 'btn-secondary', dismiss: true },
                { text: 'Grant Access', class: 'btn-primary', id: 'grantAccessBtn' }
            ],
            onShow: function() {
                loadAccessList(envId);

                $('#grantAccessBtn').off('click').on('click', function() {
                    showGrantAccessModal(envId, envName);
                });
            }
        });
    }

    /**
     * Load access list for environment
     */
    function loadAccessList(envId) {
        ApiClient.get(Config.API.access.environmentAccess(envId))
            .done(function(accessList) {
                const html = buildAccessListHtml(accessList, envId);
                $('.manage-access-content').html(html);
                bindAccessListEvents(envId);
            })
            .fail(function() {
                $('.manage-access-content').html(`
                    <div class="alert alert-danger">Failed to load access list</div>
                `);
            });
    }

    /**
     * Build access list HTML
     */
    function buildAccessListHtml(accessList, envId) {
        if (!accessList || accessList.length === 0) {
            return `
                <div class="empty-state text-center py-4">
                    <i class="fas fa-users fa-2x text-muted mb-2"></i>
                    <p>No users have access to this environment</p>
                </div>
            `;
        }

        const rows = accessList.map(access => `
            <tr>
                <td>
                    <strong>${Utils.escapeHtml(access.userDisplayName || 'Unknown')}</strong>
                    <br><small class="text-muted">${Utils.escapeHtml(access.userEmail || '')}</small>
                </td>
                <td>
                    <span class="badge bg-${Config.ACCESS_LEVELS[access.accessLevel]?.color || 'secondary'}">
                        ${access.accessLevel}
                    </span>
                </td>
                <td>${access.grantedAt ? Utils.formatRelativeTime(access.grantedAt) : '-'}</td>
                <td>${access.expiresAt ? Utils.formatDate(access.expiresAt) : 'Never'}</td>
                <td>
                    <button class="btn btn-sm btn-outline-danger btn-ghost"
                            data-user-id="${access.userId}" data-action="revoke-access">
                        <i class="fas fa-times"></i> Revoke
                    </button>
                </td>
            </tr>
        `).join('');

        return `
            <table class="table table-sm mb-0">
                <thead>
                    <tr>
                        <th>User</th>
                        <th>Access Level</th>
                        <th>Granted</th>
                        <th>Expires</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    ${rows}
                </tbody>
            </table>
        `;
    }

    /**
     * Bind access list events
     */
    function bindAccessListEvents(envId) {
        $('[data-action="revoke-access"]').off('click').on('click', function() {
            const userId = $(this).data('user-id');
            revokeAccess(envId, userId);
        });
    }

    /**
     * Revoke user access
     */
    function revokeAccess(envId, userId) {
        Modals.confirm(
            'Revoke Access',
            'Are you sure you want to revoke this user\'s access?',
            function() {
                ApiClient.delete(Config.API.access.revokeAccess(envId, userId))
                    .done(function() {
                        Notifications.success('Access revoked');
                        loadAccessList(envId);
                    })
                    .fail(function(xhr) {
                        Notifications.error(xhr.responseJSON?.message || 'Failed to revoke access');
                    });
            },
            { confirmText: 'Revoke', confirmClass: 'btn-danger' }
        );
    }

    /**
     * Show grant access modal
     */
    function showGrantAccessModal(envId, envName) {
        Modals.show({
            id: 'grantAccessModal',
            title: `Grant Access: ${envName}`,
            body: `
                <form id="grantAccessForm">
                    <div class="mb-3">
                        <label class="form-label">User Email <span class="text-danger">*</span></label>
                        <input type="email" class="form-control" id="grantUserEmail" required
                               placeholder="user@company.com">
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Access Level</label>
                        <select class="form-select" id="grantAccessLevel">
                            <option value="VIEWER">Viewer</option>
                            <option value="USER" selected>User</option>
                            <option value="ADMIN">Admin</option>
                        </select>
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Expiration</label>
                        <select class="form-select" id="grantExpiration">
                            <option value="">Never expires</option>
                            <option value="30">30 days</option>
                            <option value="90">90 days</option>
                            <option value="180">6 months</option>
                            <option value="365">1 year</option>
                        </select>
                    </div>
                </form>
            `,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Grant Access', class: 'btn-primary', id: 'confirmGrant' }
            ],
            onShow: function() {
                $('#confirmGrant').off('click').on('click', function() {
                    const email = $('#grantUserEmail').val().trim();
                    const accessLevel = $('#grantAccessLevel').val();
                    const days = $('#grantExpiration').val();

                    if (!email) {
                        $('#grantUserEmail').addClass('is-invalid');
                        return;
                    }

                    let expiresAt = null;
                    if (days) {
                        const date = new Date();
                        date.setDate(date.getDate() + parseInt(days));
                        expiresAt = date.toISOString();
                    }

                    $(this).prop('disabled', true).html('<i class="fas fa-spinner fa-spin"></i> Granting...');

                    ApiClient.post(Config.API.access.grantAccess(envId), {
                        userEmail: email,
                        accessLevel,
                        expiresAt
                    })
                    .done(function() {
                        Modals.hide('grantAccessModal');
                        Notifications.success('Access granted');
                        loadAccessList(envId);
                    })
                    .fail(function(xhr) {
                        $('#confirmGrant').prop('disabled', false).html('Grant Access');
                        Notifications.error(xhr.responseJSON?.message || 'Failed to grant access');
                    });
                });
            }
        });
    }

    // Public API
    return {
        loadRequestAccessPage,
        loadPendingRequestsPage,
        showManageAccessModal,
        showRequestAccessModal,
        updatePendingBadge
    };
})();

window.AccessRequests = AccessRequests;

