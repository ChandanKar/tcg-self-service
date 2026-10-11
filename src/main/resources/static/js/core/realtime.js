/**
 * VM Self-Service Platform - Real-time Updates Module
 * Handles auto-refresh, polling, and real-time notifications
 */

const RealTime = (function() {
    'use strict';

    // Polling intervals
    const INTERVALS = {
        dashboard: 30000,      // 30 seconds
        environmentDetail: 15000, // 15 seconds
        operationStatus: 2000,  // 2 seconds
        lockStatus: 10000,      // 10 seconds
        pendingRequests: 60000, // 1 minute
        syncStatus: 60000       // 1 minute
    };

    // The sync pill turns amber when the last cloud sync is older than this (E13-T07).
    const SYNC_STALE_MS = 15 * 60 * 1000;

    // Active timers: key -> { id, callback, interval, lastRun }
    let timers = {};
    let isPageVisible = true;
    let sessionLost = false;

    const NOTIFICATION_COUNT_INTERVAL = 60000;

    /**
     * Initialize real-time updates (call after Auth and NotificationBell are initialised).
     */
    function init() {
        // Track page visibility
        document.addEventListener('visibilitychange', handleVisibilityChange);

        // App-wide polls (not page scoped): they keep running across navigation.
        if (Auth.isEnvAdmin()) {
            startPendingRequestsPolling();
        }
        if (typeof NotificationBell !== 'undefined') {
            startPolling('notificationCount', () => NotificationBell.refreshCount(), NOTIFICATION_COUNT_INTERVAL);
        }
        if ($('#sync-indicator').length) {
            startPolling('syncStatus', updateSyncIndicator, INTERVALS.syncStatus);
        }
    }

    /**
     * Top-bar sync pill from GET /monitoring/sync-status (E13-T07): relative time of the last
     * cloud sync, spinning while one runs, amber when stale, red when the last sync had errors.
     * Hidden when the endpoint is unavailable to this user.
     */
    function updateSyncIndicator() {
        pollGet(Config.API.monitoring.syncStatus)
            .done(function(status) {
                renderSyncIndicator(status || {});
            })
            .fail(function(xhr) {
                if (xhr && (xhr.status === 403 || xhr.status === 404)) {
                    $('#sync-indicator').prop('hidden', true);
                }
            });
    }

    function renderSyncIndicator(status) {
        const $pill = $('#sync-indicator');
        const last = status.lastSyncTime ? new Date(status.lastSyncTime) : null;
        const stale = !last || (Date.now() - last.getTime()) > SYNC_STALE_MS;
        $('#sync-time').text(last ? Utils.formatRelativeTime(last) : 'Never');
        $pill.toggleClass('syncing', !!status.syncInProgress)
            .toggleClass('stale', stale && !status.syncInProgress)
            .toggleClass('error', Number(status.syncErrors) > 0)
            .attr('title', last ? `Last synced with cloud: ${last.toLocaleString()}` : 'Not synced with the cloud yet')
            .prop('hidden', false);
    }

    /**
     * GET for background polls: failures never raise the global error toast, and a 401 stops
     * all polling with one "session expired" notice instead of redirecting from a poll.
     * Page polls should use this too.
     */
    function pollGet(url) {
        return ApiClient.get(url, { suppressGlobalError: true })
            .fail(function(xhr) {
                if (xhr && xhr.status === 401) {
                    onSessionLost();
                }
            });
    }

    function onSessionLost() {
        if (sessionLost) return;
        sessionLost = true;
        stopAllPolling();
        if (typeof Notifications !== 'undefined') {
            Notifications.warning('Your session has expired. Reload the page to sign in again.', 0);
        }
    }

    /**
     * Handle page visibility change. While hidden, interval callbacks are skipped; when the tab
     * becomes visible again every poll that is overdue runs once straight away.
     */
    function handleVisibilityChange() {
        isPageVisible = !document.hidden;
        if (isPageVisible) {
            runOverduePolls();
        }
    }

    function runTimer(timer) {
        timer.lastRun = Date.now();
        try {
            timer.callback();
        } catch (error) {
            console.error('Polling callback failed:', error);
        }
    }

    function runOverduePolls() {
        const now = Date.now();
        Object.values(timers).forEach(timer => {
            if (now - timer.lastRun >= timer.interval) {
                runTimer(timer);
            }
        });
    }

    /**
     * Start polling for a specific feature.
     * @param {string} key - replaces any poll already running under this key
     * @param {function} callback
     * @param {number} interval - ms (defaults to INTERVALS[key] or 30s)
     * @param {object} options
     * @param {boolean} options.immediate - run once now (default true)
     * @param {boolean} options.pageScoped - stop automatically when the user leaves the page
     */
    function startPolling(key, callback, interval, options = {}) {
        stopPolling(key);

        const timer = { id: null, callback, interval: interval || INTERVALS[key] || 30000, lastRun: 0 };
        timers[key] = timer;

        if (isPageVisible && options.immediate !== false) {
            runTimer(timer);
        } else {
            timer.lastRun = Date.now();
        }

        timer.id = setInterval(() => {
            if (isPageVisible) {
                runTimer(timer);
            }
        }, timer.interval);

        if (options.pageScoped === true && typeof ContentRouter !== 'undefined' && ContentRouter.onLeave) {
            // Only stop this exact poll; a newer poll under the same key belongs to another page.
            ContentRouter.onLeave(() => {
                if (timers[key] === timer) {
                    stopPolling(key);
                }
            });
        }
    }

    /**
     * Stop polling for a specific feature
     */
    function stopPolling(key) {
        if (timers[key]) {
            clearInterval(timers[key].id);
            delete timers[key];
        }
    }

    /**
     * Stop all polling
     */
    function stopAllPolling() {
        Object.keys(timers).forEach(key => {
            clearInterval(timers[key].id);
        });
        timers = {};
    }

    /** Keys of the polls that are currently running (for diagnostics and tests). */
    function activePolls() {
        return Object.keys(timers);
    }

    /**
     * Start pending requests badge polling
     */
    function startPendingRequestsPolling() {
        startPolling('pendingRequests', updatePendingBadge, INTERVALS.pendingRequests);
    }

    /**
     * Update pending requests badge
     */
    function updatePendingBadge() {
        if (!Auth.isEnvAdmin()) return;

        pollGet(Config.API.access.pendingRequests)
            .done(function(requests) {
                const count = requests ? requests.length : 0;
                // Sidebar "Pending Requests" badge (index.html .pending-count)
                $('.pending-count').text(count).toggle(count > 0);
            });
    }

    /**
     * Show connection status indicator
     */
    function showConnectionStatus(status) {
        let $indicator = $('#connection-status');

        if (!$indicator.length) {
            $indicator = $('<div id="connection-status"></div>');
            $('body').append($indicator);
        }

        $indicator.removeClass('connected disconnected reconnecting');

        switch (status) {
            case 'connected':
                $indicator.addClass('connected').html(
                    '<i class="fas fa-wifi"></i> Connected'
                ).fadeIn().delay(2000).fadeOut();
                break;
            case 'disconnected':
                $indicator.addClass('disconnected').html(
                    '<i class="fas fa-wifi-slash"></i> Disconnected - Retrying...'
                ).fadeIn();
                break;
            case 'reconnecting':
                $indicator.addClass('reconnecting').html(
                    '<i class="fas fa-sync fa-spin"></i> Reconnecting...'
                ).fadeIn();
                break;
        }
    }

    // Public API
    return {
        init,
        startPolling,
        stopPolling,
        stopAllPolling,
        activePolls,
        pollGet,
        updatePendingBadge,
        showConnectionStatus
    };
})();

