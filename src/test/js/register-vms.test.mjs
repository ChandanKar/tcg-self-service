// Run with: node --test "src/test/js/**/*.test.mjs"
// U11-T04: the Add VMs page's texts, choices and the batch it sends.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/register-vms.js', 'utf8') + '\nthis.P = RegisterVmsPage;', context);
const M = context.P.model;
const plain = x => JSON.parse(JSON.stringify(x));

test('groups are offered last first, with where they start, then New group', () => {
    const groups = [{ groupId: 'w', displayName: 'Web', sequencePosition: 3 }, { groupId: 'd', displayName: 'Database', sequencePosition: 1 },
        { groupId: 'a', displayName: 'Application', sequencePosition: 2 }, { groupId: 'x', name: 'discovered', displayName: 'Auto-Discovered', sequencePosition: 999 }];
    assert.deepEqual(plain(M.groupOptions(groups).map(o => o.label)),
        ['Web · starts 3rd', 'Application · starts 2nd', 'Database · starts 1st', 'New group…']);
    assert.deepEqual(plain(M.groupOptions([]).map(o => o.value)), [M.NEW_GROUP]);
});

test('regions: the account its own, else every known one', () => {
    const accounts = [{ ref: 'a', regions: [] }, { ref: 'b', regions: ['eu-west-1', 'us-east-1'] }];
    const all = [{ value: 'ap-south-1', label: 'Asia Pacific (Mumbai)' }, { value: 'us-east-1', label: 'US East (N. Virginia)' }];
    assert.deepEqual(plain(M.regionsFor('b', accounts, all).map(r => r.value)), ['eu-west-1', 'us-east-1']);
    assert.deepEqual(plain(M.regionsFor('a', accounts, all)), [{ value: 'ap-south-1', label: 'ap-south-1 (Mumbai)' },
        { value: 'us-east-1', label: 'us-east-1 (N. Virginia)' }]);
    // U19-T08: an account without its own list offers the regions it has enabled, by name.
    const read = [{ ref: 'c', enabledRegions: ['me-central-1', 'eu-west-1'] }];
    assert.deepEqual(plain(M.regionsFor('c', read, [{ value: 'eu-west-1', label: 'Europe (Ireland)' }])),
        [{ value: 'me-central-1', label: 'me-central-1' }, { value: 'eu-west-1', label: 'eu-west-1 (Ireland)' }]);
});

test('rows: taken ones cannot be added; ended ones only when asked; search by name or IP', () => {
    const list = [{ name: 'web-03', privateIp: '10.20.3.43', status: 'AVAILABLE' },
        { name: 'pay-worker', privateIp: '10.20.4.11', status: 'DISCOVERED_UNGROUPED' },
        { name: 'etl', privateIp: '10.30.1.20', status: 'IN_ENVIRONMENT' },
        { name: 'old', privateIp: '10.0.0.9', status: 'STALE' }];
    assert.deepEqual(list.map(M.canAdd), [true, true, false, false]);
    assert.equal(M.visibleRows(list, '', false).length, 3);
    assert.equal(M.visibleRows(list, '', true).length, 4);
    assert.deepEqual(plain(M.visibleRows(list, '10.20', false).map(c => c.name)), ['web-03', 'pay-worker']);
    assert.deepEqual(plain(M.visibleRows(list, 'WEB', false).map(c => c.name)), ['web-03']);
});

test('texts', () => {
    assert.equal(M.stateText('shutting-down'), 'Shutting down');
    assert.equal(M.vmName({ name: 'web-03' }), 'web-03');
    assert.equal(M.vmName({ privateIp: '10.20.9.31' }), 'vm-10-20-9-31');
    assert.deepEqual([0, 1, 2].map(M.ctaText), ['Pick VMs to add', 'Add 1 VM', 'Add 2 VMs']);
    assert.equal(M.hintText(2), "2 selected · they start after the group's current VMs");
});

test('the batch goes to a group or a new one', () => {
    const picked = [{ ref: 'r1', name: 'web-03' }, { ref: 'r2', privateIp: '10.0.0.5' }];
    assert.deepEqual(plain(M.batchBody('g-web', '', picked)),
        { groupId: 'g-web', items: [{ ref: 'r1', name: 'web-03' }, { ref: 'r2', name: 'vm-10-0-0-5' }] });
    assert.deepEqual(plain(M.batchBody(M.NEW_GROUP, ' Reports ', picked.slice(0, 1))),
        { newGroupName: 'Reports', items: [{ ref: 'r1', name: 'web-03' }] });
});
