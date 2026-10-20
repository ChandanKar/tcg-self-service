// Run with: node --test "src/test/js/**/*.test.mjs"
// U10-T07: Access requests as cards — the texts on a card, "Approve for" and the body it sends.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/core/access-levels.js', 'utf8') + '\nthis.AccessLevels = AccessLevels;', context);
vm.runInContext(readFileSync(STATIC + 'js/features/access-requests.js', 'utf8') + '\nthis.R = AccessRequests;', context);
const M = context.R.model;
const plain = x => JSON.parse(JSON.stringify(x));
const now = new Date(2026, 9, 21, 12);
const hoursAgo = h => new Date(now.getTime() - h * 3600_000).toISOString();

test('oldest first', () => {
    const list = [{ requestId: 'b', createdAt: hoursAgo(2) }, { requestId: 'a', createdAt: hoursAgo(30) }, { requestId: 'c', createdAt: hoursAgo(1) }];
    assert.deepEqual(plain(M.oldestFirst(list).map(r => r.requestId)), ['a', 'b', 'c']);
});

test('names, initials and when it was asked', () => {
    assert.equal(M.nameOf({ requesterDisplayName: 'Priya Nair' }), 'Priya Nair');
    assert.equal(M.nameOf({ requesterEmail: 'p@x.com' }), 'p@x.com');
    assert.equal(M.initials('Priya Nair'), 'PN');
    assert.equal(M.initials('Karthik'), 'K');
    assert.equal(M.initials('arjun.mehta@x.com'), 'AM');
    assert.equal(M.askedText(hoursAgo(0), now), 'asked just now');
    assert.equal(M.askedText(hoursAgo(0.5), now), 'asked 30 min ago');
    assert.equal(M.askedText(hoursAgo(2), now), 'asked 2 h ago');
    assert.equal(M.askedText(hoursAgo(26), now), 'asked yesterday');
    assert.equal(M.askedText(hoursAgo(24 * 4), now), 'asked 4 days ago');
    assert.equal(M.askedText(new Date(2026, 9, 3, 9).toISOString(), now), 'asked on 3 Oct');
});

test('what is asked, which part, for how long and what they have today', () => {
    const fresh = { requestedAccessLevel: 'USER', scopeType: 'ENVIRONMENT', durationDays: 30 };
    assert.deepEqual(plain(M.describeRequest(fresh, now)),
        { wants: 'operator access', scope: 'Whole environment', duration: '30 days', current: 'None', extension: false });
    const ext = { requestedAccessLevel: 'USER', scopeType: 'GROUP', scopeName: 'Application', durationDays: 7, extension: true,
        currentAccessLevel: 'USER', currentExpiresAt: new Date(2026, 9, 22, 18).toISOString() };
    assert.deepEqual(plain(M.describeRequest(ext, now)),
        { wants: 'an extension', scope: 'Application group', duration: '7 more days', current: 'Operator until 22 Oct', extension: true });
    assert.equal(M.describeRequest({ requestedAccessLevel: 'VIEWER', currentAccessLevel: 'ADMIN' }, now).current, 'Admin, no end date');
    assert.equal(M.describeRequest({ requestedAccessLevel: 'VIEWER' }, now).wants, 'view access');
    assert.equal(M.describeRequest({ requestedAccessLevel: 'VIEWER' }, now).duration, 'No end');
});

test('"Approve for" starts at the requested length', () => {
    assert.deepEqual(plain(M.approveOptions({ durationDays: 30 })),
        [{ value: '30', label: '30 days' }, { value: '7', label: '7 days' }, { value: '90', label: '90 days' }, { value: 'none', label: 'No end' }]);
    assert.deepEqual(plain(M.approveOptions({ durationDays: 14, extension: true }).map(o => o.label)),
        ['14 more days', '7 more days', '30 more days', '90 more days', 'No end']);
    assert.deepEqual(plain(M.approveOptions({}).map(o => o.value)), ['none', '7', '30', '90']);
    assert.deepEqual(plain(M.approveBody('7')), { durationDays: 7 });
    assert.deepEqual(plain(M.approveBody('none')), { clearExpiry: true });
});

test('the environment filter lists environments with waiting requests', () => {
    const list = [{ environmentId: 'e2', environmentName: 'CRM UAT' }, { environmentId: 'e1', environmentName: 'Analytics QA' },
        { environmentId: 'e1', environmentName: 'Analytics QA' }];
    assert.deepEqual(plain(M.envOptions(list)),
        [{ value: '', label: 'All (3 waiting)' }, { value: 'e1', label: 'Analytics QA' }, { value: 'e2', label: 'CRM UAT' }]);
});

test('the decline dialog names the person and what they asked for', () => {
    assert.equal(M.firstNameOf({ requesterDisplayName: 'Meera Iyer' }), 'Meera');
    assert.equal(M.firstNameOf({ requesterEmail: 'm@x.com' }), 'm@x.com');
    const req = { environmentName: 'CRM UAT', durationDays: 14, businessJustification: 'Regression run for 4.2' };
    assert.equal(M.declineSubtitle(req), 'Use CRM UAT for 14 days · "Regression run for 4.2"');
    assert.equal(M.declineSubtitle({ ...req, extension: true, durationDays: 7 }), 'Use CRM UAT for 7 more days · "Regression run for 4.2"');
    assert.equal(M.declineSubtitle({ environmentName: 'CRM UAT' }), 'Use CRM UAT with no end');
});
