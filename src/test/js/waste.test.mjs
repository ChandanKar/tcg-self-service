// Run with: node --test "src/test/js/**/*.test.mjs"
// U15-T07: the Waste list's kind line per finding and the sweep schedule in the header.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/waste.js', 'utf8'), context);
const M = context.Waste.model;
const f = (findingType, details) => ({ findingType, details: details ? JSON.stringify(details) : null });

test('each finding says what it is', () => {
    assert.equal(M.kindText(f('UNATTACHED_VOLUME', { sizeGib: 500, volumeType: 'gp3' })), 'Unattached volume, 500 GiB gp3');
    assert.equal(M.kindText(f('UNATTACHED_VOLUME', null)), 'Unattached volume');
    assert.equal(M.kindText(f('UNASSOCIATED_IP', { publicIp: '13.0.0.1' })), 'Unused public IP 13.0.0.1');
    assert.equal(M.kindText(f('GP2_VOLUME', { volumeType: 'gp2', sizeGib: 200 })), 'gp2 volume, 200 GiB');
    assert.equal(M.kindText(f('LONG_STOPPED_VM', { instanceType: 'm5.2xlarge' })), 'Stopped VM, m5.2xlarge');
    assert.equal(M.kindText(f('OLD_SNAPSHOT', { startTime: '2025-03-01T00:00:00Z', sizeGib: 8 })), 'Snapshot from 2025');
    assert.equal(M.kindText({ findingType: 'OLD_SNAPSHOT', details: 'not json' }), 'Snapshot');
});

test('the header says when the sweep runs and when it last did', () => {
    const now = new Date('2026-10-22T12:00:00Z');
    assert.equal(M.scheduleText({ checked: 'Daily 04:00', lastSweepAt: '2026-10-22T04:00:00Z' }, now), 'Checked daily at 04:00 UTC; last check today.');
    assert.equal(M.scheduleText({ checked: 'Every 6 hours', lastSweepAt: '2026-10-19T04:00:00Z' }, now), 'Checked every 6 hours; last check 3 days ago.');
    assert.equal(M.scheduleText({ checked: 'Daily 04:00', lastSweepAt: null }, now), 'Checked daily at 04:00 UTC; not checked yet.');
});
