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
        [2, 'Failed operations, 24 h', 'Analytics QA, CRM UAT', '#/audit-logs-all', 'red'],
        [3, 'VMs to review', '1 drift, 2 found by discovery', '#/vm-registry', 'gold'],
        [1, 'Budget almost used', 'CRM team at 96%', '#/budgets', 'red'],
        [1, 'Late scheduled job', 'Cost snapshot, 2 h late', '#/system-health', 'gold']]);
    assert.deepEqual(plain(M.tiles(NONE, NOW)), []);
    assert.deepEqual(plain(M.tiles({ ...NONE, failedOperations: { count24h: 0, stuck: 1, environmentNames: [] } }, NOW))
        .map(t => [t.count, t.detail]), [[1, '1 stopped reporting']]);
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
});

test('health: healthy, a late job named (red when a day late or never ran), or a stale state sync', () => {
    assert.deepEqual(plain(M.health(NONE, NOW)), { tone: 'ok', lead: 'Platform healthy.',
        text: '35 of 35 scheduled jobs on time · state synced 4 min ago · 0 drift in 24 h' });
    const late = M.health({ ...NONE, lateJobs: { count: 1, worst: { label: 'Cost snapshot', lateBySeconds: 7200 } },
        health: { ...NONE.health, jobsOnTime: 34 } }, NOW);
    assert.equal(late.tone, 'warn');
    assert.equal(late.lead, '1 scheduled job is late.');
    assert.match(late.text, /^Cost snapshot, 2 h late · 34 of 35 scheduled jobs on time/);
    assert.equal(M.health({ ...NONE, lateJobs: { count: 2, worst: { label: 'Weekly report', lateBySeconds: null } } }, NOW).tone, 'bad');
    assert.equal(M.health({ ...NONE, health: { ...NONE.health, lastStateSyncAt: hoursAgo(2) } }, NOW).lead, 'State sync is behind.');
});
