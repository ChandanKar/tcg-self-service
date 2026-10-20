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
