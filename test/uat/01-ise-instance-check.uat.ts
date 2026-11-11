import { test, expect, signInAdmin, apiGet, readState, writeState } from './guard';

/**
 * Read-only: is any EC2 instance of ise also registered in another environment? Starting or
 * stopping it through ise would then change a machine another environment counts on.
 */
test('ise instances are not shared with other environments', async ({ page, guard }) => {
  const pre = readState<any>('preflight');
  expect(pre, 'run the preflight first').not.toBeNull();
  const iseId = pre.ise.environmentId;
  const iseInstances = new Set<string>();
  for (const g of pre.iseVms) for (const v of g.vms) if (v.providerVmId) iseInstances.add(v.providerVmId);

  await signInAdmin(page);
  const envs = (await (await apiGet(page, '/api/v1/environments')).json()) as any[];
  const shared: any[] = [];
  for (const e of envs) {
    if (e.environmentId === iseId) continue;
    const r = await apiGet(page, `/api/v1/environments/${e.environmentId}/vms`);
    if (!r.ok()) continue;
    for (const g of await r.json()) {
      for (const v of g.vms || []) {
        if (iseInstances.has(v.providerVmId)) {
          shared.push({ environment: e.name, group: g.group?.name, vm: v.name, instance: v.providerVmId, status: v.status });
        }
      }
    }
  }
  writeState('ise-sharing', { checkedEnvironments: envs.length - 1, iseInstances: [...iseInstances], shared });
  expect(guard.refused).toEqual([]);
});
