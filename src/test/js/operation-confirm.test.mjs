// Run with: node --test "src/test/js/**/*.test.mjs"
// U08-T02: the start and stop confirmation's targets, sentence, length chips, hints and approval wording.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));

function load() {
    const context = vm.createContext({});
    vm.runInContext(readFileSync(STATIC + 'js/features/operation-confirm.js', 'utf8') + '\nthis.OperationConfirm = OperationConfirm;', context);
    return context.OperationConfirm.model;
}

// Values made in the vm context have its prototypes; compare them as plain data.
const plain = x => JSON.parse(JSON.stringify(x));
const vm_ = (vmId, status, name = vmId) => ({ vmId, name, status });
const env = {
    environmentId: 'env-1',
    groups: [
        { group: { groupId: 'g-web', name: 'Web', sequencePosition: 3 }, vms: [vm_('web-01', 'STOPPED'), vm_('web-02', 'STOPPED')] },
        { group: { groupId: 'g-db', name: 'Database', sequencePosition: 1 }, vms: [vm_('db-01', 'RUNNING')] },
        { group: { groupId: 'g-app', name: 'Application', sequencePosition: 2 }, vms: [vm_('app-01', 'RUNNING'), vm_('app-02', 'STARTING')] }
    ]
};

test('targets: stopped VMs in scope for a start, running ones for a stop, never a VM already moving', () => {
    const M = load();
    const ids = list => plain(list.map(t => t.vm.vmId));
    assert.deepEqual(ids(M.targetsFor(env, 'START', { level: 'environment' })), ['web-01', 'web-02']);
    assert.deepEqual(ids(M.targetsFor(env, 'STOP', { level: 'environment' })), ['db-01', 'app-01']);
    assert.deepEqual(ids(M.targetsFor(env, 'STOP', { level: 'group', groupId: 'g-app' })), ['app-01']);
    assert.deepEqual(ids(M.targetsFor(env, 'START', { level: 'vm', vmId: 'db-01' })), []);
    assert.deepEqual(ids(M.targetsFor(env, 'START', { level: 'vm', vmId: 'web-02' })), ['web-02']);
});

test('sentence: the group that starts now and the groups already running, in start order', () => {
    const M = load();
    const all = { environmentId: 'e', groups: env.groups.map(g => g.group.groupId === 'g-app'
        ? { group: g.group, vms: [vm_('app-01', 'RUNNING')] } : g) };
    const t = M.targetsFor(all, 'START', { level: 'environment' });
    assert.equal(M.groupSentence(all, 'START', { level: 'environment' }, t),
        'The Web group starts now. Database and Application are already running.');
    assert.equal(M.groupSentence(env, 'STOP', { level: 'group', groupId: 'g-db' }, M.targetsFor(env, 'STOP', { level: 'group', groupId: 'g-db' })),
        'The Database group stops now.');
    assert.equal(M.groupSentence(env, 'START', { level: 'vm', vmId: 'web-01' }, M.targetsFor(env, 'START', { level: 'vm', vmId: 'web-01' })),
        'web-01 starts now.');
    assert.equal(M.groupSentence(env, 'START', { level: 'group', groupId: 'g-db' }, []), 'Everything here is already running.');
    assert.equal(M.groupSentence({ groups: [] }, 'START', { level: 'environment' }, []), '');
    assert.equal(M.joinNames(['A', 'B', 'C']), 'A, B and C');
});

test('title and cost note: counts and names, a plain title with nothing listed, the rate for both', () => {
    const M = load();
    const t = n => Array.from({ length: n }, () => ({}));
    assert.equal(M.titleFor({ displayName: 'Payments Dev' }, 'START', t(2)), 'Start 2 VMs in Payments Dev?');
    assert.equal(M.titleFor({ displayName: 'Payments Dev' }, 'STOP', t(1)), 'Stop 1 VM in Payments Dev?');
    assert.equal(M.titleFor({ displayName: 'Orders', serviceType: 'EKS' }, 'STOP', t(2)), 'Scale 2 node groups in Orders to 0?');
    assert.equal(M.titleFor({ displayName: 'Empty' }, 'START', []), 'Start Empty?');
    assert.equal(M.titleFor({ displayName: 'Empty' }, 'START', [], { level: 'vm', vmId: 'v', name: 'report-worker' }), 'Start report-worker?');
    const preview = { hours: 8, hourlyTotal: 0.09, estimatedTotal: 0.72, vms: [{}, {}] };
    assert.equal(M.costNote(preview, 2, false), '$0.09 an hour for both');
    assert.equal(M.costNote({ ...preview, unknownPriceCount: 1, approximate: true }, 3, true),
        'for 8 h, $0.09 an hour for all 3 · price unknown for 1 VM, approximate');
    assert.equal(M.costNote({ vms: [] }, 2, false), null);
});

test('chips: the schedule stop first when it comes before the default, then 2, 8 and 24 hours within the maximum', () => {
    const M = load();
    const now = new Date(2026, 9, 20, 17, 45);
    const stop = new Date(2026, 9, 20, 20, 0);
    const policy = { enabled: true, effectiveMaxMinutes: 1440, effectiveDefaultMinutes: 480 };
    const { chips, selected } = M.durationChips(policy, stop.toISOString(), now);
    assert.deepEqual(plain(chips.map(c => c.label)), ['Schedule stop, 20:00', '2 hours', '8 hours', '24 hours']);
    assert.equal(selected, 135);
    assert.equal(chips[0].schedule, true);

    const noSchedule = M.durationChips(policy, null, now);
    assert.deepEqual(plain(noSchedule.chips.map(c => c.minutes)), [120, 480, 1440]);
    assert.equal(noSchedule.selected, 480);

    const short = M.durationChips({ enabled: true, effectiveMaxMinutes: 240, effectiveDefaultMinutes: 60 }, null, now);
    assert.deepEqual(plain(short.chips.map(c => c.label)), ['1 hour', '2 hours']);

    assert.deepEqual(plain(M.durationChips({ enabled: false }, null, now).chips), []);
    assert.deepEqual(plain(M.durationChips(null, null, now).chips), []);
});

test('hints: the schedule stop with the time left, a plain stop with its reminder, and a run past the schedule', () => {
    const M = load();
    const now = new Date(2026, 9, 20, 17, 45);
    const stop = new Date(2026, 9, 20, 20, 0).toISOString();
    assert.equal(M.hintFor({ minutes: 135, schedule: true }, stop, now), 'Stops with the rest of the environment at 20:00 (in 2 h 15 min).');
    assert.equal(M.hintFor({ minutes: 120 }, stop, now), 'Stops at 19:45. You get a reminder 15 minutes before.');
    assert.equal(M.hintFor({ minutes: 480 }, stop, now), 'Runs past the schedule stop, until 01:45 tomorrow.');
    assert.equal(M.hintFor({ minutes: 480 }, null, now), 'Stops at 01:45 tomorrow. You get a reminder 15 minutes before.');
});

test('approval: none needed, the approvers by name, or the block reason', () => {
    const M = load();
    assert.equal(M.approvalText({ requiresApproval: false, matches: [] }).text, 'No approval needed');
    const needs = M.approvalText({ requiresApproval: true, matches: [{ approverNames: ['Sanjay Rao'] }, { approverNames: ['Sanjay Rao', 'Priya Nair'] }] });
    assert.equal(needs.text, 'Needs Sanjay Rao and Priya Nair');
    assert.equal(needs.needs, true);
    assert.equal(M.approvalText({ requiresApproval: true, matches: [{}] }).text, 'Needs approval');
    const blocked = M.approvalText({ blocked: true, warnings: ['The team is over its monthly quota'] });
    assert.equal(blocked.blocked, true);
    assert.equal(blocked.text, 'The team is over its monthly quota');
});

test('without a lease the start runs to the next scheduled stop, or until someone stops it (U22-T05)', () => {
    const M = load();
    const now = new Date(2026, 10, 12, 14, 0);                     // Thu 14:00 local
    const friday = new Date(2026, 10, 13, 12, 30);                 // Fri 12:30, 22.5 h later
    const run = M.runWithoutLease(friday.toISOString(), now);
    assert.equal(run.hours, 23);
    assert.equal(run.text, 'They run until the next scheduled stop, Fri 12:30 (in 23 h), unless someone stops them sooner.');
    // Staging: 17 h 36 min read "in 17 h" beside "$4.00 for 18 h"; both now say 18.
    const staging = M.runWithoutLease(new Date(2026, 10, 13, 7, 36).toISOString(), now);
    assert.equal(staging.hours, 18);
    assert.match(staging.text, /\(in 18 h\)/);
    const tonight = M.runWithoutLease(new Date(2026, 10, 12, 18, 45).toISOString(), now);
    assert.equal(tonight.hours, 5);
    assert.match(tonight.text, /today 18:45 \(in 4 h 45 min\)/);
    const none = M.runWithoutLease(null, now);
    assert.equal(none.hours, null);
    assert.equal(none.text, 'They run until someone stops them: no schedule stops this environment.');
    assert.equal(M.runWithoutLease(new Date(2026, 10, 12, 9, 0).toISOString(), now).hours, null, 'a stop in the past is no stop');
});

test('a stop names the VMs someone else started (U22-T10)', () => {
    const M = load();
    const at = new Date(2026, 10, 12, 7, 1).toISOString();
    const t = (name, startedBy, startedByUserId) => ({ vm: { vmId: name, name, status: 'RUNNING', startedBy, startedByUserId, startedAt: at } });
    assert.equal(M.othersStarted([t('master-01', 'Sayan Hazra', 'u-sayan')], 'u-me'),
        'Someone else started this VM. master-01: started by Sayan Hazra at 07:01.');
    assert.equal(M.othersStarted([t('mine', 'Me', 'u-me'), t('sched', 'the schedule Office hours', null), t('aws', 'outside the platform', null)], 'u-me'), '');
});

// U23-T04: staging priced Group-2 and Group-3 "for 17 h, until the next scheduled stop" while the only rule
// stopped Group-2.
test('each target takes its own scheduled stop; one stop for all keeps the old dialog', () => {
    const M = load();
    const fri = new Date(2026, 10, 13, 12, 30).toISOString();
    const g2 = { vm: { vmId: 'g2-1', name: 'g2-1' } };
    const g3 = { vm: { vmId: 'web-01', name: 'web-01' } };
    // A whole-environment rule: every VM has the same stop, so nothing changes.
    const whole = M.stopsFor([g2, g3], { ruleCount: 1, nextStop: fri, vmStops: { 'g2-1': fri, 'web-01': fri } });
    assert.equal(whole.nextStop, fri);
    assert.equal(whole.mixed, null);
    // An older answer without vmStops: the environment's stop, as before.
    assert.equal(M.stopsFor([g2], { ruleCount: 1, nextStop: fri }).nextStop, fri);
    assert.equal(M.stopsFor([g2], null).nextStop, null);
    // Only Group-2 is targeted: its stop, though it is not the environment's.
    assert.equal(M.stopsFor([g2], { ruleCount: 1, nextStop: null, vmStops: { 'g2-1': fri } }).nextStop, fri);
    // Only Group-3: no stop, and the words do not blame the environment.
    const none = M.stopsFor([g3], { ruleCount: 1, nextStop: null, vmStops: { 'g2-1': fri } });
    assert.equal(none.nextStop, null);
    assert.equal(none.partial, true);
    assert.equal(M.runWithoutLease(null, new Date(), { partial: true }).text,
        'They run until someone stops them: no schedule stops them.');
    // Both: mixed.
    const both = M.stopsFor([g2, g3], { ruleCount: 1, nextStop: null, vmStops: { 'g2-1': fri } });
    assert.equal(both.nextStop, null);
    assert.deepEqual(plain(both.mixed.map(e => [e.target.vm.vmId, e.stop])), [['g2-1', fri], ['web-01', null]]);
});

test('a mixed start prices each VM to its stop and the rest by the hour', () => {
    const M = load();
    const now = new Date(2026, 10, 12, 19, 30);                    // Thu 19:30
    const fri = new Date(2026, 10, 13, 12, 30).toISOString();      // 17 h later
    const g2 = { vm: { vmId: 'g2-1', name: 'g2-1' } };
    const web = { vm: { vmId: 'web-01', name: 'web-01' } };
    const run = M.mixedRun([{ target: g2, stop: fri }, { target: web, stop: null }], now);
    assert.equal(run.hours, 17);
    assert.deepEqual(plain(run.hoursByVm), { 'g2-1': 17, 'web-01': null });
    assert.equal(run.text, 'g2-1 runs until its scheduled stop, Fri 12:30 (in 17 h). web-01 runs until someone stops it: no schedule stops it.');
    const cost = M.mixedCost({ vms: [{ vmId: 'g2-1', hourlyRate: 0.2 }, { vmId: 'web-01', name: 'web-01', hourlyRate: 0.11 }],
        unknownPriceCount: 0 }, run, [g2, web]);
    assert.equal(cost.value, '$3.40');
    assert.equal(cost.note, 'to its scheduled stop · $0.11 an hour for web-01, no schedule stops it');
    assert.equal(M.mixedCost(null, run, [g2, web]), null);
    // Two stops, nothing open.
    const two = M.mixedRun([{ target: g2, stop: fri }, { target: web, stop: new Date(2026, 10, 12, 21, 0).toISOString() }], now);
    assert.equal(two.hours, 17);
    assert.match(two.text, /web-01 runs until its scheduled stop, today 21:00 \(in 1 h 30 min\)\.$/);
    assert.equal(M.mixedCost({ vms: [{ vmId: 'g2-1', hourlyRate: 0.2 }, { vmId: 'web-01', hourlyRate: 0.1 }] }, two, [g2, web]).note,
        'to their scheduled stops');
});
