// Run with: node --test "src/test/js/**/*.test.mjs"
// U08-T05: the VM details panel's header line, stats, Overview fields, Use tiles and volumes.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function load() {
    const context = vm.createContext({});
    vm.runInContext(readFileSync(STATIC + 'js/features/vm-details.js', 'utf8') + '\nthis.VmDetails = VmDetails;', context);
    return context.VmDetails.model;
}

// Values made in the vm context have its prototypes; compare them as plain data.
const plain = x => JSON.parse(JSON.stringify(x));

const vmRow = { vmId: 'v1', name: 'pay-api-01', groupId: 'g-app', groupName: 'Application', status: 'RUNNING',
    privateIp: '10.20.2.31', region: 'ap-south-1', purchaseOption: 'ON_DEMAND', purpose: 'Payments API',
    lastStateSyncAt: '2026-10-20T07:56:00Z' };
const inventory = { instanceType: 'm6i.large', vcpuCount: 2, memoryMib: 8192, availabilityZone: 'ap-south-1b',
    privateIp: '10.20.2.31', publicIp: null, launchTime: '2026-09-14T10:00:00Z',
    volumes: [{ deviceName: '/dev/xvda', volumeType: 'gp3', sizeGib: 100 }, { deviceName: '/dev/sdf', volumeType: 'gp3', sizeGib: 200, volumeId: 'vol-0abc' }] };

test('header line: environment, group and its place in the start order', () => {
    const M = load();
    const env = { displayName: 'Payments Dev', groups: [
        { group: { groupId: 'g-web', sequencePosition: 3 } }, { group: { groupId: 'g-db', sequencePosition: 1 } },
        { group: { groupId: 'g-app', name: 'app', displayName: 'Application', sequencePosition: 2 } }] };
    assert.equal(M.kicker(env, vmRow), 'Payments Dev · Application group · start order 2');
    assert.equal(M.kicker(null, { ...vmRow, groupName: 'app' }), 'app group');
});

test('stats and Overview, in the board order; disk use is not collected', () => {
    const M = load();
    const metrics = { latest: { cpuUtilization: 31.4 } };
    assert.deepEqual(plain(M.stats(vmRow, inventory, metrics)).map(s => [s.label, s.value]),
        [['Size', 'm6i.large'], ['CPU now', '31%'], ['IP address', '10.20.2.31'], ['Disk used', 'Not collected']]);
    const now = new Date('2026-10-20T08:00:00Z');
    assert.deepEqual(plain(M.overview(vmRow, inventory, now)).map(f => [f.label, f.value]), [
        ['Size', 'm6i.large'], ['vCPU / memory', '2 / 8 GiB'], ['Region / zone', 'ap-south-1b'], ['Private IP', '10.20.2.31'],
        ['Public IP', 'None'], ['Purchase', 'On-demand'], ['Purpose', 'Payments API'], ['Launched', '14 Sep 2026'],
        ['State read from AWS', '4 min ago'], ['Cost this month', '—']]);
    assert.equal(M.purchaseText('SPOT'), 'Spot');
});

test('advice: cost this month, purchase from the advice, and the rightsizing note', () => {
    const M = load();
    const now = new Date('2026-10-20T08:00:00Z');
    const fields = plain(M.overview(vmRow, inventory, now, { monthToDateCost: 38.2, purchaseOption: 'SPOT' }));
    assert.equal(fields.find(f => f.label === 'Cost this month').value, '$38.20');
    assert.equal(fields.find(f => f.label === 'Purchase').value, 'Spot');
    assert.deepEqual(plain(M.rightsizingNote({ recommendedType: 'm6i.medium', direction: 'SCALE_DOWN', cpuPeak: 18, memoryPeak: 41,
        monthlySavings: 31.0, optimizerAgrees: true })), { title: 'Could be one size smaller: m6i.medium',
        text: 'CPU peaked at 18% and memory at 41%. Saves about $31 a month. AWS Compute Optimizer agrees.', kind: 'SCALE_DOWN' });
    assert.equal(M.rightsizingNote({ recommendedType: 'm6i.xlarge', direction: 'SCALE_UP', cpuPeak: 97, monthlySavings: -70 }).text,
        'CPU peaked at 97%. Costs about $70 more a month.');
    assert.equal(M.rightsizingNote(null), null);
});

test('Use tiles: CPU with its peak, memory needs the agent, rates per second over the period', () => {
    const M = load();
    const series = [10, 58, 31].map((c, i) => ({ cpuUtilization: c, networkInBytes: 300 * 1024 * 1024 * (i + 1), diskReadBytes: 0 }));
    const tiles = plain(M.tiles({ periodSeconds: 300, series, latest: series[2] }));
    assert.deepEqual(tiles.map(t => t.label), ['CPU', 'Memory', 'Network in', 'Network out', 'Disk read', 'Disk write']);
    assert.deepEqual([tiles[0].value, tiles[0].caption], ['31%', 'peak 58%']);
    assert.equal(tiles[1].note, 'Needs the CloudWatch agent on the VM');
    assert.deepEqual([tiles[2].value, tiles[2].caption], ['3 MB', 'per second']);
    assert.equal(tiles[3].value, '—');
    assert.equal(tiles[4].value, '0 B');
    const withMemory = plain(M.tiles({ series: [{ memoryUtilization: 40 }, { memoryUtilization: 41 }], latest: { memoryUtilization: 41 } }));
    assert.deepEqual([withMemory[1].value, withMemory[1].caption], ['41%', 'peak 41%']);
});

test('line points span the box, highest value at the top; nothing for one value', () => {
    const M = load();
    assert.equal(M.points([0, 10], 240, 40), '0.0,37.0 240.0,3.0');
    assert.equal(M.points([5]), '');
});

test('volumes: the boot disk is the root disk; IDs only when sent', () => {
    const M = load();
    assert.deepEqual(plain(M.volumes(inventory)), [
        { name: 'Root disk', type: 'gp3', size: '100 GiB', volumeId: null, encrypted: false },
        { name: 'Data disk /dev/sdf', type: 'gp3', size: '200 GiB', volumeId: 'vol-0abc', encrypted: false }]);
    assert.deepEqual(plain(M.volumes(null)), []);
});

test('idle line and the small formatters', () => {
    const M = load();
    assert.deepEqual(plain(M.idleLine({ idle: { idle: true, idleDurationMinutes: 75, reason: 'CPU under 5%' } })),
        { idle: true, text: 'Idle for 1 h 15 min', note: 'CPU under 5%' });
    assert.deepEqual(plain(M.idleLine({ latest: { cpuUtilization: 31 } })), { idle: false, text: 'In use', note: 'CPU 31% now' });
    assert.equal(M.bytes(1288490), '1.2 MB');
    assert.equal(M.ago(null), 'never');
});
