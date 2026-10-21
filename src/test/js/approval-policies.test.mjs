// Run with: node --test "src/test/js/**/*.test.mjs"
// U12-T06: approval policies in words (ApprovalPolicies.dc.html).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/approval-policies.js', 'utf8') + '\nthis.P = ApprovalPolicies;', context);
const M = context.P.model;
const user = label => ({ type: 'USER', approverId: label, label });

test('the conditions, joined by or', () => {
    assert.equal(M.whenText({ maxRuntimeHours: 8, instanceTypePatterns: [] }), 'a run or extension is longer than 8 hours');
    assert.equal(M.whenText({ instanceTypePatterns: ['r6i.4xlarge*'], maxEstimatedCost: 50 }),
        'any VM matches r6i.4xlarge* or the estimated cost is over $50');
    assert.equal(M.whenText({ instanceTypePatterns: ['*.8xlarge', 'p*'], minVcpu: 16, maxEstimatedCost: 12.5 }),
        'any VM matches *.8xlarge or p*, any VM has 16 vCPU or more or the estimated cost is over $12.50');
});

test('who decides', () => {
    assert.equal(M.whoText({ approverMode: 'ANY', approvers: [user('Sanjay Rao'), user('Meera Iyer')] }), 'any one of Sanjay Rao or Meera Iyer');
    assert.equal(M.whoText({ approverMode: 'ALL', approvers: [user('Sanjay Rao'), user('Meera Iyer')] }), 'both Sanjay Rao and Meera Iyer');
    assert.equal(M.whoText({ approverMode: 'ALL', approvers: [user('A'), user('B'), user('C')] }), 'all of A, B and C');
    assert.equal(M.whoText({ approverMode: 'ANY', approvers: [user('Sanjay Rao')] }), 'Sanjay Rao');
    assert.equal(M.whoText({ approvers: [] }), 'administrators');
});

test('the scope', () => {
    assert.equal(M.scopeText({ environmentName: 'Analytics QA' }), 'Analytics QA');
    assert.equal(M.scopeText({ teamId: 't', teamName: 'Payments' }), 'Payments team');
    assert.equal(M.scopeText({}), 'All environments you look after');
});

test('Check a request in words', () => {
    assert.deepEqual([...M.checkLines({ requiresApproval: true, matches: [{ name: 'Long runs', approverMode: 'ANY',
        reasons: ['24 h is longer than 8 h'], approverNames: ['Sanjay Rao', 'Meera Iyer'] }] })],
        ['Needs approval: Long runs · 24 h is longer than 8 h. Any one of Sanjay Rao or Meera Iyer.']);
    assert.deepEqual([...M.checkLines({ requiresApproval: false, matches: [] })], ['No approval needed.']);
});
