import { test, expect, signInAdmin, apiJson, readState, writeState } from './guard';

/** Read-only: ise's recent operations and VM states. */
test('ise status', async ({ page, guard }) => {
  await signInAdmin(page);
  const ise = readState<any>('preflight')!.ise.environmentId;
  const ops = await apiJson(page, `/api/v1/environments/${ise}/operations`);
  const list = Array.isArray(ops) ? ops : (ops.content || []);
  const vms = await apiJson(page, `/api/v1/environments/${ise}/vms`);
  writeState('ise-status', {
    at: new Date().toISOString(),
    operations: list.slice(0, 5).map((o: any) => ({ id: o.executionId, type: o.operationType, status: o.status, by: o.initiatedByDisplayName, startedAt: o.startedAt, completedAt: o.completedAt })),
    vms: vms.flatMap((g: any) => g.vms.map((v: any) => ({ group: g.group.name, name: v.name, status: v.status }))),
  });
  expect(guard.refused).toEqual([]);
});
