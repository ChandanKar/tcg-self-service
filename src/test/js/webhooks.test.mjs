// Run with: node --test "src/test/js/**/*.test.mjs"
// U14-T05: webhook delivery wording: status labels, the detail with the next attempt, when, scope.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/webhooks.js', 'utf8') + '\nthis.W = WebhooksPanel;', context);
const M = context.W.model;
const plain = x => JSON.parse(JSON.stringify(x));

const NOW = new Date(2026, 9, 21, 18, 0); // Wednesday

test('status labels', () => {
    assert.deepEqual(plain(M.statusOf('DELIVERED')), { label: 'Delivered', kind: 'ok' });
    assert.deepEqual(plain(M.statusOf('FAILED')), { label: 'Failed, retrying', kind: 'bad' });
    assert.deepEqual(plain(M.statusOf('PENDING')), { label: 'Waiting', kind: 'wait' });
    assert.deepEqual(plain(M.statusOf('GAVE_UP')), { label: 'Gave up', kind: 'off' });
});

test('the detail: answer and attempt, with the next attempt while retrying', () => {
    assert.equal(M.detail({ status: 'DELIVERED', responseCode: 200, attempt: 1 }), '200 · attempt 1');
    assert.equal(M.detail({ status: 'FAILED', responseCode: 503, attempt: 3, nextAttemptAt: new Date(2026, 9, 21, 9, 11).toISOString() }),
        '503 · attempt 3, next at 09:11');
    assert.equal(M.detail({ status: 'GAVE_UP', error: 'timed out', attempt: 6 }), 'timed out · 6 attempts');
    assert.equal(M.detail({ status: 'PENDING', attempt: 0, nextAttemptAt: new Date(2026, 9, 21, 18, 1).toISOString() }), 'attempt 1, next at 18:01');
});

test('when and scope', () => {
    assert.equal(M.when(new Date(2026, 9, 21, 17, 45).toISOString(), NOW), '17:45');
    assert.equal(M.when(new Date(2026, 9, 20, 9, 0).toISOString(), NOW), 'yesterday');
    assert.equal(M.when(new Date(2026, 9, 16, 9, 0).toISOString(), NOW), 'Fri');
    assert.equal(M.scope({ environmentId: null }, {}), 'All environments');
    assert.equal(M.scope({ environmentId: 'e1' }, { e1: 'Payments Dev' }), 'Payments Dev only');
});
