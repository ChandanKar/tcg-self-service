// Run with: node --test "src/test/js/**/*.test.mjs"
// U11-T06: environment settings: the sections, what Save changes sends, and when it is dirty.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/env-settings.js', 'utf8') + '\nthis.E = EnvSettings;', context);
const M = context.E.model;
const plain = x => JSON.parse(JSON.stringify(x));

test('sections: the ones on this page; links go elsewhere', () => {
    assert.deepEqual(plain(M.SECTIONS.map(x => x.label)), ['Details', 'Groups and start order', 'Running and stopping',
        'Idle auto-stop', 'Budget', 'Who has access', 'Notifications', 'Deactivate']);
    assert.equal(M.sectionKey('deactivate'), 'deactivate');
    assert.equal(M.sectionKey('budget'), 'details');   // a link, not a section of this page
    assert.equal(M.sectionKey(null), 'details');
    assert.equal(M.teamLabel({ displayName: 'Payments', costCenter: 'CC-4105' }), 'Payments · CC-4105');
    assert.equal(M.teamLabel({ name: 'data' }), 'data');
});

test('Save changes sends only what changed; production is separate', () => {
    const loaded = M.detailsOf({ displayName: 'Payments Dev', description: 'Dev stack', teamId: 't1', awsAccountRef: 'a1', production: false });
    const same = plain(loaded);
    assert.equal(M.changes(loaded, same), null);
    assert.equal(M.isDirty(loaded, same), false);
    assert.deepEqual(plain(M.changes(loaded, { ...same, description: ' Development stack for payments ' })),
        { description: 'Development stack for payments' });
    assert.deepEqual(plain(M.changes(loaded, { ...same, teamId: '' })), { teamId: '' });
    assert.deepEqual(plain(M.changes(loaded, { ...same, displayName: 'Payments', awsAccountRef: 'a2' })),
        { displayName: 'Payments', awsAccountRef: 'a2' });
    assert.equal(M.changes(loaded, { ...same, production: true }), null);
    assert.equal(M.isDirty(loaded, { ...same, production: true }), true);
});
