import { type Page } from '@playwright/test';
import { test, expect, signInAdmin, signInAs, apiJson, readState, visit, verdict } from './guard';

/**
 * J21 again on the U21-T23 build (005dcaf): uat.operator is reactivated as an environment admin with Admin
 * on ise only, checked read-only (nothing is started or locked anywhere else), then deactivated again.
 */
const iseId = () => readState<any>('preflight')!.ise.environmentId as string;
const OP2 = { user: process.env.UAT_BROKEN_OPERATOR!, pass: process.env.UAT_BROKEN_PASS! };
const note = (type: string, v: unknown) => test.info().annotations.push({ type, description: JSON.stringify(v) });
async function h(page: Page) {
  const c = (await page.context().cookies()).find(k => k.name === 'XSRF-TOKEN');
  return { 'Content-Type': 'application/json', ...(c ? { 'X-XSRF-TOKEN': decodeURIComponent(c.value) } : {}) };
}

test('[J21] environment admin limited to their environment (T23 build)', async ({ page, browser }) => {
  await signInAdmin(page);
  const users: any = await apiJson(page, '/api/v1/users?size=1000&includeInactive=true');
  const list = Array.isArray(users) ? users : users.content;
  let u = list.find((x: any) => (x.email || '').toLowerCase() === OP2.user);
  if (!u) { const all: any = await apiJson(page, '/api/v1/users?size=1000&status=all'); u = (Array.isArray(all) ? all : all.content).find((x: any) => (x.email || '').toLowerCase() === OP2.user); }
  expect(u, 'uat.operator exists').toBeTruthy();
  const react = await page.request.post(`/api/v1/users/${u.userId}/reactivate`, { headers: await h(page) });
  const role = await page.request.patch(`/api/v1/users/${u.userId}/roles`, { headers: await h(page), data: { admin: false, envAdmin: true } });
  const grant = await page.request.post(`/api/v1/environments/${iseId()}/access`, { headers: await h(page), data: { userEmail: OP2.user, accessLevel: 'ADMIN', durationDays: 1, notes: 'UAT J21 recheck' } });
  const envs: any[] = await apiJson(page, '/api/v1/environments');
  const other = envs.find(e => e.environmentId !== iseId() && (e.vmCount || e.totalVms || 0) >= 0);
  note('setup', { reactivate: react.status(), role: role.status(), grant: grant.status() });

  const ctx = await browser.newContext({ baseURL: process.env.UAT_BASE_URL });
  const p = await ctx.newPage();
  await signInAs(p, OP2.user, OP2.pass);
  const theirs: any[] = await apiJson(p, '/api/v1/environments');
  const iseView = (await p.request.get(`/api/v1/environments/${iseId()}`)).status();
  const iseVms = (await p.request.get(`/api/v1/environments/${iseId()}/vms`)).status();
  const otherView = (await p.request.get(`/api/v1/environments/${other.environmentId}`)).status();
  const otherVms = (await p.request.get(`/api/v1/environments/${other.environmentId}/vms`)).status();
  const otherAccess = (await p.request.get(`/api/v1/environments/${other.environmentId}/access`)).status();
  const iseAccess = (await p.request.get(`/api/v1/environments/${iseId()}/access`)).status();
  await visit(p, '#/my-environments', 'j21-envs');
  await visit(p, `#/environments/${iseId()}/settings`, 'j21-ise-settings');
  const settings = (await p.locator('#content-area').innerText()).replace(/\s+/g, ' ').slice(0, 80);
  await ctx.close();

  const deact = await page.request.delete(`/api/v1/users/${u.userId}`, { headers: await h(page) });
  const unrole = await page.request.patch(`/api/v1/users/${u.userId}/roles`, { headers: await h(page), data: { admin: false, envAdmin: false } });
  const r = { theirs: theirs.map(e => e.name), iseView, iseVms, iseAccess, otherView, otherVms, otherAccess, settings, cleanup: { deact: deact.status(), unrole: unrole.status() } };
  note('result', r);
  const ok = theirs.length === 1 && theirs[0].environmentId === iseId() && iseView === 200 && iseVms === 200 && iseAccess === 200
    && otherView >= 400 && otherVms >= 400 && otherAccess >= 400 && !/is for/.test(settings);
  verdict(ok ? 'accepted' : 'rejected', `On 005dcaf (U21-T23), uat.operator as environment admin with Admin on ise only: the environment list holds ${JSON.stringify(r.theirs)}; ise: view ${iseView}, VMs ${iseVms}, access list ${iseAccess}, settings '${settings.slice(0, 40)}'; another environment (${other.name}): view ${otherView}, VMs ${otherVms}, access list ${otherAccess}. Nothing was started or locked elsewhere. The account was deactivated again after.`);
  expect(ok).toBeTruthy();
});
