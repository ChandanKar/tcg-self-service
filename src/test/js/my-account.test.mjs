// Run with: node --test "src/test/js/**/*.test.mjs"
// U09-T05: the My account panel's grant cards (level, until when), role, sign-in and cost total.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function load() {
    const context = vm.createContext({});
    vm.runInContext(readFileSync(STATIC + 'js/core/access-levels.js', 'utf8'), context);
    vm.runInContext(readFileSync(STATIC + 'js/features/my-account.js', 'utf8') + '\nthis.MyAccount = MyAccount;', context);
    return context.MyAccount.model;
}

const plain = x => JSON.parse(JSON.stringify(x));
const M = load();
const now = new Date(2026, 9, 19, 12, 0);
const inDays = d => new Date(2026, 9, 19 + d, 12, 0).toISOString();

test('initials', () => {
    assert.equal(M.initials('Priya Nair'), 'PN');
    assert.equal(M.initials('Meera Anand Iyer'), 'MI');
    assert.equal(M.initials('admin'), 'AD');
    assert.equal(M.initials(''), '?');
});

test('level: Operator, View only, a group only', () => {
    assert.equal(M.levelText({ accessLevel: 'USER', scopeType: 'ENVIRONMENT' }), 'Operator');
    assert.equal(M.levelText({ accessLevel: 'VIEWER' }), 'View only');
    assert.equal(M.levelText({ accessLevel: 'ADMIN' }), 'Admin');
    assert.equal(M.levelText({ accessLevel: 'USER', scopeType: 'GROUP', scopeName: 'Application' }), 'Operator, Application group only');
    assert.equal(M.levelText({ requestedAccessLevel: 'USER' }), 'Operator');
});

test('until: no end date, far off, ending soon in gold, tomorrow, today', () => {
    assert.deepEqual(plain(M.untilText({}, 7, now)), { text: 'No end date', soon: false });
    assert.deepEqual(plain(M.untilText({ expiresAt: inDays(29) }, 7, now)), { text: 'Until 17 Nov (29 days)', soon: false });
    assert.deepEqual(plain(M.untilText({ expiresAt: inDays(3) }, 7, now)), { text: 'Ends 22 Oct — in 3 days', soon: true });
    assert.deepEqual(plain(M.untilText({ expiresAt: inDays(1) }, 7, now)), { text: 'Ends 20 Oct — tomorrow', soon: true });
    assert.deepEqual(plain(M.untilText({ expiresAt: inDays(0) }, 7, now)), { text: 'Ends today', soon: true });
    assert.equal(M.untilText({ expiresAt: new Date(2027, 1, 3).toISOString() }, 7, now).text, 'Until 3 Feb 2027 (107 days)');
});

test('role and sign-in', () => {
    assert.equal(M.roleText({ admin: true, envAdmin: true }), 'Administrator');
    assert.equal(M.roleText({ envAdmin: true }), 'Environment admin');
    assert.equal(M.roleText({}), 'User');
    assert.equal(M.roleDetail({ envAdmin: true, administeredEnvironments: [{ name: 'Payments Dev' }, { name: 'CRM UAT' }] }),
        'Environment admin for Payments Dev and CRM UAT');
    assert.equal(M.signInText({ authMethod: 'ENTRA_ID' }), 'Microsoft (Entra ID)');
    assert.equal(M.signInText({ authMethod: 'LOCAL' }), 'Username and password');
});

test('the cost total leaves out group-only rows', () => {
    assert.equal(M.costTotal([{ monthToDateEstimated: 186.4 }, { monthToDateEstimated: '121.75' },
        { scope: 'GROUPS', monthToDateEstimated: 99 }]), 308.15);
    assert.equal(M.costTotal([]), 0);
});

test('ended grants', () => {
    assert.equal(M.endedText({ status: 'REVOKED', revokedAt: inDays(-3) }, now), 'Removed 16 Oct');
    assert.equal(M.endedText({ status: 'EXPIRED', expiresAt: inDays(-1) }, now), 'Ended 18 Oct');
});
