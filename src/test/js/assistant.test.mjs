// Run with: node --test "src/test/js/**/*.test.mjs"
// U09-T06: the Ask panel links environments an answer names, and shows chips at the right time.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
vm.runInContext(readFileSync(STATIC + 'js/features/assistant.js', 'utf8') + '\nthis.AssistantPanel = AssistantPanel;', context);
const M = context.AssistantPanel.model;
const plain = x => JSON.parse(JSON.stringify(x));

const ENVS = [
    { id: 'p', name: 'Payments Dev' }, { id: 'c', name: 'CRM UAT' }, { id: 'pp', name: 'Partner Portal QA' },
    { id: 'pd', name: 'Payments' }, { id: 'x', name: 'QA' }, { id: 'h', name: 'HR Portal Staging' }
];
const names = list => plain(list).map(e => e.name);

test('environments named in an answer, in order, whole words, case-insensitive', () => {
    const answer = 'Payments Dev, at $412 so far. Then:\n- crm uat — $296\n- Partner Portal QA — $305';
    assert.deepEqual(names(M.envMentions(answer, ENVS, 3)), ['Payments Dev', 'CRM UAT', 'Partner Portal QA']);
});

test('a longer name wins over a shorter one inside it, and the cap is 3', () => {
    assert.deepEqual(names(M.envMentions('Only Payments Dev is idle.', ENVS, 3)), ['Payments Dev']);
    assert.deepEqual(names(M.envMentions('Payments Dev and Payments are separate.', ENVS, 3)), ['Payments Dev', 'Payments']);
    assert.deepEqual(names(M.envMentions('Payments Dev, CRM UAT, HR Portal Staging and Partner Portal QA', ENVS, 3)),
        ['Payments Dev', 'CRM UAT', 'HR Portal Staging']);
});

test('no match inside another word, and none for an empty answer', () => {
    assert.deepEqual(names(M.envMentions('The QAT team asked for PaymentsDevX.', ENVS, 3)), []);
    assert.deepEqual(names(M.envMentions('', ENVS, 3)), []);
});

test('chips: before the first question and after an answer, not while waiting', () => {
    assert.equal(M.showChips([], false), true);
    assert.equal(M.showChips([{ role: 'user' }], true), false);
    assert.equal(M.showChips([{ role: 'user' }, { role: 'assistant' }], false), true);
    assert.equal(M.showChips([{ role: 'user' }], false), false);
});
