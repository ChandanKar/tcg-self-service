// Run with: node --test "src/test/js/**/*.test.mjs"
// U09-T06: the notifications popover's dot colours, times, bell label and where an item leads.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function load(file, name) {
    const chain = { off() { return chain; }, on() { return chain; } };
    const context = vm.createContext({ $: () => chain, document: {} });
    vm.runInContext(readFileSync(STATIC + file, 'utf8') + `\nthis.${name} = ${name};`, context);
    return context[name].model;
}

const plain = x => JSON.parse(JSON.stringify(x));
const M = load('js/ui/notification-bell.js', 'NotificationBell');

test('dots: red for failures, gold for something ending or waiting, grey otherwise', () => {
    assert.equal(M.typeTone('OPERATION_FAILED'), 'red');
    assert.equal(M.typeTone('LEASE_EXPIRING'), 'gold');
    assert.equal(M.typeTone('APPROVAL_REQUESTED'), 'gold');
    assert.equal(M.typeTone('ACCESS_REQUEST_APPROVED'), 'grey');
    assert.equal(M.typeTone('WEEKLY_COST_REPORT'), 'grey');
    assert.equal(M.typeTone('SOMETHING_NEW'), 'grey');
});

test('times: today, this week, older', () => {
    const now = new Date(2026, 9, 21, 18, 0);
    assert.equal(M.timeText(new Date(2026, 9, 21, 17, 45).toISOString(), now), '17:45');
    assert.equal(M.timeText(new Date(2026, 9, 16, 14, 22).toISOString(), now), 'Fri 14:22');
    assert.equal(M.timeText(new Date(2026, 9, 10, 9, 0).toISOString(), now), '10 Oct');
    assert.equal(M.timeText(null, now), '');
});

test('the bell says how many are unread', () => {
    assert.equal(M.bellLabel(0), 'Notifications');
    assert.equal(M.bellLabel(2), 'Notifications, 2 unread');
});

test('items lead to their environment, request or report', () => {
    assert.deepEqual(plain(M.linkFor({ entityType: 'ENVIRONMENT', entityId: 'e1' })), ['environment-detail', { environmentId: 'e1' }]);
    assert.deepEqual(plain(M.linkFor({ entityType: 'APPROVAL_REQUEST' })), ['approvals']);
    assert.deepEqual(plain(M.linkFor({ entityType: 'COST_ANOMALY' }, true)), ['cost-management']);
    assert.deepEqual(plain(M.linkFor({ entityType: 'COST_ANOMALY' }, false)), ['my-environments']);
    assert.deepEqual(plain(M.linkFor({ type: 'WEEKLY_COST_REPORT' })), ['cost-management']);
    assert.equal(M.linkFor({ type: 'LOCK_RELEASED' }), null);
});
