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
        pendingRequests: 60000  // 1 minute
    };

    // Active timers
    let timers = {};
    let isPageVisible = true;

    /**
     * Initialize real-time updates
     */
    function init() {
        // Track page visibility
        document.addEventListener('visibilitychange', handleVisibilityChange);

        // Start pending requests badge update for admins
        if (Auth.isEnvAdmin()) {
            startPendingRequestsPolling();
        }

        console.log('RealTime module initialized');
    }

    /**
     * Handle page visibility change
     */
    function handleVisibilityChange() {
        isPageVisible = !document.hidden;

        if (isPageVisible) {
            // Resume polling when page becomes visible
            resumeAllPolling();
        } else {
            // Pause polling when page is hidden
            pauseAllPolling();
        }
    }

    /**
     * Start polling for a specific feature
     */
    function startPolling(key, callback, interval) {
        stopPolling(key);

        // Run immediately
        if (isPageVisible) {
            callback();
        }

        // Set up interval
        timers[key] = setInterval(() => {
            if (isPageVisible) {
                callback();
            }
        }, interval || INTERVALS[key] || 30000);
    }

    /**
     * Stop polling for a specific feature
     */
    function stopPolling(key) {
        if (timers[key]) {
            clearInterval(timers[key]);
            delete timers[key];
        }
    }

    /**
     * Stop all polling
     */
    function stopAllPolling() {
        Object.keys(timers).forEach(key => {
            clearInterval(timers[key]);
        });
        timers = {};
    }

    /**
     * Pause all polling (when page hidden)
     */
    function pauseAllPolling() {
        // Timers continue but callbacks check isPageVisible
    }

    /**
     * Resume all polling (when page visible)
     */
    function resumeAllPolling() {
        // Callbacks will run on next interval
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

        ApiClient.get(Config.API.access.pendingRequests)
            .done(function(requests) {
                const count = requests ? requests.length : 0;
                const $badge = $('.pending-requests-badge');

                if (count > 0) {
                    if ($badge.length) {
                        $badge.text(count).show();
                    } else {
                        // Add badge to sidebar item
                        $('[data-content="pending-requests"] .menu-text').append(
                            `<span class="pending-requests-badge badge bg-danger ms-2">${count}</span>`
                        );
                    }
                } else {
                    $badge.hide();
                }
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

    /**
     * Register for dashboard auto-refresh
     */
    function registerDashboardRefresh(refreshCallback) {
        startPolling('dashboard', refreshCallback, INTERVALS.dashboard);
    }

    /**
     * Register for environment detail auto-refresh
     */
    function registerEnvironmentRefresh(envId, refreshCallback) {
        startPolling('environmentDetail', refreshCallback, INTERVALS.environmentDetail);
    }

    /**
     * Unregister from updates (call when leaving a page)
     */
    function unregister(key) {
        stopPolling(key);
    }

    // Public API
    return {
        init,
        startPolling,
        stopPolling,
        stopAllPolling,
        registerDashboardRefresh,
        registerEnvironmentRefresh,
        unregister,
        updatePendingBadge,
        showConnectionStatus
    };
})();

