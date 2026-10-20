// Run with: node --test "src/test/js/**/*.test.mjs"
// U08-T03: the progress dialog's stages, VM lines, live line and finished-run words.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function load() {
    const context = vm.createContext({});
    vm.runInContext(readFileSync(STATIC + 'js/features/operation-stages.js', 'utf8') + '\nthis.OperationStages = OperationStages;', context);
    return context.OperationStages;
}

// Values made in the vm context have its prototypes; compare them as plain data.
const plain = x => JSON.parse(JSON.stringify(x));

const T0 = Date.parse('2026-10-20T10:00:00Z');
const at = s => new Date(T0 + s * 1000).toISOString();
const step = (seq, group, name, status, start, end, extra = {}) => ({
    sequencePosition: seq, groupId: 'g-' + group, groupName: group, targetName: name, status,
    startedAt: start === null ? null : at(start), completedAt: end === null ? null : at(end), ...extra
});

// Database done, Application starting, Web waiting: the board's run.
const run = {
    operationType: 'START', status: 'IN_PROGRESS',
    details: [
        step(1, 'Database', 'crm-db-01', 'COMPLETED', 0, 41),
        step(2, 'Database', 'crm-db-02', 'COMPLETED', 0, 48),
        step(3, 'Application', 'crm-api-01', 'COMPLETED', 48, 87),
        step(4, 'Application', 'crm-api-02', 'IN_PROGRESS', 48, null),
        step(5, 'Application', 'crm-api-03', 'in_progress', 48, null),
        step(6, 'Web', 'crm-web-01', 'PENDING', null, null)
    ]
};
const now = T0 + 102 * 1000;

test('stages in run order with done, starting and waiting tags', () => {
    const S = load();
    const list = plain(S.stages(run, now));
    assert.deepEqual(list.map(s => [s.order, s.name, s.tag, s.tone]), [
        ['1st', 'Database', 'Done in 0:48', 'done'],
        ['2nd', 'Application', 'Starting', 'busy'],
        ['3rd', 'Web', 'Waits for Application', 'wait']
    ]);
    assert.deepEqual(list[0].vms.map(v => v.state), ['Running · 0:41', 'Running · 0:48']);
    assert.deepEqual(list[1].vms.map(v => [v.light, v.state]), [['R', 'Running · 0:39'], ['B', 'Starting · 0:54'], ['B', 'Starting · 0:54']]);
    assert.deepEqual(list[2].vms.map(v => [v.light, v.state]), [['S', 'Waiting']]);
});

test('live line: the count and what moves now', () => {
    const S = load();
    assert.deepEqual(plain(S.liveLine(run)), { done: 3, total: 6, word: 'running', text: 'Application is starting; Web waits for it' });
    const stop = { operationType: 'STOP', details: [step(1, 'Web', 'w', 'IN_PROGRESS', 0, null)] };
    assert.deepEqual(plain(S.liveLine(stop)), { done: 0, total: 1, word: 'stopped', text: 'Web is stopping' });
    assert.deepEqual(plain(S.doneCount({ completedTargets: 1, totalTargets: 2, details: [] })), { done: 1, total: 2 });
});

test('a failed step shows the server reason; skipped ones say why', () => {
    const S = load();
    const partial = {
        operationType: 'START', status: 'PARTIAL_SUCCESS',
        details: [
            step(1, 'Database', 'db-01', 'FAILED', 0, 30, { errorMessage: 'AWS has no m5.large capacity in eu-west-1a right now' }),
            step(2, 'Web', 'web-01', 'SKIPPED', null, null, { errorMessage: 'Database did not start' })
        ]
    };
    const list = plain(S.stages(partial, now));
    assert.equal(list[0].tag, '1 failed');
    assert.equal(list[0].vms[0].state, 'Failed: AWS has no m5.large capacity in eu-west-1a right now');
    assert.equal(list[0].vms[0].light, 'E');
    assert.deepEqual([list[1].tag, list[1].vms[0].state], ['Skipped', 'Skipped: Database did not start']);
    assert.equal(S.statusText(partial), 'Done with problems: 1 failed, 1 skipped');
});

test('finished runs: done, failed with the reason, cancelled; nothing while running', () => {
    const S = load();
    assert.equal(S.statusText({ status: 'COMPLETED', details: [] }), 'Done');
    assert.equal(S.statusText({ status: 'FAILED', errorMessage: 'All 3 steps failed', details: [] }), 'Failed: All 3 steps failed');
    assert.equal(S.statusText({ status: 'CANCELLED', details: [] }), 'Cancelled');
    assert.equal(S.statusText({ status: 'IN_PROGRESS', details: [] }), null);
    assert.equal(S.statusText({ status: 'PARTIAL_SUCCESS', failedTargets: 2, details: [] }), 'Done with problems: 2 failed');
});

test('steps without a group form one stage; clock and ordinals', () => {
    const S = load();
    const list = plain(S.stages({ operationType: 'START', details: [{ targetName: 'vm-1', status: 'in_progress' }] }, now));
    assert.deepEqual(list.map(s => [s.name, s.tag]), [['VMs', 'Starting']]);
    assert.equal(S.clock(3729000), '1:02:09');
    assert.deepEqual([1, 2, 3, 4, 11].map(S.ordinal), ['1st', '2nd', '3rd', '4th', '11th']);
});
