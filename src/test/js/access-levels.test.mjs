// Run with: node --test "src/test/js/**/*.test.mjs"
// U10-T03: one access-level vocabulary: labels, descriptions, chip classes and when access ends.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
vm.runInContext(readFileSync(STATIC + 'js/core/access-levels.js', 'utf8') + '\nthis.AccessLevels = AccessLevels;', context);
const A = context.AccessLevels;
const plain = x => JSON.parse(JSON.stringify(x));
const now = new Date(2026, 9, 21, 12, 0);
const at = (d, h = 12) => new Date(2026, 9, 21 + d, h).toISOString();

test('labels and descriptions follow the mockups', () => {
    assert.equal(A.label('VIEWER'), 'View only');
    assert.equal(A.label('USER'), 'Operator');
    assert.equal(A.label('ADMIN'), 'Admin');
    assert.equal(A.label('user'), 'Operator');
    assert.equal(A.label('OWNER'), 'OWNER');
    assert.equal(A.description('VIEWER'), 'See state, cost, history');
    assert.equal(A.description('USER'), 'Start, stop and lock');
    assert.equal(A.description('ADMIN'), 'Also grant access and edit');
    assert.equal(A.chipClass('ADMIN'), 'level-admin');
    assert.equal(A.chipClass('VIEWER'), 'level-viewer');
});

test('when access ends: no end, soon with days left, later with the date', () => {
    assert.deepEqual(plain(A.endsText(null, now)), { text: 'No end', soon: false, ended: false });
    assert.deepEqual(plain(A.endsText(at(1), now)), { text: '22 Oct · 1 day', soon: true, ended: false });
    assert.deepEqual(plain(A.endsText(at(7), now)), { text: '28 Oct · 7 days', soon: true, ended: false });
    assert.deepEqual(plain(A.endsText(at(0, 18), now)), { text: '21 Oct · today', soon: true, ended: false });
    assert.deepEqual(plain(A.endsText(at(8), now)), { text: '29 Oct', soon: false, ended: false });
    assert.deepEqual(plain(A.endsText(new Date(2027, 0, 16).toISOString(), now)), { text: '16 Jan 2027', soon: false, ended: false });
    assert.deepEqual(plain(A.endsText(at(-18), now)), { text: 'Ended 3 Oct', soon: false, ended: true });
});

test('durations and order', () => {
    assert.deepEqual(plain(A.durationOptions().map(o => o.label)), ['7 days', '30 days', '90 days', 'No end', 'Until a date']);
    assert.equal(A.atLeast('ADMIN', 'USER'), true);
    assert.equal(A.atLeast('VIEWER', 'USER'), false);
});
