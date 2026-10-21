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

    // Live updates (U17-T04): after this long without a successful poll, while polls fail on the
    // network or a gateway, "Reconnecting…" shows under the top bar until the next success.
    const RECONNECT_AFTER_MS = 10000;
    const NETWORK_FAILURES = [0, 502, 503, 504];
    let lastSuccessAt = Date.now();

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
        // Never synced (e.g. sync switched off) is shown plainly, not as a warning.
        const stale = !!last && (Date.now() - last.getTime()) > SYNC_STALE_MS;
        $('#sync-time').text(last ? Utils.formatRelativeTime(last) : 'Not synced');
        $pill.toggleClass('never', !last)
            .toggleClass('syncing', !!status.syncInProgress)
            .toggleClass('stale', stale && !status.syncInProgress)
            .toggleClass('error', Number(status.syncErrors) > 0)
            .attr('title', last ? `Last synced with cloud: ${last.toLocaleString()}` : 'Not synced with the cloud yet')
            .prop('hidden', false);
    }

    /**
     * GET for background polls: failures never raise the global error toast; a 401 stops all
     * polling and opens the signed-out dialog (no redirect from a poll); network failures for more
     * than 10 s show "Reconnecting…" until a poll succeeds. Page polls should use this too.
     */
    function pollGet(url) {
        return ApiClient.get(url, { suppressGlobalError: true })
            .done(function() {
                lastSuccessAt = Date.now();
                showReconnecting(false);
            })
            .fail(function(xhr) {
                const status = xhr ? xhr.status : 0;
                if (status === 401) {
                    onSessionLost();
                } else if (NETWORK_FAILURES.includes(status) && Date.now() - lastSuccessAt > RECONNECT_AFTER_MS) {
                    showReconnecting(true);
                }
            });
    }

    function onSessionLost() {
        if (sessionLost) return;
        sessionLost = true;
        stopAllPolling();
        showReconnecting(false);
        if (typeof SessionEnded !== 'undefined') SessionEnded.show();
    }

    /** The strip under the top bar: "Reconnecting… VM lights may be a minute behind." (role=status). */
    function showReconnecting(on) {
        const strip = typeof document !== 'undefined' && document.getElementById('reconnect-strip');
        if (!strip || strip.hidden === !on) return;
        strip.hidden = !on;
        // The live region announces the text when it appears.
        strip.textContent = on ? 'Reconnecting… VM lights may be a minute behind.' : '';
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
        // After a 401 nothing polls again until the page is reloaded, including a page that
        // finishes loading after the session was lost (Home's 30 s refresh, U06).
        if (sessionLost) return;

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
                // Sidebar "Access requests" count (the Manage section is always open)
                $('.pending-count').text(count).toggle(count > 0);
            });
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
        showReconnecting,
        onSessionLost
    };
})();

