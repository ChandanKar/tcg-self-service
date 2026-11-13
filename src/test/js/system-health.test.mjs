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
        drift: { value: '0', sub: 'starts and stops made in AWS' },
        actions: { value: '230', rate: '99% succeeded' },
        jobs: { value: '1/2', sub: '1 late', late: 1 } });
    assert.equal(M.tiles({ syncStatus: {}, jobs: [], driftCount: null, stats: null }, NOW).sync.value, 'Not yet');
});

test('U19-T06/T08 Regions: one row per account and region, failures first, the counts by kind, notes in words', () => {
    const scans = [
        { kind: 'EC2 instances (inbox)', accountRef: 'd', accountName: 'Default', region: 'eu-west-1', found: 12, scannedAt: '2026-10-22T08:00:00Z' },
        { kind: 'EKS clusters (inbox)', accountRef: 'd', accountName: 'Default', region: 'eu-west-1', found: 2, scannedAt: '2026-10-22T08:01:00Z' },
        { kind: 'EC2 instances by tag', accountRef: 'd', accountName: 'Default', region: 'eu-west-1', found: 3, scannedAt: '2026-10-22T07:00:00Z' },
        { kind: 'EC2 instances (inbox)', accountRef: 'd', accountName: 'Default', region: 'me-central-1', found: 0, scannedAt: '2026-10-22T08:00:00Z',
            error: 'UnauthorizedOperation', refused: true },
        { kind: 'EC2 instances (inbox)', accountRef: 'b', accountName: 'Payments', region: 'ap-south-1', found: 0, scannedAt: '2026-10-22T08:00:00Z' },
    ];
    const rows = plain(M.regionRows(scans));
    assert.deepEqual(rows.map(r => [r.accountName, r.region, !!r.error]), [
        ['Default', 'me-central-1', true], ['Default', 'eu-west-1', false], ['Payments', 'ap-south-1', false]]);
    assert.equal(rows[0].refused, true);
    assert.equal(rows[1].scannedAt, '2026-10-22T08:01:00Z');
    assert.equal(M.foundText(rows[1].found), '12 instances · 2 clusters');
    assert.equal(M.foundText(rows[2].found), 'Nothing found');
    assert.equal(M.noteKind('NOT_IN_REGION'), 'Not offered there');
    assert.equal(M.noteKind('WRONG_REGION'), 'In another region');
    assert.equal(M.noteKind('SOMETHING'), 'Failed');
});

test('U20-T09: Check VM states now says what it found', () => {
    assert.equal(M.syncResultText({ totalVmsSynced: 42, driftDetected: 2, syncErrors: 0 }), 'Checked 42 VMs: 2 changed outside the platform.');
    assert.equal(M.syncResultText({ totalVmsSynced: 1, driftDetected: 0, syncErrors: 0 }), 'Checked 1 VM: nothing changed outside the platform.');
    assert.equal(M.syncResultText({ totalVmsSynced: 9, driftDetected: 1, syncErrors: 3 }),
        "Checked 9 VMs: 1 changed outside the platform, 3 couldn't be read.");
});

test('U20-T09: one state-changes list; where each change came from; only outside the platform', () => {
    const list = [{ changeSource: 'operation', changedByUsername: 'Asha Rao' }, { changeSource: 'state_sync', drift: true },
        { changeSource: 'operation' }];
    assert.deepEqual(list.map(M.sourceText), ['Platform, by Asha Rao', 'Outside the platform', 'Platform']);
    assert.equal(M.changeRows(list, false).length, 3);
    assert.deepEqual(M.changeRows(list, true).map(c => c.changeSource), ['state_sync']);
});

test('U23-T09: a settle shows where the change it finishes came from, and is not another change outside', () => {
    const list = [{ changeSource: 'sync_settled', settledFrom: 'state_sync' }, { changeSource: 'sync_settled', settledFrom: 'operation' },
        { changeSource: 'sync_settled' }, { changeSource: 'state_sync', drift: true }];
    assert.deepEqual(list.map(M.sourceText), ['Outside the platform, finished', 'Platform', 'Platform', 'Outside the platform']);
    assert.equal(M.changeRows(list, true).length, 1);
});

test('U22-T12: changed outside the platform shows the VMs, as Home does, with the starts and stops behind them', () => {
    const t = (driftVms, driftCount) => plain(M.tiles({ syncStatus: {}, jobs: [], driftVms, driftCount, stats: null }, NOW).drift);
    assert.deepEqual(t(102, 289), { value: '102', sub: 'VMs, in 289 starts and stops made in AWS' });
    assert.deepEqual(t(1, 1), { value: '1', sub: 'VM, in 1 start or stop made in AWS' });
    assert.deepEqual(t(0, 0), { value: '0', sub: 'VMs' });
    assert.deepEqual(t(null, null), { value: '—', sub: 'Not available' });
});

test('U23-T13: a job\'s time of day is shown in the viewer\'s zone, from its next run', () => {
    const next = new Date(2026, 10, 14, 8, 0); // local
    assert.equal(M.cadenceText({ cadence: 'Daily 02:30', nextRunAt: next.toISOString() }), 'Daily 08:00');
    assert.equal(M.cadenceText({ cadence: 'Weekly, Mon 09:00', nextRunAt: new Date(2026, 10, 16, 14, 30).toISOString() }), 'Weekly, Mon 14:30');
    assert.equal(M.cadenceText({ cadence: 'Monthly, day 5 06:00', nextRunAt: new Date(2026, 11, 5, 11, 30).toISOString() }), 'Monthly, day 5 11:30');
    assert.equal(M.cadenceText({ cadence: 'Every 5 min', nextRunAt: next.toISOString() }), 'Every 5 min');
    assert.equal(M.cadenceText({ cadence: 'Daily 03:00' }), 'Daily 03:00');
});

test('U23-T13: a region that could not be reached says so plainly; the summary does not claim every region answered', () => {
    const raw = 'Unable to execute HTTP request: Connect to ec2.me-south-1.amazonaws.com:443 failed: Connect timed out';
    assert.equal(M.scanErrorText({ region: 'me-south-1', error: raw }), "Couldn't reach me-south-1.");
    assert.equal(M.scanErrorText({ region: 'eu-west-1', error: 'not authorized', refused: true }), 'not authorized');
    assert.equal(M.notesSummary([{ error: raw }, {}]), "1 region wasn't read (see above); every other service answered.");
    assert.equal(M.notesSummary([{}, {}]), 'Every service answered in every region it was asked in.');
});

test('U23-T13: lock and operation report rows in words, with a result', () => {
    const row = plain(M.complianceRow({ action: 'VM_STOP_COMPLETED', actionDisplay: 'Vm stop completed', targetName: 'web-01',
        userDisplayName: 'Asha Rao', success: true }));
    assert.equal(row.who, 'Asha Rao');
    assert.equal(row.result, 'Done');
    assert.equal(plain(M.complianceRow({ action: 'VM_STOP_FAILED', success: false })).result, 'Failed');
});

test('U24-T05: the state-changes line says what the list holds', () => {
    assert.equal(M.changesCountText(277, 251, false, false), '277 changes in 24 hours, 251 outside the platform');
    assert.equal(M.changesCountText(277, 251, true, false), '251 changes outside the platform in 24 hours');
    assert.equal(M.changesCountText(2000, 1900, false, true), '2,000+ changes in 24 hours, 1,900 outside the platform');
    assert.equal(M.changesCountText(1, 1, true, false), '1 change outside the platform in 24 hours');
    assert.equal(M.changesCountText(3, 0, false, false), '3 changes in 24 hours, 0 outside the platform');
});

test('U24-T02: a schedule\'s VM operation is listed under the schedule', () => {
    const ctx = vm.createContext({});
    ctx.window = ctx;
    vm.runInContext(readFileSync(STATIC + 'js/core/audit-text.js', 'utf8'), ctx);
    vm.runInContext(readFileSync(STATIC + 'js/features/system-health.js', 'utf8') + '\nthis.S = SystemHealth;', ctx);
    const row = plain(ctx.S.model.complianceRow({ action: 'VM_STOP_COMPLETED', targetName: 'mcube-dev-poc-work1',
        userDisplayName: 'Chandan Kar', success: true, details: 'VM stopped by user: Chandan Kar. Run by: Schedule "ise start-stop"' }));
    assert.equal(row.who, 'Schedule "ise start-stop"');
    assert.equal(row.what, 'Stopped mcube-dev-poc-work1');
    assert.equal(plain(ctx.S.model.complianceRow({ action: 'VM_STOP_COMPLETED', targetName: 'web-01', userDisplayName: 'Asha Rao',
        details: 'VM stopped by user: Asha Rao' })).who, 'Asha Rao');
});
