// Run with: node --test "src/test/js/**/*.test.mjs"
// U15-T06: the Savings page's Based on tile, Saved by bars and efficiency line.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/savings.js', 'utf8'), context);
const M = context.Savings.model;
const plain = x => JSON.parse(JSON.stringify(x));
const NOW = new Date(Date.UTC(2026, 9, 25)); // 25 Oct 2026

test('Based on: the share of the month AWS billing covers', () => {
    assert.deepEqual(plain(M.basis({ daysWithActuals: 16 }, '2026-10', NOW)), { label: 'Mostly AWS billing', note: '64% billed, the rest estimated', share: 64 });
    assert.equal(M.basis({ daysWithActuals: 6 }, '2026-09', NOW).note, '20% billed, the rest estimated'); // past month: all 30 days
    assert.equal(M.basis({ daysWithActuals: 6 }, '2026-09', NOW).label, 'Mostly estimates');
    assert.equal(M.basis(null, '2026-10', NOW).label, 'Estimates');
});

test('Saved by: the causes in order, bars against the largest, Automation only when it saved, never Other (U22-T02)', () => {
    const bars = plain(M.causeBars([{ key: 'IDLE_STOP', net: 310 }, { key: 'SCHEDULE', net: 1120 }, { key: 'AUTOMATION', net: 0 }, { key: 'OTHER', net: 900 }, { key: 'LEASE', net: -5 }]));
    assert.deepEqual(bars.map(b => [b.label, b.value]), [['Schedules', 1120], ['Idle auto-stop', 310], ['Leases ending', 0],
        ['Stopped by hand', 0], ['Rightsizing', 0]]);
    assert.equal(bars[0].width, 100);
    assert.equal(bars[1].width, 27.7);
});

test('the efficiency line runs left to right with the median at 83%', () => {
    const line = M.efficiencyLine([{ efficiencyPercent: 62 }, { efficiencyPercent: null }, { efficiencyPercent: 81 }]);
    assert.equal(line.points, '0,59.4 440,34.7'); // y = 140 - percent / 100 * 130
    assert.equal(line.median, 32.1);
    assert.deepEqual([line.first, line.last], [62, 81]);
    assert.equal(M.efficiencyLine([]).points, '');
});
