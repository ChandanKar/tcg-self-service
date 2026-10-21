// Run with: node --test "src/test/js/**/*.test.mjs"
// U14-T09: System health › Fleet use: the three lines, the fleet state and the risk rows.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/fleet-use.js', 'utf8') + '\nthis.F = FleetUse;', context);
const M = context.F.model;
const plain = x => JSON.parse(JSON.stringify(x));

const MB = 1024 * 1024;
const POINTS = [
    { label: '06:00', cpuUtilization: 12, memoryUtilization: 44, networkInBytes: 30 * MB, networkOutBytes: 10 * MB },
    { label: '10:00', cpuUtilization: 41, memoryUtilization: 63, networkInBytes: 200 * MB, networkOutBytes: 30 * MB },
    { label: '17:00', cpuUtilization: 34, memoryUtilization: 59, networkInBytes: 150 * MB, networkOutBytes: 30 * MB }];

test('the three lines: now, the peak and their labels', () => {
    const [cpu, memory, network] = plain(M.charts(POINTS));
    assert.equal(cpu.now, '34%');
    assert.equal(cpu.note, 'Average of running VMs. Peak 41% at 10:00.');
    assert.equal(cpu.label, 'Average CPU over 24 hours, from 12% to 34%, peak 41%');
    assert.equal(memory.now, '59%');
    assert.equal(network.now, '180 MB');
    assert.deepEqual(network.data.values, [40, 230, 180]);
    const empty = plain(M.charts([]));
    assert.equal(empty[0].data, null);
    assert.equal(plain(M.charts([{ label: 'x', cpuUtilization: null }]))[0].data, null);
});

test('fleet state: running, stopped, moving and failed from the status counts', () => {
    const s = plain(M.states({ RUNNING: 41, STOPPED: 66, STARTING: 2, STOPPING: 1, ERROR: 1, NOT_FOUND: 1 }));
    assert.deepEqual(s.rows.map(r => [r.name, r.count]), [['Running', 41], ['Stopped', 66], ['Starting or stopping', 3], ['Failed', 2]]);
    assert.equal(s.total, 112);
    assert.deepEqual(plain(M.states({})).rows.map(r => r.name), ['Running', 'Stopped']);
});

test('risk rows in plain words with their links; nothing to show without counts', () => {
    const rows = plain(M.risks([
        { title: 'EBS encryption', description: '9 volume(s) are not encrypted.', count: 9, tone: 'danger' },
        { title: 'State drift', description: 'No state drift detected.', count: 0, tone: 'success' },
        { title: 'Idle exposure', description: '6 VM(s) may be waste candidates.', count: 6, tone: 'warning' }]));
    assert.deepEqual(rows.map(r => [r.title, r.href, r.count]), [['Unencrypted disks', '#/waste', 9], ['Idle VMs', '#/waste', 6]]);
    assert.deepEqual(plain(M.risks([{ title: 'State drift', count: 0 }])), []);
});
