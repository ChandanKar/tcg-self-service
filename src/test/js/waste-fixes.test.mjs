// Run with: node --test "src/test/js/**/*.test.mjs"
// U15-T08: Waste › Fixes, what each row offers and when it was proposed.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/waste-fixes.js', 'utf8'), context);
const M = context.WasteFixes.model;
const plain = x => JSON.parse(JSON.stringify(x));

test('a proposal: Approve and Reject for another admin; the proposer waits', () => {
    const a = { status: 'PROPOSED', proposedBy: 'admin-a' };
    assert.deepEqual(plain(M.actionsFor(a, 'admin-b', true)), { buttons: ['approve', 'reject'], note: '' });
    assert.deepEqual(plain(M.actionsFor(a, 'admin-a', true)), { buttons: [], note: 'Another admin approves' });
    assert.deepEqual(plain(M.actionsFor(a, 'admin-b', false)), { buttons: [], note: 'Fixes are switched off' });
});

test('done fixes roll back where AWS allows; failed ones show details', () => {
    assert.deepEqual(plain(M.actionsFor({ status: 'EXECUTED', rollbackSupported: true }, 'x', true)), { buttons: ['rollback'], note: '' });
    assert.deepEqual(plain(M.actionsFor({ status: 'EXECUTED', rollbackSupported: false }, 'x', true)), { buttons: [], note: "Can't be undone" });
    assert.deepEqual(plain(M.actionsFor({ status: 'FAILED' }, 'x', true)), { buttons: ['details'], note: '' });
    assert.deepEqual(plain(M.actionsFor({ status: 'REJECTED' }, 'x', true)), { buttons: [], note: '' });
});

test('when: today with the time, a weekday within the week, else the date', () => {
    const now = new Date(2026, 9, 22, 15, 0);
    assert.equal(M.whenText(new Date(2026, 9, 22, 9, 20).toISOString(), now), 'today 09:20');
    assert.equal(M.whenText(new Date(2026, 9, 16, 9, 0).toISOString(), now), 'Fri');
    assert.equal(M.whenText(new Date(2026, 9, 9, 9, 0).toISOString(), now), '9 Oct');
    assert.equal(M.whenText(null, now), '');
});
