// Run with: node --test "src/test/js/**/*.test.mjs"
// U23-T04: the environment page's Schedule fact: the sooner of the next start and stop first, and a rule on
// part of the environment named ("Group-2 stops Fri 12:30").
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function load() {
    const context = vm.createContext({ window: {}, Actions: { register() {} } });
    vm.runInContext(readFileSync(STATIC + 'js/features/env-cost.js', 'utf8') + '\nthis.EnvCost = EnvCost;', context);
    return context.EnvCost.model;
}

const when = ts => ({ '2026-11-13T07:00:00.000Z': 'Fri 12:30', '2026-11-13T05:30:00.000Z': 'Fri 11:00',
    '2026-11-16T02:30:00.000Z': 'Mon 08:00', '2026-11-16T14:30:00.000Z': 'Mon 20:00' })[ts];

test('the sooner of the next start and stop comes first', () => {
    const M = load();
    assert.equal(M.scheduleValue({ ruleCount: 1, nextStop: '2026-11-16T14:30:00.000Z', nextStart: '2026-11-16T02:30:00.000Z' }, when),
        'Next start Mon 08:00 · next stop Mon 20:00');
    assert.equal(M.scheduleValue({ ruleCount: 1, nextStop: '2026-11-13T07:00:00.000Z', nextStart: '2026-11-16T02:30:00.000Z' }, when),
        'Next stop Fri 12:30 · next start Mon 08:00');
    assert.equal(M.scheduleValue({ ruleCount: 1 }, when), 'Nothing planned');
});

test('a rule on part of the environment names its groups', () => {
    const M = load();
    const s = { ruleCount: 1, nextStop: null, nextStart: null,
        partialStop: { at: '2026-11-13T07:00:00.000Z', scope: 'Group-2' },
        partialStart: { at: '2026-11-13T05:30:00.000Z', scope: 'Group-2' } };
    assert.equal(M.scheduleValue(s, when), 'Group-2 starts Fri 11:00 · Group-2 stops Fri 12:30');
    assert.equal(M.scheduleValue(Object.assign({}, s, { nextStop: '2026-11-16T14:30:00.000Z' }), when),
        'Group-2 starts Fri 11:00 · Group-2 stops Fri 12:30 · next stop Mon 20:00');
});
