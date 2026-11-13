// Run with: node --test "src/test/js/**/*.test.mjs"
// U08-T08: the insights panel's subtitle, What to do lines by role, idle, busiest, groups and storage.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function load() {
    const context = vm.createContext({});
    vm.runInContext(readFileSync(STATIC + 'js/features/env-insights.js', 'utf8') + '\nthis.EnvInsights = EnvInsights;', context);
    return context.EnvInsights.model;
}

const plain = x => JSON.parse(JSON.stringify(x));

const actions = [
    { kind: 'STOP_IDLE', vmId: 'v-cache', vmName: 'pay-cache', hourlyRate: 0.07, detail: 'Idle for 6 h' },
    { kind: 'RESIZE', vmId: 'v-api', vmName: 'pay-api-01', recommendedType: 'm6i.medium', monthlySavings: 31.2, detail: 'CPU peaked at 18%' },
    { kind: 'SCHEDULE_WEEKENDS', detail: '2 VMs ran idle for about 48 h over the weekend' },
    { kind: 'RESIZE', vmId: 'v-x', vmName: 'fourth', recommendedType: 't3.small', monthlySavings: 5 }
];

test('subtitle: VMs, running, disk and how fresh the metrics are', () => {
    const M = load();
    const now = new Date('2026-10-20T12:00:00Z');
    assert.equal(M.subtitle({ totalVms: 7, runningVms: 5, totalAllocatedStorageGib: 1229, latestMetricSampleTime: '2026-10-20T11:56:00Z' }, now),
        '7 VMs · 5 running · 1.2 TiB of disk · metrics up to 4 min ago');
    assert.equal(M.subtitle({ totalVms: 1, runningVms: 0 }, now), '1 VM · 0 running · no metrics yet');
});

test('what to do: three lines; Stop for operators, Edit schedule for administrators, Details for everyone', () => {
    const M = load();
    const operator = plain(M.actionLines(actions, true, false));
    assert.deepEqual(operator.map(l => [l.title, l.saves, l.action && l.action.label]), [
        ['Stop pay-cache.', '$0.07/h', 'Stop'],
        ['Resize pay-api-01 to m6i.medium.', '$31/mo', 'Details'],
        ['Stop on weekends too.', '', null]]);
    assert.equal(operator[0].detail, 'Idle for 6 h.');
    const viewer = plain(M.actionLines(actions, false, false));
    assert.deepEqual(viewer.map(l => l.action && l.action.kind), [null, 'details', null]);
    const admin = plain(M.actionLines(actions, true, true));
    assert.equal(admin[2].action.label, 'Edit schedule');
});

test('idle now, busiest bars relative to the busiest, groups in start order, storage hints', () => {
    const M = load();
    const ins = {
        actions,
        idleVmRows: [{ vmId: 'v-cache', name: 'pay-cache', idleDurationMinutes: 370, cpuUtilization: 3.9 }],
        busiestVms: [{ vmId: 'a', name: 'pay-api-01', avgCpuWindow: 31, cpuUtilization: 60 }, { vmId: 'b', name: 'pay-cache', avgCpuWindow: 4 }],
        groups: [{ name: 'Web', sequencePosition: 3, runningVms: 0, totalVms: 2, avgCpuUtilization: null },
            { name: 'Database', sequencePosition: 1, runningVms: 3, totalVms: 3, avgCpuUtilization: 12.4 }],
        volumeTypeCounts: { gp3: 6, gp2: 1 }
    };
    assert.deepEqual(plain(M.idleRows(ins)), [{ vmId: 'v-cache', name: 'pay-cache', since: '6 h 10 min', cpu: '3.9%', rate: '$0.07/h' }]);
    assert.deepEqual(plain(M.busiest(ins)).map(b => [b.name, b.cpu, b.width]), [['pay-api-01', '31%', 100], ['pay-cache', '4%', 13]]);
    assert.deepEqual(plain(M.groupRows(ins)).map(g => [g.name, g.running, g.cpu]), [['Database', 3, '12%'], ['Web', 0, '—']]);
    assert.deepEqual(plain(M.storageRows(ins)), [{ type: 'gp3', hint: '', count: '6 disks' },
        { type: 'gp2', hint: 'move to gp3, same speed for less', count: '1 disk' }]);
});

test('U23-T10: a budget forecast past its amount is the first thing to do, in the words Home uses', () => {
    const M = load();
    const ise = { enabled: true, monthlyAmount: 50, spendToDate: 31.1, forecastMonthEnd: 65.57, forecastPercent: 131.1 };
    assert.deepEqual(plain(M.budgetLine(ise, 'ISE')), { title: 'ISE heading for 131% of its budget.',
        detail: 'Forecast $65.57 of $50.00 this month, $31.10 spent so far.', saves: '', tone: 'gold', action: null });
    assert.equal(M.budgetLine({ ...ise, forecastMonthEnd: 48, forecastPercent: 96 }, 'ISE'), null); // within the budget
    assert.equal(M.budgetLine({ ...ise, enabled: false }, 'ISE'), null);
    assert.equal(M.budgetLine({ ...ise, forecastMonthEnd: null, forecastPercent: null }, 'ISE'), null);
    assert.equal(M.budgetLine(null, 'ISE'), null);
    assert.equal(M.budgetLine({ ...ise, forecastPercent: null }, 'ISE').title, 'ISE heading for 131% of its budget.');
});

test('a long busiest name is cut in the middle, keeping the end that tells the VMs apart (U23-T11)', () => {
    const M = load();
    assert.equal(M.middleCut('mcube_dev_poc_work1_100.112.1.162'), 'mcube_dev…work1_100.112.1.162');
    assert.equal(M.middleCut('pay-api-01'), 'pay-api-01');
    const rows = plain(M.busiest({ busiestVms: [{ vmId: 'a', name: 'mcube_dev_poc_worker2_100.112.1.170', avgCpuWindow: 3 }] }));
    assert.equal(rows[0].name, 'mcube_dev_poc_worker2_100.112.1.170');
    assert.equal(rows[0].short, 'mcube_dev…rker2_100.112.1.170');
});
