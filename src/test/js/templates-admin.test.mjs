// Run with: node --test "src/test/js/**/*.test.mjs"
// U14-T08: template administration: tabs per role, the request lines and the tab label.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function load(role) {
    const context = vm.createContext({ Auth: { isAdmin: () => role === 'ADMIN', isEnvAdmin: () => role !== 'USER' } });
    context.window = context;
    vm.runInContext(readFileSync(STATIC + 'js/features/templates-admin.js', 'utf8') + '\nthis.T = TemplatesAdmin;', context);
    return context.T;
}

const MINE = [['catalogue', 'Templates'], ['mine', 'My requests']];
const ids = list => JSON.parse(JSON.stringify(list.map(([id]) => id)));

test('tabs: admins manage first; environment admins get Requests only; users neither', () => {
    assert.deepEqual(ids(load('ADMIN').tabs(MINE)), ['manage', 'approvals', 'catalogue', 'mine']);
    assert.deepEqual(JSON.parse(JSON.stringify(load('ADMIN').tabs(MINE).map(([, label]) => label))), ['Templates', 'Requests', 'Ask for one', 'My requests']);
    assert.deepEqual(ids(load('ENV_ADMIN').tabs(MINE)), ['catalogue', 'mine', 'approvals']);
    assert.deepEqual(ids(load('USER').tabs(MINE)), ['catalogue', 'mine']);
});

test('request lines and the tab label', () => {
    const M = load('ADMIN').model;
    assert.equal(M.forText(120), 'for 5 days');
    assert.equal(M.forText(72), 'for 3 days');
    assert.equal(M.forText(36), 'for 36 hours');
    assert.equal(M.aboutCost(240, 120), 'about $39');
    assert.equal(M.aboutCost(null, 120), null);
    assert.equal(M.requestsLabel(2), 'Requests · 2 waiting');
    assert.equal(M.requestsLabel(0), 'Requests');
});
