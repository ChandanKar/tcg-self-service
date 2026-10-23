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
        people: [{ directoryObjectId: 'oid', email: 'a@x.com', displayName: 'A', department: 'Eng', projectCode: 'DLK-204', teamId: 't1',
            initialPassword: null }],
        role: 'ENV_ADMIN',
        access: [{ environmentId: 'e1', scopeType: 'ENVIRONMENT', groupIds: [], accessLevel: 'USER', durationDays: 30 },
            { environmentId: 'e2', scopeType: 'GROUP', groupIds: ['g1'], accessLevel: 'VIEWER', durationDays: null }],
        emailPeople: false
    });
});

test('U20-T07: a generated initial password is 16 characters from the clear set, and differs each time', () => {
    let seed = 7;
    const random = n => Array.from({ length: n }, () => (seed = (seed * 1103515245 + 12345) % 2147483648) % 256);
    const a = M.generatePassword(random);
    const b = M.generatePassword(random);
    assert.equal(a.length, 16);
    assert.ok([...a].every(c => M.PASSWORD_CHARS.includes(c)));
    assert.notEqual(a, b);
    assert.doesNotMatch(M.PASSWORD_CHARS, /[0O1lI]/, 'no look-alikes');
    assert.equal(M.generatePassword(n => new Array(n).fill(255).map((v, i) => (i % 2 ? 3 : 255))).length, 16, 'biased bytes are skipped');
});

test('U20-T07: an initial password is for one person, 12 to 72 characters', () => {
    assert.equal(M.passwordProblem('x'.repeat(12), 1), '');
    assert.match(M.passwordProblem('x'.repeat(11), 1), /at least 12/);
    assert.match(M.passwordProblem('x'.repeat(73), 1), /at most 72/);
    assert.match(M.passwordProblem('x'.repeat(16), 2), /one person at a time/);
    const body = plain(M.requestOf({ role: 'USER', people: [{ email: 'p@x.com', initialPassword: 'given-by-admin-123' }], access: [] }));
    assert.equal(body.people[0].initialPassword, 'given-by-admin-123');
});
