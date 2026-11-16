import { test, signInAdmin, apiJson, readState, writeState } from './guard';
test('ops probe', async ({ page }) => {
  await signInAdmin(page);
  const ise = readState<any>('preflight')!.ise.environmentId;
  const ops: any = await apiJson(page, `/api/v1/environments/${ise}/operations`);
  const list = (Array.isArray(ops) ? ops : ops.content || []).slice(0, 6);
  const vms: any[] = await apiJson(page, `/api/v1/environments/${ise}/vms`);
  writeState('ops-probe', { ops: list.map((o: any) => ({ id: o.executionId, type: o.operationType, status: o.status, by: o.initiatedByDisplayName, src: o.triggerSource, trig: o.triggerName, at: o.startedAt })),
    vms: vms.flatMap((g: any) => g.vms.map((v: any) => `${g.group.name}:${v.status}`)) });
});
