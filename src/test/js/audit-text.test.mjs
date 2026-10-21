// Run with: node --test "src/test/js/**/*.test.mjs"
// U12-T08: audit rows in words (core/audit-text.js) and the audit log's counts and times.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({ Intl, Date, URLSearchParams });
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/core/audit-text.js', 'utf8') + '\nthis.T = AuditText;', context);
vm.runInContext(readFileSync(STATIC + 'js/features/all-logs.js', 'utf8') + '\nthis.L = AllLogs;', context);
const T = context.T;
const L = context.L.model;
const plain = x => JSON.parse(JSON.stringify(x));
const say = log => T.what({ success: true, ...log }).text;

test('operations', () => {
    assert.equal(say({ action: 'OPERATION_COMPLETED', targetName: 'START', details: 'Total VMs: 5, Failed: 0' }), 'Started 5 VMs');
    assert.equal(say({ action: 'OPERATION_COMPLETED', targetName: 'STOP', details: 'Total VMs: 6, Failed: 0' }), 'Stopped 6 VMs');
    const failed = plain(T.what({ action: 'VM_START_FAILED', targetName: 'report-worker', success: false,
        errorMessage: 'AWS: InsufficientInstanceCapacity' }));
    assert.deepEqual(failed, { text: 'Started report-worker', note: 'AWS: InsufficientInstanceCapacity', result: 'Failed', tone: 'bad' });
});

test('locks', () => {
    assert.equal(say({ action: 'LOCK_BROKEN', details: 'Original holder: Karthik S (karthik@x.com). Reason: release' }),
        'Broke the lock held by Karthik S · "release"');
    assert.equal(say({ action: 'LOCK_EXTENDED', details: 'Extended by 120 minutes' }), 'Kept the lock 2 hours longer');
});

test('access: a grant names the person, the level and how long', () => {
    assert.equal(say({ action: 'ACCESS_GRANTED',
        details: 'Access granted to Neha Gupta (neha@x.com). Level: USER. Approved by Sanjay Rao (sanjay@x.com). For 7 days' }),
        'Granted Neha Gupta operator access, 7 days');
    assert.equal(say({ action: 'ACCESS_GRANTED',
        details: 'Access granted to Neha Gupta (neha@x.com). Level: VIEWER. Approved by Sanjay Rao (sanjay@x.com)' }),
        'Granted Neha Gupta view-only access');
});

test('automation: who is the schedule, what happened to the run', () => {
    const run = { action: 'AUTOMATION_RULE_SKIPPED', targetName: 'UAT with the client', details: 'Skipped: Environment locked by Priya Nair' };
    assert.equal(T.who(run), 'Schedule "UAT with the client"');
    assert.equal(say(run), 'Run skipped: environment was locked by Priya Nair');
    assert.equal(say({ action: 'AUTOMATION_RULE_SKIPPED', details: 'Skipped: holiday (Diwali)' }), 'Run skipped: holiday (Diwali)');
    const lock = { action: 'AUTOMATION_RULE_TRIGGERED', targetName: 'On lock', details: 'Triggered START (lock acquired)' };
    assert.equal(T.who(lock), 'Rule "On lock"');
    assert.equal(say(lock), 'Started VMs');
    assert.equal(say({ action: 'AUTOMATION_RULE_UPDATED', targetName: 'Office hours' }), 'Changed rule "Office hours"');
    assert.equal(T.what({ action: 'AUTOMATION_RULE_FAILED', details: 'Instance limit exceeded' }).result, 'Failed');
});

test('settings and anything else read as added / changed / deleted, else the action name', () => {
    assert.equal(say({ action: 'VM_GROUP_CREATED', targetName: 'web' }), 'Added VM group "web"');
    assert.equal(say({ action: 'ENVIRONMENT_UPDATED', targetName: 'CRM UAT', environmentName: 'CRM UAT' }), 'Changed the environment');
    assert.equal(say({ action: 'WEBHOOK_DELETED', targetName: 'Ops' }), 'Deleted webhook "Ops"');
    assert.equal(say({ action: 'SOMETHING_NEW' }), 'Something new');
    assert.equal(T.who({ action: 'LOCK_ACQUIRED', userDisplayName: 'Priya Nair' }), 'Priya Nair');
    assert.equal(T.who({ action: 'LOCK_EXPIRED' }), 'The platform');
});

test('the text never adds a target id', () => {
    const text = say({ action: 'VM_STOP_COMPLETED', targetName: 'web-1', targetId: 'i-0abc123' });
    assert.equal(text, 'Stopped web-1');
    assert.ok(!say({ action: 'VM_UPDATED', targetId: 'i-0abc123' }).includes('i-0'));
});

test('the counts line and the request', () => {
    const parts = plain(L.statsParts({ total: 230, successRate: 99, failures: 2, topEnvironment: { name: 'CRM UAT', count: 90 } },
        { timeRange: '7d' }));
    assert.deepEqual(parts.map(p => `${p.prefix || ''}${p.value}${p.label ? ' ' + p.label : ''}`),
        ['230 actions in 7 days', '99% succeeded', '2 failed', 'busiest: CRM UAT']);
    assert.deepEqual(plain(L.statsParts({ total: 0 }, { timeRange: '24h' })).length, 1);
    const p = L.filterParams({ from: 'a', to: 'b', category: 'LOCKS', resultStatus: 'false', environmentId: '' });
    assert.equal(p.toString(), 'from=a&to=b&category=LOCKS&success=false');
});

test('when: today, yesterday, a weekday this week, else the date', () => {
    const now = new Date(2026, 9, 21, 12, 0); // Wednesday
    assert.equal(L.whenText(new Date(2026, 9, 21, 8, 2), now), 'Today 08:02');
    assert.equal(L.whenText(new Date(2026, 9, 20, 19, 0), now), 'Yesterday 19:00');
    assert.equal(L.whenText(new Date(2026, 9, 16, 19, 0), now), 'Fri 19:00');
    assert.equal(L.whenText(new Date(2026, 9, 12, 9, 5), now), '12 Oct 09:05');
});
