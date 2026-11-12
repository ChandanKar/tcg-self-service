import { test, expect, signInAdmin, apiJson, apiGet, writeState } from './guard';

/** Read-only: what automation rules exist (and on which environments), the notification settings, and the users. */
test('probe: automation rules, settings, users', async ({ page, guard }) => {
  await signInAdmin(page);
  const rules: any = await apiJson(page, '/api/v1/automation-rules');
  const list = Array.isArray(rules) ? rules : (rules.content || []);
  const settingsResp = await apiGet(page, '/api/v1/settings');
  const settings: any = settingsResp.ok() ? await settingsResp.json() : { status: settingsResp.status() };
  const usersResp = await apiGet(page, '/api/v1/users?size=500');
  const users: any = usersResp.ok() ? await usersResp.json() : { status: usersResp.status() };
  const ulist = Array.isArray(users) ? users : (users.content || []);
  writeState('probe', {
    rules: list.map((r: any) => ({ id: r.ruleId, name: r.name, env: r.environmentName || r.environmentId, enabled: r.enabled ?? r.active,
      type: r.ruleType || r.type, action: r.action || r.actionType, schedule: r.cronExpression || r.schedule || r.windows })),
    settings: JSON.stringify(settings).slice(0, 6000),
    userCount: ulist.length,
    users: ulist.map((u: any) => ({ username: u.username, email: u.email, role: u.role, admin: u.admin, auth: u.authProvider || u.authSource })).slice(0, 80),
  });
  expect(guard.refused).toEqual([]);
});
