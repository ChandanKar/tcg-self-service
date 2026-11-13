// Run with: node --test "src/test/js/**/*.test.mjs"
// U09-T02: Request access's request lines, environment tags and descriptions, lengths and reason check.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function load() {
    const context = vm.createContext({});
    vm.runInContext(readFileSync(STATIC + 'js/features/request-access.js', 'utf8') + '\nthis.RequestAccess = RequestAccess;', context);
    return context.RequestAccess.model;
}

const plain = x => JSON.parse(JSON.stringify(x));
const now = new Date('2026-10-20T12:00:00Z');

test('request lines: waiting with who decides, approved with who and when, denied with the note', () => {
    const M = load();
    const waiting = plain(M.requestLine({ status: 'PENDING', requestedAccessLevel: 'USER', environmentName: 'Analytics QA', durationDays: 30,
        createdAt: '2026-10-20T10:00:00Z', reviewerNames: ['Sanjay Rao'] }, now));
    assert.deepEqual([waiting.chip, waiting.level, waiting.env, waiting.what, waiting.extra, waiting.action.label],
        ['Waiting', 'Operator', 'Analytics QA', ', 30 days', 'sent 2 h ago to Sanjay Rao', 'Cancel request']);
    const approved = plain(M.requestLine({ status: 'APPROVED', requestedAccessLevel: 'USER', environmentName: 'CRM UAT', durationDays: 7,
        reviewedByUserName: 'Sanjay Rao', reviewedAt: '2026-10-15T09:00:00Z' }, now));
    assert.equal(approved.extra, 'approved by Sanjay Rao on 15 Oct');
    assert.equal(approved.action.label, 'Open CRM UAT');
    const denied = plain(M.requestLine({ status: 'DENIED', requestedAccessLevel: 'USER', environmentName: 'Billing Prod Mirror',
        reviewDecisionNotes: 'Production mirror is limited to the billing team' }, now));
    assert.deepEqual([denied.chip, denied.extra, denied.action.kind], ['Declined', 'declined: "Production mirror is limited to the billing team"', 'again']);
    assert.equal(M.requestLine({ status: 'DENIED', reviewedByUserName: 'Sanjay Rao', reviewDecisionNotes: 'Not this sprint' }, now).extra,
        'declined by Sanjay Rao: "Not this sprint"');
    const group = plain(M.requestLine({ status: 'PENDING', requestedAccessLevel: 'VIEWER', environmentName: 'HR', scopeType: 'GROUP',
        scopeName: 'Web', createdAt: '2026-10-20T11:55:00Z', reviewerNames: ['A', 'B'] }, now));
    assert.equal(`${group.level}${group.what} · ${group.extra}`, 'View only, Web group · sent 5 min ago to A and B');
});

test('environment rows: tag, description and lengths', () => {
    const M = load();
    const waiting = new Set(['e1']);
    const view = new Set(['e2']);
    assert.equal(M.envTag({ environmentId: 'e1' }, waiting, view), 'Request waiting');
    assert.equal(M.envTag({ environmentId: 'e2' }, waiting, view), 'You can view');
    assert.equal(M.envTag({ environmentId: 'e3' }, waiting, view), '');
    assert.equal(M.envDescription({ description: 'Data platform QA', vmCount: 5 }), 'Data platform QA · 5 VMs');
    assert.equal(M.envDescription({ serviceType: 'EKS', vmCount: 3 }), 'EKS · 3 node groups');
    assert.deepEqual(plain(M.durations(180)), [7, 30, 90]);
    assert.deepEqual(plain(M.durations(30)), [7, 30]);
    assert.deepEqual(plain(M.durations(5)), [5]);
});

test('the reason needs 10 to 1000 characters', () => {
    const M = load();
    assert.equal(M.reasonProblem('too short'), 'Say a little more: at least 10 characters.');
    assert.equal(M.reasonProblem('Regression testing for release 4.2'), null);
    assert.equal(M.reasonProblem('x'.repeat(1001)), 'Keep it under 1000 characters.');
});

test('the environments are read a page at a time, the search going to the server (U23-T12)', () => {
    const M = load();
    assert.equal(M.availableUrl('/api/v1/environments/available', 0, 10, ''),
        '/api/v1/environments/available?page=0&size=10&includeViewOnly=true');
    assert.equal(M.availableUrl('/api/v1/environments/available', 2, 7, 'pay qa&x'),
        '/api/v1/environments/available?page=2&size=7&includeViewOnly=true&search=pay%20qa%26x');
    assert.deepEqual(plain(M.pageOf({ content: [{ environmentId: 'a' }], page: { size: 10, number: 0, totalElements: 161, totalPages: 17 } })),
        { rows: [{ environmentId: 'a' }], total: 161, number: 0 });
    assert.equal(M.availableUrl('/x', 0, 10, '', 'env-9'), '/x?page=0&size=10&includeViewOnly=true&around=env-9');
    assert.equal(M.pageOf({ content: [], page: { number: 3, totalElements: 40 } }).number, 3);
    // An older server's whole list, and nothing.
    assert.deepEqual(plain(M.pageOf([{ environmentId: 'a' }, { environmentId: 'b' }])).total, 2);
    assert.deepEqual(plain(M.pageOf(null)), { rows: [], total: 0, number: 0 });
});
