// Run with: node --test "src/test/js/**/*.test.mjs"
// U08-T07: idle hours per day and the VM's history in plain sentences.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function load() {
    const context = vm.createContext({});
    vm.runInContext(readFileSync(STATIC + 'js/features/vm-details-model.js', 'utf8') + '\nthis.VmDetailsModel = VmDetailsModel;', context);
    return context.VmDetailsModel;
}

const plain = x => JSON.parse(JSON.stringify(x));

test('idle hours per day: hourly samples under 5% CPU, weekend idle all day, today last', () => {
    const M = load();
    const now = new Date(2026, 9, 25, 23, 30);                 // Sunday 25 Oct
    const samples = [];
    for (let d = 6; d >= 0; d--) {
        for (let h = 0; h < 24; h++) {
            const t = new Date(2026, 9, 25 - d, h, 0);
            const weekend = t.getDay() === 0 || t.getDay() === 6;
            const office = h >= 8 && h < 20;
            samples.push({ sampleTime: t.toISOString(), cpuUtilization: weekend || !office ? 2 : 30 });
        }
    }
    const days = plain(M.idleHoursPerDay(samples, 5, now));
    assert.equal(days.length, 7);
    assert.deepEqual(days.map(d => d.label), ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']);
    assert.deepEqual(days.map(d => d.hours), [12, 12, 12, 12, 12, 24, 24]);
    assert.equal(days[6].today, true);
    assert.equal(M.idleLabel(days), 'Idle hours per day, last 7 days: 12, 12, 12, 12, 12, 24, 24');
    assert.deepEqual(plain(M.idleHoursPerDay([], 5, now)).map(d => d.sampled), [0, 0, 0, 0, 0, 0, 0]);
});

test('history: a person and their run, a schedule, a lease, and a change outside the platform', () => {
    const M = load();
    const now = new Date(2026, 9, 23, 12, 0);                  // Friday
    const at = (d, h, m = 0) => new Date(2026, 9, d, h, m).toISOString();
    assert.deepEqual(plain(M.historyText({ newStatus: 'RUNNING', changeSource: 'operation', operationId: 'r1', changedAt: at(23, 8, 5),
        changedByUsername: 'Priya Nair', triggerSource: 'MANUAL', operationType: 'START', operationTargets: 5 }, now)),
        { time: 'Today 08:05', text: 'Started', by: 'Priya Nair, as part of "Start 5 VMs"', tone: 'green' });
    assert.equal(M.historyText({ newStatus: 'RUNNING', changeSource: 'operation', operationId: 'r2', changedByUsername: 'Priya Nair',
        triggerSource: 'MANUAL', operationTargets: 1, changedAt: at(23, 9) }, now).by, 'Priya Nair');
    assert.deepEqual(plain(M.historyText({ newStatus: 'STOPPED', changeSource: 'operation', operationId: 'r3', triggerSource: 'SCHEDULE',
        triggerName: 'Office hours', changedAt: at(22, 19) }, now)),
        { time: 'Yesterday 19:00', text: 'Stopped', by: 'Schedule "Office hours"', tone: 'grey' });
    assert.equal(M.historyText({ newStatus: 'STOPPED', changeSource: 'operation', operationId: 'r4', triggerSource: 'LEASE', changedAt: at(20, 18) }, now).by,
        'The lease ended');
    assert.deepEqual(plain(M.historyText({ newStatus: 'STOPPED', changeSource: 'state_sync', drift: true, changedAt: at(21, 11, 0) }, now)),
        { time: 'Wed 11:00', text: 'State changed outside the platform (stopped in the AWS console)', by: 'Found by the 11:00 sync', tone: 'gold' });
    assert.equal(M.historyText({ newStatus: 'STOPPED', changeSource: 'spot_interruption', changedAt: at(10, 3) }, now).text, 'Stopped by AWS');
    assert.equal(M.when(at(14, 9), now), '14 Oct');
});

test('idle auto-stop line: the group rule wins, then the environment rule; off without one', () => {
    const M = load();
    const status = { featureEnabled: true, rules: [
        { groupId: null, mode: 'DRY_RUN', idleMinutes: 60, enabled: true },
        { groupId: 'g-app', groupName: 'Application', mode: 'ENFORCE', idleMinutes: 30, enabled: true }] };
    assert.equal(M.idleStopLine(status, 'g-web'), 'Idle auto-stop for this environment: dry run, after 60 min idle');
    assert.equal(M.idleStopLine(status, 'g-app'), 'Idle auto-stop for the Application group: on, after 30 min idle');
    assert.equal(M.idleStopLine({ featureEnabled: true, rules: [] }, 'g'), 'Idle auto-stop for this environment: off');
    assert.equal(M.idleStopLine({ featureEnabled: false }, 'g'), 'Idle auto-stop is off on this platform');
});
