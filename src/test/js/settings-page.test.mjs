// Run with: node --test "src/test/js/**/*.test.mjs"
// U14-T07: the Settings page's sections, row states, number checks and the save bar's words.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/settings.js', 'utf8') + '\nthis.S = Settings;', context);
const M = context.S.model;
const plain = x => JSON.parse(JSON.stringify(x));

test('sections by their names', () => {
    assert.equal(M.sectionOf('Notifications'), 'notif');
    assert.equal(M.sectionOf('Integrations'), 'int');
    assert.equal(M.sectionOf('Data retention'), 'ret');
    assert.equal(M.sectionOf('Restart required'), null);
});

test('row states', () => {
    const flag = { editable: true, type: 'BOOLEAN', source: 'DEFAULT', value: 'true' };
    assert.deepEqual(plain(M.stateOf(flag)), { text: 'On by default', kind: 'default' });
    assert.deepEqual(plain(M.stateOf({ ...flag, value: 'false' })), { text: 'Off by default', kind: 'default' });
    assert.deepEqual(plain(M.stateOf(flag, 'false')), { text: 'Changed', kind: 'changed' });
    assert.deepEqual(plain(M.stateOf({ ...flag, source: 'OVERRIDE', updatedByName: 'Test Admin' })), { text: 'Set by Test Admin', kind: 'set' });
    assert.deepEqual(plain(M.stateOf({ editable: true, type: 'INT', source: 'DEFAULT', value: '365' })), { text: 'Default 365', kind: 'default' });
    assert.deepEqual(plain(M.stateOf({ editable: false, type: 'STRING', value: 'false' })), { text: 'Set in .env', kind: 'env' });
});

test('numbers are checked against their range', () => {
    const cpu = { type: 'DECIMAL', min: 0, max: 100 };
    assert.equal(M.numberProblem(cpu, '7.5'), null);
    assert.equal(M.numberProblem(cpu, '150'), 'Enter a number from 0 to 100.');
    assert.equal(M.numberProblem({ type: 'INT', min: 30, max: 3650 }, '40.5'), 'Enter a whole number from 30 to 3650.');
    assert.equal(M.numberProblem(cpu, ''), 'Enter a number from 0 to 100.');
});

test('the bar and the server errors on their rows', () => {
    assert.equal(M.pendingText(1), '1 change not saved');
    assert.equal(M.pendingText(2), '2 changes not saved');
    assert.deepEqual(plain(M.errorsByKey('Nothing was saved: vm.metrics.idle.cpu-threshold-percent must be between 0 and 100; '
        + 'retention.audit-log.days must be a whole number', ['vm.metrics.idle.cpu-threshold-percent', 'retention.audit-log.days', 'x'])), {
        'vm.metrics.idle.cpu-threshold-percent': 'vm.metrics.idle.cpu-threshold-percent must be between 0 and 100',
        'retention.audit-log.days': 'retention.audit-log.days must be a whole number' });
});
