/**
 * VM Self-Service Platform - Access Requests Module
 * The reviewer side of access requests: pending requests, approve and deny, manage and grant
 * access. The requester's page is RequestAccess (request-access.js, U09-T02).
 */

const AccessRequests = (function() {
    'use strict';

    // Pending Requests page: the loaded requests, so Approve can show the full request.
    let pendingList = [];

    // Approve dialog: override choices (the server caps grants at access.grant.max-duration-days).
    const APPROVE_DAY_OPTIONS = [7, 30, 90, 180, 365];

    /**
     * Load Pending Requests page (admin view)
     */
    /** Router loader (see the page contract in core/router.js). */
    async function loadPendingRequestsPage() {
        const t = ContentRouter.token();
        if (!Auth.isEnvAdmin()) {
            $('#content-area').html('<div class="alert alert-danger">Access denied</div>');
            return;
        }

        showLoading();

        try {
            const requests = await fetchPendingRequests();
            if (!ContentRouter.isCurrent(t)) return;
            pendingList = requests || [];
            const html = buildPendingRequestsPageHtml(requests);
            $('#content-area').html(html);
            bindPendingRequestsEvents();
        } catch (error) {
            if (!ContentRouter.isCurrent(t)) return;
            console.error('Failed to load pending requests:', error);
            showError('Failed to load pending requests.');
        }
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
                <button class="btn btn-primary" data-action="go-dashboard">Back to Dashboard</button>
            </div>
        `);
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
                <td class="ra-wrap" data-label="Requester">
                    <strong>${Utils.escapeHtml(req.requesterDisplayName || 'Unknown')}</strong>
                    <br><small class="text-muted">${Utils.escapeHtml(req.requesterEmail || '')}</small>
                </td>
                <td class="ra-wrap" data-label="Environment">
                    <strong>${Utils.escapeHtml(req.environmentName || 'Unknown')}</strong>
                </td>
                <td data-label="Access level">
                    <span class="access-level-badge ${AccessLevels.chipClass(req.requestedAccessLevel)}">
                        ${Utils.escapeHtml(AccessLevels.label(req.requestedAccessLevel))}
                    </span>
                </td>
                <td class="ra-wrap" data-col="scope" data-label="Scope">${Utils.escapeHtml(describeRequest(req).scope)}</td>
                <td data-col="duration" data-label="Duration">${Utils.escapeHtml(describeRequest(req).duration)}</td>
                <td class="ra-wrap" data-col="current" data-label="Current access">${currentAccessHtml(req)}</td>
                <td data-label="Requested">${Utils.formatRelativeTime(req.createdAt)}</td>
                <td data-label="Reason" title="${Utils.escapeHtml(req.businessJustification || '')}">
                    <span class="reason-text">${Utils.escapeHtml(req.businessJustification || '-')}</span>
                </td>
                <td class="text-end ra-actions-cell td-actions">
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
                        <a class="ms-2" href="#/approvals"><i class="fas fa-stamp me-1" aria-hidden="true"></i>Operation approvals</a>
                    </p>
                </div>
                <div class="card">
                    <div class="card-body p-0">
                        <table class="table table-hover mb-0 table-cards" id="pending-requests-table">
                            <thead class="table-light">
                                <tr>
                                    <th>Requester</th>
                                    <th>Environment</th>
                                    <th>Access Level</th>
                                    <th>Scope</th>
                                    <th>Duration</th>
                                    <th>Current access</th>
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
     * Bind events for Pending Requests page
     */
    function bindPendingRequestsEvents() {
        // Approve request
        $('[data-action="approve"]').off('click').on('click', function() {
            const request = pendingList.find(r => r.requestId === $(this).data('request-id'));
            if (request) {
                showApproveModal(request, () => { loadPendingRequestsPage(); updatePendingBadge(); });
            }
        });

        // Deny request
        $('[data-action="deny"]').off('click').on('click', function() {
            const requestId = $(this).data('request-id');
            showDenyModal(requestId);
        });
    }

    function formatDay(ts) {
        return new Date(ts).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
    }

    /**
     * Plain-text facts a reviewer needs about a request (M20): what it covers, for how long, and
     * what the requester holds today. Shared with Access Management's pending list.
     */
    function describeRequest(req) {
        const level = req.currentAccessLevel ? AccessLevels.label(req.currentAccessLevel) : null;
        return {
            scope: req.scopeType === 'GROUP' ? `Group ${req.scopeName || req.scopeId}` : 'Whole environment',
            duration: req.durationDays ? `${req.durationDays} days requested` : 'No end date requested',
            current: !level ? 'None'
                : req.currentExpiresAt ? `${level} until ${formatDay(req.currentExpiresAt)}` : `${level}, no end`,
            extension: !!req.extension
        };
    }

    /** Current access plus an "Extension" pill when the request extends it. */
    function currentAccessHtml(req) {
        const d = describeRequest(req);
        const pill = d.extension ? ' <span class="badge ra-extension-pill">Extension</span>' : '';
        return `${Utils.escapeHtml(d.current)}${pill}`;
    }

    /**
     * Approve dialog: shows what is being approved and lets the reviewer keep the requested
     * duration, pick another, or grant with no expiry; nothing is approved until Approve.
     *
     * @param {object} request - a pending request (EnvironmentAccessRequestDTO)
     * @param {function=} onDone - called after the request was approved or found already reviewed
     */
    function showApproveModal(request, onDone) {
        const d = describeRequest(request);
        const requested = request.durationDays ? `As requested (${request.durationDays} days)` : 'As requested (no end date)';
        const dayOptions = APPROVE_DAY_OPTIONS.map(n => `<option value="${n}">${n} days</option>`).join('');
        const countsFrom = request.extension && request.currentExpiresAt
            ? `Days are added to the current expiry (${formatDay(request.currentExpiresAt)}).`
            : 'Days count from the moment you approve.';
        const row = (label, value) => `<dt class="col-5">${label}</dt><dd class="col-7">${value}</dd>`;

        Modals.show({
            id: 'approveRequestModal',
            title: 'Approve Access Request',
            body: `
                <dl class="row small mb-3 ra-approve-summary">
                    ${row('Requester', Utils.escapeHtml(request.requesterDisplayName || request.requesterEmail || 'Unknown'))}
                    ${row('Environment', Utils.escapeHtml(request.environmentName || 'Unknown'))}
                    ${row('Scope', Utils.escapeHtml(d.scope))}
                    ${row('Access level', Utils.escapeHtml(AccessLevels.label(request.requestedAccessLevel)))}
                    ${row('Duration', Utils.escapeHtml(d.duration))}
                    ${row('Current access', currentAccessHtml(request))}
                </dl>
                <form id="approveRequestForm">
                    <div class="mb-3">
                        <label class="form-label" for="approveDuration">Grant for</label>
                        <select class="form-select" id="approveDuration" aria-describedby="approveDurationHelp">
                            <option value="requested" selected>${Utils.escapeHtml(requested)}</option>
                            ${dayOptions}
                            <option value="none">No expiry</option>
                        </select>
                        <div class="form-text" id="approveDurationHelp">${Utils.escapeHtml(countsFrom)}</div>
                    </div>
                    <div class="mb-3">
                        <label class="form-label" for="approveComments">Notes (optional)</label>
                        <textarea class="form-control" id="approveComments" rows="2" maxlength="500"
                                  placeholder="Any notes for the requester..."></textarea>
                    </div>
                </form>
            `,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Approve', class: 'btn-success', id: 'confirmApprove' }
            ],
            onShow: function() {
                $('#approveDuration').trigger('focus');
                $('#confirmApprove').off('click').on('click', function() {
                    const choice = $('#approveDuration').val();
                    const body = {
                        notes: $('#approveComments').val().trim() || null,
                        durationDays: /^\d+$/.test(choice) ? parseInt(choice, 10) : null,
                        clearExpiry: choice === 'none' ? true : null
                    };

                    $(this).prop('disabled', true).html('<i class="fas fa-spinner fa-spin"></i> Approving...');

                    ApiClient.post(Config.API.access.approveRequest(request.requestId), body, { suppressGlobalError: true })
                    .done(function() {
                        Modals.hide('approveRequestModal');
                        Notifications.success('Request approved');
                        if (onDone) onDone();
                    })
                    .fail(function(xhr) {
                        if (xhr.status === 409) {
                            // E04-T04: another reviewer (or the requester) got there first.
                            Modals.hide('approveRequestModal');
                            Notifications.warning('Someone else already reviewed this request');
                            if (onDone) onDone();
                            return;
                        }
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
                    <span class="access-level-badge ${AccessLevels.chipClass(access.accessLevel)}">
                        ${Utils.escapeHtml(AccessLevels.label(access.accessLevel))}
                    </span>
                </td>
                <td>${access.grantedAt ? Utils.formatRelativeTime(access.grantedAt) : '-'}</td>
                <td>${access.expiresAt ? Utils.formatDate(access.expiresAt) : 'Never'}</td>
                <td class="td-actions">
                    <button class="btn btn-sm btn-outline-danger btn-ghost"
                            data-user-id="${access.userId}" data-action="revoke-access">
                        <i class="fas fa-times"></i> Revoke
                    </button>
                </td>
            </tr>
        `).join('');

        return `
            <table class="table table-sm mb-0 table-cards">
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
        loadPendingRequestsPage,
        showManageAccessModal,
        showApproveModal,
        describeRequest,
        currentAccessHtml,
        updatePendingBadge
    };
})();

window.AccessRequests = AccessRequests;

