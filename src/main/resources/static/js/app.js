/**
 * VM Self-Service Platform - Main Application
 * Initializes all modules and sets up the application
 */

const App = (function() {
    'use strict';

    let isInitialized = false;

    /**
     * Initialize the application
     */
    async function init() {
        console.log('VM Self-Service Platform initializing...');

        // Show loading state
        showLoadingState();

        try {
            // Initialize authentication first
            const user = await Auth.init();

            if (!user) {
                // Auth.init handles redirect to login
                return;
            }

            // Initialize UI components
            UserMenu.init();
            Sidebar.init();
            Slideout.init();
            Notifications.init();
            if (typeof VmCharts !== 'undefined') {
                VmCharts.init();
            }
            RealTime.init();
            Keyboard.init();

            // Update sidebar menu based on user role
            updateSidebarForRole();

            // Initialise hash routing — restores last-visited section on refresh
            ContentRouter.init();

            isInitialized = true;
            console.log('VM Self-Service Platform ready.');

        } catch (error) {
            console.error('Failed to initialize application:', error);
            showErrorState('Failed to load application. Please refresh the page.');
        }
    }

    /**
     * Show loading state while initializing
     */
    function showLoadingState() {
        $('#content-area').html(`
            <div class="loading-state">
                <div class="spinner-border text-primary" role="status">
                    <span class="visually-hidden">Loading...</span>
                </div>
                <p class="mt-3">Loading application...</p>
            </div>
        `);
    }

    /**
     * Show error state
     */
    function showErrorState(message) {
        $('#content-area').html(`
            <div class="error-state">
                <i class="fas fa-exclamation-triangle fa-3x text-danger"></i>
                <h4 class="mt-3">Error</h4>
                <p>${message}</p>
                <button class="btn btn-primary" onclick="location.reload()">
                    <i class="fas fa-sync"></i> Refresh Page
                </button>
            </div>
        `);
    }

    /**
     * Update sidebar menu based on user role
     */
    function updateSidebarForRole() {
        // Show admin section for admins and env admins
        if (Auth.isEnvAdmin()) {
            $('#admin-section').show();
            $('.env-admin-only').show();
            $('#pending-requests-link').show();
        } else {
            $('#admin-section').hide();
            $('.env-admin-only').hide();
            $('#pending-requests-link').hide();
        }

        // Show user management only for full admins
        if (Auth.isAdmin()) {
            $('#user-management-link').show();
            $('.admin-only').show();
        } else {
            $('#user-management-link').hide();
        }

        // Load pending requests count for env admins
        if (Auth.isEnvAdmin()) {
            loadPendingRequestsCount();
        }
    }

    /**
     * Load pending access requests count for badge
     */
    function loadPendingRequestsCount() {
        ApiClient.get(Config.API.access.pendingRequests)
            .done(function(requests) {
                const count = requests ? requests.length : 0;
                if (count > 0) {
                    $('.pending-count').text(count).show();
                } else {
                    $('.pending-count').hide();
                }
            })
            .fail(function() {
                // Silently fail - not critical
                $('.pending-count').hide();
            });
    }

    /**
     * Refresh current view
     */
    function refresh() {
        // Get current active menu item and reload
        const activeContent = $('.sidebar-menu-link.active').data('content');
        if (activeContent) {
            ContentRouter.loadContent(activeContent);
        } else {
            Dashboard.load();
        }
    }

    /**
     * Check if app is initialized
     */
    function ready() {
        return isInitialized;
    }

    return {
        init,
        refresh,
        ready,
        showLoadingState,
        showErrorState
    };
})();

// Initialize when document is ready
$(document).ready(function() {
    App.init();
});

