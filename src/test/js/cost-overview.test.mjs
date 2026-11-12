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

// U15-T04: the Commitments and Carbon tabs.
test('commitment figures: coverage in navy, use green from 90% and gold below, with what is unused', () => {
    const f = plain(M.commitmentFigures({ spCoveragePercent: 38, spUtilizationPercent: 97, riCoveragePercent: 12.4, riUtilizationPercent: 81 }));
    assert.deepEqual(f.map(x => [x.text, x.bar, x.tone]), [['38%', 38, 'cover'], ['97%', 97, 'good'], ['12%', 12, 'cover'], ['81%', 81, 'low']]);
    assert.equal(f[0].note, 'of eligible compute spend');
    assert.equal(f[3].note, '19% of reserved hours unused');
    const none = plain(M.commitmentFigures(null));
    assert.deepEqual(none.map(x => [x.text, x.bar]), [['—', 0], ['—', 0], ['—', 0], ['—', 0]]);
});

test('what AWS suggests buying, in plain words', () => {
    assert.equal(M.recommendationDetail({ type: 'COMPUTE_SP' }), 'Covers EC2 in any region, family or size');
    assert.equal(M.recommendationDetail({ type: 'EC2_INSTANCE_SP', details: [{ instanceFamily: 'm6i', region: 'ap-south-1' }] }), 'm6i family in ap-south-1');
    assert.equal(M.recommendationDetail({ type: 'EC2_RI', details: [{ instanceType: 'c6i.xlarge', region: 'ap-south-1', count: 4 }, {}] }),
        '4 × c6i.xlarge, ap-south-1 and 1 more');
    assert.equal(M.recommendationDetail({ type: 'EC2_RI', details: [] }), '');
    assert.equal(M.termText({ term: 'THREE_YEARS', paymentOption: 'ALL_UPFRONT' }), '3 years · all upfront');
    assert.equal(M.termText({ term: 'ONE_YEAR', paymentOption: 'PARTIAL_UPFRONT' }), '1 year · partial');
});

test('carbon: the last six months as stacked bars', () => {
    const trend = Array.from({ length: 8 }, (_, i) => ({ month: `2026-0${i + 1}`, attributedKg: 100 * (i + 1), unallocatedKg: 0 }));
    const bars = plain(M.carbonBars(trend, 50));
    assert.deepEqual(bars.map(b => b.label), ['Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug']);
    assert.equal(bars[5].at, 50);
    assert.equal(bars[0].at, 19);
    assert.deepEqual(plain(M.carbonBars([])), []);
});

test('a saving reads as a saving and extra cost as extra cost: arrow, words, colour (U20-T03)', () => {
    assert.equal(M.savingHtml(120.4), '<span class="cost-delta cost-delta-save"><span class="cost-delta-arrow" aria-hidden="true">↓</span> saves $120</span>');
    assert.equal(M.savingHtml(-45), '<span class="cost-delta cost-delta-more"><span class="cost-delta-arrow" aria-hidden="true">↑</span> costs $45 more</span>');
    assert.doesNotMatch(M.savingHtml(-45), /\$-|cost-delta-save/, 'a scale-up is never a green or negative saving');
    assert.equal(M.savingHtml(0.2), '<span class="cost-delta">$0</span>');
    assert.match(M.savingHtml(null), /—/);
    assert.match(M.savingHtml(-3.5, v => `$${v.toFixed(2)}`), /costs \$3\.50 more/);
});

test('U22-T14: the EC2 instances Found in AWS lists are named under the title, with a way to review them', () => {
    const found = { items: [
        { kind: 'EC2', state: 'running' }, { kind: 'EC2', state: 'stopped' }, { kind: 'EC2', state: 'RUNNING' },
        { kind: 'EKS', state: null }] };
    assert.deepEqual(plain(M.unregisteredCount(found)), { instances: 3, running: 2 });
    const html = M.unregisteredHtml({ instances: 3, running: 2 });
    assert.match(html, /3 instances in AWS \(2 running\) are not in the registry, so their cost is not counted here\./);
    assert.match(html, /data-action="cost-found-in-aws" title="Opens Found in AWS">Review them</);
    assert.match(M.unregisteredHtml({ instances: 1, running: 0 }), /^ 1 instance in AWS is not in the registry, so its cost/);
    assert.equal(M.unregisteredHtml({ instances: 0, running: 0 }), '');
    assert.equal(M.unregisteredHtml(null), '');
    assert.deepEqual(plain(M.unregisteredCount(null)), { instances: 0, running: 0 });
});
