// Run with: node --test "src/test/js/**/*.test.mjs"
// U13-T05, U25-T01: Add people's address chips, mistyped domains, summary bar and request body.
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

const DOMAINS = [{ domain: 'tcgdigital.com', count: 120 }, { domain: 'lummustech.com', count: 14 }, { domain: 'tiny.io', count: 2 }];
const ENVS = { e1: 'ISE', e2: 'pay-qa', e3: 'SIT 55' };
const envName = id => ENVS[id] || id;

test('pasted addresses: commas, semicolons, new lines, names around them, once each', () => {
    assert.deepEqual(plain(M.parseAddresses('a@x.com; b@x.com')), ['a@x.com', 'b@x.com']);
    assert.deepEqual(plain(M.parseAddresses('Neha Gupta <Neha@Corp.com>,\nravi@corp.com\tasha@corp.com, neha@corp.com')),
        ['neha@corp.com', 'ravi@corp.com', 'asha@corp.com']);
    assert.deepEqual(plain(M.parseAddresses('not an address; @x.com; a@b')), []);
});

test('U25-T01: entries keep the name and the pieces that are not addresses (they show as such)', () => {
    assert.deepEqual(plain(M.parseEntries('"Pia Sen" <Pia@Corp.com>; Ravi <ravi@corp.com> Asha <asha@corp.com>')), [
        { email: 'pia@corp.com', name: 'Pia Sen', valid: true },
        { email: 'ravi@corp.com', name: 'Ravi', valid: true },
        { email: 'asha@corp.com', name: 'Asha', valid: true }]);
    assert.deepEqual(plain(M.parseEntries('not an address, a@b, ok@x.com')), [
        { email: 'not an address', name: '', valid: false },
        { email: 'a@b', name: '', valid: false },
        { email: 'ok@x.com', name: '', valid: true }]);
    assert.deepEqual(plain(M.parseEntries(' , ;\n')), []);
});

test('U25-T01: a domain within two edits of one at least three people use looks mistyped', () => {
    assert.equal(M.editDistance('tcgdigital.com', 'tcgdigitalc.com'), 1);
    assert.equal(M.editDistance('kitten', 'sitting'), 3);
    assert.equal(M.mistypedDomain('bailsali@tcgdigitalc.com', DOMAINS), 'tcgdigital.com');
    assert.equal(M.mistypedDomain('a@lumustech.com', DOMAINS), 'lummustech.com');
    assert.equal(M.mistypedDomain('a@tcgdigital.com', DOMAINS), '', 'a domain in use is fine');
    assert.equal(M.mistypedDomain('a@tiny.co', DOMAINS), '', 'too few people use tiny.io to call it a typo');
    assert.equal(M.mistypedDomain('a@partner.org', DOMAINS), '', 'far from every known domain');
    assert.equal(M.mistypedDomain('a@tcgdigitalc.com', null), '', 'no domain list (the call failed): no check');
    assert.equal(M.colleagueDomain('x@tcgdigital.com', DOMAINS), true);
    assert.equal(M.colleagueDomain('x@partner.org', DOMAINS), false);
});

test('U25-T01: chip statuses', () => {
    const ctx = { domains: DOMAINS };
    assert.deepEqual(plain(M.chipStatus({ email: 'pat@tcgdigital.com', valid: true }, ctx)), { kind: 'new', note: '' });
    assert.deepEqual(plain(M.chipStatus({ email: 'nope', valid: false }, ctx)), { kind: 'invalid', note: 'not an email address' });
    assert.deepEqual(plain(M.chipStatus({ email: 'b@tcgdigitalc.com', valid: true }, ctx)),
        { kind: 'typo', note: 'domain looks mistyped', fixTo: 'tcgdigital.com' });
    assert.equal(M.chipStatus({ email: 'b@tcgdigitalc.com', valid: true, kept: true }, ctx).kind, 'new', 'kept as typed');
    assert.equal(M.chipStatus({ email: 'v@lummustech.com', valid: true },
        { domains: DOMAINS, outcome: { outcome: 'EXISTING' } }).note, 'already a user, skipped');
    assert.equal(M.chipStatus({ email: 'v@lummustech.com', valid: true },
        { domains: DOMAINS, outcome: { outcome: 'EXISTING' }, giving: true }).note, 'already a user, access and team only');
    assert.equal(M.chipStatus({ email: 'v@lummustech.com', valid: true, alreadyInApp: true, directoryObjectId: 'oid' }, ctx).kind, 'existing',
        'the directory says so before the preview');
    assert.deepEqual(plain(M.chipStatus({ email: 'd@tcgdigital.com', valid: true }, { outcome: { outcome: 'SKIPPED', reason: 'Deactivated.' } })),
        { kind: 'skipped', note: 'skipped: deactivated' });
    assert.equal(M.sendable('new') && M.sendable('existing') && M.sendable('skipped'), true);
    assert.equal(M.sendable('typo') || M.sendable('invalid'), false, 'addresses that need fixing are not sent');
});

test('U25-T01: the summary sentence', () => {
    const access = [{ environmentId: 'e1', groupIds: [], groupNames: [], level: 'USER', days: '30' },
        { environmentId: 'e2', groupIds: [], groupNames: [], level: 'VIEWER', days: '90' }];
    assert.equal(M.summarySentence({ count: 2, role: 'ENV_ADMIN', access, envName }),
        'Adding 2 people as Environment admin, with access to ISE (Operator, 30 days) and pay-qa (View only, 90 days).');
    assert.equal(M.summarySentence({ count: 1, role: 'ADMIN', access, envName }), 'Adding 1 person as Administrator, who can use every environment.');
    assert.equal(M.summarySentence({ count: 3, role: 'USER', access: [], envName }), 'Adding 3 people as User, with no access yet.');
    assert.equal(M.summarySentence({ count: 1, role: 'USER', envName, access: [access[0], access[1],
        { environmentId: 'e3', groupIds: ['g1'], groupNames: ['Web', 'DB'], level: 'ADMIN', days: '' }] }),
    'Adding 1 person as User, with access to ISE (Operator, 30 days), pay-qa (View only, 90 days) and SIT 55: Web, DB (Admin, no end).');
    assert.equal(M.summarySentence({ count: 0, role: 'USER', access: [], envName }), 'Nobody to add yet.');
    assert.equal(M.summarySentence({ count: 0, existing: 1, giving: true, role: 'USER', access, envName }),
        'Nobody new to add. 1 already a user: access and team only.');
    assert.equal(M.grantText(access[0], envName), 'ISE · Operator · 30 days');
});

test('U25-T01: the note line under it', () => {
    assert.equal(M.noteLine({ existing: 1, fixing: 1, signIn: 'PASSWORD' }),
        '1 already a user (skipped) · 1 address needs fixing (not added) · Passwords are shown once after adding.');
    assert.equal(M.noteLine({ existing: 0, fixing: 2, signIn: 'MICROSOFT' }),
        '2 addresses need fixing (not added) · They sign in with Microsoft; no email is sent.');
    assert.equal(M.noteLine({ signIn: 'MICROSOFT', emailOn: true }), 'Each person gets an email with what they can use and how to sign in.');
    assert.equal(M.noteLine({ existing: 2, giving: true, skipped: [{ email: 'x@y.com', reason: 'Deactivated.' }], signIn: 'MICROSOFT' }),
        '2 already users (access and team only) · 1 skipped (x@y.com: deactivated) · They sign in with Microsoft; no email is sent.');
});

test('the request: people, details for everyone, role, access rows (groups, days or no end)', () => {
    const body = plain(M.requestOf({
        role: 'ENV_ADMIN',
        signIn: 'MICROSOFT',
        details: { department: 'Eng', teamId: 't1', projectCode: 'DLK-204' },
        people: [{ email: 'a@x.com', name: 'A', directoryObjectId: 'oid', department: 'Directory dept' }, { email: 'b@x.com' }],
        access: [{ environmentId: 'e1', groupIds: [], level: 'USER', days: '30' },
            { environmentId: 'e2', groupIds: ['g1'], level: 'VIEWER', days: '' }]
    }));
    assert.deepEqual(body, {
        people: [{ directoryObjectId: 'oid', email: 'a@x.com', displayName: 'A', department: 'Eng', projectCode: 'DLK-204', teamId: 't1' },
            { directoryObjectId: null, email: 'b@x.com', displayName: null, department: 'Eng', projectCode: 'DLK-204', teamId: 't1' }],
        role: 'ENV_ADMIN',
        access: [{ environmentId: 'e1', scopeType: 'ENVIRONMENT', groupIds: [], accessLevel: 'USER', durationDays: 30 },
            { environmentId: 'e2', scopeType: 'GROUP', groupIds: ['g1'], accessLevel: 'VIEWER', durationDays: null }],
        emailPeople: false
    });
    const own = plain(M.requestOf({ role: 'USER', people: [{ email: 'a@x.com', directoryObjectId: 'oid', department: 'Quality' }], access: [] }));
    assert.equal(own.people[0].department, 'Quality', 'a directory person keeps their department when none is given for everyone');
});

test('U25-T01: with a password each person gets a generated one (none is sent); an administrator gets no access rows', () => {
    const body = plain(M.requestOf({ role: 'ADMIN', signIn: 'PASSWORD', people: [{ email: 'p@x.com' }, { email: 'q@x.com' }],
        access: [{ environmentId: 'e1', groupIds: [], level: 'USER', days: '30' }] }));
    assert.deepEqual(body.people.map(p => p.generatePassword), [true, true]);
    assert.ok(body.people.every(p => !('initialPassword' in p)));
    assert.deepEqual(body.access, []);
    assert.equal('generatePassword' in plain(M.requestOf({ role: 'USER', signIn: 'MICROSOFT', people: [{ email: 'p@x.com' }], access: [] })).people[0], false);
});

test('U25-T01: the environment search filters by name and leaves out those already given', () => {
    const envs = [{ environmentId: 'e1', displayName: 'ISE' }, { environmentId: 'e2', name: 'pay-qa' }, { environmentId: 'e3', displayName: 'Pay prod', name: 'pay-prod' }];
    assert.deepEqual(plain(M.matchEnvironments(envs, 'pay', ['e2']).map(e => e.environmentId)), ['e3']);
    assert.equal(M.matchEnvironments(envs, '', []).length, 3);
});
