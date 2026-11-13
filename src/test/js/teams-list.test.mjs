// Run with: node --test "src/test/js/**/*.test.mjs"
// U25-T05: the Teams table's counts, filters and cells (Members, Environments, This month, Monthly limit).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/teams.js', 'utf8') + '\nthis.T = Teams;', context);
const M = context.T.model;
const plain = x => JSON.parse(JSON.stringify(x));

const team = (over = {}) => Object.assign({ teamId: 't', name: 'payments', displayName: 'Payments', costCenter: 'CC-1',
    memberCount: 3, entraGroupId: null, entraGroupName: null, environments: [], quotas: [] }, over);
const TEAMS = [
    team({ teamId: 't1', name: 'payments', displayName: 'Payments', costCenter: 'CC-4105', memberCount: 6, entraGroupId: 'g', entraGroupName: 'SG-Pay' }),
    team({ teamId: 't2', name: 'data', displayName: 'Data', costCenter: null, memberCount: 0 }),
    team({ teamId: 't3', name: 'crm', displayName: 'CRM', costCenter: 'CC-4300', memberCount: 4 })];

test('counts: teams, without a cost centre, with no members, environments without a team', () => {
    const envs = [{ environmentId: 'a', teamId: 't1' }, { environmentId: 'b', teamId: null }, { environmentId: 'c' },
        { environmentId: 'd', isActive: false }];
    assert.deepEqual(plain(M.counts(TEAMS, envs)), { teams: 3, noCostCentre: 1, noMembers: 1, unassigned: 2 });
    // The environments did not load: no count rather than a wrong one.
    assert.equal(M.counts(TEAMS, null).unassigned, null);
    assert.deepEqual(plain(M.counts([], [])), { teams: 0, noCostCentre: 0, noMembers: 0, unassigned: 0 });
});

test('search matches the display name, the short name and the cost centre', () => {
    const ids = list => list.map(t => t.teamId);
    assert.deepEqual(ids(M.filterList(TEAMS, 'pay', '')), ['t1']);
    assert.deepEqual(ids(M.filterList(TEAMS, 'CRM', '')), ['t3']);
    assert.deepEqual(ids(M.filterList(TEAMS, 'cc-41', '')), ['t1']);
    assert.deepEqual(ids(M.filterList(TEAMS, '  ', '')), ['t1', 't2', 't3']);
    assert.deepEqual(ids(M.filterList(TEAMS, 'nothing', '')), []);
});

test('the Show filter: without a cost centre, with no members, linked to Entra, not linked', () => {
    const ids = show => M.filterList(TEAMS, '', show).map(t => t.teamId);
    assert.deepEqual(ids(''), ['t1', 't2', 't3']);
    assert.deepEqual(ids('no-cost-centre'), ['t2']);
    assert.deepEqual(ids('no-members'), ['t2']);
    assert.deepEqual(ids('entra'), ['t1']);
    assert.deepEqual(ids('manual'), ['t2', 't3']);
    assert.deepEqual(M.filterList(TEAMS, 'data', 'entra').length, 0);
});

test('the Members cell names the Entra group on its second line', () => {
    assert.equal(M.entraText(TEAMS[0]), 'synced from Entra group "SG-Pay"');
    assert.equal(M.entraText(TEAMS[1]), '');
});

test('Environments: "162 · 23 running", a strip only while it fits one line', () => {
    const envs = Array.from({ length: 162 }, (_, i) => ({ environmentId: 'e' + i, name: 'e' + i, displayName: 'E' + i }));
    const byId = {};
    envs.forEach((e, i) => { byId[e.environmentId] = { environmentId: e.environmentId, runningVmCount: i < 23 ? 1 : 0,
        groupStates: [{ name: 'app', sequencePosition: 1, running: i < 23 ? 1 : 0, busy: 0, stopped: 2, problem: 0, unknown: 0 }] }; });
    const big = M.environmentsCell(team({ environments: envs }), byId);
    assert.equal(big.text, '162 · 23 running');
    assert.equal(big.groups, null);

    const small = M.environmentsCell(team({ environments: envs.slice(0, 2) }), byId);
    assert.equal(small.text, '2 · 2 running');
    assert.deepEqual(plain(small.groups), [{ name: 'E0', lights: ['R', 'S', 'S'] }, { name: 'E1', lights: ['R', 'S', 'S'] }]);

    // Running from the group counts when the row has no runningVmCount; unknown environments count as not running.
    const fromGroups = M.environmentsCell(team({ environments: [{ environmentId: 'x' }, { environmentId: 'y' }] }),
        { x: { groupStates: [{ running: 2, stopped: 1 }] } });
    assert.equal(fromGroups.text, '2 · 1 running');

    assert.equal(M.environmentsCell(team(), {}).text, 'None yet');
    assert.equal(M.environmentsCell(team(), {}).groups, null);
});

test('This month: the team\'s month to date by display name, "—" when unknown', () => {
    const byTeam = M.monthByTeam([{ dimensionKey: 'Payments', dimensionLabel: 'Payments', cost: 1234.5 },
        { dimensionKey: 'Unassigned', dimensionLabel: 'Unassigned', cost: 10 }]);
    assert.equal(M.monthText(TEAMS[0], byTeam), '$1,234.50');
    assert.equal(M.monthText(TEAMS[1], byTeam), '—');
    assert.equal(M.monthByTeam(null), null);
    assert.equal(M.monthText(TEAMS[0], null), '—');
    assert.equal(M.money(0), '$0.00');
    assert.equal(M.money(null), '—');
});

test('Monthly limit: VM hours used / limit while quotas are on, else "No limit"', () => {
    const t = team({ quotas: [{ metric: 'VM_HOURS', used: 2890, limit: 3000 }] });
    assert.deepEqual(plain(M.limitCell(t, true)), { text: '2,890 / 3,000 h', over: true, label: '96% of the monthly VM hours used', none: false });
    assert.equal(M.limitCell(t, false).text, 'No limit');
    assert.equal(M.limitCell(team(), true).text, 'No limit');
    assert.equal(M.limitCell(team({ quotas: [{ metric: 'VM_HOURS', used: 100, limit: 4000 }] }), true).over, false);
});
