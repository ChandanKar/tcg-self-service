/**
 * VM Self-Service Platform - Content Router
 * Handles navigation and content loading with hash-based routing.
 *
 * URL scheme:  /#/dashboard,  /#/vm-registry,  /#/my-environments, etc.
 * Parameterised:  /#/environments/<environmentId>  (Environment Detail).
 * A ?query part is kept as-is (read it with ContentRouter.query()), so per-page state such as
 * Cost Management's table pages survives refresh and bookmarks.
 * Refreshing the page restores the last-visited section.
 *
 * Page lifecycle contract (every routed page follows it):
 *   - At the start of a loader capture the navigation token:  const t = ContentRouter.token();
 *   - After every await / AJAX callback bail out if the user has left:
 *         if (!ContentRouter.isCurrent(t)) return;
 *   - Register teardown (timers, document/window listeners, namespaced delegated handlers on
 *     #content-area) with ContentRouter.onLeave(fn). Leave handlers run once, before the next
 *     page's loader, including when the same route reloads.
 *   - Page polls use RealTime.startPolling(key, fn, ms, { pageScoped: true }), which stops on leave.
 */

const ContentRouter = (function() {
    'use strict';

    // content-type → hash  (single source of truth for all routes)
    const ROUTES = {
        'dashboard':          '#/dashboard',
        'my-environments':    '#/my-environments',
        'request-access':     '#/request-access',
        'pending-requests':   '#/pending-requests',
        'activity-logs':      '#/activity-logs',
        'user-management':    '#/user-management',
        'access-management':  '#/access-management',
        'vm-registry':        '#/vm-registry',
        'audit-logs-all':     '#/audit-logs-all',
        'automation-rules':   '#/automation-rules',
        'cost-management':    '#/cost-management',
        'cost-setup':         '#/cost-setup',
        'system-health':      '#/system-health',
        'settings':           '#/settings',
        'help':               '#/help'
    };

    // hash → content-type  (derived from ROUTES — not hand-maintained)
    const HASH_TO_CONTENT = Object.fromEntries(
        Object.entries(ROUTES).map(([k, v]) => [v, k])
    );

    // Routes carrying an id in the path. Ids are opaque; never put display names in the URL.
    const PARAM_ROUTES = [
        {
            contentType: 'environment-detail',
            pattern: /^#\/environments\/([^/?]+)$/,
            build: p => (p && p.environmentId) ? '#/environments/' + encodeURIComponent(p.environmentId) : null,
            params: m => ({ environmentId: decodeURIComponent(m[1]) })
        }
    ];

    // Old Environment Detail hash: only usable with an in-memory environment name (hidden
    // Favorites/Recents submenu); without one it redirects to the list.
    const LEGACY_ENV_DETAIL_HASH = '#/environment-detail';

    const INTENDED_ROUTE_KEY = 'vmcontrol.intendedRoute';

    // Params that cannot be encoded in a plain hash (e.g. environment-detail env name)
    let _pendingParams = {};

    // Incremented on every page load; a loader's captured token goes stale when the user leaves.
    let _navToken = 0;
    // Teardown callbacks registered by the current page (see onLeave).
    let _leaveHandlers = [];

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
            'cost-management':    () => window.CostManagement?.load?.(),
            'cost-setup':         () => window.CostSetup?.load?.(),
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
     * @param {object} params - route params (e.g. { environmentId } for environment-detail);
     *        values that are not part of the URL are handed to the loader once
     */
    function navigate(contentType, params) {
        if (params && Object.keys(params).length > 0) {
            _pendingParams = params;
        }
        const hash = hashFor(contentType, params);
        if (window.location.hash === hash) {
            // Same hash — hashchange won't fire, so load directly
            _resolveCurrentHash();
        } else {
            window.location.hash = hash;
        }
    }

    /**
     * Return the hash for a content type and its params (also used by the sidebar for hrefs).
     */
    function hashFor(contentType, params) {
        const paramRoute = PARAM_ROUTES.find(r => r.contentType === contentType);
        if (paramRoute) {
            return paramRoute.build(params) || LEGACY_ENV_DETAIL_HASH;
        }
        return ROUTES[contentType] || '#/dashboard';
    }

    /**
     * Normalize common hash variants to the canonical #/route form, keeping any ?query.
     * Examples: #vm-registry, #/vm-registry/, #/cost-management?idle=2.
     */
    function normalizeHash(rawHash) {
        if (!rawHash || rawHash === '#') {
            return '#/dashboard';
        }

        const queryIndex = rawHash.indexOf('?');
        const pathPart = queryIndex >= 0 ? rawHash.slice(0, queryIndex) : rawHash;
        const queryPart = queryIndex >= 0 ? rawHash.slice(queryIndex) : '';

        const hashPath = pathPart
            .replace(/^#\/?/, '')
            .replace(/\/+$/, '');

        if (!hashPath) {
            return '#/dashboard';
        }

        return `#/${hashPath}${queryPart}`;
    }

    /**
     * Resolve a hash path (without ?query) to { contentType, params }; the old Environment
     * Detail hash resolves with legacy: true. Unknown paths fall back to the dashboard.
     */
    function matchRoute(hashPath) {
        if (HASH_TO_CONTENT[hashPath]) {
            return { contentType: HASH_TO_CONTENT[hashPath], params: {} };
        }
        for (const route of PARAM_ROUTES) {
            const m = route.pattern.exec(hashPath);
            if (m) {
                return { contentType: route.contentType, params: route.params(m) };
            }
        }
        if (hashPath === LEGACY_ENV_DETAIL_HASH) {
            return { contentType: 'environment-detail', params: {}, legacy: true };
        }
        return { contentType: 'dashboard', params: {} };
    }

    /** The current hash's ?query as an object (e.g. { idle: '2' }). */
    function query() {
        const hash = window.location.hash || '';
        const queryIndex = hash.indexOf('?');
        return Utils.parseQueryString(queryIndex >= 0 ? hash.slice(queryIndex + 1) : '');
    }

    /**
     * Load content based on type.
     * @param {string} contentType
     * @param {object} params
     */
    function loadContent(contentType, params = {}) {
        runLeaveHandlers();
        _navToken++;
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
        const route = matchRoute(hash.split('?')[0]);
        const pending = _pendingParams;
        _pendingParams = {};

        if (route.legacy) {
            if (pending.environmentId) {
                window.location.replace(hashFor('environment-detail', pending));
                return;
            }
            if (!pending.environmentName) {
                window.location.replace(ROUTES['my-environments']);
                return;
            }
        }

        const contentType = route.contentType;
        // Route params (from the URL) win over in-memory ones.
        const params = Object.assign({}, pending, route.params);

        // A slide-out (VM details, My Account) belongs to the page it was opened from.
        if (typeof Slideout !== 'undefined' && Slideout.isOpen && Slideout.isOpen()) {
            Slideout.close();
        }

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
                    <p class="text-muted">${Utils.escapeHtml(message)}</p>
                    <button class="btn btn-primary" data-action="router-reload">Try Again</button>
                </div>
            </div>
        `);
    }

    /** Run and clear the current page's teardown callbacks; a failing one never blocks navigation. */
    function runLeaveHandlers() {
        const handlers = _leaveHandlers;
        _leaveHandlers = [];
        handlers.forEach(fn => {
            try {
                fn();
            } catch (error) {
                console.error('Route leave handler failed:', error);
            }
        });
    }

    /** Current navigation token; capture it at the start of a page loader. */
    function token() {
        return _navToken;
    }

    /** False once the user has navigated (or reloaded) since the token was captured. */
    function isCurrent(t) {
        return t === _navToken;
    }

    /** Register a teardown callback for the current page; it runs once when the page is left. */
    function onLeave(fn) {
        if (typeof fn === 'function') {
            _leaveHandlers.push(fn);
        }
    }

    /** Reload the current route (tears the page down and loads it again). */
    function reload() {
        _resolveCurrentHash();
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
        showPlaceholder,
        token,
        isCurrent,
        onLeave,
        reload,
        query
    };
})();

Actions.register('router-reload', () => ContentRouter.reload());
