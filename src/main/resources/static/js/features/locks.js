/**
 * VM Self-Service Platform - Lock Management Module
 * Handles environment lock operations with enhanced UI
 */

const Locks = (function() {
    'use strict';

    const pad = n => String(n).padStart(2, '0');

    /** "14:30" for a time today, "Mon 14:30" on another day. */
    function hhmm(ms) {
        const d = new Date(ms);
        const time = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
        return d.toDateString() === new Date().toDateString() ? time
            : `${d.toLocaleDateString([], { weekday: 'short' })} ${time}`;
    }

    /** Minutes from now until 23:59 today (at least 15). */
    function minutesToEndOfDay() {
        const end = new Date();
        end.setHours(23, 59, 0, 0);
        return Math.max(15, Math.round((end.getTime() - Date.now()) / 60000));
    }

    /**
     * Lock the environment (LockLease.dc.html): why (others see it) and until when, as chips:
     * 1 hour, 2 hours, End of day, Until I release it. Without the expiry sweep a lock only ends
     * when released, so that is the one choice offered.
     */
    function acquire(envId, onSuccess, expiryEnabled, envName) {
        const options = expiryEnabled
            ? [{ value: 60, label: '1 hour' }, { value: 120, label: '2 hours' },
               { value: minutesToEndOfDay(), label: 'End of day' }, { value: '', label: 'Until I release it' }]
            : [{ value: '', label: 'Until I release it' }];
        const help = value => !expiryEnabled
            ? 'For your team\'s information; the lock stays until you release it.'
            : value === ''
                ? 'It stays locked until you release it or an admin breaks it.'
                : 'It unlocks on its own at that time; you are warned 15 and 5 minutes before.';
        const name = envName || 'this environment';
        Modals.show({
            id: 'acquireLockModal',
            title: `Lock ${name}`,
            body: `
                <form id="acquireLockForm" class="dialog-form">
                    <p class="dialog-lead">While it's locked, only you can start or stop its VMs. Schedules wait and retry.</p>
                    <div class="mb-3">
                        <label class="form-label" for="lockReason">Why (others see this)</label>
                        <input type="text" class="form-control" id="lockReason" maxlength="500"
                               placeholder="e.g. Data migration rehearsal">
                    </div>
                    ${ChoiceChips.markup({ id: 'lockUntil', legend: 'Until', options, selected: expiryEnabled ? 120 : '' })}
                    <p class="form-text mt-2 mb-0" id="lockDurationHelp">${Utils.escapeHtml(help(expiryEnabled ? '120' : ''))}</p>
                </form>
            `,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Lock environment', class: 'btn-primary', id: 'confirmAcquireLock' }
            ],
            onShow: function() {
                $('#lockUntil').off('choice-change').on('choice-change', e => $('#lockDurationHelp').text(help(e.detail)));
                $('#acquireLockForm').off('submit').on('submit', e => { e.preventDefault(); $('#confirmAcquireLock').trigger('click'); });
                $('#confirmAcquireLock').off('click').on('click', function() {
                    const reason = $('#lockReason').val().trim();
                    const durationMinutes = ChoiceChips.value('lockUntil');

                    $(this).prop('disabled', true).text('Locking…');

                    const data = { reason };
                    if (durationMinutes) {
                        data.expectedDurationMinutes = parseInt(durationMinutes, 10);
                    }

                    ApiClient.post(Config.API.locks.acquire(envId), data)
                        .done(function(lock) {
                            Modals.hide('acquireLockModal');
                            Notifications.success('Locked');
                            if (onSuccess) onSuccess(lock);
                        })
                        .fail(function(xhr) {
                            $('#confirmAcquireLock').prop('disabled', false).text('Lock environment');
                            const msg = xhr.responseJSON?.message || 'Could not lock the environment';
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
     * Extend my lock (E07-T03/T05): 30 min, 1 h or 2 h more, as chips; the server enforces the maximum.
     */
    function extend(envId, onSuccess) {
        Modals.show({
            id: 'extendLockModal',
            title: 'Keep the lock longer',
            body: `
                <form id="extendLockForm" class="dialog-form">
                    ${ChoiceChips.markup({ id: 'extendLockMinutes', legend: 'Extend by', selected: 60,
                        options: [{ value: 30, label: '30 min' }, { value: 60, label: '1 h' }, { value: 120, label: '2 h' }] })}
                    <p class="form-text mt-2 mb-0">Counted from the current end time.</p>
                </form>
            `,
            buttons: [
                { text: 'Cancel', class: 'btn-secondary', dismiss: true },
                { text: 'Extend', class: 'btn-primary', id: 'confirmExtendLock' }
            ],
            onShow: function() {
                $('#confirmExtendLock').off('click').on('click', function() {
                    const minutes = parseInt(ChoiceChips.value('extendLockMinutes'), 10);
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
     * The "Lock" fact in "About this environment" (EnvDetail.dc.html, U07-T05): the value
     * ("Not locked", "Locked by you until 14:30", "Locked by Alice"), a note (the reason, since
     * when, the countdown, or what a lock is for) and the actions the server allows this viewer
     * (canAcquire / canExtend / canRelease / canBreak, E07-T05), plus Lock history.
     */
    function buildLockFact(lockStatus, envId) {
        if (!lockStatus) return '';
        const id = Utils.escapeHtml(envId);
        const action = (name, label) =>
            `<button type="button" class="btn btn-link env-fact-action" data-lock-action="${name}" data-env-id="${id}"`
            + (name === 'acquire' ? ` data-expiry-enabled="${lockStatus.lockExpiryEnabled ? 'true' : 'false'}"` : '')
            + `>${label}</button>`;
        const history = `<button type="button" class="btn btn-link env-fact-link" data-lock-action="history" data-env-id="${id}">Lock history</button>`;

        if (!lockStatus.isLocked) {
            return `
                <div class="env-fact lock-fact unlocked">
                    <div class="env-fact-head"><span>Lock</span><span class="env-fact-actions">${lockStatus.canAcquire ? action('acquire', 'Lock') : ''}</span></div>
                    <div class="env-fact-value">Not locked</div>
                    <div class="env-fact-note">${lockStatus.canAcquire ? 'Lock it so others can\'t start or stop VMs. ' : ''}${history}</div>
                </div>`;
        }

        const isMyLock = lockStatus.canRelease === true;
        const holder = lockStatus.lockedByDisplayName || 'another user';
        const expiresAtMs = toMillis(lockStatus.expiresAt);
        const lockedAtMs = toMillis(lockStatus.lockedAt);
        const expiring = expiresAtMs !== null && expiresAtMs - Date.now() < EXPIRING_SOON_MS;
        const value = isMyLock
            ? 'Locked by you' + (expiresAtMs !== null ? ` until ${hhmm(expiresAtMs)}` : '')
            : Utils.html`Locked by ${holder}`;
        const parts = [];
        if (lockStatus.lockReason) parts.push(Utils.html`“${lockStatus.lockReason}”`);
        if (lockedAtMs !== null) parts.push(`since ${hhmm(lockedAtMs)}`);
        if (!isMyLock) parts.push('starting and stopping are blocked for you');
        const countdown = expiresAtMs !== null
            ? `<span class="lock-countdown" data-expires-at="${expiresAtMs}" title="${Utils.escapeHtml(Utils.formatDate(expiresAtMs))}">`
                + `${Utils.escapeHtml(countdownText(expiresAtMs))}</span>`
            : '';
        const actions = (lockStatus.canExtend ? action('extend', 'Extend') : '')
            + (lockStatus.canRelease ? action('release', 'Release') : '')
            + (lockStatus.canBreak ? action('break', 'Break') : '');
        const classes = ['env-fact', 'lock-fact', 'locked', isMyLock ? 'mine' : 'other'];
        if (expiring) classes.push('expiring');
        return `
            <div class="${classes.join(' ')}">
                <div class="env-fact-head"><span>Lock</span><span class="env-fact-actions">${actions}</span></div>
                <div class="env-fact-value">${value}</div>
                <div class="env-fact-note">${parts.join(' · ')}${countdown ? (parts.length ? ' · ' : '') + countdown : ''}</div>
                <div class="env-fact-note">${history}</div>
            </div>`;
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
                $el.text(countdownText(expiresAtMs));
                $el.closest('.lock-fact').toggleClass('expiring', remaining < EXPIRING_SOON_MS);
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
            acquire(envId, onUpdate, $(this).attr('data-expiry-enabled') === 'true', envName);
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
        buildLockFact,
        bindLockEvents,
        canOperate,
        showLockRequired
    };
})();

