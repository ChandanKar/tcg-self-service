// Run with: node --test "src/test/js/**/*.test.mjs"
// U08-T04: the history panel's when, what, who, took, result chip, step lines and pager text.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function load() {
    const context = vm.createContext({});
    vm.runInContext(readFileSync(STATIC + 'js/features/operation-history.js', 'utf8') + '\nthis.OperationHistory = OperationHistory;', context);
    return context.OperationHistory.model;
}

// Values made in the vm context have its prototypes; compare them as plain data.
const plain = x => JSON.parse(JSON.stringify(x));

test('when: today, yesterday, a weekday within the week, else the date', () => {
    const M = load();
    const now = new Date(2026, 9, 20, 15, 0);            // Tue 20 Oct
    assert.equal(M.whenText(new Date(2026, 9, 20, 6, 30).toISOString(), now), 'Today 06:30');
    assert.equal(M.whenText(new Date(2026, 9, 19, 22, 5).toISOString(), now), 'Yesterday 22:05');
    assert.equal(M.whenText(new Date(2026, 9, 16, 14, 22).toISOString(), now), 'Fri 14:22');
    assert.equal(M.whenText(new Date(2026, 9, 12, 9, 15).toISOString(), now), '12 Oct 09:15');
    assert.equal(M.whenText(null, now), '—');
});

test('what and who: the scope, and the person or what started it', () => {
    const M = load();
    assert.equal(M.whatText({ operationType: 'START', scopeLabel: 'whole environment' }), 'Start, whole environment');
    assert.equal(M.whatText({ operationType: 'START', scopeLabel: 'report-worker' }), 'Start report-worker');
    assert.equal(M.whatText({ operationType: 'STOP', scopeLabel: 'Application group' }), 'Stop Application group');
    assert.equal(M.whoText({ triggerSource: 'SCHEDULE', triggerName: 'Nightly batch' }), 'Schedule "Nightly batch"');
    assert.equal(M.whoText({ triggerSource: 'MANUAL', initiatedByDisplayName: 'Priya Nair' }), 'Priya Nair');
    assert.equal(M.whoText({ triggerSource: 'LEASE' }), 'Lease');
    assert.equal(M.whoText({ triggerSource: 'IDLE_STOP' }), 'Idle auto-stop');
    assert.equal(M.whoText({ triggerSource: 'BUDGET' }), 'Budget');
});

test('took and the result chip', () => {
    const M = load();
    assert.equal(M.tookText({ status: 'COMPLETED', startedAt: '2026-10-20T06:30:00Z', completedAt: '2026-10-20T06:33:58Z' }), '3:58');
    assert.equal(M.tookText({ status: 'IN_PROGRESS', startedAt: '2026-10-20T06:30:00Z' }), '—');
    assert.deepEqual(plain(M.resultChip({ status: 'COMPLETED' })), { text: 'Done', tone: 'ok' });
    assert.deepEqual(plain(M.resultChip({ status: 'PARTIAL_SUCCESS', failedTargets: 1, skippedTargets: 1 })), { text: '1 failed', tone: 'part' });
    assert.deepEqual(plain(M.resultChip({ status: 'PARTIAL_SUCCESS', skippedTargets: 2 })), { text: '2 skipped', tone: 'part' });
    assert.deepEqual(plain(M.resultChip({ status: 'CANCELLED' })), { text: 'Cancelled', tone: 'skip' });
    assert.deepEqual(plain(M.resultChip({ status: 'IN_PROGRESS' })), { text: 'Running', tone: 'run' });
});

test('steps: a failed VM with its reason and a retry; a quiet group in one line; a run without steps', () => {
    const M = load();
    const step = (seq, group, name, status, extra = {}) => ({ sequencePosition: seq, groupId: 'g-' + group, groupName: group,
        targetType: 'vm', targetId: 'id-' + name, targetName: name, status, ...extra });
    const lines = plain(M.stepLines({ operationType: 'START', details: [
        step(1, 'Database', 'db-01', 'COMPLETED'), step(2, 'Database', 'db-02', 'COMPLETED'),
        step(3, 'Application', 'report-worker', 'FAILED', { errorMessage: 'AWS: no r6i.xlarge capacity in ap-south-1b right now.' }),
        step(4, 'Application', 'etl-runner', 'SKIPPED')] }));
    assert.deepEqual(lines.map(l => [l.group, l.vm, l.result]), [
        ['Database', '2 VMs', 'Done'], ['Application', 'report-worker', 'Failed'], ['Application', 'etl-runner', 'Skipped']]);
    assert.equal(lines[1].note, 'AWS: no r6i.xlarge capacity in ap-south-1b right now.');
    assert.deepEqual(lines[1].retry, { vmId: 'id-report-worker', name: 'report-worker' });
    assert.equal(lines[0].retry, undefined);
    const none = plain(M.stepLines({ status: 'CANCELLED', errorMessage: 'Cancelled by Neha Gupta before Application started', details: [] }));
    assert.deepEqual(none.map(l => [l.group, l.vm, l.result]), [['Whole run', 'Cancelled by Neha Gupta before Application started', 'Cancelled']]);
});

test('pager text', () => {
    const M = load();
    assert.equal(M.rangeText(0, 7, 23), '1–7 of 23');
    assert.equal(M.rangeText(3, 7, 23), '22–23 of 23');
    assert.equal(M.rangeText(0, 7, 0), '0 of 0');
});
