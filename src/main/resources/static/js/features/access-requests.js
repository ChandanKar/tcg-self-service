/**
 * VM Self-Service Platform - Access Requests Module
 * Handles access request workflow for users and admins
 */

const AccessRequests = (function() {
    'use strict';

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

            const html = buildRequestAccessPageHtml(environments, myRequests);
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
            // Get all environments, then filter out ones user already has access to
            ApiClient.get(Config.API.environments.list)
                .done(function(environments) {
                    // For now, return all - backend should handle filtering
                    resolve(environments || []);
                })
                .fail(reject);
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
    function buildRequestAccessPageHtml(environments, myRequests) {
        // Separate pending and completed requests
        const pendingRequests = myRequests.filter(r => r.status === 'PENDING');
        const completedRequests = myRequests.filter(r => r.status !== 'PENDING');

        return `
            <div class="content-header">
                <h1>Request Environment Access</h1>
                <p>Request access to environments you don't currently have</p>
            </div>

            <!-- Search and Filter -->
            <div class="row mb-4">
                <div class="col-md-6">
                    <div class="input-group">
                        <span class="input-group-text"><i class="fas fa-search"></i></span>
                        <input type="text" class="form-control" id="env-search"
                               placeholder="Search environments...">
                    </div>
                </div>
            </div>

            <!-- Available Environments -->
            <div class="card mb-4">
                <div class="card-header">
                    <h5 class="mb-0"><i class="fas fa-folder-open me-2"></i>Available Environments</h5>
                </div>
                <div class="card-body p-0">
                    ${buildEnvironmentsList(environments, pendingRequests)}
                </div>
            </div>

            <!-- My Pending Requests -->
            ${pendingRequests.length > 0 ? `
                <div class="card mb-4">
                    <div class="card-header">
                        <h5 class="mb-0"><i class="fas fa-clock me-2"></i>My Pending Requests</h5>
                    </div>
                    <div class="card-body p-0">
                        ${buildMyRequestsTable(pendingRequests, true)}
                    </div>
                </div>
            ` : ''}

            <!-- Request History -->
            ${completedRequests.length > 0 ? `
                <div class="card">
                    <div class="card-header d-flex justify-content-between align-items-center">
                        <h5 class="mb-0"><i class="fas fa-history me-2"></i>Request History</h5>
                        <button class="btn btn-sm btn-outline-secondary" id="toggle-history">
                            <i class="fas fa-chevron-down"></i>
                        </button>
                    </div>
                    <div class="card-body p-0" id="request-history" style="display: none;">
                        ${buildMyRequestsTable(completedRequests, false)}
                    </div>
                </div>
            ` : ''}
        `;
    }

    /**
     * Build environments list for requesting access
     */
    function buildEnvironmentsList(environments, pendingRequests) {
        if (!environments || environments.length === 0) {
            return `
                <div class="empty-state p-4">
                    <i class="fas fa-check-circle fa-2x text-success mb-2"></i>
                    <p>You have access to all environments!</p>
                </div>
            `;
        }

        // Get IDs of environments with pending requests
        const pendingEnvIds = new Set(pendingRequests.map(r => r.environmentId));

        const rows = environments.map(env => {
            const hasPending = pendingEnvIds.has(env.environmentId);

            return `
                <tr class="env-row" data-env-name="${Utils.escapeHtml(env.name).toLowerCase()}">
                    <td>
                        <strong>${Utils.escapeHtml(env.name)}</strong>
                        ${env.description ? `<br><small class="text-muted">${Utils.escapeHtml(env.description)}</small>` : ''}
                    </td>
                    <td>${env.vmCount || 0} VMs</td>
                    <td>
                        ${hasPending ?
                            `<span class="badge bg-warning">Request Pending</span>` :
                            `<button class="btn btn-sm btn-primary" data-env-id="${env.environmentId}"
                                     data-env-name="${Utils.escapeHtml(env.name)}" data-action="request-access">
                                <i class="fas fa-paper-plane"></i> Request Access
                            </button>`
                        }
                    </td>
                </tr>
            `;
        }).join('');

        return `
            <table class="table table-hover mb-0">
                <thead>
                    <tr>
                        <th>Environment</th>
                        <th>Size</th>
                        <th>Action</th>
                    </tr>
                </thead>
                <tbody id="env-list">
                    ${rows}
                </tbody>
            </table>
        `;
    }

    /**
     * Build my requests table
     */
    function buildMyRequestsTable(requests, showCancel) {
        const rows = requests.map(req => {
            const statusBadge = getStatusBadge(req.status);
            const cancelBtn = showCancel && req.status === 'PENDING' ?
                `<button class="btn btn-sm btn-outline-danger" data-request-id="${req.requestId}" data-action="cancel">
                    <i class="fas fa-times"></i> Cancel
                </button>` : '';

            return `
                <tr>
                    <td><strong>${Utils.escapeHtml(req.environmentName || 'Unknown')}</strong></td>
                    <td>${statusBadge}</td>
                    <td>${Utils.escapeHtml(req.requestedAccessLevel || 'USER')}</td>
                    <td>${Utils.formatRelativeTime(req.createdAt)}</td>
                    <td>${req.processedAt ? Utils.formatRelativeTime(req.processedAt) : '-'}</td>
                    <td>${cancelBtn}</td>
                </tr>
            `;
        }).join('');

        return `
            <table class="table table-sm mb-0">
                <thead>
                    <tr>
                        <th>Environment</th>
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
                <div class="content-header">
                    <h1>Pending Access Requests</h1>
                    <p>Review and approve user access requests</p>
                </div>
                <div class="empty-state">
                    <i class="fas fa-inbox fa-3x text-muted"></i>
                    <p class="mt-3">No pending requests</p>
                    <p class="text-muted">All access requests have been processed</p>
                </div>
            `;
        }

        const rows = requests.map(req => `
            <tr>
                <td>
                    <strong>${Utils.escapeHtml(req.requesterDisplayName || 'Unknown')}</strong>
                    <br><small class="text-muted">${Utils.escapeHtml(req.requesterEmail || '')}</small>
                </td>
                <td>
                    <strong>${Utils.escapeHtml(req.environmentName || 'Unknown')}</strong>
                </td>
                <td>
                    <span class="badge bg-${Config.ACCESS_LEVELS[req.requestedAccessLevel]?.color || 'secondary'}">
                        ${req.requestedAccessLevel}
                    </span>
                </td>
                <td>${Utils.formatRelativeTime(req.createdAt)}</td>
                <td>
                    <div class="reason-text">${Utils.escapeHtml(req.reason || '-')}</div>
                </td>
                <td>
                    <div class="btn-group btn-group-sm">
                        <button class="btn btn-success" data-request-id="${req.requestId}" data-action="approve">
                            <i class="fas fa-check"></i> Approve
                        </button>
                        <button class="btn btn-danger" data-request-id="${req.requestId}" data-action="deny">
                            <i class="fas fa-times"></i> Deny
                        </button>
                    </div>
                </td>
            </tr>
        `).join('');

        return `
            <div class="content-header">
                <h1>Pending Access Requests</h1>
                <p>Review and approve user access requests</p>
            </div>
            <div class="card">
                <div class="card-body p-0">
                    <table class="table table-hover mb-0">
                        <thead>
                            <tr>
                                <th>Requester</th>
                                <th>Environment</th>
                                <th>Access Level</th>
                                <th>Requested</th>
                                <th>Reason</th>
                                <th>Actions</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${rows}
                        </tbody>
                    </table>
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
        // Search environments
        $('#env-search').off('input').on('input', Utils.debounce(function() {
            const query = $(this).val().toLowerCase();
            $('.env-row').each(function() {
                const name = $(this).data('env-name');
                $(this).toggle(name.includes(query));
            });
        }, 300));

        // Request access button
        $('[data-action="request-access"]').off('click').on('click', function() {
            const envId = $(this).data('env-id');
            const envName = $(this).data('env-name');
            showRequestAccessModal(envId, envName);
        });

        // Cancel request
        $('[data-action="cancel"]').off('click').on('click', function() {
            const requestId = $(this).data('request-id');
            cancelRequest(requestId);
        });

        // Toggle history
        $('#toggle-history').off('click').on('click', function() {
            const $history = $('#request-history');
            const $icon = $(this).find('i');

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
                        <label class="form-label">Access Level</label>
                        <select class="form-select" id="accessLevel">
                            <option value="VIEWER">Viewer - Can view environment details</option>
                            <option value="USER" selected>User - Can start/stop VMs</option>
                            <option value="ADMIN">Admin - Full environment control</option>
                        </select>
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Reason for Request <span class="text-danger">*</span></label>
                        <textarea class="form-control" id="requestReason" rows="3" required
                                  placeholder="Explain why you need access to this environment..."></textarea>
                    </div>
                </form>
            `,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Submit Request', class: 'btn-primary', id: 'submitRequest' }
            ],
            onShow: function() {
                $('#submitRequest').off('click').on('click', function() {
                    const accessLevel = $('#accessLevel').val();
                    const reason = $('#requestReason').val().trim();

                    if (!reason) {
                        $('#requestReason').addClass('is-invalid');
                        return;
                    }

                    $(this).prop('disabled', true).html('<i class="fas fa-spinner fa-spin"></i> Submitting...');

                    submitAccessRequest(envId, accessLevel, reason);
                });
            }
        });
    }

    /**
     * Submit access request
     */
    function submitAccessRequest(envId, accessLevel, reason) {
        ApiClient.post(Config.API.access.requestAccess(envId), {
            requestedAccessLevel: accessLevel,
            reason: reason
        })
        .done(function() {
            Modals.hide('requestAccessModal');
            Notifications.success('Access request submitted successfully');
            loadRequestAccessPage(); // Refresh page
        })
        .fail(function(xhr) {
            $('#submitRequest').prop('disabled', false).html('Submit Request');
            const msg = xhr.responseJSON?.message || 'Failed to submit request';
            Notifications.error(msg);
        });
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
                    const comments = $('#approveComments').val().trim();

                    let expiresAt = null;
                    if (days) {
                        const date = new Date();
                        date.setDate(date.getDate() + parseInt(days));
                        expiresAt = date.toISOString();
                    }

                    $(this).prop('disabled', true).html('<i class="fas fa-spinner fa-spin"></i> Approving...');

                    ApiClient.post(Config.API.access.approveRequest(requestId), {
                        expiresAt,
                        comments
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

                    ApiClient.post(Config.API.access.denyRequest(requestId), { reason })
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
                    <button class="btn btn-sm btn-outline-danger"
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
        updatePendingBadge
    };
})();

