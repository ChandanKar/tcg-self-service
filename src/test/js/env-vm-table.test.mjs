// Run with: node --test "src/test/js/**/*.test.mjs"
// U07-T03: the Groups and VMs table's words and rules (state, CPU, row and group actions, start order).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function load() {
    const context = vm.createContext({});
    vm.runInContext(readFileSync(STATIC + 'js/features/env-vm-table.js', 'utf8') + '\nthis.EnvVmTable = EnvVmTable;', context);
    return context.EnvVmTable.model;
}

const NOW = new Date(2026, 9, 20, 17, 45);

test('state: running since a time today or a date before; transitional, failed and stopped words', () => {
    const M = load();
    const s = (status, stateSince) => ({ ...M.state({ status, stateSince }, NOW) });
    assert.deepEqual(s('RUNNING', new Date(2026, 9, 20, 8, 2).toISOString()), { text: 'Running since 08:02', kind: 'running' });
    assert.deepEqual(s('RUNNING', new Date(2026, 9, 16, 9, 0).toISOString()), { text: 'Running since 16 Oct', kind: 'running' });
    assert.deepEqual(s('RUNNING', null), { text: 'Running', kind: 'running' });
    assert.deepEqual(s('STARTING'), { text: 'Starting', kind: 'waiting' });
    assert.deepEqual(s('ERROR'), { text: 'Failed to start', kind: 'failed' });
    assert.deepEqual(s('STOPPED'), { text: 'Stopped', kind: 'stopped' });
    assert.deepEqual(s('UNKNOWN'), { text: 'Unknown', kind: 'unknown' });
});

test('CPU 24 h: a whole percent, a dash without samples', () => {
    const M = load();
    assert.equal(M.cpu({ cpuAvg24h: 23.4 }), '23%');
    assert.equal(M.cpu({ cpuAvg24h: '9.6' }), '10%');
    assert.equal(M.cpu({ cpuAvg24h: null }), '—');
});

test('row actions: Stop a running VM, Retry a failed one, Start others; nothing for viewers', () => {
    const M = load();
    assert.equal(M.vmAction({ status: 'RUNNING' }, true), 'stop');
    assert.equal(M.vmAction({ status: 'ERROR' }, true), 'retry');
    assert.equal(M.vmAction({ status: 'STOPPED' }, true), 'start');
    assert.equal(M.vmAction({ status: 'STOPPING' }, true), 'busy');
    assert.equal(M.vmAction({ status: 'RUNNING' }, false), null);
});

test('group actions: Stop group when all run, Start group when none, both when some; none for viewers', () => {
    const M = load();
    const vms = (...st) => st.map(status => ({ status }));
    assert.deepEqual([...M.groupActions(vms('RUNNING', 'RUNNING'), true)], ['stop']);
    assert.deepEqual([...M.groupActions(vms('STOPPED', 'ERROR'), true)], ['start']);
    assert.deepEqual([...M.groupActions(vms('RUNNING', 'STOPPED'), true)], ['start', 'stop']);
    assert.deepEqual([...M.groupActions(vms('STARTING', 'STOPPED'), true)], ['busy']);
    assert.deepEqual([...M.groupActions(vms('RUNNING'), false)], []);
});

test('start order text names the groups it waits for', () => {
    const M = load();
    const names = { g1: 'Database', g2: 'Application' };
    assert.equal(M.orderText({ sequencePosition: 1, dependsOnGroupIds: [] }, names), 'Start order 1');
    assert.equal(M.orderText({ sequencePosition: 3, dependsOnGroupIds: '["g2"]' }, names), 'Start order 3 · after Application');
    assert.equal(M.orderText({ sequencePosition: 2, dependsOnGroupIds: ['gone'] }, names), 'Start order 2 · after removed group');
});

// U07-T04: large environments
const many = (status, n) => Array.from({ length: n }, () => ({ status }));

test('counts: running, stopped and failed to start across VMs', () => {
    const M = load();
    const vms = [...many('RUNNING', 44), ...many('STOPPED', 9), ...many('ERROR', 1)];
    assert.deepEqual({ ...M.countByState(vms) }, { running: 44, stopped: 9, failed: 1, total: 54 });
});

test('filters: running, stopped, problems (failed, gone, drifted), all', () => {
    const M = load();
    const vms = [{ status: 'RUNNING' }, { status: 'STOPPED' }, { status: 'ERROR' }, { status: 'NOT_FOUND' },
        { status: 'RUNNING', stateDriftDetected: true }];
    assert.equal(M.filterVms(vms, 'running').length, 2);
    assert.equal(M.filterVms(vms, 'stopped').length, 1);
    assert.equal(M.filterVms(vms, 'problems').length, 3);
    assert.equal(M.filterVms(vms, 'all').length, 5);
    assert.equal(M.filterVms(vms, 'bogus').length, 5);
});

test('paging: 10 per page, the second page of 18 is 11–18, out-of-range pages clamp', () => {
    const M = load();
    const list = Array.from({ length: 18 }, (_, i) => i + 1);
    const second = M.pageOf(list, 1);
    assert.deepEqual([second.from, second.to, second.total, second.pages, second.page], [11, 18, 18, 2, 1]);
    assert.equal(second.items.length, 8);
    assert.equal(M.pageOf(list, 9).page, 1);
    assert.deepEqual([M.pageOf([], 0).from, M.pageOf([], 0).to], [0, 0]);
});

test('group note and the group chosen first (the first with a problem)', () => {
    const M = load();
    assert.equal(M.groupNote([...many('RUNNING', 10), { status: 'ERROR' }, { status: 'STOPPED' }]), '1 failed · 1 stopped');
    assert.equal(M.groupNote([...many('RUNNING', 14), ...many('STOPPED', 4)]), '4 stopped');
    assert.equal(M.groupNote(many('RUNNING', 8)), 'All running');
    const env = { groups: [
        { group: { groupId: 'web', sequencePosition: 3 }, vms: [{ status: 'ERROR' }] },
        { group: { groupId: 'db', sequencePosition: 1 }, vms: [{ status: 'RUNNING' }] },
    ] };
    assert.equal(M.defaultGroupId(env), 'web');
    env.groups[0].vms = [{ status: 'STOPPED' }];
    assert.equal(M.defaultGroupId(env), 'db');
});
