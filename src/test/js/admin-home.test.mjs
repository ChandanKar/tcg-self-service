// Run with: node --test "src/test/js/**/*.test.mjs"
// U14-T01: the administrator's Home: attention tiles, fleet rows, spend forecast and the health line.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({ Utils: { escapeHtml: s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;') } });
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/admin-home.js', 'utf8') + '\nthis.A = AdminHome;', context);
const M = context.A.model;
const plain = x => JSON.parse(JSON.stringify(x));

const NOW = new Date(2026, 9, 19, 12, 0); // 19 Oct 2026
const hoursAgo = h => new Date(NOW.getTime() - h * 3600000).toISOString();
const NONE = { accessRequests: { count: 0 }, approvals: { count: 0 }, failedOperations: { count24h: 0, stuck: 0, environmentNames: [] },
    vmsToReview: { drift: 0, pendingReview: 0 }, budgets: { nearLimit: 0, top: null }, lateJobs: { count: 0, worst: null },
    health: { jobsEnabled: 35, jobsOnTime: 35, lastStateSyncAt: hoursAgo(0.07), driftEvents24h: 0 }, monthPlan: null };

test('tiles: the mockup six with their counts, details and links; zero counts are hidden', () => {
    const att = { ...NONE,
        accessRequests: { count: 3, oldestAt: hoursAgo(48) },
        approvals: { count: 2, oldestAt: hoursAgo(8) },
        failedOperations: { count24h: 2, stuck: 0, environmentNames: ['Analytics QA', 'CRM UAT'] },
        vmsToReview: { drift: 1, pendingReview: 2 },
        budgets: { nearLimit: 1, top: { scopeName: 'CRM team', percentUsed: 96.2 } },
        lateJobs: { count: 1, worst: { label: 'Cost snapshot', lateBySeconds: 7200 } } };
    assert.deepEqual(plain(M.tiles(att, NOW)).map(t => [t.count, t.title, t.detail, t.href, t.tone]), [
        [3, 'Access requests', 'Oldest waiting 2 days', '#/pending-requests', 'navy'],
        [2, 'Approvals', 'Oldest waiting 8 h', '#/approvals', 'navy'],
        [2, 'Failed operations, 24 h', 'Analytics QA, CRM UAT', '#/audit-logs-all?result=failed&range=24h', 'red'], // U22-T09
        [3, 'VMs to review', '1 changed outside the platform, 24 h, 2 found by discovery', '#/system-health?tab=reports&changes=outside', 'gold'], // U22-T12, U23-T09
        [1, 'Budget almost used', 'CRM team at 96%', '#/budgets', 'red'],
        [1, 'Late scheduled job', 'Cost snapshot, 2 h late', '#/system-health', 'gold']]);
    assert.deepEqual(plain(M.tiles(NONE, NOW)), []);
    // U23-T09: with nothing changed outside the platform, the tile opens the registry for the VMs found.
    assert.equal(plain(M.tiles({ ...NONE, vmsToReview: { drift: 0, pendingReview: 2 } }, NOW))[0].href, '#/vm-registry');
    assert.deepEqual(plain(M.tiles({ ...NONE, failedOperations: { count24h: 0, stuck: 1, environmentNames: [] } }, NOW))
        .map(t => [t.count, t.detail]), [[1, '1 stopped reporting']]);
});

test('U23-T10: the budget tile names the real risk, the forecast when it passes the budget', () => {
    const tile = top => plain(M.tiles({ ...NONE, budgets: { nearLimit: 1, top } }, NOW))[0];
    assert.deepEqual([tile({ scopeName: 'ISE', percentUsed: 62, forecastPercent: 131.1 })].map(t => [t.title, t.detail]),
        [['Budget heading over', 'ISE heading for 131% of its budget']]);
    assert.deepEqual([tile({ scopeName: 'CRM team', percentUsed: 96.2, forecastPercent: 99 })].map(t => [t.title, t.detail]),
        [['Budget almost used', 'CRM team at 96%']]);
    // Spent 95%, forecast 120%: almost used, and the forecast says by how much.
    assert.deepEqual([tile({ scopeName: 'Payments', percentUsed: 95, forecastPercent: 120 })].map(t => [t.title, t.detail]),
        [['Budget almost used', 'Payments heading for 120% of its budget']]);
    assert.equal(tile({ scopeName: 'Old', percentUsed: 91 }).detail, 'Old at 91%'); // no forecast field
    assert.equal(plain(M.tiles({ ...NONE, budgets: { nearLimit: 2, top: { scopeName: 'ISE', percentUsed: 62, forecastPercent: 131 } } }, NOW))[0].title,
        'Budgets heading over');
});

test('fleet: most running first, then by name; at most 7; only environments with a strip', () => {
    const card = (name, running, strip = true) => ({ environmentId: name, displayName: name, runningVms: running, groupStates: strip ? [{}] : [] });
    const cards = [card('B', 2), card('A', 2), card('C', 6), card('D', 0), card('E', 9, false),
        card('F', 1), card('G', 1), card('H', 1), card('I', 1)];
    assert.deepEqual(M.fleet(cards).map(c => c.displayName), ['C', 'A', 'B', 'F', 'G', 'H', 'I']);
});

test('spend: this month added up, forecast from the last 7 days, the plan only with budgets', () => {
    const points = [{ date: '2026-09-30', estimatedCost: 999 }]
        .concat(Array.from({ length: 18 }, (_, i) => ({ date: `2026-10-${String(i + 1).padStart(2, '0')}`, estimatedCost: i < 11 ? 300 : 350 })));
    const m = M.spend(points, 6284, { amount: 10000, budgetCount: 3 }, NOW);
    assert.equal(m.spent, 6284);
    assert.equal(m.cumulative.length, 18); // September's day is left out
    assert.equal(m.cumulative[17].value, 11 * 300 + 7 * 350);
    assert.equal(m.forecast, 6284 + 350 * 12); // 31 days, 12 after the 19th
    assert.equal(m.plan, 10000);
    assert.equal(M.spend(points, 6284, null, NOW).plan, null);
    assert.equal(M.spend([], null, null, NOW).hasData, false);
    const svg = M.spendSvg(m);
    assert.match(svg, /class="ah-plan"/);
    assert.doesNotMatch(M.spendSvg(M.spend(points, 6284, null, NOW)), /ah-plan/);
    assert.equal(M.sourceText(m, NOW), 'Estimated from instance prices');
    assert.equal(M.forecastText(m), 'Spent so far plus the average of the last 7 days ($350 a day) for the 12 days left.');
});

test('U20-T10: AWS billing where it has arrived, the estimate for the rest, and the first day with data', () => {
    const points = Array.from({ length: 9 }, (_, i) => ({ date: `2026-10-${String(i + 11).padStart(2, '0')}`,
        estimatedCost: 100, actualCost: i < 6 ? 90 : null }));
    const m = M.spend(points, 900, null, NOW);
    assert.equal(m.billedDays, 6);
    assert.equal(m.estimatedDays, 3);
    assert.equal(m.spent, 6 * 90 + 3 * 100); // the days' sum, not the estimate-only month to date
    assert.equal(m.firstDay, 11);
    assert.equal(M.sourceText(m, NOW), 'AWS billing for 6 days, estimated for 3 · data from 11 Oct');
    const all = M.spend(points.map(p => ({ ...p, actualCost: 80 })), null, null, NOW);
    assert.equal(M.sourceText(all, NOW), 'From AWS billing · data from 11 Oct');
    assert.equal(M.sourceText(M.spend([], null, null, NOW), NOW), '');
});

test('U23-T01: with the server month, spent and the forecast are the figures Cost management shows', () => {
    const points = Array.from({ length: 18 }, (_, i) => ({ date: `2026-10-${String(i + 1).padStart(2, '0')}`, estimatedCost: 100 }));
    const month = { monthToDate: 2612.4, forecast: 6490.2, dailyAverage: 215.21, averagedDays: 7, daysLeft: 12,
        billedDays: 0, estimatedDays: 18, firstDay: 1 };
    const m = M.spend(points, 9999, null, NOW, month);
    assert.equal(m.spent, 2612.4);
    assert.equal(m.forecast, 6490.2);
    assert.equal(m.cumulative.length, 18); // the line still follows the days
    assert.equal(M.sourceText(m, NOW), 'Estimated from instance prices');
    assert.equal(M.forecastText(m), 'Spent so far plus the average of the last 7 days ($215 a day) for the 12 days left.');
    // No forecast from the server (no complete day yet): spent plus the average so far.
    const early = M.spend([], null, null, NOW, { ...month, forecast: null, averagedDays: 0, dailyAverage: null, monthToDate: 190 });
    assert.equal(early.hasData, true);
    assert.equal(Math.round(early.forecast), Math.round(190 + (190 / 19) * 12));
});

test('health: healthy, a late job named (red when a day late or never ran), or a stale state sync', () => {
    assert.deepEqual(plain(M.health(NONE, NOW)), { tone: 'ok', lead: 'Platform healthy.',
        text: '35 of 35 scheduled jobs on time · state synced 4 min ago · 0 VMs changed outside the platform in 24 h' }); // U22-T12
    const late = M.health({ ...NONE, lateJobs: { count: 1, worst: { label: 'Cost snapshot', lateBySeconds: 7200 } },
        health: { ...NONE.health, jobsOnTime: 34 } }, NOW);
    assert.equal(late.tone, 'warn');
    assert.equal(late.lead, '1 scheduled job is late.');
    assert.match(late.text, /^Cost snapshot, 2 h late · 34 of 35 scheduled jobs on time/);
    assert.equal(M.health({ ...NONE, lateJobs: { count: 2, worst: { label: 'Weekly report', lateBySeconds: null } } }, NOW).tone, 'bad');
    assert.equal(M.health({ ...NONE, health: { ...NONE.health, lastStateSyncAt: hoursAgo(2) } }, NOW).lead, 'State sync is behind.');
});

test('U19-T04: clusters without a region: a gold tile naming them, linking to the first one\'s settings', () => {
    const att = { ...NONE, regionsUnknown: { count: 2, environmentNames: ['Payments EKS', 'Data EKS'], firstEnvironmentId: 'env 1' } };
    assert.deepEqual(plain(M.tiles(att, NOW)).map(t => [t.count, t.title, t.detail, t.href, t.tone]), [
        [2, 'Clusters without a region', 'Payments EKS, Data EKS', '#/environments/env%201/settings/details', 'gold']]);
    assert.equal(M.tiles({ ...NONE, regionsUnknown: { count: 1, environmentNames: ['A'], firstEnvironmentId: 'a' } }, NOW)[0].title,
        'Cluster without a region');
    assert.deepEqual(plain(M.tiles({ ...NONE, regionsUnknown: { count: 0, environmentNames: [], firstEnvironmentId: null } }, NOW)), []);
});
