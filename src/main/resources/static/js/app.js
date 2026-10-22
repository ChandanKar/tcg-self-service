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
            HelpMenu.init();
            MyAccount.init();
            Sidebar.init();
            Slideout.init();
            if (typeof AssistantPanel !== 'undefined') {
                AssistantPanel.init();
            }
            Notifications.init();
            // Signed in from here on: the bell's count is polled by RealTime (no pre-auth calls).
            NotificationBell.init();
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
            showErrorState('The app didn\'t load. Reload the page to try again.');
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
                <button class="btn btn-primary" data-action="reload-page">
                    <i class="fas fa-sync"></i> Refresh Page
                </button>
            </div>
        `);
    }

    /**
     * Update sidebar menu based on user role
     */
    function updateSidebarForRole() {
        // Manage is for environment admins and admins; Cost and Administration for admins only.
        // Sections are always open (U04-T01).
        const envAdmin = Auth.isEnvAdmin();
        $('.env-admin-only').toggle(envAdmin);
        $('.admin-only').toggle(Auth.isAdmin());
        // New environment from a template (E32-T07): only while provisioning is on.
        if (typeof Templates !== 'undefined') {
            Templates.probeNav();
        }
        // Approvals: only while it concerns this user (policies on, or requests to see).
        if (typeof Approvals !== 'undefined' && Approvals.probeNav) {
            Approvals.probeNav();
        }
        // The Pending Requests badge is kept current by RealTime (started in RealTime.init).
    }

    /**
     * Refresh current view
     */
    function refresh() {
        // Reload the current route (tears the page down first; keeps route params).
        ContentRouter.reload();
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

// Page-level actions shared by several modules (see core/actions.js).
Actions.registerAll({
    'reload-page': () => location.reload(),
    'go-dashboard': () => ContentRouter.navigate('dashboard')
});
