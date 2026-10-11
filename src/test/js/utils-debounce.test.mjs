// Run with: node --test "src/test/js/**/*.test.mjs"
// Utils.debounce keeps `this` and the latest arguments (jQuery handlers read the element with $(this)).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const utilsPath = fileURLToPath(new URL('../../main/resources/static/js/core/utils.js', import.meta.url));

function loadUtils() {
    const context = vm.createContext({ window: {}, document: {}, URLSearchParams, Intl, setTimeout, clearTimeout });
    vm.runInContext(readFileSync(utilsPath, 'utf8') + '\nthis.Utils = Utils;', context);
    return context.Utils;
}

const Utils = loadUtils();

test('debounce calls once, with the caller\'s this and the last arguments', async () => {
    const calls = [];
    const handler = Utils.debounce(function (value) { calls.push({ self: this, value }); }, 20);
    const input = { id: 'user-search' };
    handler.call(input, 'd');
    handler.call(input, 'do');
    handler.call(input, 'dora');
    await new Promise(resolve => setTimeout(resolve, 60));
    assert.equal(calls.length, 1);
    assert.equal(calls[0].self, input);
    assert.equal(calls[0].value, 'dora');
});
