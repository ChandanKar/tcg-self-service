// Run with: node --test "src/test/js/**/*.test.mjs"
// U13-T07: the Teams cards' members line and VM-hours bar.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/teams.js', 'utf8') + '\nthis.T = Teams;', context);
const M = context.T.model;
const plain = x => JSON.parse(JSON.stringify(x));

test('the members line names the Entra group when linked', () => {
    assert.equal(M.membersText({ memberCount: 6, entraGroupId: 'g', entraGroupName: 'Payments Engineering' }),
        '6 people · synced from Entra group "Payments Engineering"');
    assert.equal(M.membersText({ memberCount: 1 }), '1 person');
    assert.equal(M.membersText({ memberCount: 3, entraGroupId: 'g' }), '3 people · synced from Entra group "linked group"');
});

test('VM hours: used / limit, red over 90%, none without a VM_HOURS quota', () => {
    assert.deepEqual(plain(M.vmHours({ quotas: [{ metric: 'VM_HOURS', used: 1840.4, limit: 4000 }] })),
        { used: 1840, limit: 4000, percent: 46, over: false, text: '1,840 / 4,000', label: '46% of the monthly VM hours used' });
    assert.equal(M.vmHours({ quotas: [{ metric: 'VM_HOURS', used: 2890, limit: 3000 }] }).over, true);
    assert.equal(M.vmHours({ quotas: [{ metric: 'COST', used: 10, limit: 20 }] }), null);
    assert.equal(M.vmHours({ quotas: [] }), null);
});
