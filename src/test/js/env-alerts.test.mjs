// Run with: node --test "src/test/js/**/*.test.mjs"
// U07-T07: the environment alert stack's order, stale-sync detection and drift wording.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function load() {
    const context = vm.createContext({});
    vm.runInContext(readFileSync(STATIC + 'js/features/env-alerts.js', 'utf8') + '\nthis.EnvAlerts = EnvAlerts;', context);
    return context.EnvAlerts.model;
}

test('most urgent first: red, navy, gold, grey; within a tone budget, lock, approval, lease, cost, drift, sync', () => {
    const M = load();
    const a = (kind, tone) => ({ kind, tone });
    const ranked = M.rank([a('sync', 'grey'), a('drift', 'gold'), a('lease', 'gold'), a('approval', 'navy'),
        a('lock', 'red'), a('cost', 'gold'), a('budget', 'red'), null]);
    assert.deepEqual(ranked.map(x => x.kind), ['budget', 'lock', 'approval', 'lease', 'cost', 'drift', 'sync']);
});

test('stale sync: the newest read older than 15 minutes; never-read VMs do not count', () => {
    const M = load();
    const now = Date.parse('2026-10-20T12:00:00Z');
    const at = m => new Date(now - m * 60000).toISOString();
    assert.equal(M.staleSync([{ lastStateSyncAt: at(120) }, { lastStateSyncAt: at(130) }], now).stale, true);
    assert.equal(M.staleSync([{ lastStateSyncAt: at(120) }, { lastStateSyncAt: at(4) }], now).stale, false);
    assert.equal(M.staleSync([{ lastStateSyncAt: null }, {}], now).stale, false);
    const alert = M.syncAlert([{ lastStateSyncAt: at(120) }], false, now);
    assert.equal(alert.title, 'VM states may be out of date.');
    assert.equal(alert.detail, 'They were last read from AWS 2 hours ago; the platform keeps trying.');
    assert.equal(alert.action, null);
    assert.equal(M.syncAlert([{ lastStateSyncAt: at(120) }], true, now).action.href, '#/system-health');
});

test('drift names the first changed VM and counts the rest', () => {
    const M = load();
    assert.equal(M.driftAlert([{ name: 'a', stateDriftDetected: false }]), null);
    const one = M.driftAlert([{ vmId: 'v1', name: 'crm-web-01', stateDriftDetected: true }]);
    assert.equal(one.title, '1 VM changed outside the platform:');
    assert.equal(one.detail, 'crm-web-01 is not in the state the platform last set.');
    assert.equal(one.action.data['vm-id'], 'v1');
    const two = M.driftAlert([{ vmId: 'v1', name: 'a', stateDriftDetected: true }, { vmId: 'v2', name: 'b', stateDriftDetected: true }]);
    assert.equal(two.title, '2 VMs changed outside the platform:');
    assert.equal(two.detail, 'a and 1 more are not in the state the platform last set.');
});

test('ago in minutes, hours and days', () => {
    const M = load();
    const now = 1_000_000_000;
    assert.equal(M.ago(now - 45 * 60000, now), '45 minutes ago');
    assert.equal(M.ago(now - 120 * 60000, now), '2 hours ago');
    assert.equal(M.ago(now - 72 * 3600000, now), '3 days ago');
});
