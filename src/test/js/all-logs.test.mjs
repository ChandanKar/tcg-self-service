// Run with: node --test "src/test/js/**/*.test.mjs"
// U20-T04: the audit export says which filters made it, in its file name and message.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({ URLSearchParams });
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/all-logs.js', 'utf8') + '\nthis.L = AllLogs;', context);
const M = context.L.model;
const plain = x => JSON.parse(JSON.stringify(x));

const BASE = { timeRange: '7d', customStart: '', customEnd: '', userId: '', environmentId: '', category: '', resultStatus: '' };

test('the filters in words: the period always, then only what is chosen', () => {
    assert.deepEqual(plain(M.filterWords(BASE)), ['Last 7 days']);
    assert.deepEqual(plain(M.filterWords({ ...BASE, timeRange: '24h', userId: 'u1', environmentId: 'e1', category: 'ACCESS', resultStatus: 'false' },
        { person: 'Ana Ruiz', environment: 'CRM UAT', action: 'Access' })), ['Last 24 hours', 'Ana Ruiz', 'CRM UAT', 'Access', 'Failed only']);
    assert.deepEqual(plain(M.filterWords({ ...BASE, timeRange: 'custom', customStart: '2026-10-01', customEnd: '2026-10-05' })), ['2026-10-01 to 2026-10-05']);
    assert.deepEqual(plain(M.filterWords({ ...BASE, timeRange: 'custom', customStart: '2026-10-01', customEnd: '2026-10-01' })), ['2026-10-01']);
    assert.deepEqual(plain(M.filterWords({ ...BASE, environmentId: 'gone' })), ['Last 7 days', 'One environment']);
});

test('the file is named after the filters, in safe characters', () => {
    assert.equal(M.exportFileName(['Last 7 days'], '2026-10-23'), 'audit-log-2026-10-23-last-7-days.csv');
    assert.equal(M.exportFileName(['Last 24 hours', 'José O\'Neil', 'CRM / UAT', 'Failed only'], '2026-10-23'),
        'audit-log-2026-10-23-last-24-hours-jose-o-neil-crm-uat-failed-only.csv');
    assert.ok(M.exportFileName(['x'.repeat(200)], '2026-10-23').length <= 'audit-log-2026-10-23-.csv'.length + 80);
});
