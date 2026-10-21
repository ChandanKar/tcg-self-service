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
        'user-management-add': '#/user-management/add',
        'access-management':  '#/access-management',
        'access-grant':       '#/access-management/grant',
        'vm-registry':        '#/vm-registry',
        'audit-logs-all':     '#/audit-logs-all',
        'automation-rules':   '#/automation-rules',
        'automation-rule-new': '#/automation-rules/new',
        'cost-management':    '#/cost-management',
        'savings':            '#/savings',
        'budgets':            '#/budgets',
        'waste':              '#/waste',
        'teams':              '#/teams',
        'aws-accounts':       '#/aws-accounts',
        'api-access':         '#/api-access',
        'chargeback':         '#/chargeback',
        'approvals':          '#/approvals',
        'approval-policies':  '#/approval-policies',
        'cost-setup':         '#/cost-setup',
        'system-health':      '#/system-health',
        'settings':           '#/settings',
        'templates':          '#/templates',
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
        },
        {
            // An environment's settings, optionally at one section (U11-T06).
            contentType: 'environment-settings',
            pattern: /^#\/environments\/([^/?]+)\/settings(?:\/([a-z-]+))?$/,
            build: p => (p && p.environmentId) ? '#/environments/' + encodeURIComponent(p.environmentId) + '/settings'
                + (p.section ? '/' + p.section : '') : null,
            params: m => ({ environmentId: decodeURIComponent(m[1]), section: m[2] || null })
        },
        {
            // Add VMs to an environment (U11-T04).
            contentType: 'register-vms',
            pattern: /^#\/vm-registry\/([^/?]+)\/add$/,
            build: p => (p && p.environmentId) ? '#/vm-registry/' + encodeURIComponent(p.environmentId) + '/add' : null,
            params: m => ({ environmentId: decodeURIComponent(m[1]) })
        },
        {
            // A schedule's editor (U12-T03); #/automation-rules/new is in ROUTES and matched first.
            contentType: 'automation-rule-edit',
            pattern: /^#\/automation-rules\/([^/?]+)$/,
            build: p => (p && p.ruleId) ? '#/automation-rules/' + encodeURIComponent(p.ruleId) : null,
            params: m => ({ ruleId: decodeURIComponent(m[1]) })
        },
        {
            // A help topic (E29-T07).
            contentType: 'help',
            pattern: /^#\/help\/([a-z0-9-]+)$/,
            build: p => (p && p.topic) ? '#/help/' + p.topic : null,
            params: m => ({ topic: m[1] })
        },
        {
            // One-click Teams action confirm page (E24-T04); the token is a signed, single-use link.
            contentType: 'chat-action',
            pattern: /^#\/chat-action\/([A-Za-z0-9_\-.]+)$/,
            build: p => (p && p.token) ? '#/chat-action/' + p.token : null,
            params: m => ({ token: m[1] })
        }
    ];

    /**
     * Who may open each page (U02-T01, side menu of the approved mockup): role null = everyone,
     * ENV_ADMIN = environment admins and administrators, ADMIN = administrators. feature = the
     * Capabilities switch the page needs. label = the page's name in "no access" messages.
     * The server still checks every API call; this keeps people out of pages that cannot work for them.
     */
    const ROUTE_ACCESS = {
        // Workspace
        'dashboard':          { role: null, label: 'Home' },
        'my-environments':    { role: null, label: 'Environments' },
        'environment-detail': { role: null, label: 'Environment' },
        'request-access':     { role: null, label: 'Request access' },
        'approvals':          { role: null, label: 'Approvals' },
        'activity-logs':      { role: null, label: 'My activity' },
        'templates':          { role: null, feature: 'templates', label: 'New environments' },
        'help':               { role: null, label: 'Help' },
        'chat-action':        { role: null, label: 'Teams action' },
        // Manage
        'pending-requests':   { role: 'ENV_ADMIN', label: 'Access requests' },
        'access-management':  { role: 'ENV_ADMIN', label: 'Access management' },
        'access-grant':       { role: 'ENV_ADMIN', label: 'Grant access' },
        'register-vms':       { role: 'ENV_ADMIN', label: 'Add VMs' },
        'environment-settings': { role: 'ENV_ADMIN', label: 'Environment settings' },
        'vm-registry':        { role: 'ENV_ADMIN', label: 'VM registry' },
        'automation-rules':   { role: 'ENV_ADMIN', label: 'Automation rules' },
        'automation-rule-new': { role: 'ENV_ADMIN', label: 'New schedule' },
        'automation-rule-edit': { role: 'ENV_ADMIN', label: 'Schedule' },
        'approval-policies':  { role: 'ENV_ADMIN', label: 'Approval policies' },
        'budgets':            { role: 'ENV_ADMIN', label: 'Budgets' },
        'audit-logs-all':     { role: 'ENV_ADMIN', label: 'Audit log' },
        // Cost
        'cost-management':    { role: 'ADMIN', label: 'Cost management' },
        'savings':            { role: 'ADMIN', label: 'Savings' },
        'waste':              { role: 'ADMIN', label: 'Waste' },
        'chargeback':         { role: 'ADMIN', label: 'Chargeback' },
        'cost-setup':         { role: 'ADMIN', label: 'Cost setup' },
        // Administration
        'user-management':    { role: 'ADMIN', label: 'Users' },
        'user-management-add': { role: 'ADMIN', label: 'Add people' },
        'teams':              { role: 'ADMIN', label: 'Teams' },
        'aws-accounts':       { role: 'ADMIN', label: 'AWS accounts' },
        'api-access':         { role: 'ADMIN', label: 'API access' },
        'settings':           { role: 'ADMIN', label: 'Settings' },
        'system-health':      { role: 'ADMIN', label: 'System health' }
    };

    /** Why the current person cannot open a page, or null when they can. */
    function accessProblem(contentType) {
        const rule = ROUTE_ACCESS[contentType];
        if (!rule) {
            return { kind: 'not-found', label: null };
        }
        const caps = typeof Capabilities !== 'undefined' ? Capabilities : null;
        const admin = caps ? caps.isAdmin() : (typeof Auth !== 'undefined' && Auth.isAdmin());
        const envAdmin = caps ? caps.isEnvAdmin() : (typeof Auth !== 'undefined' && Auth.isEnvAdmin());
        if (rule.role === 'ADMIN' && !admin) {
            return { kind: 'role-admin', label: rule.label };
        }
        if (rule.role === 'ENV_ADMIN' && !envAdmin) {
            return { kind: 'role-env-admin', label: rule.label };
        }
        if (rule.feature && caps && !caps.has(rule.feature)) {
            return { kind: 'feature-off', label: rule.label };
        }
        return null;
    }

    /** The access rule of a page (the side menu is built from the same table). */
    function access(contentType) {
        return ROUTE_ACCESS[contentType] || null;
    }

    /** Show a "no access" variant from inside a loader, e.g. an environment that answered 403/404. */
    function showNoAccess(kind, opts = {}) {
        if (typeof NoAccessPage !== 'undefined') {
            NoAccessPage.render(kind, opts);
        }
    }

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
            'request-access':     () => RequestAccess.load(),
            'pending-requests':   () => AccessRequests.loadPendingRequestsPage(),
            'activity-logs':      () => ActivityLogs.loadMyActivityLogs(),
            // load() returns nothing, so `load() || fallback()` loaded these pages twice (two
            // fetches, two renders); use the fallback only when the module is missing.
            'user-management':    () => window.UserManagement ? window.UserManagement.load() : window.Features?.loadUserManagement?.(),
            'user-management-add': () => window.AddPeople?.load?.(),
            'access-management':  () => window.AccessManagement ? window.AccessManagement.load() : window.Features?.loadAccessManagement?.(),
            'access-grant':       () => window.GrantAccessPage?.load?.(query()),
            'vm-registry':        () => window.VmRegistry?.load?.(),
            'register-vms':       (params) => window.RegisterVmsPage?.load?.(params, query()),
            'environment-settings': (params) => window.EnvSettings?.load?.(params),
            'automation-rules':   () => window.Features?.loadAutomationRules?.(),
            'automation-rule-new': () => window.RuleEditor?.load?.({}),
            'automation-rule-edit': (params) => window.RuleEditor?.load?.(params),
            'audit-logs-all':     () => AllLogs.loadAllAuditLogs(),
            'cost-management':    () => window.CostManagement?.load?.(),
            'savings':            () => window.Savings?.load?.(),
            'budgets':            () => window.Budgets?.load?.(),
            'waste':              () => window.Waste?.load?.(),
            'teams':              () => window.Teams?.load?.(),
            'aws-accounts':       () => window.AwsAccounts?.load?.(),
            'api-access':         () => window.ApiAccess?.load?.(),
            'chargeback':         () => window.Chargeback?.load?.(),
            'approvals':          () => window.Approvals?.load?.(),
            'approval-policies':  () => window.ApprovalPolicies?.load?.(),
            'chat-action':        (params) => window.ChatActionPage?.load?.(params),
            'cost-setup':         () => window.CostSetup?.load?.(),
            'system-health':      () => SystemHealth.load(),
            'settings':           () => window.Settings?.load?.(),
            'templates':          () => window.Templates?.load?.(),
            'help':               (params) => window.Help?.load?.(params)
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
     * Detail hash resolves with legacy: true. Unknown paths resolve to 'not-found' (U02-T01), which
     * shows "This page doesn't exist" instead of quietly opening the dashboard.
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
        return { contentType: 'not-found', params: {} };
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
    // The page on screen (its route key), for the top bar's "Help for this page" (U04-T04).
    let _currentContentType = null;

    /** The route key of the page on screen, e.g. 'my-environments' or 'environment-detail'. */
    function current() {
        return _currentContentType;
    }

    function loadContent(contentType, params = {}) {
        runLeaveHandlers();
        _navToken++;
        // The browser tab names the page; pages built with PageHeader set their own title (U04-T03).
        document.title = 'Self-Service';
        // Pages this person cannot use say so, and their loader (and its requests) never runs (U02-T01).
        const problem = accessProblem(contentType);
        if (problem) {
            showNoAccess(problem.kind, { label: problem.label });
            return;
        }
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
        _currentContentType = contentType;
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
        query,
        access,
        showNoAccess,
        current,
        // For the front-end tests: every routable page must have an access rule.
        _routeKeys: () => Object.keys(ROUTES).concat(PARAM_ROUTES.map(r => r.contentType)),
        _accessKeys: () => Object.keys(ROUTE_ACCESS)
    };
})();

Actions.register('router-reload', () => ContentRouter.reload());
