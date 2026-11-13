// Run with: node --test "src/test/js/**/*.test.mjs"
// U06-T02: Home's card words and order (chip, status line, access, card choice, needs-you tiles).
// U06-T03: the recent-activity lines, results and times; the barely-used rows.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function load() {
    const context = vm.createContext({});
    vm.runInContext(readFileSync(STATIC + 'js/features/home-model.js', 'utf8') + '\nthis.HomeModel = HomeModel;', context);
    return context.HomeModel;
}

// Local times on a fixed day (Tuesday 20 Oct 2026, 17:45)
const NOW = new Date(2026, 9, 20, 17, 45);
const at = (dayOffset, h, m) => new Date(2026, 9, 20 + dayOffset, h, m).toISOString();

const card = over => Object.assign({ name: 'payments-dev', displayName: 'Payments Dev', totalVms: 0, runningVms: 0,
    transitionalVms: 0, startingVms: 0, failedVms: 0, groupStates: [] }, over);
const group = (name, running, total) => ({ name, running, total });

test('RRR|RR|SS with a lease ending at 20:00: Running, "Web group stopped · stops at 20:00"', () => {
    const H = load();
    const c = card({ totalVms: 7, runningVms: 5, leaseEndsAt: at(0, 20, 0), nextStop: at(1, 19, 0),
        groupStates: [group('Database', 3, 3), group('Application', 2, 2), group('Web', 0, 2)] });
    assert.deepEqual({ ...H.chip(c) }, { label: 'Running', kind: 'running' });
    assert.equal(H.cardLine(c, NOW), 'Web group stopped · stops at 20:00');
});

test('starting with my lock: "Starting 1 VM · locked by you until 14:30"; stopping', () => {
    const H = load();
    const c = card({ totalVms: 4, runningVms: 2, transitionalVms: 1, startingVms: 1, lockedByMe: true, lockExpiresAt: at(0, 14, 30) });
    assert.equal(H.chip(c).label, 'Starting');
    assert.equal(H.cardLine(c, NOW), 'Starting 1 VM · locked by you until 14:30');
    const stopping = card({ totalVms: 4, runningVms: 0, transitionalVms: 2, startingVms: 0 });
    assert.equal(H.chip(stopping).label, 'Stopping');
    assert.equal(H.cardLine(stopping, NOW), 'Stopping 2 VMs');
});

test('stopped with a scheduled start: "Stopped · starts Mon 08:00"; locked by someone else: only the lock', () => {
    const H = load();
    assert.equal(H.cardLine(card({ totalVms: 6, nextStart: at(6, 8, 0) }), NOW), 'Stopped · starts Mon 08:00');
    const locked = card({ totalVms: 6, lockedByName: 'Rahul Verma', locked: true });
    assert.equal(H.chip(locked).label, 'Stopped');
    assert.equal(H.cardLine(locked, NOW), 'Locked by Rahul Verma');
});

test('failures, partial runs and empty environments', () => {
    const H = load();
    const failing = card({ totalVms: 4, runningVms: 2, failedVms: 1 });
    assert.deepEqual({ ...H.chip(failing) }, { label: 'Problems', kind: 'failed' });
    assert.equal(H.cardLine(failing, NOW), '1 failed to start');
    assert.equal(H.cardLine(card({ totalVms: 6, runningVms: 3, groupStates: [group('A', 1, 2), group('B', 2, 4)] }), NOW), '3 of 6 running');
    assert.equal(H.cardLine(card({}), NOW), 'No VMs yet');
    assert.equal(H.cardLine(card({ totalVms: 2, runningVms: 2 }), NOW), 'All running');
});

test('at most two parts', () => {
    const H = load();
    const c = card({ totalVms: 4, runningVms: 2, transitionalVms: 1, startingVms: 1, lockedByName: 'Priya', nextStop: at(0, 20, 0) });
    assert.equal(H.cardLine(c, NOW).split(' · ').length, 2);
});

test('access in words', () => {
    const H = load();
    assert.equal(H.accessLabel('ADMIN'), 'Admin');
    assert.equal(H.accessLabel('USER'), 'Operator');
    assert.equal(H.accessLabel('VIEWER'), 'View only');
    assert.equal(H.accessLabel(null), 'Some groups');
});

test('cards: locked by me first, then running or changing, then by name; four at most', () => {
    const H = load();
    const cards = [
        card({ displayName: 'Zeta', totalVms: 1 }),
        card({ displayName: 'Alpha', totalVms: 1 }),
        card({ displayName: 'Running B', totalVms: 1, runningVms: 1 }),
        card({ displayName: 'Mine', totalVms: 1, lockedByMe: true }),
        card({ displayName: 'Changing', totalVms: 1, transitionalVms: 1, startingVms: 1 }),
    ];
    assert.deepEqual([...H.pickCards(cards, 4).map(c => c.displayName)], ['Mine', 'Changing', 'Running B', 'Alpha']);
});

test('needs-you tiles: a lease ending and access ending', () => {
    const H = load();
    const lease = H.needsYouItem({ kind: 'LEASE_ENDING', environmentId: 'env 1', environmentName: 'Payments Dev', at: at(0, 20, 0) }, NOW);
    assert.equal(lease.at, '20:00');
    assert.equal(lease.after, ', in 2 h 15 min');
    assert.equal(lease.action, 'Extend lease');
    assert.equal(lease.href, '#/environments/env%201?extendLease=1');
    const access = H.needsYouItem({ kind: 'ACCESS_ENDING', environmentId: 'e2', environmentName: 'CRM UAT',
        at: at(2, 9, 0), referenceId: 'acc-9' }, NOW);
    assert.equal(access.before + access.environment + access.middle + access.at, 'Your access to CRM UAT ends 22 Oct');
    assert.equal(access.action, 'Ask for more time');
    assert.equal(access.extend, 'acc-9');
    assert.equal(H.needsYouItem({ kind: 'OTHER' }, NOW), null);
});

test('header totals across cards', () => {
    const H = load();
    assert.deepEqual({ ...H.totals([card({ totalVms: 7, runningVms: 5 }), card({ totalVms: 3, runningVms: 0 })]) },
        { running: 5, total: 10, environments: 2 });
});

test('activity lines: operations with their VM count, access events in words', () => {
    const H = load();
    const op = o => Object.assign({ kind: 'OPERATION', environmentName: 'Payments Dev', totalTargets: 5 }, o);
    assert.equal(H.activityText(op({ event: 'START' })), 'Started Payments Dev (5 VMs)');
    assert.equal(H.activityText(op({ event: 'STOP', totalTargets: 1 })), 'Stopped Payments Dev (1 VM)');
    assert.equal(H.activityText(op({ event: 'RESTART', totalTargets: 0 })), 'Restarted Payments Dev');
    const acc = o => Object.assign({ kind: 'ACCESS', environmentName: 'CRM UAT', accessLevel: 'USER' }, o);
    assert.equal(H.activityText(acc({ event: 'REQUESTED' })), 'Asked for Operator access to CRM UAT');
    assert.equal(H.activityText(acc({ event: 'REQUESTED', scopeName: 'Web' })), 'Asked for Operator access to Web in CRM UAT');
    assert.equal(H.activityText(acc({ event: 'APPROVED' })), 'Access to CRM UAT approved');
    assert.equal(H.activityText(acc({ event: 'DENIED' })), 'Request for CRM UAT declined');
    assert.equal(H.activityText(acc({ event: 'EXTENDED' })), 'Access to CRM UAT extended');
    assert.equal(H.activityText(acc({ event: 'GRANTED', accessLevel: 'VIEWER' })), 'Given View only access to CRM UAT');
    assert.equal(H.activityText(acc({ event: 'UPDATED', accessLevel: 'ADMIN' })), 'Access to CRM UAT changed to Admin');
    assert.equal(H.activityText(acc({ event: 'REVOKED' })), 'Access to CRM UAT removed');
    assert.equal(H.activityText(acc({ event: 'EXPIRED' })), 'Access to CRM UAT ended');
});

test('activity results: Done, Failed, Partly done, In progress; Declined and Waiting; none otherwise', () => {
    const H = load();
    const r = o => { const x = H.resultLabel(o); return x && `${x.label}/${x.kind}`; };
    assert.equal(r({ kind: 'OPERATION', status: 'COMPLETED' }), 'Done/done');
    assert.equal(r({ kind: 'OPERATION', status: 'FAILED' }), 'Failed/failed');
    assert.equal(r({ kind: 'OPERATION', status: 'PARTIAL_SUCCESS' }), 'Partly done/failed');
    assert.equal(r({ kind: 'OPERATION', status: 'IN_PROGRESS' }), 'In progress/waiting');
    assert.equal(r({ kind: 'ACCESS', event: 'DENIED' }), 'Declined/failed');
    assert.equal(r({ kind: 'ACCESS', event: 'REQUESTED' }), 'Waiting/waiting');
    assert.equal(r({ kind: 'ACCESS', event: 'APPROVED' }), null);
});

test('activity times: 08:02 today, the weekday within a week, else the date', () => {
    const H = load();
    assert.equal(H.activityTime(at(0, 8, 2), NOW), '08:02');
    assert.equal(H.activityTime(at(-4, 9, 0), NOW), 'Fri');
    assert.equal(H.activityTime(at(-9, 9, 0), NOW), '11 Oct');
    assert.equal(H.activityTime(null, NOW), '');
});

test('barely-used rows: running only, longest idle first, at most two', () => {
    const H = load();
    const rows = H.idleRows([
        { vmId: 'a', status: 'RUNNING', idleDurationMinutes: 40 },
        { vmId: 'b', status: 'STOPPED', idleDurationMinutes: 900 },
        { vmId: 'c', status: 'RUNNING', idleDurationMinutes: 300 },
        { vmId: 'd', status: 'RUNNING', idleDurationMinutes: 90 },
    ]);
    assert.deepEqual(rows.map(v => v.vmId), ['c', 'd']);
    assert.equal(H.idleRows(null).length, 0);
});

// U16-T02: the phone Home's gold row.
test('the phone alert: the soonest lease ending within 4 hours, else nothing', () => {
    const H = load();
    const items = [
        { kind: 'LEASE_ENDING', environmentId: 'e2', environmentName: 'CRM UAT', at: at(0, 21, 30) },
        { kind: 'LEASE_ENDING', environmentId: 'e1', environmentName: 'Payments Dev', at: at(0, 20, 0) },
        { kind: 'ACCESS_ENDING', environmentName: 'X', at: at(0, 18, 0), referenceId: 'g' },
    ];
    assert.deepEqual(JSON.parse(JSON.stringify(H.phoneAlert(items, NOW))),
        { environment: 'Payments Dev', at: '20:00', href: '#/environments/e1?extendLease=1' });
    assert.equal(H.phoneAlert([{ kind: 'LEASE_ENDING', environmentId: 'e', at: at(0, 23, 0) }], NOW), null); // over 4 h away
    assert.equal(H.phoneAlert([{ kind: 'LEASE_ENDING', environmentId: 'e', at: at(0, 17, 0) }], NOW), null); // already past
    assert.equal(H.phoneAlert([], NOW), null);
});

// U23-T04: staging's Home said "1 of 3 running · stops at Fri 12:30" while the schedule stopped only Group-2.
test('a schedule on part of the environment: named, and only beside the groups it stops', () => {
    const H = load();
    const groups = [{ groupId: 'g1', name: 'Group-1', running: 1, total: 1 }, { groupId: 'g2', name: 'Group-2', running: 0, total: 1 },
        { groupId: 'g3', name: 'Group-3', running: 0, total: 1 }];
    const partialStop = { at: at(3, 12, 30), scope: 'Group-2', groupIds: ['g2'] };
    assert.equal(H.cardLine(card({ totalVms: 3, runningVms: 1, groupStates: groups, partialStop }), NOW), '1 of 3 running');
    const g2Running = groups.map(g => g.groupId === 'g2' ? Object.assign({}, g, { running: 1 }) : g);
    assert.equal(H.cardLine(card({ totalVms: 3, runningVms: 2, groupStates: g2Running, partialStop }), NOW),
        'Group-3 group stopped · Group-2 stops at Fri 12:30');
    const partialStart = { at: at(3, 11, 0), scope: 'Group-2', groupIds: ['g2'] };
    assert.equal(H.cardLine(card({ totalVms: 3, partialStart }), NOW), 'Stopped · Group-2 starts Fri 11:00');
    // The whole environment's times win.
    assert.equal(H.cardLine(card({ totalVms: 3, nextStart: at(6, 8, 0), partialStart }), NOW), 'Stopped · starts Mon 08:00');
});
