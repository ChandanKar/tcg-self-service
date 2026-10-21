// Run with: node --test "src/test/js/**/*.test.mjs"
// U11-T05: a group cannot wait for a group that waits for it; the start order keeps waits first.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/registry-dialogs.js', 'utf8') + '\nthis.D = RegistryDialogs;', context);
const M = context.D.model;
const plain = x => JSON.parse(JSON.stringify(x));

const groups = [
    { groupId: 'db', displayName: 'Database', sequencePosition: 1, dependsOnGroupIds: [] },
    { groupId: 'app', displayName: 'Application', sequencePosition: 2, dependsOnGroupIds: ['db'] },
    { groupId: 'web', displayName: 'Web', sequencePosition: 3, dependsOnGroupIds: ['app'] },
    { groupId: 'disc', name: 'discovered', displayName: 'Auto-Discovered', sequencePosition: 999 }
];

test('loops, direct and through other groups', () => {
    const deps = M.dependsOnMap(groups);
    assert.equal(M.wouldMakeLoop('app', 'web', deps), true);   // Web waits for Application
    assert.equal(M.wouldMakeLoop('db', 'web', deps), true);    // Web -> Application -> Database
    assert.equal(M.wouldMakeLoop('web', 'db', deps), false);
    assert.equal(M.wouldMakeLoop('app', 'app', deps), true);
    assert.equal(M.wouldMakeLoop(null, 'web', deps), false);   // a new group
});

test('the start order: Auto-Discovered stays out, waits come first', () => {
    assert.deepEqual(plain(M.ordered(groups).map(g => g.groupId)), ['db', 'app', 'web']);
    assert.deepEqual(plain(M.planOrder(groups, 'web', 2, ['app'])),
        { ids: ['db', 'web', 'app'], problem: 'Web waits for Application, so it cannot start first' });
    assert.deepEqual(plain(M.planOrder(groups, 'web', 2, [])), { ids: ['db', 'web', 'app'], problem: null });
    assert.deepEqual(plain(M.planOrder(groups, '__new', 1, ['web'])),
        { ids: ['__new', 'db', 'app', 'web'], problem: 'This group waits for Web, so it cannot start first' });
    assert.deepEqual(plain(M.planOrder(groups, '__new', 4, ['web']).problem), null);
});

test('texts', () => {
    assert.deepEqual([1, 2, 3, 11, 22].map(M.ordinal), ['1st', '2nd', '3rd', '11th', '22nd']);
    assert.equal(M.people(1), '1 person');
    assert.equal(M.people(9), '9 people');
});
