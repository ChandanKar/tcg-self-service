// Run with: node --test "src/test/js/**/*.test.mjs"
// U02-T04: every page's help "?" opens a topic that exists, keyed by the router's page name.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function load() {
    const context = vm.createContext({
        window: { addEventListener() {}, location: { hash: '' } },
        Actions: { register() {} },
        sessionStorage: { getItem: () => null, removeItem() {} },
    });
    vm.runInContext(readFileSync(STATIC + 'js/core/router.js', 'utf8') + '\nthis.ContentRouter = ContentRouter;', context);
    vm.runInContext(readFileSync(STATIC + 'js/features/help-content.js', 'utf8') + '\nthis.HelpContent = HelpContent;', context);
    return context;
}

test('every page topic exists', () => {
    const { HelpContent } = load();
    const ids = new Set(HelpContent.all().map(t => t.id));
    const missing = Object.entries(HelpContent.PAGE_TOPICS).filter(([, topic]) => !ids.has(topic)).map(([page]) => page);
    assert.equal(missing.length, 0, 'pages pointing at a missing topic: ' + missing.join(', '));
});

test('every routable page has a help topic', () => {
    const { HelpContent, ContentRouter } = load();
    const missing = ContentRouter._routeKeys().filter(page => !HelpContent.PAGE_TOPICS[page]);
    assert.equal(missing.length, 0, 'pages without a help topic: ' + missing.join(', '));
});

test('pages open the right topic', () => {
    const { HelpContent } = load();
    assert.equal(HelpContent.PAGE_TOPICS['my-environments'], 'environments');
    assert.equal(HelpContent.PAGE_TOPICS['environment-detail'], 'environments');
    assert.equal(HelpContent.PAGE_TOPICS['teams'], 'teams');
    assert.equal(HelpContent.PAGE_TOPICS['vm-registry'], 'vm-registry');
});
