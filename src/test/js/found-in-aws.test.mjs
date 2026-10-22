// Run with: node --test "src/test/js/**/*.test.mjs"
// U19-T03: Found in AWS: the filters, the regions with their counts, the summary, names without instance ids.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/found-in-aws.js', 'utf8') + '\nthis.F = FoundInAws;', context);
const M = context.F.model;
const plain = x => JSON.parse(JSON.stringify(x));

const ITEMS = [
    { kind: 'EC2', name: 'web-01', size: 't3.medium', accountName: 'Default account', region: 'eu-west-1', zone: 'eu-west-1a' },
    { kind: 'EC2', name: null, size: 'm5.large', accountName: 'Payments', region: 'me-central-1', zone: 'me-central-1b', taggedEnvironment: 'CRM UAT' },
    { kind: 'EKS', name: 'data-platform', accountName: 'Default account', region: 'eu-west-1' },
];

test('filters: region, kind and a search over name, size, account, zone and the tagged environment', () => {
    assert.equal(M.filterItems(ITEMS, {}).length, 3);
    assert.deepEqual(plain(M.filterItems(ITEMS, { region: 'eu-west-1' })).map(i => i.name), ['web-01', 'data-platform']);
    assert.deepEqual(plain(M.filterItems(ITEMS, { kind: 'EKS' })).map(i => i.name), ['data-platform']);
    assert.deepEqual(plain(M.filterItems(ITEMS, { search: 'M5' })).map(i => i.zone), ['me-central-1b']);
    assert.deepEqual(plain(M.filterItems(ITEMS, { search: 'crm' })).map(i => i.region), ['me-central-1']);
    assert.equal(M.filterItems(ITEMS, { region: 'eu-west-1', kind: 'EC2', search: 'payments' }).length, 0);
});

test('regions with their counts, sorted', () => {
    assert.deepEqual(plain(M.regionsOf(ITEMS)), [{ region: 'eu-west-1', count: 2 }, { region: 'me-central-1', count: 1 }]);
});

test('the summary counts instances and clusters', () => {
    assert.equal(M.summaryText(ITEMS), '2 instances and 1 cluster need a decision.');
    assert.equal(M.summaryText([ITEMS[2]]), '1 cluster needs a decision.');
    assert.equal(M.summaryText([]), 'Nothing waiting: everything found is in an environment or ignored.');
});

test('a name never falls back to the instance id', () => {
    assert.equal(M.nameOf(ITEMS[1]), '(no name tag)');
    assert.equal(M.nameOf({ kind: 'EKS', name: null }), 'Cluster');
    assert.equal(M.nameOf(ITEMS[0]), 'web-01');
});
