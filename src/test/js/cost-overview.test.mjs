// Run with: node --test "src/test/js/**/*.test.mjs"
// U15-T02: Cost management's Overview: month to date, the month-end forecast, bars and biggest savings.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/cost-management.js', 'utf8') + '\nthis.C = CostManagement;', context);
const M = context.C.model;
const plain = x => JSON.parse(JSON.stringify(x));

const NOW = new Date(2026, 9, 25, 12, 0); // 25 Oct 2026: 6 days left
const day = d => `2026-10-${String(d).padStart(2, '0')}`;

test('month to date: billing where it exists, else the estimate, and the share from billing', () => {
    const trend = [{ date: '2026-09-30', estimatedCost: 999, actualCost: null }]
        .concat(Array.from({ length: 25 }, (_, i) => ({ date: day(i + 1), estimatedCost: 100, actualCost: i < 16 ? 90 : null })));
    const m = plain(M.monthToDate(trend, NOW));
    assert.equal(m.days, 25);
    assert.equal(m.value, 16 * 90 + 9 * 100);
    assert.equal(m.billedShare, 64); // acceptance: actuals for 64% of the month
    assert.equal(M.monthToDate([], NOW).days, 0);
});

test('the month-end forecast, and AWS only when it covers every day left', () => {
    const trend = Array.from({ length: 25 }, (_, i) => ({ date: day(i + 1), estimatedCost: 100, actualCost: null }));
    const aws = Array.from({ length: 6 }, (_, i) => ({ periodStart: day(26 + i), mean: 110 }));
    assert.deepEqual(plain(M.monthEnd(trend, { awsDaily: aws }, NOW)), { platform: 2500 + 6 * 100, aws: 2500 + 6 * 110 });
    assert.equal(M.monthEnd(trend, { awsDaily: aws.slice(0, 3) }, NOW).aws, null);
    assert.equal(M.monthEnd([], null, NOW).platform, null);
});

test('bars: the six largest and Other; teams this month', () => {
    const rows = Array.from({ length: 8 }, (_, i) => ({ label: `E${i}`, cost: 10 * (i + 1) }));
    const bars = plain(M.topBars(rows));
    assert.deepEqual(bars.map(b => b.label), ['E7', 'E6', 'E5', 'E4', 'E3', 'E2', 'Other']);
    assert.equal(bars[6].cost, 10 + 20);
    const teams = plain(M.teamsThisMonth([{ date: day(1), team: 'Data', estimatedCost: 5 }, { date: day(2), team: 'Data', estimatedCost: 7 },
        { date: '2026-09-30', team: 'Data', estimatedCost: 100 }, { date: day(2), team: null, estimatedCost: 3 }], NOW));
    assert.deepEqual(teams, [{ label: 'Data', cost: 12 }, { label: 'No team', cost: 3 }]);
});

test('the biggest savings across rightsizing and idle VMs', () => {
    const rows = plain(M.topSavings(
        [{ vmName: 'etl-1', environmentName: 'Data Lake Dev', currentInstanceType: 'r6i.4xlarge', suggestedInstanceType: 'r6i.2xlarge',
            peakCpuUtilization: 22.4, estimatedMonthlySavings: 540 },
            { vmName: 'api-2', environmentName: 'CRM UAT', currentInstanceType: 'm5.xlarge', suggestedInstanceType: 'm5.large', estimatedMonthlySavings: 60 }],
        [{ vmName: 'batch', environmentName: 'Billing', monthlyIdleCost: 210 }, { vmName: 'quiet', monthlyIdleCost: 0 }]));
    assert.deepEqual(rows.map(r => [r.what, r.suggestion, r.saves]), [
        ['etl-1', 'r6i.4xlarge → r6i.2xlarge, CPU peaks at 22%', 540],
        ['batch', 'Stop it when idle, or add a schedule', 210],
        ['api-2', 'm5.xlarge → m5.large', 60]]);
});
