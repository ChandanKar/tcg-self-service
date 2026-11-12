// Run with: node --test "src/test/js/**/*.test.mjs"
// U22-T13: the Environments list's Schedule column words.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function load() {
    const context = vm.createContext({ Actions: { register() {} }, Utils: { debounce: f => f }, console });
    vm.runInContext(readFileSync(STATIC + 'js/features/environments.js', 'utf8') + '\nthis.Environments = Environments;', context);
    return context.Environments.model;
}

const NOW = new Date(2026, 10, 13, 10, 0); // a Friday

test('schedule: the next stop today as a time, a later day with its weekday', () => {
    const M = load();
    assert.equal(M.scheduleText({ nextScheduledStop: new Date(2026, 10, 13, 19, 0).toISOString(), scheduleRuleCount: 1 }, NOW), 'Stops 19:00');
    assert.equal(M.scheduleText({ nextScheduledStop: new Date(2026, 10, 16, 20, 30).toISOString(), scheduleRuleCount: 2 }, NOW), 'Stops Mon 20:30');
});

test('schedule: rules without a stop ahead, and none at all', () => {
    const M = load();
    assert.equal(M.scheduleText({ scheduleRuleCount: 1, nextScheduledStop: null }, NOW), 'Scheduled');
    assert.equal(M.scheduleText({ scheduleRuleCount: 0 }, NOW), 'No schedule');
    assert.equal(M.scheduleText({}, NOW), 'No schedule');
});
