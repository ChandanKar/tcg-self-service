// Run with: node --test "src/test/js/**/*.test.mjs"
// U14-T03: AWS accounts: the grouped account ID, access in words and the last check.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/aws-accounts.js', 'utf8') + '\nthis.A = AwsAccounts;', context);
const M = context.A.model;
const plain = x => JSON.parse(JSON.stringify(x));

const NOW = new Date(2026, 9, 21, 15, 0);

test('the account ID in groups of four', () => {
    assert.equal(M.accountId('418322970561'), '4183 2297 0561');
    assert.equal(M.accountId(null), '—');
});

test('access in words from the role ARN', () => {
    assert.equal(M.access({ roleArn: null }), 'Platform credentials');
    assert.equal(M.access({ roleArn: 'arn:aws:iam::772055193048:role/PlatformOperator' }), 'Assumes PlatformOperator role');
    assert.equal(M.access({ roleArn: 'arn:aws:iam::772055193048:role/ops/VmControlAccess' }), 'Assumes VmControlAccess role');
});

test('last check: OK with the time, the error in words, or never checked', () => {
    assert.deepEqual(plain(M.lastCheck({ lastVerifiedAt: new Date(2026, 9, 21, 6, 0).toISOString() }, NOW)),
        { ok: true, text: 'OK · today 06:00', title: '' });
    assert.deepEqual(plain(M.lastCheck({ lastVerifiedAt: NOW.toISOString(), lastError: 'AccessDenied: sts:AssumeRole',
        lastErrorSummary: "Role can't be assumed" }, NOW)), { ok: false, text: "Role can't be assumed", title: 'AccessDenied: sts:AssumeRole' });
    assert.deepEqual(plain(M.lastCheck({}, NOW)), { ok: null, text: 'Not checked yet', title: '' });
    assert.equal(M.when(new Date(2026, 9, 20, 18, 30).toISOString(), NOW), 'yesterday 18:30');
});
