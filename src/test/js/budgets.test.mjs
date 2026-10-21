// Run with: node --test "src/test/js/**/*.test.mjs"
// U12-T07: the Budgets page's chips, totals, action words and alert thresholds (Budgets.dc.html).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({ Intl, Date });
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/budgets.js', 'utf8') + '\nthis.B = Budgets;', context);
const M = context.B.model;
const plain = x => JSON.parse(JSON.stringify(x));

test('the chip: reached from 90%, then forecast over, else on track', () => {
    assert.deepEqual(plain(M.chipOf({ percentUsed: 46.5, forecastPercent: 77.5 })), { text: 'On track', tone: 'ok' });
    assert.deepEqual(plain(M.chipOf({ percentUsed: 81.3, forecastPercent: 136.7 })), { text: 'Forecast over', tone: 'warn' });
    assert.deepEqual(plain(M.chipOf({ percentUsed: 96, forecastPercent: 156.7 })), { text: '96% reached', tone: 'bad' });
    assert.deepEqual(plain(M.chipOf({ percentUsed: 104.2 })), { text: '104% reached', tone: 'bad' });
});

test('what happens when reached', () => {
    assert.equal(M.actionText({ scopeType: 'ENVIRONMENT', actionType: 'NONE' }), 'Alerts only');
    assert.equal(M.actionText({ scopeType: 'ENVIRONMENT', actionType: 'FREEZE_STARTS' }, true), 'When reached, an owner approves freezing starts');
    assert.equal(M.actionText({ scopeType: 'ENVIRONMENT', actionType: 'STOP_ENVIRONMENT' }, true), 'When reached, an owner approves stopping it');
    assert.equal(M.actionText({ scopeType: 'ENVIRONMENT', actionType: 'STOP_ENVIRONMENT' }, false),
        'When reached, an owner approves stopping it (switched off by an administrator)');
    assert.equal(M.actionText({ scopeType: 'TEAM', actionType: 'NONE' }), 'Alerts only (team budgets cannot freeze or stop)');
    assert.equal(M.kindText({ scopeType: 'TEAM', environmentCount: 3 }), 'team · 3 environments');
    assert.equal(M.kindText({ scopeType: 'ENVIRONMENT' }), 'environment');
});

test('totals over environment budgets, and the day of the month', () => {
    const t = plain(M.totalsOf([
        { scopeType: 'ENVIRONMENT', enabled: true, spendToDate: 186, forecastMonthEnd: 310 },
        { scopeType: 'ENVIRONMENT', enabled: true, spendToDate: 122, forecastMonthEnd: 205 },
        { scopeType: 'TEAM', enabled: true, spendToDate: 288, forecastMonthEnd: 470 }]));
    assert.deepEqual(t, { spent: 308, forecast: 515, count: 2, teams: true });
    assert.equal(M.monthDay(new Date(2026, 9, 19)), 'October · day 19 of 31');
    assert.equal(M.dollars(1234.5), '$1,235');
});

test('alert thresholds: 1 to 4 different whole percentages between 1 and 200', () => {
    assert.equal(M.thresholdProblem([50, 75], '120'), '');
    assert.equal(M.thresholdProblem([50, 75], '75'), '75% is already there.');
    assert.equal(M.thresholdProblem([50], '0'), 'A whole percentage between 1 and 200.');
    assert.equal(M.thresholdProblem([50], '201'), 'A whole percentage between 1 and 200.');
    assert.equal(M.thresholdProblem([50], '12.5'), 'A whole percentage between 1 and 200.');
    assert.equal(M.thresholdProblem([50, 75, 90, 100], '120'), 'Up to 4.');
});
