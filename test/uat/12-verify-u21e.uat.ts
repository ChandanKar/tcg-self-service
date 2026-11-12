import { type Page } from '@playwright/test';
import { test, expect, signInAdmin, signInAs, apiJson, readState, visit } from './guard';

/**
 * Checks of the U21-T20/T21 build on staging (dbb1206, 2026-11-12).
 * T21: the admin sets a password (Users › Set password, PUT /users/{id}/password) for uat.operator@example.com,
 * added by email earlier today with no username; the person then signs in with their email, chooses their
 * own password, and reaches Home. Before the fix this sign-in said 'Invalid username or password.'.
 * T20: the ise page at 1280 x 720 shows the VM names in at most three lines and every button.
 */
const iseId = () => readState<any>('preflight')!.ise.environmentId as string;
const BROKEN = { user: process.env.UAT_BROKEN_OPERATOR!, initial: process.env.UAT_BROKEN_INITIAL!, pass: process.env.UAT_BROKEN_PASS! };

async function xsrf(page: Page): Promise<Record<string, string>> {
  const c = (await page.context().cookies()).find(k => k.name === 'XSRF-TOKEN');
  return { 'Content-Type': 'application/json', ...(c ? { 'X-XSRF-TOKEN': decodeURIComponent(c.value) } : {}) };
}

test('T21: Set password for a person added by email lets them sign in with their email', async ({ page, playwright }) => {
  expect(BROKEN.user && BROKEN.initial && BROKEN.pass).toBeTruthy();
  await signInAdmin(page);
  const users: any = await apiJson(page, '/api/v1/users?size=1000');
  const u = (Array.isArray(users) ? users : users.content).find((x: any) => (x.email || '').toLowerCase() === BROKEN.user);
  expect(u, 'the account added by email earlier').toBeTruthy();
  test.info().annotations.push({ type: 'before', description: JSON.stringify({ username: u.username ?? null }) });

  // The dialog's words for someone without a username (opened, then cancelled).
  await visit(page, '#/user-management', 't21-users');
  if (!u.username) {
    const words = await page.evaluate((who) => {
      const um = (window as any).UserManagement;
      return um && um.showSetPasswordModal ? (um.showSetPasswordModal(who), true) : false;
    }, u).catch(() => false);
    if (words) {
      await expect(page.locator('#setPasswordModal')).toContainText('They sign in with their email');
      await page.locator('#setPasswordModal .modal-footer button', { hasText: 'Cancel' }).click();
    }
  }

  const put = await page.request.put(`/api/v1/users/${u.userId}/password`, { headers: await xsrf(page), data: { password: BROKEN.initial } });
  expect(put.status(), 'password set').toBe(204);

  const ctx = await playwright.request.newContext({ baseURL: process.env.UAT_BASE_URL });
  await ctx.get('/login');
  const token = (await ctx.storageState()).cookies.find(k => k.name === 'XSRF-TOKEN');
  const h = { 'Content-Type': 'application/json', ...(token ? { 'X-XSRF-TOKEN': decodeURIComponent(token.value) } : {}) };
  const login = await ctx.post('/api/auth/login', { headers: h, data: { username: BROKEN.user, password: BROKEN.initial } });
  const loginBody = await login.json();
  test.info().annotations.push({ type: 'first sign-in', description: JSON.stringify({ status: login.status(), passwordChangeRequired: loginBody.passwordChangeRequired, message: loginBody.message }) });
  expect(loginBody.passwordChangeRequired, `first sign-in asks for their own password: ${JSON.stringify(loginBody)}`).toBe(true);
  const ch = await ctx.post('/api/auth/change-password', { headers: h, data: { username: BROKEN.user, password: BROKEN.initial, newPassword: BROKEN.pass } });
  expect(ch.status(), await ch.text()).toBe(200);
  await ctx.dispose();

  await page.context().clearCookies();
  await signInAs(page, BROKEN.user, BROKEN.pass);
  await expect(page.locator('#content-area h1').first()).toHaveText('Home');
  await page.screenshot({ path: test.info().outputPath('t21-home.png') });
});

test('T20: the ise page at 1280 x 720 shows each VM name in at most three lines and every button', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await signInAdmin(page);
  await visit(page, '#/environments/' + iseId(), 't20-ise-1280');
  await expect(page.locator('#env-vms .env-vm-row').first()).toBeVisible({ timeout: 30_000 });
  const fit = await page.evaluate(() => {
    const card = document.querySelector('.env-vms-panel')!.getBoundingClientRect();
    const buttons = [...document.querySelectorAll('.env-vm-table .env-vm-actions .btn, .env-vm-table .env-group-actions .btn')];
    const inCard = buttons.filter(b => { const r = b.getBoundingClientRect(); return r.width > 0 && r.left >= card.left - 1 && r.right <= card.right + 1; });
    const names = [...document.querySelectorAll('.env-vm-name-text')] as HTMLElement[];
    return { buttons: buttons.length, inCard: inCard.length,
      lines: names.map(n => Math.round(n.getBoundingClientRect().height / parseFloat(getComputedStyle(n).lineHeight || '18'))),
      cpuShown: !!document.querySelector('.env-vm-table thead th.env-vm-cpu') && getComputedStyle(document.querySelector('.env-vm-table thead th.env-vm-cpu')!).display !== 'none' };
  });
  test.info().annotations.push({ type: 'fit', description: JSON.stringify(fit) });
  expect(fit.buttons).toBeGreaterThanOrEqual(6);
  expect(fit.inCard).toBe(fit.buttons);
  expect(Math.max(...fit.lines)).toBeLessThanOrEqual(3);
  expect(fit.cpuShown).toBe(false);
});
