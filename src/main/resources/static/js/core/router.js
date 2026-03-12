/**
 * VM Self-Service Platform - Content Router
 * Handles navigation and content loading
 */

const ContentRouter = (function() {
    'use strict';

    /**
     * Get content loader for a content type
     * Using a function to ensure lazy evaluation (modules loaded at runtime)
     */
    function getLoader(contentType) {
        const loaders = {
            'dashboard': () => Dashboard.load(),
            'my-environments': () => Environments.loadList(),
            'environment-detail': (params) => Environments.loadDetail(params),
            'request-access': () => AccessRequests.loadRequestAccessPage(),
            'pending-requests': () => AccessRequests.loadPendingRequestsPage(),
            'activity-logs': () => ActivityLogs.loadMyActivityLogs(),
            'user-management': () => window.Features?.loadUserManagement?.(),
            'access-management': () => window.Features?.loadAccessManagement?.(),
            'vm-registry': () => window.Features?.loadVmRegistry?.(),
            'automation-rules': () => window.Features?.loadAutomationRules?.(),
            'audit-logs-all': () => AllLogs.loadAllAuditLogs(),
            'cost-management': () => showPlaceholder('cost-management'),
            'system-health': () => window.Features?.loadSystemHealth?.(),
            'settings': () => showPlaceholder('settings'),
            'help': () => showPlaceholder('help')
        };
        return loaders[contentType];
    }

    /**
     * Load content based on type
     * @param {string} contentType - Content type identifier
     * @param {object} params - Optional parameters
     */
    function loadContent(contentType, params = {}) {
        const loader = getLoader(contentType);

        if (loader) {
            // Show loading state
            showLoading();

            // Load content
            try {
                loader(params);
            } catch (error) {
                console.error('Error loading content:', error);
                showError('Failed to load content. Please try again.');
            }
        } else {
            showPlaceholder(contentType);
        }
    }

    /**
     * Show loading indicator
     */
    function showLoading() {
        $('#content-area').html(`
            <div class="d-flex justify-content-center align-items-center" style="min-height: 400px;">
                <div class="text-center">
                    <div class="spinner-border text-primary mb-3" role="status">
                        <span class="visually-hidden">Loading...</span>
                    </div>
                    <p class="text-muted">Loading...</p>
                </div>
            </div>
        `);
    }

    /**
     * Show error message
     */
    function showError(message) {
        $('#content-area').html(`
            <div class="d-flex justify-content-center align-items-center" style="min-height: 400px;">
                <div class="text-center">
                    <i class="fas fa-exclamation-triangle text-danger fa-3x mb-3"></i>
                    <h5>Error</h5>
                    <p class="text-muted">${message}</p>
                    <button class="btn btn-primary" onclick="location.reload()">Refresh Page</button>
                </div>
            </div>
        `);
    }

    /**
     * Show placeholder for features not yet implemented
     */
    function showPlaceholder(contentType) {
        const title = contentType.replace(/-/g, ' ').replace(/\b\w/g, l => l.toUpperCase());

        $('#content-area').html(`
            <div class="content-header">
                <h1>${title}</h1>
                <p>This feature is coming soon</p>
            </div>
            <div class="metric-card text-center py-5">
                <i class="fas fa-hard-hat text-warning fa-4x mb-3"></i>
                <h4>Under Construction</h4>
                <p class="text-muted">This feature is currently being developed.</p>
            </div>
        `);
    }


    return {
        loadContent,
        showLoading,
        showError,
        showPlaceholder
    };
})();
