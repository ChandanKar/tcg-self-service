// UX review seeding (shared by seed-and-shoot.mjs and compare-mockup.mjs): on the smoke app's test
// schema, three environments with Database / Application / Web groups of two VMs each (records only,
// no AWS calls), grants per persona, one waiting request and a team. Safe to run again: existing
// environments and the team are looked up by name.
import { request } from '@playwright/test';

export const BASE = process.env.BASE_URL || 'http://localhost:18080';

export async function api(userId) {
  return request.newContext({ baseURL: BASE, extraHTTPHeaders: { 'X-User-Id': userId, 'Content-Type': 'application/json' } });
}

/** Returns { envs: [paymentsDev, crmUat, analyticsQa], team }. */
export async function seed() {
  const admin = await api('admin-001');
  const me = await (await admin.get('/api/v1/users/dev-user-001')).json();
  if (!me.envAdmin && !me.isEnvAdmin) await admin.patch('/api/v1/users/dev-user-001/env-admin');
  const envs = [
    { name: 'payments-dev', displayName: 'Payments Dev', description: 'Payments team development stack' },
    { name: 'crm-uat', displayName: 'CRM UAT', description: 'CRM user acceptance testing' },
    { name: 'analytics-qa', displayName: 'Analytics QA', description: 'Data platform QA' },
  ];
  const ids = [];
  for (const e of envs) {
    const r = await admin.post('/api/v1/environments', { data: { ...e, cloudProvider: 'AWS' } });
    const created = r.ok();
    const body = await r.json().catch(() => ({}));
    let id = body.environmentId;
    if (!id) id = (await (await admin.get(`/api/v1/environments/by-name/${e.name}`)).json()).environmentId;
    ids.push(id);
    if (!created) continue;
    const groups = [['database', 'Database', 1], ['app', 'Application', 2], ['web', 'Web', 3]];
    for (const [gname, gdisp, seq] of groups) {
      const g = await (await admin.post(`/api/v1/environments/${id}/groups`, { data: { name: gname, displayName: gdisp, sequencePosition: seq } })).json();
      if (!g.groupId) continue;
      for (let i = 1; i <= 2; i++) {
        await admin.post(`/api/v1/environments/${id}/vms`, { data: {
          groupId: g.groupId, name: `${e.name}-${gname}-0${i}`, displayName: `${gdisp} ${i}`, provider: 'AWS',
          region: 'us-east-1', providerVmId: `i-0${Math.random().toString(16).slice(2, 15)}`, sequencePosition: i } });
      }
    }
  }
  const u1 = await (await admin.get('/api/v1/users/user-001')).json();
  const u2 = await (await admin.get('/api/v1/users/user-002')).json();
  const dv = await (await admin.get('/api/v1/users/dev-user-001')).json();
  await admin.post(`/api/v1/environments/${ids[0]}/access`, { data: { userEmail: u1.email, accessLevel: 'USER', durationDays: 30 } });
  await admin.post(`/api/v1/environments/${ids[1]}/access`, { data: { userEmail: u1.email, accessLevel: 'USER', durationDays: 7 } });
  await admin.post(`/api/v1/environments/${ids[0]}/access`, { data: { userEmail: u2.email, accessLevel: 'VIEWER', durationDays: 30 } });
  await admin.post(`/api/v1/environments/${ids[1]}/access`, { data: { userEmail: dv.email, accessLevel: 'ADMIN', durationDays: 90 } });
  await admin.post(`/api/v1/environments/${ids[2]}/access`, { data: { userEmail: dv.email, accessLevel: 'ADMIN', durationDays: 90 } });
  const viewer = await api('user-002');
  await viewer.post(`/api/v1/environments/${ids[2]}/access-requests`, { data: { accessLevel: 'USER', businessJustification: 'Need to run QA regression', durationDays: 7 } });
  await viewer.dispose();

  let team = null;
  const made = await admin.post('/api/v1/teams', { data: { displayName: 'Payments', name: 'payments', costCenter: 'CC-PAY' } });
  if (made.ok()) team = (await made.json()).teamId;
  if (!team) {
    const list = await (await admin.get('/api/v1/teams')).json();
    team = ((Array.isArray(list) ? list : []).find(t => t.name === 'payments') || {}).teamId || null;
  }
  await admin.dispose();
  return { envs: ids, team };
}
