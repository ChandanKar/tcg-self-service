// Run with: node --test "src/test/js/**/*.test.mjs"
// U09-T09: template cards: the VM layout, cost per day, lifetime and the requests tab count.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
vm.runInContext(readFileSync(STATIC + 'js/features/templates.js', 'utf8') + '\nthis.Templates = Templates;', context);
const M = context.Templates.model;
const plain = x => JSON.parse(JSON.stringify(x));

test('the VM layout: groups in start order, nothing when missing or unreadable', () => {
    assert.deepEqual(plain(M.layoutGroups('1|2|1')), [1, 2, 1]);
    assert.deepEqual(plain(M.layoutGroups('3')), [3]);
    assert.deepEqual(plain(M.layoutGroups(null)), []);
    assert.deepEqual(plain(M.layoutGroups('1,2')), []);
});

test('per day is the monthly estimate over 30 days', () => {
    assert.equal(M.perDay(294), 9.8);
    assert.equal(M.perDay('642'), 21.4);
    assert.equal(M.perDay(null), null);
});

test('lifetime in days, or hours below two days', () => {
    assert.equal(M.lifetimeText(720), 'Up to 30 days');
    assert.equal(M.lifetimeText(168), 'Up to 7 days');
    assert.equal(M.lifetimeText(36), 'Up to 36 hours');
    assert.equal(M.lifetimeText(1), 'Up to 1 hour');
});

test('the requests tab carries its count', () => {
    assert.equal(M.tabLabel('mine', 'My requests', 1), 'My requests · 1');
    assert.equal(M.tabLabel('mine', 'My requests', 0), 'My requests');
    assert.equal(M.tabLabel('catalogue', 'Templates', 3), 'Templates');
});
