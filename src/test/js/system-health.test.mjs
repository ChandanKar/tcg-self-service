// Run with: node --test "src/test/js/**/*.test.mjs"
// U14-T02: System health's last-run text, status pills, job order and the four figures.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/system-health.js', 'utf8') + '\nthis.S = SystemHealth;', context);
const M = context.S.model;
const plain = x => JSON.parse(JSON.stringify(x));

const NOW = new Date(2026, 9, 21, 17, 45);

test('last run: today, yesterday, a date, or a dash', () => {
    assert.equal(M.lastRun(new Date(2026, 9, 21, 9, 40).toISOString(), NOW), 'Today 09:40');
    assert.equal(M.lastRun(new Date(2026, 9, 20, 1, 0).toISOString(), NOW), 'Yesterday 01:00');
    assert.equal(M.lastRun(new Date(2026, 9, 18, 6, 5).toISOString(), NOW), '18 Oct 06:05');
    assert.equal(M.lastRun(null, NOW), '—');
});

test('status pills: on time, running, late by, never ran, switched off', () => {
    assert.deepEqual(plain(M.status({ status: 'ON_TIME' })), { text: 'On time', kind: 'ok' });
    assert.deepEqual(plain(M.status({ status: 'ON_TIME', running: true })), { text: 'Running', kind: 'ok' });
    assert.deepEqual(plain(M.status({ status: 'LATE', lateBySeconds: 7200 })), { text: '2 h late', kind: 'late' });
    assert.deepEqual(plain(M.status({ status: 'LATE', lateBySeconds: 420 })), { text: '7 min late', kind: 'late' });
    assert.deepEqual(plain(M.status({ status: 'LATE', lateBySeconds: null })), { text: 'Never ran', kind: 'late' });
    assert.deepEqual(plain(M.status({ status: 'OFF' })), { text: 'Switched off', kind: 'off' });
});

test('order: problems first keeps the server order; all follows the catalog', () => {
    const jobs = [{ name: 'Cost snapshot', position: 11 }, { name: 'State sync', position: 0 }, { name: 'Idle auto-stop', position: 8 }];
    assert.deepEqual(M.ordered(jobs, 'problems').map(j => j.name), ['Cost snapshot', 'State sync', 'Idle auto-stop']);
    assert.deepEqual(M.ordered(jobs, 'all').map(j => j.name), ['State sync', 'Idle auto-stop', 'Cost snapshot']);
});

test('the four figures', () => {
    const jobs = [{ jobName: 'cost_snapshot', status: 'LATE', cadence: 'Daily 03:00' },
        { jobName: 'vm_state_sync', status: 'ON_TIME', cadence: 'Every 5 min' },
        { jobName: 'idle_auto_stop', status: 'OFF', cadence: 'Every 5 min' }];
    const t = M.tiles({ syncStatus: { lastSyncTime: new Date(NOW.getTime() - 4 * 60000).toISOString(), totalVmsSynced: 112 },
        jobs, driftCount: 0, stats: { total: 230, successRate: 99 } }, NOW);
    assert.deepEqual(plain(t), {
        sync: { value: '4 min ago', fresh: true, sub: '112 VMs, every 5 min' },
        drift: { value: '0' },
        actions: { value: '230', rate: '99% succeeded' },
        jobs: { value: '1/2', sub: '1 late', late: 1 } });
    assert.equal(M.tiles({ syncStatus: {}, jobs: [], driftCount: null, stats: null }, NOW).sync.value, 'Not yet');
});
