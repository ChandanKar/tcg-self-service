// Run with: node --test "src/test/js/**/*.test.mjs"
// U15-T03: the kinds of change a rightsizing suggestion makes, for the Rightsizing tab's rows.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/rightsizing-kind.js', 'utf8'), context);
const K = context.RightsizingKind;
const plain = x => JSON.parse(JSON.stringify(x));
const row = (direction, from, to, agreement) => ({ direction, currentInstanceType: from, suggestedInstanceType: to, agreement });

test('kinds and labels read from the direction and the type names', () => {
    assert.deepEqual(plain(K.kindsOf(row('SCALE_DOWN', 'r6i.4xlarge', 'r6i.2xlarge'))), ['SMALLER']);
    assert.equal(K.label(row('SCALE_DOWN', 'r6i.4xlarge', 'r6i.2xlarge')), 'Smaller');
    assert.equal(K.label(row('SCALE_DOWN', 'm5.2xlarge', 'm6a.xlarge')), 'Smaller, newer');
    assert.equal(K.label(row('SCALE_DOWN', 'c5.2xlarge', 'c7g.xlarge')), 'Smaller, Graviton');
    assert.equal(K.label(row('MODERNIZE', 'c5.2xlarge', 'c7g.2xlarge')), 'Graviton');
    assert.equal(K.label(row('MODERNIZE', 'm5.large', 'm6a.large')), 'Newer generation');
    assert.equal(K.label(row('SCALE_UP', 't3.large', 't3.xlarge')), 'Larger');
    assert.deepEqual(plain(K.kindsOf(row('SCALE_DOWN', 'r5.xlarge', null))), []);
});

test('Graviton families, and an arm64 VM moving to a newer arm64 type', () => {
    ['m6g.large', 'c7gn.xlarge', 'r6gd.2xlarge', 't4g.micro', 'im4gn.large', 'g5g.xlarge'].forEach(t => assert.ok(K.isGraviton(t), t));
    ['g4dn.xlarge', 'm5.large', 'm7i.large', 'c6a.large', 'nonsense', null].forEach(t => assert.ok(!K.isGraviton(t), String(t)));
    assert.deepEqual(plain(K.kindsOf(row('MODERNIZE', 'm6g.large', 'm7g.large'))), ['NEWER']);
});

test('what AWS thinks of the suggestion', () => {
    assert.equal(K.agreementText(row('SCALE_DOWN', 'a', 'b', 'AGREE')), 'AWS agrees');
    assert.equal(K.agreementText(row('SCALE_DOWN', 'a', 'b', 'ONLY_AWS')), 'Suggested by AWS');
    assert.equal(K.agreementText(row('SCALE_DOWN', 'a', 'b', 'DISAGREE')), 'AWS disagrees');
    assert.equal(K.agreementText(row('SCALE_DOWN', 'a', 'b', 'ONLY_RULE')), '');
});
