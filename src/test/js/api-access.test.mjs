// Run with: node --test "src/test/js/**/*.test.mjs"
// U14-T04: API access: what a token can do in words, last used, expiry and token counts.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/api-tokens.js', 'utf8') + '\nthis.A = ApiAccess;', context);
const M = context.A.model;

const NOW = new Date(2026, 9, 21, 15, 0); // Wednesday

test('scopes in words', () => {
    assert.equal(M.scopesText(['environments:read', 'operations:write']), 'Read environments, start and stop');
    assert.equal(M.scopesText(['cost:read']), 'Read cost');
    assert.equal(M.scopesText(['assistant:read']), 'Ask the assistant');
    assert.equal(M.scopesText([]), '—');
});

test('last used: today, yesterday, a weekday, a date, never', () => {
    assert.equal(M.used(new Date(2026, 9, 21, 6, 0).toISOString(), NOW), 'Today 06:00');
    assert.equal(M.used(new Date(2026, 9, 20, 18, 30).toISOString(), NOW), 'Yesterday 18:30');
    assert.equal(M.used(new Date(2026, 9, 19, 9, 0).toISOString(), NOW), 'Mon 09:00');
    assert.equal(M.used(new Date(2026, 8, 1, 9, 0).toISOString(), NOW), '1 Sept');
    assert.equal(M.used(null, NOW), 'Never');
});

test('expiry and token counts', () => {
    assert.equal(M.day(new Date(2027, 0, 18).toISOString()), '18 Jan 2027');
    assert.equal(M.tokensText(2), '2 active tokens');
    assert.equal(M.tokensText(1), '1 active token');
    assert.equal(M.tokensText(0), 'no tokens');
});
