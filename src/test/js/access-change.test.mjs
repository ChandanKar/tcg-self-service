// Run with: node --test "src/test/js/**/*.test.mjs"
// U10-T05: the Change access dialog's texts and the PATCH body it sends.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
vm.runInContext(readFileSync(STATIC + 'js/core/access-levels.js', 'utf8') + '\nthis.AccessLevels = AccessLevels;', context);
vm.runInContext(readFileSync(STATIC + 'js/features/access-change-dialogs.js', 'utf8') + '\nthis.D = AccessChangeDialogs;', context);
const M = context.D.model;
const plain = x => JSON.parse(JSON.stringify(x));
const now = new Date(2026, 9, 21, 12);
const grant = { accessId: 'a', userDisplayName: 'Ananya Iyer', environmentName: 'Payments Dev', accessLevel: 'USER',
    scopeType: 'ENVIRONMENT', expiresAt: new Date(2026, 9, 31, 12).toISOString(), notes: 'Covering release testing' };

test('names and the subtitle', () => {
    assert.equal(M.nameOf(grant), 'Ananya Iyer');
    assert.equal(M.firstName(grant), 'Ananya');
    assert.equal(M.firstName({ userEmail: 'v@x.com' }), 'v@x.com');
    assert.equal(M.subtitle(grant, now), 'Payments Dev · Operator · ends 31 Oct');
    assert.equal(M.subtitle({ ...grant, expiresAt: null, scopeType: 'GROUP', scopeName: 'Application' }, now),
        'Payments Dev, Application group · Operator · no end date');
});

test('ends options keep the current end; no end date only when there is one', () => {
    assert.deepEqual(plain(M.endsOptions(grant, now).map(o => o.label)),
        ['Keep 31 Oct', '30 days from today', '90 days from today', 'No end date']);
    assert.deepEqual(plain(M.endsOptions({ ...grant, expiresAt: null }, now).map(o => o.label)),
        ['Keep no end date', '30 days from today', '90 days from today']);
});

test('the PATCH body sends only what changed', () => {
    assert.deepEqual(plain(M.patchBody(grant, 'ADMIN', '90', grant.notes)), { accessLevel: 'ADMIN', durationDays: 90 });
    assert.deepEqual(plain(M.patchBody(grant, 'ADMIN', 'keep', grant.notes)), { accessLevel: 'ADMIN' });
    assert.deepEqual(plain(M.patchBody(grant, 'USER', 'none', grant.notes)), { accessLevel: 'USER', clearExpiry: true });
    assert.deepEqual(plain(M.patchBody(grant, 'USER', 'keep', 'New note ')), { accessLevel: 'USER', notes: 'New note' });
    assert.equal(M.patchBody(grant, 'USER', 'keep', ' Covering release testing '), null);
});
