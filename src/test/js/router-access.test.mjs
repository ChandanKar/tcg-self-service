// Run with: node --test "src/test/js/**/*.test.mjs"
// U02-T01: every page the router can open has an access rule, so none is reachable by URL without a check.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function loadRouter() {
    const context = vm.createContext({
        window: { addEventListener() {}, location: { hash: '' } },
        Actions: { register() {} },
        sessionStorage: { getItem: () => null, removeItem() {} },
    });
    vm.runInContext(readFileSync(STATIC + 'js/core/router.js', 'utf8') + '\nthis.ContentRouter = ContentRouter;', context);
    return context.ContentRouter;
}

test('every routable page has an access rule', () => {
    const router = loadRouter();
    const missing = router._routeKeys().filter(key => !router._accessKeys().includes(key));
    assert.equal(missing.length, 0, 'pages without an access rule: ' + missing.join(', '));
});

test('manage pages are for environment admins and cost/administration pages for administrators', () => {
    const router = loadRouter();
    for (const page of ['vm-registry', 'access-management', 'pending-requests', 'budgets', 'automation-rules']) {
        assert.equal(router.access(page).role, 'ENV_ADMIN', page);
    }
    for (const page of ['cost-management', 'user-management', 'settings', 'system-health', 'api-access', 'teams']) {
        assert.equal(router.access(page).role, 'ADMIN', page);
    }
    for (const page of ['dashboard', 'my-environments', 'environment-detail', 'request-access', 'help']) {
        assert.equal(router.access(page).role, null, page);
    }
    assert.equal(router.access('templates').feature, 'templates');
});
