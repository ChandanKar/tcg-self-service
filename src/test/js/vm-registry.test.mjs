// Run with: node --test "src/test/js/**/*.test.mjs"
// U11-T03: the VM registry's texts: environment rows, start order, VM notes and the banner.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/vm-registry.js', 'utf8') + '\nthis.R = VmRegistry;', context);
const M = context.R.model;
const plain = x => JSON.parse(JSON.stringify(x));

test('environment rows', () => {
    assert.equal(M.envMeta({ name: 'payments-dev', groupCount: 3, vmCount: 7 }), 'payments-dev · 3 groups · 7 VMs');
    assert.equal(M.envMeta({ name: 'search-qa', groupCount: 1, vmCount: 1 }), 'search-qa · 1 group · 1 VM');
    assert.equal(M.envMeta({ name: 'hr-portal-stg', serviceType: 'EKS', vmCount: 6 }), 'hr-portal-stg · EKS · 6 nodes');
});

test('start order', () => {
    assert.deepEqual([1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 101].map(M.ordinal),
        ['1st', '2nd', '3rd', '4th', '11th', '12th', '13th', '21st', '22nd', '23rd', '101st']);
    const groups = [
        { groupId: 'w', displayName: 'Web', sequencePosition: 3, dependsOnGroupIds: ['a'] },
        { groupId: 'd', displayName: 'Database', sequencePosition: 1 },
        { groupId: 'a', displayName: 'Application', sequencePosition: 2, dependsOnGroupIds: ['d'] }
    ];
    assert.equal(M.startOrderText(groups[1], groups), '1st');
    assert.equal(M.startOrderText(groups[2], groups), '2nd · after Database');
    assert.equal(M.startOrderText(groups[0], groups), '3rd · after Application');
});

test('notes on VM rows', () => {
    assert.deepEqual(plain(M.vmNote({ stateDriftDetected: true })), { text: 'State changed outside the platform', warn: true });
    assert.deepEqual(plain(M.vmNote({ discoveryPending: true })), { text: 'Found by discovery', warn: true });
    assert.deepEqual(plain(M.vmNote({ isActive: false, deletedAt: '2026-10-01' })), { text: 'Removed from the platform', warn: false });
    assert.deepEqual(plain(M.vmNote({ purpose: 'Primary database' })), { text: 'Primary database', warn: false });
    assert.deepEqual(plain(M.vmNote({})), { text: '', warn: false });
});

test('the banner adds up every environment', () => {
    assert.deepEqual(plain(M.bannerText([{ drift: 1, pending: 0 }, { drift: 0, pending: 2 }])),
        { lead: '3 VMs need a look:', rest: '1 changed outside the platform, 2 found in AWS and not in a group.' });
    assert.deepEqual(plain(M.bannerText([{ drift: 0, pending: 1 }])),
        { lead: '1 VM needs a look:', rest: '1 found in AWS and not in a group.' });
    assert.equal(M.bannerText([]), null);
});

test('search matches the name, display name or description', () => {
    const envs = [{ name: 'payments-dev', displayName: 'Payments Dev' }, { name: 'crm-uat', displayName: 'CRM UAT', description: 'Client testing' }];
    assert.equal(M.matching(envs, 'PAY').length, 1);
    assert.equal(M.matching(envs, 'client')[0].name, 'crm-uat');
    assert.equal(M.matching(envs, ' ').length, 2);
});
