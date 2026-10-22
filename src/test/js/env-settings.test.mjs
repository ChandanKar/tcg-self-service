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
        'Spot', 'Idle auto-stop', 'Budget', 'Who has access', 'Notifications', 'Deactivate']);
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

test('U19-T04: the AWS region: read from the metadata, sent as a metadata patch, checked as a region code', () => {
    const loaded = M.detailsOf({ displayName: 'Payments EKS', metadata: '{"region":"eu-west-1","owner":"x"}', production: false });
    assert.equal(loaded.region, 'eu-west-1');
    assert.equal(M.detailsOf({ displayName: 'x', metadata: 'not json' }).region, '');
    const same = plain(loaded);
    assert.equal(M.changes(loaded, same), null);
    assert.deepEqual(plain(M.changes(loaded, { ...same, region: ' me-central-1 ' })), { metadata: '{"region":"me-central-1"}' });
    assert.deepEqual(plain(M.changes(loaded, { ...same, region: '' })), { metadata: '{"region":null}' });
    assert.equal(M.regionProblem(''), null);
    assert.equal(M.regionProblem('ap-southeast-5'), null);
    assert.equal(M.regionProblem('Mumbai'), 'Mumbai is not an AWS region code. Use a code such as eu-west-1.');
    assert.equal(M.regionHelp({ region: 'ap-south-1' }, ''), 'Not set: its VMs are in ap-south-1, so that is used.');
    assert.equal(M.regionHelp({ regionMissing: true }, ''), "Not known: the cluster isn't synced until you set its region.");
    assert.equal(M.regionHelp({}, 'eu-west-1'), 'AWS calls for this environment go to this region.');
});

test('U11-T07: groups in start order, what each waits for, moving one', () => {
    const groups = [{ groupId: 'w', displayName: 'Web', sequencePosition: 3, dependsOnGroupIds: ['a'] },
        { groupId: 'd', displayName: 'Database', sequencePosition: 1 },
        { groupId: 'a', displayName: 'Application', sequencePosition: 2, dependsOnGroupIds: ['d'] },
        { groupId: 'x', name: 'discovered', sequencePosition: 999 }];
    assert.deepEqual(plain(M.startOrder(groups).map(g => g.groupId)), ['d', 'a', 'w']);
    assert.equal(M.waitsText(groups[1], groups), 'Starts first');
    assert.equal(M.waitsText(groups[0], groups), 'Waits for Application');
    assert.deepEqual(plain(M.moved(['d', 'a', 'w'], 'w', -1)), ['d', 'w', 'a']);
    assert.deepEqual(plain(M.moved(['d', 'a', 'w'], 'd', 1)), ['a', 'd', 'w']);
    assert.equal(M.moved(['d', 'a', 'w'], 'd', -1), null);
    assert.equal(M.moved(['d', 'a', 'w'], 'w', 1), null);
});

test('U11-T07: the schedule line', () => {
    assert.equal(M.scheduleText([]), 'Schedule: none');
    assert.equal(M.scheduleText([{ name: 'Night stop', enabled: false }]), 'Schedule: none');
    assert.equal(M.scheduleText([{ name: 'Office hours', enabled: true, scheduleSummary: 'Mon–Fri 08:00–20:00' },
        { name: 'Weekend', enabled: true }]), 'Schedule: Office hours, Mon–Fri 08:00–20:00 (and 1 more)');
    assert.deepEqual(plain(M.SECTIONS.find(x => x.key === 'spot')), { key: 'spot', label: 'Spot', feature: 'spot', eksOnly: true });
});
