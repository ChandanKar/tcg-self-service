// Run with: node --test "src/test/js/**/*.test.mjs"
// U15-T09: Chargeback's Unassigned banner, environment costs, the 100% check and last month's shares.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/chargeback.js', 'utf8'), context);
const M = context.Chargeback.model;
const plain = x => JSON.parse(JSON.stringify(x));

const STATEMENT = { rows: [
    { teamId: 't1', total: 350, environments: [{ environmentId: 'a', amount: 300 }, { environmentId: 's', amount: 50 }] },
    { teamId: 't2', total: 30, environments: [{ environmentId: 's', amount: 30 }] },
    { teamId: null, total: 212, environments: [{ environmentId: 'u1', amount: 200 }, { environmentId: 'u2', amount: 12 }] }] };

test('the Unassigned row gives the banner its environments and amount', () => {
    const gap = plain(M.unassigned(STATEMENT));
    assert.equal(gap.environments.length, 2);
    assert.equal(gap.amount, 212);
    assert.deepEqual(plain(M.unassigned({ rows: [] })), { environments: [], amount: 0 });
});

test("an environment's whole cost adds every team's share of it", () => {
    assert.deepEqual(plain(M.environmentCosts(STATEMENT)), { a: 300, s: 80, u1: 200, u2: 12 });
});

test('fixed percentages: 100% ✓, or how far off', () => {
    assert.deepEqual(plain(M.percentCheck([40, 25, 20, 15])), { ok: true, text: '100% ✓' });
    assert.deepEqual(plain(M.percentCheck([40, 25, 20, 10])), { ok: false, text: '95%, needs 100%' });
    assert.deepEqual(plain(M.percentCheck([33.33, 33.33, 33.33])), { ok: false, text: '99.99%, needs 100%' });
    assert.equal(M.percentCheck([60, '']).text, 'Give every team a share above 0%');
});

test("what each team would have paid last month", () => {
    assert.deepEqual(plain(M.shareAmounts('FIXED_PERCENT', 1920, [40, 25])), [768, 480]);
    assert.deepEqual(plain(M.shareAmounts('EVEN', 640, ['', '', ''])).map(Math.round), [213, 213, 213]);
    assert.deepEqual(plain(M.shareAmounts('PROPORTIONAL_DIRECT', 1110, [''])), [null]);
    assert.deepEqual(plain(M.shareAmounts('EVEN', null, [''])), [null]);
});

test('U23-T02: the statement names its month and days; the current month gives Cost management\'s month to date', () => {
    const october = { month: '2026-10', dataFrom: '2026-10-01', dataThrough: '2026-10-31' };
    assert.equal(M.periodText(october, false, null), 'October 2026, 1 to 31 Oct, from the daily cost snapshots.');
    const november = { month: '2026-11', dataFrom: '2026-11-01', dataThrough: '2026-11-12' };
    assert.equal(M.periodText(november, true, { monthToDate: 2612.4 }),
        "November 2026, 1 to 12 Nov, from the daily cost snapshots. Cost management's month to date, $2,612, adds today so far.");
    assert.equal(M.periodText(november, true, null), 'November 2026, 1 to 12 Nov, from the daily cost snapshots.');
    assert.equal(M.periodText({ month: '2026-09', dataThrough: null }, false, null), ''); // no data, or finalised before U23
});
