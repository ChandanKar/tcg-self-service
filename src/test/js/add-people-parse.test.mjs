// Run with: node --test "src/test/js/**/*.test.mjs"
// U13-T05: Add people's address paste, summary line and request body.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/add-people.js', 'utf8') + '\nthis.A = AddPeople;', context);
const M = context.A.model;
const plain = x => JSON.parse(JSON.stringify(x));

test('pasted addresses: commas, semicolons, new lines, names around them, once each', () => {
    assert.deepEqual(plain(M.parseAddresses('a@x.com; b@x.com')), ['a@x.com', 'b@x.com']);
    assert.deepEqual(plain(M.parseAddresses('Neha Gupta <Neha@Corp.com>,\nravi@corp.com\tasha@corp.com, neha@corp.com')),
        ['neha@corp.com', 'ravi@corp.com', 'asha@corp.com']);
    assert.deepEqual(plain(M.parseAddresses('not an address; @x.com; a@b')), []);
});

test('the summary line', () => {
    assert.equal(M.summaryText({ newPeople: 2, existingPeople: 0, newGrants: 4, changedGrants: 0 }, 'ENV_ADMIN'),
        'Add 2 new people as Environment admin, 4 access grants');
    assert.equal(M.summaryText({ newPeople: 1, existingPeople: 1, newGrants: 1, changedGrants: 1 }, 'USER'),
        'Add 1 new person as User, 2 access grants; 1 already uses the app (access and team only)');
});

test('the request: people, role, access rows (groups, days or no end)', () => {
    const body = plain(M.requestOf({
        role: 'ENV_ADMIN',
        people: [{ email: 'a@x.com', name: 'A', department: 'Eng', teamId: 't1', projectCode: 'DLK-204', directoryObjectId: 'oid' }],
        access: [{ environmentId: 'e1', groupIds: [], level: 'USER', days: '30' },
            { environmentId: 'e2', groupIds: ['g1'], level: 'VIEWER', days: '' }]
    }));
    assert.deepEqual(body, {
        people: [{ directoryObjectId: 'oid', email: 'a@x.com', displayName: 'A', department: 'Eng', projectCode: 'DLK-204', teamId: 't1' }],
        role: 'ENV_ADMIN',
        access: [{ environmentId: 'e1', scopeType: 'ENVIRONMENT', groupIds: [], accessLevel: 'USER', durationDays: 30 },
            { environmentId: 'e2', scopeType: 'GROUP', groupIds: ['g1'], accessLevel: 'VIEWER', durationDays: null }],
        emailPeople: false
    });
});
