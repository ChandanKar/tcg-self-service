import { type Page, type BrowserContext, type Browser } from '@playwright/test';
import { test, expect, signInAdmin, signInAs, PEOPLE, apiJson, readState, writeState, visit, verdict } from './guard';

/**
 * Acceptance with changes, 2026-11-12 (owner's go: everything on ise or on the test accounts).
 * Run one at a time with -g. op = uat.op@example.com (Operate on ise); op2 = uat.operator@example.com
 * (Operate on ise, the U21-T21 account), used as the second user, then for group access, a request,
 * an environment admin, and finally deactivated.
 */
const iseId = () => readState<any>('preflight')!.ise.environmentId as string;
const group = (name: string) => (readState<any>('preflight')!.iseVms as any[]).find(g => g.group.name === name);
const OP2 = { user: process.env.UAT_BROKEN_OPERATOR!, pass: process.env.UAT_BROKEN_PASS! };
const note = (type: string, v: unknown) => test.info().annotations.push({ type, description: typeof v === 'string' ? v : JSON.stringify(v) });

async function headers(ctx: BrowserContext): Promise<Record<string, string>> {
  const c = (await ctx.cookies()).find(k => k.name === 'XSRF-TOKEN');
  return { 'Content-Type': 'application/json', ...(c ? { 'X-XSRF-TOKEN': decodeURIComponent(c.value) } : {}) };
}
async function post(page: Page, url: string, data: unknown) {
  return page.request.post(url, { headers: await headers(page.context()), data });
}
async function as(browser: Browser, who: { user: string; pass: string }): Promise<Page> {
  const ctx = await browser.newContext({ baseURL: process.env.UAT_BASE_URL });
  const p = await ctx.newPage();
  await signInAs(p, who.user, who.pass);
  return p;
}
async function userId(page: Page, email: string): Promise<string> {
  const users: any = await apiJson(page, '/api/v1/users?size=1000');
  return (Array.isArray(users) ? users : users.content).find((u: any) => (u.email || '').toLowerCase() === email.toLowerCase()).userId;
}

test.describe.configure({ timeout: 25 * 60_000 });

test('[J08] lock and break a lock', async ({ page, browser }) => {
  await signInAdmin(page);
  const before = await apiJson(page, `/api/v1/environments/${iseId()}/lock`);
  expect(before.isLocked, 'ise starts unlocked').toBe(false);
  const op = await as(browser, PEOPLE.operator);
  const acq = await post(op, `/api/v1/environments/${iseId()}/lock/acquire`, { reason: 'UAT J08: operator holds ise (Claude)', expectedDurationMinutes: 30 });
  expect(acq.status(), await acq.text()).toBeLessThan(300);

  const op2 = await as(browser, OP2);
  await visit(op2, '#/environments/' + iseId(), 'j08-op2-sees-lock');
  const g3 = group('group-3');
  const tryStart = await post(op2, `/api/v1/environments/${iseId()}/operations`, { operationType: 'START', groupIds: [g3.group.groupId], reason: 'UAT J08: should be refused' });
  const refusal = { status: tryStart.status(), body: (await tryStart.text()).slice(0, 200) };
  note('second user start', refusal);

  const noReason = await post(page, `/api/v1/environments/${iseId()}/lock/break`, { reason: '' });
  const broke = await post(page, `/api/v1/environments/${iseId()}/lock/break`, { reason: 'UAT J08: admin breaks the operator\'s lock (Claude)' });
  const after = await apiJson(page, `/api/v1/environments/${iseId()}/lock`);
  const history: any[] = await apiJson(page, `/api/v1/environments/${iseId()}/lock/history`);
  const recent = history.slice(0, 3).map((h: any) => ({ action: h.action || h.eventType, who: h.performedByDisplayName || h.userDisplayName || h.lockedByDisplayName, reason: h.reason || h.lockReason || h.breakReason, at: h.createdAt || h.timestamp }));
  note('break', { noReason: noReason.status(), broke: broke.status(), afterLocked: after.isLocked, recent });
  await visit(op, '#/dashboard', 'j08-op-after-break');
  const opBell: any = await apiJson(op, '/api/v1/notifications');
  const opNotes = (Array.isArray(opBell) ? opBell : opBell.content || []).slice(0, 5).map((n: any) => n.title);
  note('operator bell', opNotes);
  if (tryStart.status() < 300) note('WARNING', 'the second user\'s start was accepted');
  const ok = tryStart.status() >= 400 && noReason.status() === 400 && broke.status() < 300 && after.isLocked === false;
  verdict(ok ? 'accepted' : 'rejected', `Operator (uat.op) locked ise with a reason; a second operator's start of group-3 got ${refusal.status} (${refusal.body.slice(0, 120)}); admin breaking without a reason got ${noReason.status()}, with a reason ${broke.status()}; ise unlocked after; history: ${JSON.stringify(recent).slice(0, 300)}; the operator's bell: ${JSON.stringify(opNotes).slice(0, 200)}.`);
  await op.context().close(); await op2.context().close();
  expect(ok).toBeTruthy();
});

test('[J09] a schedule fires (group-3 only, a few minutes ahead)', async ({ page }) => {
  await signInAdmin(page);
  const g3 = group('group-3');
  const vm = g3.vms[0];
  const state = async () => { const gs = await apiJson(page, `/api/v1/environments/${iseId()}/vms`); return gs.flatMap((g: any) => g.vms).find((v: any) => v.vmId === vm.vmId).status; };
  expect(await state(), 'worker2 starts stopped').toBe('STOPPED');
  const now = new Date();
  const hhmm = (d: Date) => d.toISOString().slice(11, 16);
  const start = new Date(now.getTime() + 3 * 60_000), stop = new Date(now.getTime() + 9 * 60_000);
  const day = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'][now.getUTCDay()];
  const r = await post(page, '/api/v1/automation-rules', { environmentId: iseId(), name: 'UAT J09 (Claude): worker2 for 6 minutes', description: 'Acceptance J09; deleted after the run',
    scopeType: 'GROUP', scopeId: g3.group.groupId, triggerType: 'SCHEDULE', timezone: 'UTC', enabled: true,
    windows: [{ daysOfWeek: [day], startTime: hhmm(start), stopTime: hhmm(stop) }] });
  expect(r.status(), await r.text()).toBeLessThan(300);
  const rule = await r.json();
  writeState('j09', { ruleId: rule.ruleId });
  const next = await apiJson(page, `/api/v1/automation-rules/${rule.ruleId}/next-runs`);
  note('next runs', next);
  await visit(page, '#/automation-rules', 'j09-rules');
  const seen: string[] = [];
  let started = '', stopped = '';
  for (let i = 0; i < 80 && !stopped; i++) {
    const s = await state();
    if (!seen.length || seen[seen.length - 1] !== s) seen.push(`${new Date().toISOString().slice(11, 19)} ${s}`);
    if (!started && s === 'RUNNING') started = new Date().toISOString();
    if (started && s === 'STOPPED') stopped = new Date().toISOString();
    if (Date.now() > stop.getTime() + 8 * 60_000) break;
    await page.waitForTimeout(15_000);
  }
  const audit: any = await apiJson(page, `/api/v1/audit/logs?environmentId=${iseId()}&size=40`);
  const auto = (audit.content || []).filter((a: any) => new Date(a.createdAt).getTime() >= now.getTime() && /OPERATION/.test(a.action))
    .map((a: any) => ({ a: a.action, who: a.userDisplayName || a.userName || null, d: String(a.details || '').slice(0, 120), at: a.createdAt }));
  const del = await page.request.delete(`/api/v1/automation-rules/${rule.ruleId}`, { headers: await headers(page.context()) });
  note('states', seen); note('audit', auto); note('rule deleted', del.status());
  const ok = !!started && !!stopped && auto.length > 0;
  verdict(ok ? 'accepted' : 'rejected', `A rule on ise group-3 (worker2) for today ${hhmm(start)}-${hhmm(stop)} UTC; next runs ${JSON.stringify(next).slice(0, 160)}; worker2 ${seen.join(' > ')}; audit ${JSON.stringify(auto).slice(0, 400)}; rule deleted (${del.status()}).`);
  expect(ok).toBeTruthy();
});

test('[L02] access to one group only', async ({ page, browser }) => {
  await signInAdmin(page);
  const op2Id = await userId(page, OP2.user);
  const g3 = group('group-3');
  const del = await page.request.delete(`/api/v1/environments/${iseId()}/access/${op2Id}`, { headers: await headers(page.context()) });
  const add = await post(page, '/api/v1/users/bulk-onboard?dryRun=false', { people: [{ email: OP2.user }], role: 'USER',
    access: [{ environmentId: iseId(), scopeType: 'GROUP', groupIds: [g3.group.groupId], accessLevel: 'USER', durationDays: 1 }], notes: 'UAT L02: group-3 only', emailPeople: false });
  note('grant', { revoke: del.status(), add: add.status(), body: (await add.text()).slice(0, 200) });
  const op2 = await as(browser, OP2);
  const gs: any[] = await apiJson(op2, `/api/v1/environments/${iseId()}/vms`);
  await visit(op2, '#/environments/' + iseId(), 'l02-op2-group3');
  const rows = (await op2.locator('#env-vms .env-group-row').allInnerTexts()).map(s => s.replace(/\s+/g, ' ').trim());
  const startButtons = await op2.locator('#env-vms [data-action="start-vm"], #env-vms [data-action="group-action"]').count();
  note('op2 sees', { groups: gs.map(g => ({ g: g.group.name, vms: g.vms.length, canOperate: g.viewerCanOperate })), rows, startButtons });
  const visible = gs.filter(g => g.vms.length > 0).map(g => g.group.name);
  const ok = visible.length === 1 && visible[0] === 'group-3' && startButtons > 0;
  verdict(ok ? 'accepted' : 'rejected', `uat.operator given Operate on ise group-3 only: they see ${JSON.stringify(gs.map(g => `${g.group.name}:${g.vms.length} VMs${g.viewerCanOperate === false ? ' (view)' : ''}`))}, page rows ${JSON.stringify(rows)}, ${startButtons} start buttons.`);
  await op2.context().close();
  expect(ok).toBeTruthy();
});

test('[J07] request and approve access (expiry follows tomorrow)', async ({ page, browser }) => {
  const op2 = await as(browser, OP2);
  const req = await post(op2, `/api/v1/environments/${iseId()}/access-requests`, { accessLevel: 'USER', scopeType: 'ENVIRONMENT', durationDays: 1,
    businessJustification: 'UAT J07 (Claude): need the whole of ise for a test run' });
  note('request', { status: req.status(), body: (await req.text()).slice(0, 200) });
  expect(req.status()).toBeLessThan(300);
  await signInAdmin(page);
  const pending: any = await apiJson(page, '/api/v1/access-requests/pending');
  const list = Array.isArray(pending) ? pending : pending.content || [];
  const mine = list.find((r: any) => /UAT J07/.test(r.businessJustification || ''));
  note('pending', mine ? { id: mine.requestId, who: mine.requesterDisplayName || mine.userDisplayName, reason: mine.businessJustification, level: mine.accessLevel } : 'not found');
  await visit(page, '#/pending-requests', 'j07-pending');
  const adminBell: any = await apiJson(page, '/api/v1/notifications');
  const adminNotes = (Array.isArray(adminBell) ? adminBell : adminBell.content || []).slice(0, 4).map((n: any) => n.title);
  const ap = await post(page, `/api/v1/access-requests/${mine.requestId}/approve`, { notes: 'UAT J07 approved for one day', durationDays: 1 });
  note('approve', ap.status());
  await op2.goto('/home#/dashboard');
  await op2.waitForTimeout(3000);
  const gs: any[] = await apiJson(op2, `/api/v1/environments/${iseId()}/vms`);
  const bell: any = await apiJson(op2, '/api/v1/notifications');
  const opNotes = (Array.isArray(bell) ? bell : bell.content || []).slice(0, 4).map((n: any) => n.title);
  const access: any = await apiJson(op2, '/api/v1/users/me/access');
  note('after', { groups: gs.map(g => `${g.group.name}:${g.vms.length}`), opNotes, adminNotes, access: JSON.stringify(access).slice(0, 300) });
  const ok = !!mine && ap.status() < 300 && gs.filter(g => g.vms.length).length === 3;
  verdict(ok ? 'blocked' : 'rejected', `uat.operator requested Operate on ise with a reason; the admin saw it in Pending requests (${JSON.stringify(adminNotes).slice(0, 150)}) and approved it for 1 day; the requester then sees all three groups and was notified (${JSON.stringify(opNotes).slice(0, 150)}). Expiry: the shortest grant is one day, so access ending is checked on 2026-11-13.`);
  await op2.context().close();
  expect(ok).toBeTruthy();
});

test('[J21] an environment admin manages only their own environment', async ({ page, browser }) => {
  await signInAdmin(page);
  const op2Id = await userId(page, OP2.user);
  const role = await page.request.patch(`/api/v1/users/${op2Id}/roles`, { headers: await headers(page.context()), data: { admin: false, envAdmin: true } });
  const grant = await post(page, `/api/v1/environments/${iseId()}/access`, { userEmail: OP2.user, accessLevel: 'ADMIN', durationDays: 1, notes: 'UAT J21: Admin on ise only' });
  note('setup', { role: role.status(), grant: grant.status() });
  const envs: any[] = await apiJson(page, '/api/v1/environments');
  const other = envs.find(e => e.environmentId !== iseId());
  const op2 = await as(browser, OP2);
  const theirs: any[] = await apiJson(op2, '/api/v1/environments');
  const iseAccess = (await op2.request.get(`/api/v1/environments/${iseId()}/access`)).status();
  const otherAccess = (await op2.request.get(`/api/v1/environments/${other.environmentId}/access`)).status();
  await visit(op2, `#/environments/${iseId()}/settings`, 'j21-ise-settings');
  const settingsText = (await op2.locator('#content-area').innerText()).replace(/\s+/g, ' ').slice(0, 120);
  await visit(op2, '#/access-management', 'j21-access-management');
  const menu = (await op2.locator('.sidebar .sidebar-menu-link:visible').allInnerTexts()).map(s => s.trim().split('\n')[0]);
  note('env admin', { theirs: theirs.map(e => e.name), iseAccess, otherAccess, settingsText, menu });
  const ok = theirs.length === 1 && iseAccess === 200 && otherAccess === 403 && !/is for/.test(settingsText);
  verdict(ok ? 'accepted' : 'rejected', `uat.operator as environment admin with Admin on ise only: environments ${JSON.stringify(theirs.map(e => e.name))}; ise's access list ${iseAccess}, another environment's ${otherAccess}; ise settings page '${settingsText.slice(0, 60)}'; menu ${JSON.stringify(menu)}.`);
  await op2.context().close();
  expect(ok).toBeTruthy();
});

test('[J15] role change and deactivation while signed in', async ({ page, browser }) => {
  await signInAdmin(page);
  const op2Id = await userId(page, OP2.user);
  const op2 = await as(browser, OP2);
  const before: any = await apiJson(op2, '/api/v1/users/me');
  const role = await page.request.patch(`/api/v1/users/${op2Id}/roles`, { headers: await headers(page.context()), data: { admin: false, envAdmin: false } });
  await op2.waitForTimeout(2000);
  const after: any = await apiJson(op2, '/api/v1/users/me').catch(() => null);
  const deact = await page.request.delete(`/api/v1/users/${op2Id}`, { headers: await headers(page.context()) });
  await op2.waitForTimeout(2000);
  await visit(op2, '#/my-environments', 'j15-op2-after-deactivate');
  const shown = (await op2.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 200);
  const apiAfter = (await op2.request.get('/api/v1/users/me')).status();
  const ctx = await browser.newContext({ baseURL: process.env.UAT_BASE_URL });
  const p3 = await ctx.newPage();
  await p3.goto('/login'); await p3.locator('#username').fill(OP2.user); await p3.locator('#password').fill(OP2.pass); await p3.locator('#submitBtn').click();
  await p3.waitForTimeout(4000);
  const signIn = { url: p3.url(), msg: (await p3.locator('body').innerText()).replace(/\s+/g, ' ').match(/(Invalid[^.]*\.|deactivated[^.]*\.|not active[^.]*\.)/i)?.[0] || '' };
  await ctx.close();
  note('j15', { before: { envAdmin: before.envAdmin }, role: role.status(), after: after && { envAdmin: after.envAdmin }, deact: deact.status(), shown, apiAfter, signIn });
  const ok = role.status() < 300 && after && after.envAdmin === false && deact.status() < 300 && apiAfter >= 400 && /\/login/.test(signIn.url);
  verdict(ok ? 'accepted' : 'rejected', `Admin changed uat.operator from environment admin to user (${role.status()}); their next request shows envAdmin=${after && after.envAdmin}. Deactivated while signed in (${deact.status()}): their next click shows '${shown.slice(0, 90)}', the API answers ${apiAfter}; signing in again stays on the sign-in page ('${signIn.msg}').`);
  await op2.context().close();
  expect(ok).toBeTruthy();
});

test('[L10] notification preferences and Help (operator)', async ({ page }) => {
  await signInAs(page, PEOPLE.operator.user, PEOPLE.operator.pass);
  const prefs: any[] = await apiJson(page, '/api/v1/users/me/notification-preferences');
  const free = prefs.find(p => !p.inAppLocked && p.inApp);
  const locked = prefs.find(p => p.inAppLocked);
  const put = async (changes: any[]) => page.request.put('/api/v1/users/me/notification-preferences', { headers: await headers(page.context()), data: changes });
  const off = free ? await put([{ type: free.type, inApp: false }]) : null;
  const lockedTry = locked ? await put([{ type: locked.type, inApp: false }]) : null;
  const now: any[] = await apiJson(page, '/api/v1/users/me/notification-preferences');
  const freeNow = free && now.find(p => p.type === free.type);
  const lockedNow = locked && now.find(p => p.type === locked.type);
  if (free) await put([{ type: free.type, inApp: true }]);
  await visit(page, '#/help', 'l10-help');
  const helpTitle = (await page.locator('#content-area h1').first().innerText()).trim();
  await visit(page, '#/environments/' + iseId(), 'l10-env');
  const q = page.locator('#content-area button[aria-label*="help" i], #content-area .help-button, #content-area [data-help]').first();
  let panel = '';
  if (await q.count()) { await q.click(); await page.waitForTimeout(1500); panel = (await page.locator('.help-panel, .offcanvas.show, [role="dialog"]').first().innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 160); await page.screenshot({ path: test.info().outputPath('l10-help-panel.png') }); }
  note('l10', { types: prefs.length, free: free && free.label, off: off && off.status(), freeNow: freeNow && freeNow.inApp, locked: locked && locked.label, lockedTry: lockedTry && lockedTry.status(), lockedNow: lockedNow && lockedNow.inApp, helpTitle, panel });
  const ok = !!free && freeNow.inApp === false && (!locked || lockedNow.inApp === true) && /help/i.test(helpTitle);
  verdict(ok ? 'accepted' : 'rejected', `Operator's ${prefs.length} notification types: turning '${free && free.label}' off stuck (restored after); the required '${locked && locked.label}' stays on (the request got ${lockedTry && lockedTry.status()}). Help opens '${helpTitle}'; the environment page's help ${panel ? `opens '${panel.slice(0, 80)}'` : 'button was not found by the check'}.`);
  expect(ok).toBeTruthy();
});
