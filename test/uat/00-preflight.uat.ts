import { test, expect, signInAdmin, apiGet, apiJson, writeState, ENV } from './guard';

/**
 * Read-only preflight: signs in as the admin, finds the one environment that may be started and
 * stopped, records its VMs and their states (restored at the end of the run), and reads what the
 * scenarios need to know (scheduling, switches, the signed-in user).
 */
test('preflight: sign in, find ise, record its state', async ({ page, guard }) => {
  await signInAdmin(page);
  await expect(page.locator('.user-profile-trigger')).toBeVisible();

  const me = await apiJson(page, '/api/v1/users/me');
  const envs: any[] = await apiJson(page, '/api/v1/environments');
  writeState('whoami', { me: { email: me.email, role: me.role ?? me.roles, admin: me.admin, envAdmin: me.envAdmin }, envNames: envs.map((e: any) => e.name), envs: envs.map((e: any) => ({ id: e.environmentId, name: e.name, displayName: e.displayName })) });
  const matches = envs.filter(e => [e.name, e.displayName].some((n: string) => (n || '').toLowerCase() === ENV.allowedEnvName.toLowerCase()));
  expect(matches, `exactly one environment named ${ENV.allowedEnvName}`).toHaveLength(1);
  const ise = matches[0];

  const detail = await apiJson(page, `/api/v1/environments/${ise.environmentId}`);
  const vmsResp = await apiGet(page, `/api/v1/environments/${ise.environmentId}/vms`);
  const vms = vmsResp.ok() ? await vmsResp.json() : null;
  const groupsResp = await apiGet(page, `/api/v1/environments/${ise.environmentId}/groups`);
  const groups = groupsResp.ok() ? await groupsResp.json() : null;
  const dash = await apiGet(page, '/api/v1/dashboard/summary');
  const summary = dash.ok() ? await dash.json() : null;

  writeState('preflight', {
    at: new Date().toISOString(),
    me: { userId: me.userId, email: me.email, role: me.role ?? me.roles, displayName: me.displayName },
    environmentCount: envs.length,
    ise: { environmentId: ise.environmentId, name: ise.name, displayName: ise.displayName, raw: ise },
    iseDetail: detail,
    iseVms: vms,
    iseGroups: groups,
    schedulerHealth: summary?.schedulerHealth ?? null,
  });
  await page.screenshot({ path: test.info().outputPath('home.png'), fullPage: true });
  expect(guard.refused).toEqual([]);
});
