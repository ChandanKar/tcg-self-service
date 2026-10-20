// Run with: node --test "src/test/js/**/*.test.mjs"
// U08-T09: the idle auto-stop dialog's headline, summary, decisions and when stopping becomes available.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function load() {
    const context = vm.createContext({});
    vm.runInContext(readFileSync(STATIC + 'js/features/idle-stop.js', 'utf8') + '\nthis.IdleStop = IdleStop;', context);
    return context.IdleStop.model;
}

const plain = x => JSON.parse(JSON.stringify(x));
const now = new Date(2026, 9, 20, 12, 0);
const daysAgo = d => new Date(now.getTime() - d * 86400000).toISOString();
const dryRun = { ruleId: 'r', scopeType: 'ENVIRONMENT', mode: 'DRY_RUN', enabled: true, idleMinutes: 60, dryRunStartedAt: daysAgo(8.5) };

test('headline: dry run day of 14, on, off, production', () => {
    const M = load();
    assert.deepEqual(plain(M.headline({ minDryRunDays: 14 }, dryRun, now)),
        { title: 'Dry run · day 9 of 14', sub: 'Records what it would do; stops nothing yet' });
    assert.equal(M.headline({}, { ...dryRun, mode: 'ENFORCE' }, now).title, 'On · after 60 min idle');
    assert.equal(M.headline({}, { ...dryRun, enabled: false }, now).title, 'Off');
    assert.equal(M.headline({ production: true }, dryRun, now).title, 'Production — never stopped');
    assert.equal(M.headline({}, null, now).title, 'No rule yet');
});

test('summary of the last days, dry run and stopping', () => {
    const M = load();
    assert.equal(M.summary({ days: 14, wouldStopEpisodes: 11, wouldHaveSaved: 46 }, dryRun),
        'In the last 14 days it would have stopped the environment 11 times, saving about $46.00.');
    assert.equal(M.summary({ days: 14, stoppedCount: 1, savedEstimate: 3.5 }, { ...dryRun, mode: 'ENFORCE' }),
        'In the last 14 days it stopped the environment 1 time, saving about $3.50.');
});

test('decisions newest first with their tags', () => {
    const M = load();
    const at = h => new Date(now.getTime() - h * 3600000).toISOString();
    const list = plain(M.decisions({ recentEvents: [
        { outcome: 'WOULD_STOP', reason: 'All 7 VMs idle for 60 min', evaluatedAt: at(1) },
        { outcome: 'SKIPPED', reason: 'Locked by Priya Nair', evaluatedAt: at(30) },
        { outcome: 'SNOOZED', reason: 'Paused by Karthik S until 23:00', evaluatedAt: at(50) },
        { outcome: 'STOPPED', reason: 'Stopped 3 VMs', evaluatedAt: at(70) },
        { outcome: 'SKIPPED', reason: 'An operation is running', evaluatedAt: at(90) }] }, now));
    assert.deepEqual(list.map(d => d.tag), ['Would stop', 'Skipped: locked', 'Skipped: paused', 'Stopped', 'Skipped']);
    assert.equal(list[0].time, 'Tue 11:00');
    assert.equal(list[0].text, 'All 7 VMs idle for 60 min');
});

test('stopping is available after the dry run, at once for administrators', () => {
    const M = load();
    assert.equal(M.enforceAllowed({ minDryRunDays: 14 }, dryRun, now), false);
    const date = M.enforceDate({ minDryRunDays: 14 }, dryRun);
    assert.equal(date.getDate(), new Date(new Date(dryRun.dryRunStartedAt).getTime() + 14 * 86400000).getDate());
    assert.equal(M.enforceAllowed({ minDryRunDays: 14 }, { ...dryRun, dryRunStartedAt: daysAgo(15) }, now), true);
    assert.equal(M.enforceAllowed({ minDryRunDays: 14, canEnforceEarly: true }, dryRun, now), true);
});
