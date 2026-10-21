// Run with: node --test "src/test/js/**/*.test.mjs"
// U13-T08: the team page's headline, Entra line and the limits form's request body.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/team-detail.js', 'utf8') + '\nthis.T = TeamDetail;', context);
const M = context.T.model;
const plain = x => JSON.parse(JSON.stringify(x));

test('the headline: slug, cost centre, environments and members', () => {
    assert.equal(M.headline({ name: 'payments', costCenter: 'CC-4410', environments: [{}, {}, {}], memberCount: 14 }),
        'payments · cost centre CC-4410 · 3 environments · 14 members');
    assert.equal(M.headline({ name: 'crm', environments: [{}], memberCount: 1 }), 'crm · 1 environment · 1 member');
});

test('the Entra line: group, members from it and the sync time', () => {
    const now = new Date(2026, 9, 21, 15, 0);
    const synced = new Date(2026, 9, 21, 9, 5).toISOString();
    assert.equal(M.entraLine({ entraGroupId: 'g', entraGroupName: 'SG-Payments', entraMemberCount: 12, lastSyncedAt: synced }, now),
        'Linked to Entra group SG-Payments · 12 members from it, last synced 09:05');
    assert.equal(M.entraLine({ entraGroupId: 'g', entraMemberCount: 1 }, now), 'Linked to Entra group g · 1 member from it');
    assert.match(M.entraLine({}, now), /^Not linked to an Entra group/);
});

test('the limits body leaves out a metric without a limit', () => {
    assert.deepEqual(plain(M.quotasBody([
        { metric: 'VM_HOURS', limit: '3000', warnPercent: '75', enforcement: 'BLOCK' },
        { metric: 'COST', limit: '', warnPercent: '80', enforcement: 'WARN' }])),
        [{ metric: 'VM_HOURS', limit: 3000, enforcement: 'BLOCK', warnPercent: 75 }]);
    assert.deepEqual(plain(M.quotasBody([{ metric: 'COST', limit: '250.5', warnPercent: '', enforcement: 'REQUIRE_APPROVAL' }])),
        [{ metric: 'COST', limit: 250.5, enforcement: 'REQUIRE_APPROVAL', warnPercent: 80 }]);
});

test('amounts: hours and dollars', () => {
    assert.equal(M.amount('VM_HOURS', 1840.4), '1,840 h');
    assert.equal(M.amount('COST', 2500), '$2,500');
});
