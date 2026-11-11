// Run with: node --test "src/test/js/**/*.test.mjs"
// U10-T06: the Grant access page's duration, preview rows, summary and button text.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
vm.runInContext(readFileSync(STATIC + 'js/core/access-levels.js', 'utf8') + '\nthis.AccessLevels = AccessLevels;', context);
vm.runInContext(readFileSync(STATIC + 'js/features/grant-access.js', 'utf8') + '\nthis.G = GrantAccessPage;', context);
const M = context.G.model;
const plain = x => JSON.parse(JSON.stringify(x));
const now = new Date(2026, 9, 21, 15);

test('For: days, no end, or until a date counted in whole days from today', () => {
    assert.deepEqual(plain(M.durationRequest('30', '', now)), { durationDays: 30 });
    assert.deepEqual(plain(M.durationRequest('none', '', now)), { clearExpiry: true });
    assert.deepEqual(plain(M.durationRequest('date', '2026-10-31', now)), { durationDays: 10 });
    assert.deepEqual(plain(M.durationRequest('date', '', now)), { error: 'Pick the date access ends.' });
    assert.deepEqual(plain(M.durationRequest('date', '2026-10-21', now)), { error: 'Pick a date after today.' });
});

test('preview rows read like the mockup', () => {
    const end = new Date(2026, 10, 20, 12).toISOString();
    assert.equal(M.rowDetail({ outcome: 'NEW', resultLevel: 'USER', scopeType: 'ENVIRONMENT', scopeName: 'Payments Dev', resultExpiresAt: end }),
        'Operator on the whole environment, until 20 Nov');
    assert.equal(M.rowDetail({ outcome: 'NEW', resultLevel: 'USER', scopeType: 'GROUP', scopeName: 'CRM UAT, Application group', resultExpiresAt: null }),
        'Operator on the Application group, with no end date');
    assert.equal(M.rowDetail({ outcome: 'EXTEND', currentLevel: 'USER', currentExpiresAt: new Date(2026, 9, 22).toISOString(), resultLevel: 'USER', resultExpiresAt: end }),
        'Has Operator until 22 Oct; becomes Operator until 20 Nov');
    assert.equal(M.rowDetail({ outcome: 'UPGRADE', currentLevel: 'VIEWER', resultLevel: 'USER', resultExpiresAt: end }),
        'Has View only; becomes Operator until 20 Nov');
    assert.equal(M.rowDetail({ outcome: 'SKIP', reason: 'Already has Admin, which is more' }), 'Already has Admin, which is more');
    assert.deepEqual(plain(['NEW', 'EXTEND', 'UPGRADE', 'SKIP'].map(o => M.tagOf(o)[0])), ['New', 'Extends', 'Upgrades', 'No change']);
});

test('summary, button and who was granted', () => {
    const plan = { people: 3, environments: 2, changes: 5, skipped: 1,
        rows: [{ userId: 'a', outcome: 'NEW' }, { userId: 'a', outcome: 'NEW' }, { userId: 'b', outcome: 'SKIP' }, { userId: 'c', outcome: 'EXTEND' }] };
    assert.equal(M.summaryText(plan), '3 people × 2 environments: 5 changes, 1 skipped');
    assert.equal(M.summaryText({ people: 1, environments: 1, changes: 1, skipped: 0 }), '1 person × 1 environment: 1 change, 0 skipped');
    assert.equal(M.ctaText(plan), 'Grant access (5)');
    assert.equal(M.ctaText({ changes: 0 }), 'Nothing to grant');
    assert.equal(M.grantedPeople(plan), 2);
    // People from the directory have no user id in the preview: each counts once, by email.
    assert.equal(M.grantedPeople({ rows: [{ userId: null, userEmail: 'asha@x.test', outcome: 'NEW' },
        { userId: null, userEmail: 'asha@x.test', outcome: 'NEW' }, { userId: null, userEmail: 'li@x.test', outcome: 'NEW' }] }), 2);
});
