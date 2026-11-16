import { test, signInAdmin, apiJson, apiGet, readState, writeState } from './guard';
test('verify review claims', async ({ page }) => {
  await signInAdmin(page);
  const get = async (u: string) => { const r = await apiGet(page, u); return r.ok() ? r.json() : { status: r.status() }; };
  const idle: any = await get('/api/v1/cost-management/idle-waste');
  const savings: any = await get('/api/v1/savings/summary');
  const rs: any = await get('/api/v1/cost-management/rightsizing?page=0&size=100');
  const rows: any[] = rs.content || [];
  const ise = readState<any>('preflight')!.ise.environmentId;
  const envs: any[] = await apiJson(page, '/api/v1/environments');
  const iseRow = envs.find(e => e.environmentId === ise);
  const iseCost: any = await get(`/api/v1/environments/${ise}/cost`);
  const myAccess: any = await get('/api/v1/users/me/access');
  writeState('verify-review', {
    idle: JSON.stringify(idle).slice(0, 900),
    savings: JSON.stringify(savings).slice(0, 900),
    rs: { total: rs.totalElements, hot: rows.filter(r => r.direction === 'SCALE_DOWN' && (r.peakCpuUtilization ?? 0) >= 85).map(r => [r.vmName, r.peakCpuUtilization, r.agreement, r.source]).slice(0, 15),
          stopped: rows.filter(r => r.vmStatus === 'STOPPED').length },
    iseList: { cost: iseRow.costThisMonth ?? iseRow.monthToDateCost ?? iseRow.cost, keys: Object.keys(iseRow).filter(k => /cost|spend/i.test(k)) },
    iseCost: JSON.stringify(iseCost).slice(0, 700),
    myAccess: Array.isArray(myAccess) ? myAccess.length : myAccess,
    groupStates: envs.filter(e => e.groupStates).length,
  });
});
