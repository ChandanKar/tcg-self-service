// Run with: node --test "src/test/js/**/*.test.mjs"
// U09-T04: My activity rows as sentences with a Done / Failed / Sent chip, day labels and the count line.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function load() {
    const context = vm.createContext({});
    // U12-T08: the sentences moved to core/audit-text.js (AuditText), shared with the audit log.
    vm.runInContext(readFileSync(STATIC + 'js/core/audit-text.js', 'utf8') + '\nthis.AuditText = AuditText;', context);
    vm.runInContext(readFileSync(STATIC + 'js/features/activity-logs.js', 'utf8') + '\nthis.ActivityLogs = ActivityLogs;', context);
    return context.ActivityLogs.model;
}

const plain = x => JSON.parse(JSON.stringify(x));
const M = load();
const say = log => plain(M.describe({ success: true, ...log }));

test('operations: asked, finished with a count, partly failed, failed with the reason, cancelled', () => {
    assert.equal(say({ action: 'OPERATION_STARTED', targetName: 'START',
        details: 'Operation started by user: priya. Environment: Payments Dev' }).text, 'Asked to start VMs');
    assert.equal(say({ action: 'OPERATION_STARTED', targetName: 'STOP',
        details: 'Operation started by the platform: Lease expired (on behalf of priya). Environment: X' }).text,
        'The platform stopped VMs when the running time ended');
    assert.deepEqual(say({ action: 'OPERATION_COMPLETED', targetName: 'START',
        details: 'Operation completed by user: priya. Environment: Payments Dev. Total VMs: 5, Failed: 0' }),
        { text: 'Started 5 VMs', note: '', result: 'Done', tone: 'ok' });
    assert.deepEqual(say({ action: 'OPERATION_COMPLETED', targetName: 'STOP',
        details: 'Operation completed by user: priya. Environment: X. Total VMs: 6, Failed: 1' }),
        { text: 'Stopped 5 of 6 VMs', note: '1 VM did not stop or was skipped.', result: 'Done', tone: 'ok' });
    assert.deepEqual(say({ action: 'OPERATION_FAILED', targetName: 'START', success: false,
        errorMessage: 'User: priya. Environment: X. Error: Step timed out' }),
        { text: 'Started VMs', note: 'Step timed out', result: 'Failed', tone: 'bad' });
    assert.equal(say({ action: 'OPERATION_CANCELLED', targetName: 'RESTART', details: 'Cancelled' }).text, 'Cancelled the restart');
});

test('a failed VM start shows the AWS reason under the sentence', () => {
    assert.deepEqual(say({ action: 'VM_START_FAILED', targetName: 'report-worker', success: false,
        errorMessage: 'User: priya. Error: InsufficientInstanceCapacity for r6i.xlarge in ap-south-1b' }),
        { text: 'Started report-worker', note: 'InsufficientInstanceCapacity for r6i.xlarge in ap-south-1b', result: 'Failed', tone: 'bad' });
    assert.equal(say({ action: 'VM_STOP_COMPLETED', targetName: 'pay-api-02' }).text, 'Stopped pay-api-02');
});

test('locks', () => {
    assert.equal(say({ action: 'LOCK_ACQUIRED',
        details: 'Lock acquired by user: priya. Environment: Analytics QA. Reason: Data migration rehearsal' }).text,
        'Locked · "Data migration rehearsal"');
    assert.equal(say({ action: 'LOCK_ACQUIRED', details: 'Lock acquired by user: priya. Environment: A. Reason: ' }).text, 'Locked');
    assert.equal(say({ action: 'LOCK_RELEASED', details: 'Lock released by user: priya.' }).text, 'Released lock');
    assert.equal(say({ action: 'LOCK_EXTENDED', details: 'Lock extended by priya. Extended by 120 minutes; expires 2026-10-20 14:30 UTC' }).text,
        'Kept the lock 2 hours longer');
    assert.equal(say({ action: 'LOCK_BROKEN',
        details: 'Lock broken by admin: sanjay. Original holder: Rahul Verma. Reason: Release window' }).text,
        'Broke the lock held by Rahul Verma · "Release window"');
});

test('access and approvals: requests are Sent, decisions are Done', () => {
    assert.deepEqual(say({ action: 'ACCESS_REQUESTED', details: 'Access requested by Priya Nair (priya@x.com). Level: USER' }),
        { text: 'Asked for operator access', note: '', result: 'Sent', tone: 'wait' });
    assert.equal(say({ action: 'ACCESS_GRANTED',
        details: 'Access granted to Priya Nair (priya@x.com). Level: VIEWER. Approved by Sanjay Rao (sanjay@x.com)' }).text,
        'Granted Priya Nair view-only access');
    assert.equal(say({ action: 'ACCESS_DENIED',
        details: 'Access denied for Priya Nair (priya@x.com). Denied by Sanjay Rao. Reason: Ask your lead first' }).text,
        'Turned down Priya Nair\'s request · "Ask your lead first"');
    assert.equal(say({ action: 'ACCESS_REVOKED', details: 'Access revoked for Priya Nair (priya@x.com). Revoked by Sanjay' }).text,
        'Removed Priya Nair\'s access');
    assert.equal(say({ action: 'APPROVAL_REQUESTED', details: 'START held for approval (ANY of 2): runs 24 h' }).result, 'Sent');
    assert.equal(say({ action: 'APPROVAL_DENIED', details: 'Denied by Meera Iyer: Too long for a weekend' }).text,
        'Denied a request · "Too long for a weekend"');
    assert.equal(say({ action: 'LEASE_EXTENSION_REQUESTED', details: 'Asked to extend by 360 minutes, beyond the 720-minute maximum.' }).text,
        'Asked to keep it running 6 hours longer');
    assert.equal(say({ action: 'LEASE_STARTED', details: 'Lease started by priya. Runs for 90 minutes.' }).text, 'Started for 90 minutes');
    assert.equal(say({ action: 'IDLE_STOP_SNOOZED', details: 'Idle auto-stop snoozed for 4 h until 2026-10-20T16:00:00Z' }).text,
        'Paused idle auto-stop for 4 hours');
    assert.equal(say({ action: 'IDLE_STOP_SNOOZED', details: 'Idle auto-stop snooze ended' }).text, 'Resumed idle auto-stop');
});

test('unknown actions fall back to the action name and target', () => {
    // U12-T08: an added / changed / deleted thing reads as a sentence.
    assert.equal(say({ action: 'TEMPLATE_CREATED', actionDisplay: 'Template Created', targetName: 'Java stack' }).text,
        'Added template "Java stack"');
    assert.equal(say({ action: 'SOMETHING_NEW' }).text, 'Something new');
});

test('day labels: Today, Yesterday, then the weekday and date', () => {
    const now = new Date(2026, 9, 20, 12, 0);
    assert.equal(M.dayLabel(new Date(2026, 9, 20, 8, 2), now), 'Today');
    assert.equal(M.dayLabel(new Date(2026, 9, 19, 23, 59), now), 'Yesterday');
    assert.equal(M.dayLabel(new Date(2026, 9, 17, 19, 0), now), 'Saturday 17 October');
    assert.equal(M.dayLabel(new Date(2025, 11, 31, 9, 0), now), 'Wednesday 31 December 2025');
});

test('rows get a day row before each new day', () => {
    const now = new Date(2026, 9, 20, 12, 0);
    const at = (d, h) => new Date(2026, 9, d, h).toISOString();
    const rows = M.rowsWithDays([{ createdAt: at(20, 9) }, { createdAt: at(20, 8) }, { createdAt: at(18, 10) }], now);
    assert.deepEqual(plain(rows.map(r => r.day !== undefined ? r.day : 'row')), ['Today', 'row', 'row', 'Sunday 18 October', 'row']);
});

test('the count line', () => {
    assert.equal(M.countLine(9, '7d'), '9 actions in the last 7 days');
    assert.equal(M.countLine(1, '24h'), '1 action in the last 24 hours');
    assert.equal(M.countLine(3, 'dates', '2026-10-01', '2026-10-15'), '3 actions from 1 Oct to 15 Oct');
    assert.equal(M.countLine(2, 'dates', '2026-10-05', '2026-10-05'), '2 actions on 5 Oct');
});
