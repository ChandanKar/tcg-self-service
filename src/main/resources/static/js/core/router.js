/**
 * VM Self-Service Platform - Content Router
 * Handles navigation and content loading with hash-based routing.
 *
 * URL scheme:  /#/dashboard,  /#/vm-registry,  /#/my-environments, etc.
 * Refreshing the page restores the last-visited section.
 */

const ContentRouter = (function() {
    'use strict';

    // content-type → hash  (single source of truth for all routes)
    const ROUTES = {
        'dashboard':          '#/dashboard',
        'my-environments':    '#/my-environments',
        'environment-detail': '#/environment-detail',
        'request-access':     '#/request-access',
        'pending-requests':   '#/pending-requests',
        'activity-logs':      '#/activity-logs',
        'user-management':    '#/user-management',
        'access-management':  '#/access-management',
        'vm-registry':        '#/vm-registry',
        'audit-logs-all':     '#/audit-logs-all',
        'automation-rules':   '#/automation-rules',
        'cost-management':    '#/cost-management',
        'system-health':      '#/system-health',
        'settings':           '#/settings',
        'help':               '#/help'
    };

    // hash → content-type  (derived from ROUTES — not hand-maintained)
    const HASH_TO_CONTENT = Object.fromEntries(
        Object.entries(ROUTES).map(([k, v]) => [v, k])
    );

    const INTENDED_ROUTE_KEY = 'vmcontrol.intendedRoute';

    // Params that cannot be encoded in a plain hash (e.g. environment-detail env name)
    let _pendingParams = {};

    function getLoader(contentType) {
        const loaders = {
            'dashboard':          () => Dashboard.load(),
            'my-environments':    () => Environments.loadList(),
            'environment-detail': (params) => Environments.loadDetail(params),
            'request-access':     () => AccessRequests.loadRequestAccessPage(),
            'pending-requests':   () => AccessRequests.loadPendingRequestsPage(),
            'activity-logs':      () => ActivityLogs.loadMyActivityLogs(),
            'user-management':    () => window.UserManagement?.load?.() || window.Features?.loadUserManagement?.(),
            'access-management':  () => window.AccessManagement?.load?.() || window.Features?.loadAccessManagement?.(),
            'vm-registry':        () => window.VmRegistry?.load?.(),
            'automation-rules':   () => window.Features?.loadAutomationRules?.(),
            'audit-logs-all':     () => AllLogs.loadAllAuditLogs(),
            'cost-management':    () => showPlaceholder('cost-management'),
            'system-health':      () => SystemHealth.load(),
            'settings':           () => showPlaceholder('settings'),
            'help':               () => showPlaceholder('help')
        };
        return loaders[contentType];
    }

    /**
     * Navigate to a content section by updating the hash.
     * The hashchange listener picks this up and calls loadContent.
     * @param {string} contentType
     * @param {object} params - extra params (e.g. { environmentName } for environment-detail)
     */
    function navigate(contentType, params) {
        if (params && Object.keys(params).length > 0) {
            _pendingParams = params;
        }
        const hash = ROUTES[contentType] || '#/dashboard';
        if (window.location.hash === hash) {
            // Same hash — hashchange won't fire, so load directly
            _resolveCurrentHash();
        } else {
            window.location.hash = hash;
        }
    }

    /**
     * Return the hash string for a given content type (used by sidebar to set href).
     */
    function hashFor(contentType) {
        return ROUTES[contentType] || '#/dashboard';
    }

    /**
     * Normalize common hash variants to the canonical #/route format.
     * Examples: #vm-registry, #/vm-registry/, #/vm-registry?tab=x.
     */
    function normalizeHash(rawHash) {
        if (!rawHash || rawHash === '#') {
            return '#/dashboard';
        }

        const hashPath = rawHash
            .split('?')[0]
            .replace(/^#\/?/, '')
            .replace(/\/+$/, '');

        if (!hashPath) {
            return '#/dashboard';
        }

        return `#/${hashPath}`;
    }

    /**
     * Load content based on type.
     * @param {string} contentType
     * @param {object} params
     */
    function loadContent(contentType, params = {}) {
        const loader = getLoader(contentType);
        if (loader) {
            showLoading();
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
     * Read the current hash and load the matching section.
     * Also updates the sidebar active item.
     */
    function _resolveCurrentHash() {
        const hash = normalizeHash(window.location.hash);
        if (window.location.hash !== hash) {
            window.location.replace(hash);
            return;
        }
        const contentType = HASH_TO_CONTENT[hash] || 'dashboard';
        const params = _pendingParams;
        _pendingParams = {};

        // Sync sidebar active state (covers browser back/forward too)
        if (typeof Sidebar !== 'undefined' && Sidebar.setActiveItem) {
            Sidebar.setActiveItem(contentType);
        }

        loadContent(contentType, params);
    }

    /**
     * Initialise hash routing.  Call once after Auth is ready.
     */
    function init() {
        window.addEventListener('hashchange', _resolveCurrentHash);
        const intendedRoute = sessionStorage.getItem(INTENDED_ROUTE_KEY);
        if (intendedRoute) {
            sessionStorage.removeItem(INTENDED_ROUTE_KEY);
            const hash = normalizeHash(intendedRoute);
            if (window.location.hash === hash) {
                _resolveCurrentHash();
            } else {
                window.location.hash = hash;
            }
            return;
        }

        // If there is no hash yet, default to dashboard
        if (!window.location.hash || window.location.hash === '#') {
            window.location.hash = '#/dashboard';
        } else {
            _resolveCurrentHash();
        }
    }

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
        init,
        navigate,
        hashFor,
        loadContent,
        showLoading,
        showError,
        showPlaceholder
    };
})();
