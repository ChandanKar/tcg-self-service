/**
 * VM Self-Service Platform - Lock Management Module
 * Handles environment lock operations with enhanced UI
 */

const Locks = (function() {
    'use strict';

    /**
     * Acquire lock on an environment
     */
    function acquire(envId, onSuccess) {
        Modals.show({
            id: 'acquireLockModal',
            title: 'Acquire Environment Lock',
            body: `
                <form id="acquireLockForm">
                    <div class="mb-3">
                        <label class="form-label">Reason for acquiring lock</label>
                        <textarea class="form-control" id="lockReason" rows="3"
                                  placeholder="e.g., Deploying new version, Running maintenance..."></textarea>
                        <div class="form-text">Optional but recommended for team visibility</div>
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Expected duration</label>
                        <select class="form-select" id="lockDuration">
                            <option value="">Until manually released</option>
                            <option value="30">30 minutes</option>
                            <option value="60">1 hour</option>
                            <option value="120">2 hours</option>
                            <option value="240">4 hours</option>
                            <option value="480">8 hours</option>
                        </select>
                        <div class="form-text">Lock will auto-release after this time (optional)</div>
                    </div>
                </form>
            `,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Acquire Lock', class: 'btn-primary', id: 'confirmAcquireLock' }
            ],
            onShow: function() {
                $('#confirmAcquireLock').off('click').on('click', function() {
                    const reason = $('#lockReason').val().trim();
                    const durationMinutes = $('#lockDuration').val();

                    $(this).prop('disabled', true).html('<i class="fas fa-spinner fa-spin"></i> Acquiring...');

                    const data = { reason };
                    if (durationMinutes) {
                        data.expectedDurationMinutes = parseInt(durationMinutes);
                    }

                    ApiClient.post(Config.API.locks.acquire(envId), data)
                        .done(function(lock) {
                            Modals.hide('acquireLockModal');
                            Notifications.success('Lock acquired successfully');
                            if (onSuccess) onSuccess(lock);
                        })
                        .fail(function(xhr) {
                            $('#confirmAcquireLock').prop('disabled', false).html('Acquire Lock');
                            const msg = xhr.responseJSON?.message || 'Failed to acquire lock';
                            Notifications.error(msg);
                        });
                });
            }
        });
    }

    /**
     * Release lock on an environment
     */
    function release(envId, onSuccess) {
        Modals.confirm(
            'Release Lock',
            'Are you sure you want to release the lock on this environment?<br><br>' +
            '<small class="text-muted">Other users will be able to lock and modify this environment.</small>',
            function() {
                ApiClient.post(Config.API.locks.release(envId))
                    .done(function() {
                        Notifications.success('Lock released');
                        if (onSuccess) onSuccess();
                    })
                    .fail(function(xhr) {
                        Notifications.error(xhr.responseJSON?.message || 'Failed to release lock');
                    });
            },
            { confirmText: 'Release Lock', confirmClass: 'btn-warning' }
        );
    }

    /**
     * Break lock on an environment (admin only)
     */
    function breakLock(envId, currentLock, onSuccess) {
        const lockedBy = currentLock?.lockedByDisplayName || 'another user';

        Modals.show({
            id: 'breakLockModal',
            title: 'Break Lock',
            body: `
                <div class="alert alert-warning">
                    <i class="fas fa-exclamation-triangle"></i>
                    <strong>Warning:</strong> This will forcibly remove the lock held by <strong>${Utils.escapeHtml(lockedBy)}</strong>.
                </div>
                <form id="breakLockForm">
                    <div class="mb-3">
                        <label class="form-label">Reason for breaking lock <span class="text-danger">*</span></label>
                        <textarea class="form-control" id="breakLockReason" rows="3" required
                                  placeholder="Explain why you need to break this lock..."></textarea>
                        <div class="form-text">This will be logged and the lock holder will be notified.</div>
                    </div>
                </form>
            `,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Break Lock', class: 'btn-danger', id: 'confirmBreakLock' }
            ],
            onShow: function() {
                $('#confirmBreakLock').off('click').on('click', function() {
                    const reason = $('#breakLockReason').val().trim();

                    if (!reason) {
                        $('#breakLockReason').addClass('is-invalid');
                        return;
                    }

                    $(this).prop('disabled', true).html('<i class="fas fa-spinner fa-spin"></i> Breaking...');

                    ApiClient.post(Config.API.locks.break(envId), { reason })
                        .done(function() {
                            Modals.hide('breakLockModal');
                            Notifications.success('Lock broken successfully');
                            if (onSuccess) onSuccess();
                        })
                        .fail(function(xhr) {
                            $('#confirmBreakLock').prop('disabled', false).html('Break Lock');
                            const msg = xhr.responseJSON?.message || 'Failed to break lock';
                            Notifications.error(msg);
                        });
                });
            }
        });
    }

    /**
     * Show lock history modal
     */
    function showHistory(envId, envName) {
        Modals.show({
            id: 'lockHistoryModal',
            title: `Lock History: ${envName}`,
            size: 'lg',
            body: `
                <div class="lock-history">
                    <div class="text-center py-4">
                        <i class="fas fa-spinner fa-spin"></i> Loading history...
                    </div>
                </div>
            `,
            buttons: [
                { text: 'Close', class: 'btn-secondary', dismiss: true }
            ],
            onShow: function() {
                loadLockHistory(envId);
            }
        });
    }

    /**
     * Load lock history into modal
     */
    function loadLockHistory(envId) {
        ApiClient.get(Config.API.locks.history(envId))
            .done(function(history) {
                if (!history || history.length === 0) {
                    $('.lock-history').html(`
                        <div class="empty-state text-center py-4">
                            <i class="fas fa-lock-open fa-2x text-muted mb-2"></i>
                            <p>No lock history found</p>
                        </div>
                    `);
                    return;
                }

                const rows = history.map(entry => {
                    const action = entry.action || 'LOCK';
                    const actionBadge = getActionBadge(action);
                    const duration = entry.releasedAt && entry.acquiredAt ?
                        Utils.formatDuration(new Date(entry.releasedAt) - new Date(entry.acquiredAt)) :
                        (entry.releasedAt ? '-' : 'Active');

                    return `
                        <tr>
                            <td>${Utils.formatRelativeTime(entry.acquiredAt || entry.timestamp)}</td>
                            <td>${Utils.escapeHtml(entry.userDisplayName || '-')}</td>
                            <td>${actionBadge}</td>
                            <td>${Utils.escapeHtml(entry.reason || '-')}</td>
                            <td>${duration}</td>
                        </tr>
                    `;
                }).join('');

                $('.lock-history').html(`
                    <table class="table table-sm table-hover">
                        <thead>
                            <tr>
                                <th>Time</th>
                                <th>User</th>
                                <th>Action</th>
                                <th>Reason</th>
                                <th>Duration</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${rows}
                        </tbody>
                    </table>
                `);
            })
            .fail(function() {
                $('.lock-history').html(`
                    <div class="alert alert-danger">Failed to load lock history</div>
                `);
            });
    }

    /**
     * Get action badge HTML
     */
    function getActionBadge(action) {
        const badges = {
            'ACQUIRED': '<span class="badge bg-success">Acquired</span>',
            'RELEASED': '<span class="badge bg-secondary">Released</span>',
            'BROKEN': '<span class="badge bg-danger">Broken</span>',
            'EXPIRED': '<span class="badge bg-warning">Expired</span>',
            'LOCK': '<span class="badge bg-primary">Lock</span>',
            'UNLOCK': '<span class="badge bg-secondary">Unlock</span>'
        };
        return badges[action] || `<span class="badge bg-secondary">${action}</span>`;
    }

    /**
     * Get lock status for an environment
     */
    function getStatus(envId) {
        return new Promise((resolve, reject) => {
            ApiClient.get(Config.API.locks.status(envId))
                .done(resolve)
                .fail(reject);
        });
    }

    /**
     * Build lock status banner HTML
     */
    function buildLockBanner(lockStatus, envId, isEnvAdmin) {
        if (!lockStatus) {
            return '';
        }

        const isMyLock = lockStatus.lockedByUserId === Auth.getUserId();

        if (lockStatus.isLocked) {
            const lockedBy = isMyLock ? 'you' : (lockStatus.lockedByDisplayName || 'another user');
            const lockedAt = lockStatus.lockedAt ? Utils.formatRelativeTime(lockStatus.lockedAt) : '';
            const reason = lockStatus.lockReason ?
                `<br><small class="text-muted"><i class="fas fa-comment"></i> ${Utils.escapeHtml(lockStatus.lockReason)}</small>` : '';

            let buttons = '';
            if (isMyLock) {
                buttons = `
                    <button class="btn btn-sm btn-warning" data-lock-action="release" data-env-id="${envId}">
                        <i class="fas fa-unlock"></i> Release Lock
                    </button>
                `;
            } else if (isEnvAdmin) {
                buttons = `
                    <button class="btn btn-sm btn-danger" data-lock-action="break" data-env-id="${envId}">
                        <i class="fas fa-hammer"></i> Break Lock
                    </button>
                `;
            }

            return `
                <div class="lock-banner locked">
                    <div class="lock-info">
                        <i class="fas fa-lock"></i>
                        <div>
                            <strong>Locked by ${Utils.escapeHtml(lockedBy)}</strong>
                            ${lockedAt ? `<span class="lock-time">(${lockedAt})</span>` : ''}
                            ${reason}
                        </div>
                    </div>
                    <div class="lock-actions">
                        <button class="btn btn-sm btn-outline-secondary me-2" data-lock-action="history" data-env-id="${envId}">
                            <i class="fas fa-history"></i> History
                        </button>
                        ${buttons}
                    </div>
                </div>
            `;
        } else {
            return `
                <div class="lock-banner unlocked">
                    <div class="lock-info">
                        <i class="fas fa-unlock"></i>
                        <span>This environment is unlocked</span>
                    </div>
                    <div class="lock-actions">
                        <button class="btn btn-sm btn-outline-secondary me-2" data-lock-action="history" data-env-id="${envId}">
                            <i class="fas fa-history"></i> History
                        </button>
                        <button class="btn btn-sm btn-primary" data-lock-action="acquire" data-env-id="${envId}">
                            <i class="fas fa-lock"></i> Acquire Lock
                        </button>
                    </div>
                </div>
            `;
        }
    }

    /**
     * Bind lock action events
     */
    function bindLockEvents(envId, envName, onUpdate) {
        // Acquire lock
        $('[data-lock-action="acquire"]').off('click').on('click', function() {
            acquire(envId, onUpdate);
        });

        // Release lock
        $('[data-lock-action="release"]').off('click').on('click', function() {
            release(envId, onUpdate);
        });

        // Break lock
        $('[data-lock-action="break"]').off('click').on('click', function() {
            getStatus(envId).then(function(lockStatus) {
                breakLock(envId, lockStatus, onUpdate);
            });
        });

        // Lock history
        $('[data-lock-action="history"]').off('click').on('click', function() {
            showHistory(envId, envName);
        });
    }

    /**
     * Check if user can perform operations (must hold lock or env is unlocked)
     */
    function canOperate(lockStatus) {
        if (!lockStatus || !lockStatus.isLocked) {
            return true; // Unlocked, anyone can operate
        }
        return lockStatus.lockedByUserId === Auth.getUserId();
    }

    /**
     * Show lock required message
     */
    function showLockRequired(envName) {
        Notifications.warning(`You must acquire the lock on "${envName}" before performing this operation.`);
    }

    // Public API
    return {
        acquire,
        release,
        breakLock,
        showHistory,
        getStatus,
        buildLockBanner,
        bindLockEvents,
        canOperate,
        showLockRequired
    };
})();

