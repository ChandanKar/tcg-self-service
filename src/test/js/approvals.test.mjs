// Run with: node --test "src/test/js/**/*.test.mjs"
// U09-T03: the approval card's title, who decides, the expiry chip and the requester's status lines.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function load() {
    const context = vm.createContext({});
    vm.runInContext(readFileSync(STATIC + 'js/features/approvals.js', 'utf8') + '\nthis.Approvals = Approvals;', context);
    return context.Approvals.model;
}

const plain = x => JSON.parse(JSON.stringify(x));
const approver = (userId, name, decision = null, reason = null) => ({ userId, name, decision, reason });

test('titles for a start and an extension', () => {
    const M = load();
    assert.equal(M.titleText({ requestType: 'START', environmentName: 'Payments Dev', requestedHours: 24 }), 'Start Payments Dev for 24 hours');
    assert.equal(M.titleText({ requestType: 'EXTEND', environmentName: 'CRM UAT', requestedHours: 12 }), 'Keep CRM UAT running 12 hours longer');
    assert.equal(M.titleText({ requestType: 'START', environmentName: 'X' }), 'Start X');
});

test('who decides: any one of you or the others; you and the other (both); without you', () => {
    const M = load();
    const r = (mode, list) => ({ approverMode: mode, approvers: list });
    assert.equal(M.decidedByText(r('ANY', [approver('me', 'Me'), approver('m', 'Meera Iyer')]), 'me'), 'Any one of you or Meera Iyer');
    assert.equal(M.decidedByText(r('ALL', [approver('me', 'Me'), approver('m', 'Meera Iyer', 'APPROVED')]), 'me'), 'You and Meera Iyer (both)');
    assert.equal(M.decidedByText(r('ALL', [approver('a', 'A'), approver('b', 'B'), approver('c', 'C')]), 'x'), 'A, B and C (all)');
    assert.equal(M.decidedByText(r('ANY', [approver('a', 'A'), approver('b', 'B')]), 'x'), 'Any one of A or B');
});

test('expiry chip: hours and minutes, urgent under an hour, none once decided', () => {
    const M = load();
    const now = new Date('2026-10-20T12:00:00Z');
    assert.deepEqual(plain(M.expiresText({ status: 'PENDING', expiresAt: '2026-10-20T15:20:00Z' }, now)), { text: 'Expires in 3 h 20 min', urgent: false });
    assert.deepEqual(plain(M.expiresText({ status: 'PENDING', expiresAt: '2026-10-20T12:45:00Z' }, now)), { text: 'Expires in 45 min', urgent: true });
    assert.equal(M.expiresText({ status: 'EXECUTED', expiresAt: '2026-10-20T15:00:00Z' }, now), null);
});

test('my requests as status lines', () => {
    const M = load();
    const now = new Date(2026, 9, 23, 12, 0);
    assert.deepEqual(plain(M.mineLine({ status: 'PENDING', canCancel: true, approvers: [approver('m', 'Meera Iyer')] }, now)),
        { chip: 'Waiting', tone: 'wait', text: 'waiting for Meera Iyer', cancel: true });
    assert.equal(M.mineLine({ status: 'EXECUTED', requestType: 'START', decidedAt: new Date(2026, 9, 23, 9, 14).toISOString(),
        approvers: [approver('m', 'Meera Iyer', 'APPROVED')] }, now).text, 'approved by Meera Iyer, started Fri 09:14');
    assert.equal(M.mineLine({ status: 'DENIED', approvers: [approver('m', 'Meera Iyer', 'DENIED', 'Too long for a weekend')] }, now).text,
        'denied by Meera Iyer: "Too long for a weekend"');
    assert.equal(M.mineLine({ status: 'EXPIRED', approvers: [] }, now).chip, 'Expired');
});
