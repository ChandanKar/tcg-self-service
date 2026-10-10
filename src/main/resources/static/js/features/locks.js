/**
 * VM Self-Service Platform - Lock Management Module
 * Handles environment lock operations with enhanced UI
 */

const Locks = (function() {
    'use strict';

    /**
     * Acquire lock on an environment
     */
    function acquire(envId, onSuccess, expiryEnabled) {
        // E07-T05: only promise an automatic release when the expiry sweep is on.
        const durationHelp = expiryEnabled
            ? 'The lock is released automatically at the end of this time. You will be warned 15 and 5 minutes before.'
            : 'For your team\'s information; the lock stays until you release it.';
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
                        <div class="form-text" id="lockDurationHelp">${Utils.escapeHtml(durationHelp)}</div>
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
            { confirmText: 'Release Lock', confirmClass: 'btn-warning', html: true }
        );
    }

    /**
     * Extend my lock (E07-T03/T05): 30, 60 or 120 more minutes; the server enforces the maximum.
     */
    function extend(envId, onSuccess) {
        Modals.show({
            id: 'extendLockModal',
            title: 'Extend Lock',
            body: `
                <form id="extendLockForm">
                    <div class="mb-3">
                        <label class="form-label" for="extendLockMinutes">Extend by</label>
                        <select class="form-select" id="extendLockMinutes">
                            <option value="30">30 minutes</option>
                            <option value="60" selected>1 hour</option>
                            <option value="120">2 hours</option>
                        </select>
                        <div class="form-text">Counted from the current expiry time.</div>
                    </div>
                </form>
            `,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Extend', class: 'btn-primary', id: 'confirmExtendLock' }
            ],
            onShow: function() {
                $('#confirmExtendLock').off('click').on('click', function() {
                    const minutes = parseInt($('#extendLockMinutes').val(), 10);
                    $(this).prop('disabled', true).html('<i class="fas fa-spinner fa-spin"></i> Extending...');

                    ApiClient.post(Config.API.locks.extend(envId), { minutes })
                        .done(function(lock) {
                            Modals.hide('extendLockModal');
                            Notifications.success('Lock extended');
                            if (onSuccess) onSuccess(lock);
                        })
                        .fail(function(xhr) {
                            $('#confirmExtendLock').prop('disabled', false).html('Extend');
                            Notifications.error(xhr.responseJSON?.message || 'Failed to extend lock');
                        });
                });
            }
        });
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
                    // E07-T05: the row's own time; a duration only where the lock ended.
                    const at = entry.performedAt || entry.timestamp;
                    const ended = ['RELEASED', 'BROKEN', 'EXPIRED'].includes(action);
                    const duration = ended && entry.releasedAt && entry.acquiredAt ?
                        Utils.formatDuration(new Date(entry.releasedAt) - new Date(entry.acquiredAt)) : '-';

                    return `
                        <tr>
                            <td title="${Utils.escapeHtml(Utils.formatDate(at))}">${Utils.escapeHtml(Utils.formatRelativeTime(at))}</td>
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
            'EXTENDED': '<span class="badge bg-info">Extended</span>',
            'LOCK': '<span class="badge bg-primary">Lock</span>',
            'UNLOCK': '<span class="badge bg-secondary">Unlock</span>'
        };
        return badges[action] || `<span class="badge bg-secondary">${Utils.escapeHtml(action)}</span>`;
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

    /** Milliseconds since the epoch for a server timestamp (ISO string or number), or null. */
    function toMillis(value) {
        if (value === null || value === undefined || value === '') return null;
        const ms = typeof value === 'number' ? value : Date.parse(value);
        return isNaN(ms) ? null : ms;
    }

    /** "Auto-releases in 42 min" for a lock expiring at {@code expiresAtMs}. */
    function countdownText(expiresAtMs) {
        const minutes = Math.ceil((expiresAtMs - Date.now()) / 60000);
        if (minutes <= 0) return 'Auto-release due';
        if (minutes < 60) return `Auto-releases in ${minutes} min`;
        const h = Math.floor(minutes / 60);
        const m = minutes % 60;
        return `Auto-releases in ${h} h${m ? ` ${m} min` : ''}`;
    }

    const EXPIRING_SOON_MS = 15 * 60 * 1000;

    /**
     * Build lock status banner HTML. Buttons come only from the server's per-viewer flags
     * (canAcquire/canRelease/canExtend/canBreak, E07-T05).
     */
    function buildLockBanner(lockStatus, envId) {
        if (!lockStatus) {
            return '';
        }
        const id = Utils.escapeHtml(envId);
        const historyButton = `
            <button class="btn btn-sm btn-ghost me-2" data-lock-action="history" data-env-id="${id}">
                <i class="fas fa-history"></i> History
            </button>`;

        if (lockStatus.isLocked) {
            const isMyLock = lockStatus.canRelease === true;
            const holder = lockStatus.lockedByDisplayName || 'another user';
            const lockedAt = lockStatus.lockedAt ? Utils.formatRelativeTime(lockStatus.lockedAt) : '';
            const reason = lockStatus.lockReason ?
                `<br><small class="text-muted"><i class="fas fa-comment"></i> ${Utils.escapeHtml(lockStatus.lockReason)}</small>` : '';
            const title = isMyLock
                ? 'Locked by you'
                : `Held by ${Utils.escapeHtml(holder)} &ndash; operations are blocked for you`;

            const expiresAtMs = toMillis(lockStatus.expiresAt);
            const expiring = expiresAtMs !== null && expiresAtMs - Date.now() < EXPIRING_SOON_MS;
            const countdown = expiresAtMs !== null
                ? `<span class="lock-countdown" data-expires-at="${expiresAtMs}" title="${Utils.escapeHtml(Utils.formatDate(expiresAtMs))}">`
                    + `<i class="fas fa-hourglass-half"></i> ${Utils.escapeHtml(countdownText(expiresAtMs))}</span>`
                : '';

            let buttons = '';
            if (lockStatus.canExtend) {
                buttons += `
                    <button class="btn btn-sm btn-tonal btn-primary" data-lock-action="extend" data-env-id="${id}">
                        <i class="fas fa-clock"></i> Extend
                    </button>`;
            }
            if (lockStatus.canRelease) {
                buttons += `
                    <button class="btn btn-sm btn-tonal btn-warning" data-lock-action="release" data-env-id="${id}">
                        <i class="fas fa-unlock"></i> Release Lock
                    </button>`;
            }
            if (lockStatus.canBreak) {
                buttons += `
                    <button class="btn btn-sm btn-tonal btn-danger" data-lock-action="break" data-env-id="${id}">
                        <i class="fas fa-hammer"></i> Break Lock
                    </button>`;
            }

            const classes = ['lock-banner', 'locked', isMyLock ? 'mine' : 'other'];
            if (expiring) classes.push('expiring');
            return `
                <div class="${classes.join(' ')}">
                    <div class="lock-info">
                        <i class="fas fa-lock"></i>
                        <div>
                            <strong>${title}</strong>
                            ${lockedAt ? `<span class="lock-time">(${Utils.escapeHtml(lockedAt)})</span>` : ''}
                            ${countdown}
                            ${reason}
                        </div>
                    </div>
                    <div class="lock-actions">
                        ${historyButton}
                        ${buttons}
                    </div>
                </div>
            `;
        }

        const acquireButton = lockStatus.canAcquire ? `
            <button class="btn btn-sm btn-primary" data-lock-action="acquire" data-env-id="${id}"
                    data-expiry-enabled="${lockStatus.lockExpiryEnabled ? 'true' : 'false'}">
                <i class="fas fa-lock"></i> Acquire Lock
            </button>` : '';
        return `
            <div class="lock-banner unlocked">
                <div class="lock-info">
                    <i class="fas fa-unlock"></i>
                    <span>This environment is unlocked</span>
                </div>
                <div class="lock-actions">
                    ${historyButton}
                    ${acquireButton}
                </div>
            </div>
        `;
    }

    /**
     * Keep every .lock-countdown on the page current (every 30 s, stops when the user leaves the
     * page). When a watched countdown reaches zero, {@code onExpired} runs once so the caller can
     * re-fetch the status; a lock already overdue when shown never triggers it (no reload loop).
     */
    function startCountdown(onExpired) {
        if (typeof RealTime === 'undefined' || !RealTime.startPolling) return;
        if (!$('.lock-countdown').length) {
            RealTime.stopPolling && RealTime.stopPolling('lock-countdown');
            return;
        }
        RealTime.startPolling('lock-countdown', function() {
            const $counters = $('.lock-countdown');
            if (!$counters.length) {
                RealTime.stopPolling('lock-countdown');
                return;
            }
            $counters.each(function() {
                const $el = $(this);
                const expiresAtMs = Number($el.attr('data-expires-at'));
                const remaining = expiresAtMs - Date.now();
                $el.html(`<i class="fas fa-hourglass-half"></i> ${Utils.escapeHtml(countdownText(expiresAtMs))}`);
                $el.closest('.lock-banner').toggleClass('expiring', remaining < EXPIRING_SOON_MS);
                if (remaining > 0) {
                    $el.data('armed', true);
                } else if ($el.data('armed') && !$el.data('fired')) {
                    $el.data('fired', true);
                    if (onExpired) onExpired();
                }
            });
        }, 30000, { pageScoped: true });
    }

    /**
     * Bind lock action events
     */
    function bindLockEvents(envId, envName, onUpdate) {
        // Acquire lock
        $('[data-lock-action="acquire"]').off('click').on('click', function() {
            acquire(envId, onUpdate, $(this).attr('data-expiry-enabled') === 'true');
        });

        // Extend my lock
        $('[data-lock-action="extend"]').off('click').on('click', function() {
            extend(envId, onUpdate);
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
        extend,
        startCountdown,
        breakLock,
        showHistory,
        getStatus,
        buildLockBanner,
        bindLockEvents,
        canOperate,
        showLockRequired
    };
})();

