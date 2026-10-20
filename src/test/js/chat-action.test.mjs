// Run with: node --test "src/test/js/**/*.test.mjs"
// U09-T08: the Teams confirm page's times, lease sentence, outcome states and Done text.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
vm.runInContext(readFileSync(STATIC + 'js/features/chat-action.js', 'utf8') + '\nthis.ChatActionPage = ChatActionPage;', context);
const M = context.ChatActionPage.model;
const plain = x => JSON.parse(JSON.stringify(x));
const now = new Date(2026, 9, 21, 15, 0);
const at = (d, h, m = 0) => new Date(2026, 9, 21 + d, h, m).toISOString();

test('times: today, tomorrow, this week, later', () => {
    assert.equal(M.when(at(0, 18), now), '18:00 today');
    assert.equal(M.when(at(1, 10, 12), now), '10:12 tomorrow');
    assert.equal(M.when(at(2, 9), now), 'Fri 09:00');
    assert.equal(M.when(at(30, 9), now), '20 Nov 09:00');
});

test('the lease sentence follows the chosen hours, in the reader\'s time', () => {
    assert.equal(M.extendSentence(at(0, 18), 120, now), 'It stops at 18:00 today; 2 hours more would be 20:00.');
    assert.equal(M.extendSentence(at(0, 18), 60, now), 'It stops at 18:00 today; 1 hour more would be 19:00.');
    assert.equal(M.extendSentence(at(0, 23), 240, now), 'It stops at 23:00 today; 4 hours more would be 03:00 tomorrow.');
});

test('outcome states', () => {
    assert.deepEqual(plain(M.outcome('LINK_USED')), { tone: 'gold', title: 'This link was already used', body: 'Each link works once.' });
    assert.equal(M.outcome('LINK_EXPIRED').title, 'This link has expired');
    assert.deepEqual(plain(M.outcome('NOT_ALLOWED', 'You can view Payments Dev but not change its lease. Ask its admin, Sanjay Rao.')),
        { tone: 'red', title: "You can't do this", body: 'You can view Payments Dev but not change its lease. Ask its admin, Sanjay Rao.' });
    assert.equal(M.outcome(null, 'boom').title, 'Something went wrong');
});

test('done text uses the new end when the server sends it', () => {
    assert.equal(M.doneText({ type: 'EXTEND_LEASE', endsAt: at(0, 20), message: 'x' }, 'Payments Dev', now), 'Payments Dev now stops at 20:00 today.');
    assert.equal(M.doneText({ type: 'SNOOZE_IDLE_STOP', endsAt: at(0, 19), message: 'x' }, 'Payments Dev', now),
        'Idle auto-stop is paused for Payments Dev until 19:00 today.');
    assert.equal(M.doneText({ type: 'STOP_ENVIRONMENT', message: 'Stopping Payments Dev.' }, 'Payments Dev', now), 'Stopping Payments Dev.');
});
