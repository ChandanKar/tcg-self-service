import { type Page } from '@playwright/test';
import { test, expect, signInAdmin, apiJson, readState, writeState } from './guard';

/**
 * Readiness r01 (owner's go, 2026-11-12): two password sign-in accounts for acceptance, with access to
 * ise only: an operator (Operate on ise) and a viewer (View only on ise). The admin adds each person
 * with the grant and an initial password (POST /users/bulk-onboard); the person then chooses their
 * own at first sign-in (POST /api/auth/change-password), as a real newcomer would.
 * Idempotent: an account that already exists is left as it is. Passwords come from UAT_ENV_FILE only.
 */
const ACCOUNTS = [
  { key: 'operator', user: process.env.UAT_OPERATOR_USER!, initial: process.env.UAT_OPERATOR_INITIAL!, pass: process.env.UAT_OPERATOR_PASS!,
    name: 'UAT Operator', level: 'USER' },
  { key: 'viewer', user: process.env.UAT_VIEWER_USER!, initial: process.env.UAT_VIEWER_INITIAL!, pass: process.env.UAT_VIEWER_PASS!,
    name: 'UAT Viewer', level: 'VIEWER' },
];

async function xsrf(page: Page): Promise<Record<string, string>> {
  const c = (await page.context().cookies()).find(k => k.name === 'XSRF-TOKEN');
  return c ? { 'X-XSRF-TOKEN': decodeURIComponent(c.value), 'Content-Type': 'application/json' } : { 'Content-Type': 'application/json' };
}

test('r01: an operator and a viewer on ise, with their own passwords', async ({ page, playwright, guard }) => {
  for (const a of ACCOUNTS) expect(a.user && a.initial && a.pass, `${a.key} credentials in UAT_ENV_FILE`).toBeTruthy();
  await signInAdmin(page);
  const ise = readState<any>('preflight')!.ise.environmentId as string;
  const users: any = await apiJson(page, '/api/v1/users?size=1000');
  const list: any[] = Array.isArray(users) ? users : (users.content || []);
  const out: Record<string, any> = {};

  for (const a of ACCOUNTS) {
    let u = list.find(x => (x.email || '').toLowerCase() === a.user.toLowerCase());
    if (!u) {
      // "Add people" with an initial password (U20-T07): the only path that gives a username today.
      const r = await page.request.post('/api/v1/users/bulk-onboard?dryRun=false', { headers: await xsrf(page), data: {
        people: [{ email: a.user, displayName: a.name, initialPassword: a.initial }], role: 'USER',
        access: [{ environmentId: ise, scopeType: 'ENVIRONMENT', accessLevel: a.level, durationDays: 60 }],
        notes: 'Acceptance test account (staging only)', emailPeople: false } });
      expect(r.status(), `add ${a.key}: ${await r.text()}`).toBe(200);
      const again: any = await apiJson(page, '/api/v1/users?size=1000');
      u = (Array.isArray(again) ? again : (again.content || [])).find((x: any) => (x.email || '').toLowerCase() === a.user.toLowerCase());
      expect(u, `${a.key} listed after adding`).toBeTruthy();

      // First sign-in: the person chooses their own password (a fresh context: no admin session).
      const ctx = await playwright.request.newContext({ baseURL: process.env.UAT_BASE_URL });
      await ctx.get('/login');
      const token = (await ctx.storageState()).cookies.find(k => k.name === 'XSRF-TOKEN');
      const ch = await ctx.post('/api/auth/change-password', {
        headers: { 'Content-Type': 'application/json', ...(token ? { 'X-XSRF-TOKEN': decodeURIComponent(token.value) } : {}) },
        data: { username: a.user, password: a.initial, newPassword: a.pass } });
      expect(ch.status(), `first sign-in of ${a.key}: ${await ch.text()}`).toBe(200);
      await ctx.dispose();
    }
    const access: any = await apiJson(page, `/api/v1/environments/${ise}/access`).catch(() => null);
    out[a.key] = { userId: u.userId, email: u.email, displayName: u.displayName, level: a.level,
      iseAccess: Array.isArray(access) ? access.filter((g: any) => g.userId === u.userId).map((g: any) => g.accessLevel) : 'n/a' };
  }
  writeState('accounts', out);
  expect(guard.refused).toEqual([]);
});
