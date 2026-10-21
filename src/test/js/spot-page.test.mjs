// Run with: node --test "src/test/js/**/*.test.mjs"
// U11-T08: the Spot page's rows and interruption lines.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({
    Actions: { registerAll() {} },
    Utils: { formatCurrency: n => `$${Math.round(n)}`, escapeHtml: x => String(x) }
});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/spot.js', 'utf8') + '\nthis.P = SpotPage;', context);
const M = context.P.model;
const plain = x => JSON.parse(JSON.stringify(x));
const admin = { canAdminister: true };

test('a node group on Spot: its standby, what it saved, and going back', () => {
    const r = M.rowModel({ vmId: 'a', name: 'app-nodes-spot', kind: 'EKS_NODE_GROUP', purchaseOption: 'SPOT', partnerName: 'app-nodes',
        instanceType: 'c6i.xlarge', nodeCount: 2, monthlySavings: 61, savingsEstimated: false, revertible: true }, admin);
    assert.deepEqual(plain(r), { name: 'app-nodes-spot', size: '2 × c6i.xlarge', runsOn: 'Spot', onSpot: true,
        standby: 'Ready at 0 nodes', saves: '$61', actions: [{ kind: 'revert', label: 'Back to on-demand', danger: true }] });
    assert.equal(M.rowModel({ purchaseOption: 'SPOT', partnerName: 'x', fallbackActive: true }, admin).standby, 'Running: Spot is short');
});

test('on-demand groups: an estimate, Move to Spot when it can, Keep off Spot / Allow Spot', () => {
    const batch = { vmId: 'b', name: 'batch', purchaseOption: 'ON_DEMAND', instanceType: 'c6i.2xlarge', nodeCount: 2,
        monthlySavings: 96, savingsEstimated: true, convertible: true };
    const r = M.rowModel(batch, admin);
    assert.equal(r.saves, 'about $96');
    assert.equal(r.runsOn, 'On-demand');
    assert.deepEqual(plain(r.actions.map(a => a.label)), ['Move to Spot', 'Keep off Spot']);
    assert.deepEqual(plain(M.rowModel({ ...batch, convertible: false }, admin).actions.map(a => a.label)), ['Keep off Spot']);
    const off = M.rowModel({ ...batch, excluded: true, monthlySavings: null }, admin);
    assert.equal(off.runsOn, 'On-demand · kept off Spot');
    assert.equal(off.saves, '—');
    assert.deepEqual(plain(off.actions), [{ kind: 'exclude', label: 'Allow Spot', excluded: false }]);
    assert.deepEqual(plain(M.rowModel(batch, { canAdminister: false }).actions), []);
    assert.deepEqual(plain(M.rowModel(batch, admin, new Set(['b'])).actions), [{ kind: 'busy', label: 'Moving to Spot…' }]);
});

test('standbys are not listed on their own; interruption lines', () => {
    const rows = M.rows({ vms: [{ kind: 'EKS_NODE_GROUP', standby: true }, { kind: 'EKS_NODE_GROUP' }, { kind: 'EC2' }] });
    assert.equal(rows.length, 1);
    const now = new Date(2026, 9, 23, 18, 0);
    assert.deepEqual(plain(M.interruptionText({ vmName: 'app-nodes', at: new Date(2026, 9, 23, 14, 6).toISOString(), standbyMinutes: 22 }, now)),
        { what: 'app-nodes · today 14:06', standby: 'standby ran 22 min' });
    assert.equal(M.interruptionText({ vmName: 'x', at: now.toISOString(), standbyMinutes: null }, now).standby, 'standby still running');
});
