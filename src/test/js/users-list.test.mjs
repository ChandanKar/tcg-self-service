// Run with: node --test "src/test/js/**/*.test.mjs"
// U13-T03: the Users list's role names, sign-in methods, counts, last sign-in and filters.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({ Intl, Date });
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/user-management.js', 'utf8') + '\nthis.U = UserManagement;', context);
const M = context.U.model;
const plain = x => JSON.parse(JSON.stringify(x));

const people = [
    { email: 'meera@x.com', displayName: 'Meera Iyer', admin: true, envAdmin: true, active: true, signInMethod: 'MICROSOFT' },
    { email: 'sanjay@x.com', displayName: 'Sanjay Rao', envAdmin: true, active: true, signInMethod: 'MICROSOFT' },
    { email: 'ops@x.com', displayName: 'Ops service', active: true, signInMethod: 'PASSWORD' },
    { email: 'divya@x.com', displayName: 'Divya Ghosh', active: true, pendingFirstLogin: true, signInMethod: 'MICROSOFT' },
    { email: 'bot@x.com', displayName: 'Bot', active: true, serviceAccount: true, pendingFirstLogin: true, signInMethod: 'NONE' },
    { email: 'gone@x.com', displayName: 'Gone', admin: true, active: false, signInMethod: 'MICROSOFT' }
];

test('plain role names and sign-in methods', () => {
    assert.deepEqual(people.map(M.roleLabel), ['Administrator', 'Environment admin', 'User', 'User', 'Service account', 'Administrator']);
    assert.deepEqual(people.map(M.signInLabel), ['Microsoft', 'Microsoft', 'Password', 'Microsoft', 'API tokens', 'Microsoft']);
    assert.equal(M.signsInWithPasswordOnly(people[2]), true);
    assert.equal(M.signsInWithPasswordOnly({ signInMethod: 'MICROSOFT_AND_PASSWORD' }), false);
});

test('counts: active, administrators, environment admins, not signed in yet (service accounts never sign in)', () => {
    assert.deepEqual(plain(M.counts(people)), { active: 5, admins: 1, envAdmins: 1, notSignedIn: 1 });
});

test('last sign-in', () => {
    const now = new Date(2026, 9, 21, 12, 0);
    assert.equal(M.lastSignIn(new Date(2026, 9, 21, 9, 2), now), 'Today 09:02');
    assert.equal(M.lastSignIn(new Date(2026, 9, 16, 19, 0), now), 'Fri 19:00');
    assert.equal(M.lastSignIn(new Date(2026, 8, 2, 9, 0), now), '2 Sept');
    assert.equal(M.lastSignIn(null, now), null);
});

test('filters: search, role (environment admins who are not administrators), status', () => {
    assert.deepEqual(plain(M.filterList(people, '', 'ENV_ADMIN', 'active')).map(u => u.email), ['sanjay@x.com']);
    assert.deepEqual(plain(M.filterList(people, 'GHOSH', '', 'active')).map(u => u.email), ['divya@x.com']);
    assert.deepEqual(plain(M.filterList(people, '', '', 'inactive')).map(u => u.email), ['gone@x.com']);
    assert.equal(M.filterList(people, '', 'USER', 'active').length, 3);
});
