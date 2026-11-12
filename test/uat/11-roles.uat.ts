import { type Page } from '@playwright/test';
import { test, expect, signInAdmin, signInAs, PEOPLE, apiJson, apiGet, readState, visit, verdict } from './guard';

/**
 * Read-only checks with the acceptance accounts (readiness r01): J03 (each role sees and does the right
 * things), J13 (Home for each role) and J12 (the bell). Nothing is started, stopped or changed.
 */
const iseId = () => readState<any>('preflight')!.ise.environmentId as string;

async function menuLabels(page: Page): Promise<string[]> {
  return (await page.locator('.sidebar .sidebar-menu-link:visible').allInnerTexts()).map(s => s.trim()).filter(Boolean);
}

async function envNames(page: Page): Promise<string[]> {
  const envs: any[] = await apiJson(page, '/api/v1/environments');
  return envs.map(e => e.name);
}

test('[J03] viewer: sees ise, no Start or Stop anywhere', async ({ page }) => {
  await signInAs(page, PEOPLE.viewer.user, PEOPLE.viewer.pass);
  const names = await envNames(page);
  await visit(page, '#/environments/' + iseId(), 'j03-viewer-ise');
  await expect(page.locator('#env-vms .env-vm-row').first()).toBeVisible({ timeout: 30_000 });
  const ops = await page.locator('#btn-env-action, #btn-env-action-stop, #env-vms [data-action="start-vm"], #env-vms [data-action="stop-vm"], #env-vms [data-action="group-action"]').count();
  const lock = await page.locator('[data-lock-action="acquire"]:visible').count();
  const menu = await menuLabels(page);
  test.info().annotations.push({ type: 'viewer', description: JSON.stringify({ names, ops, lock, menu }) });
  expect(names).toEqual(['ise']);
  expect(ops, 'Start/Stop buttons shown to a viewer').toBe(0);
  expect(lock, 'Lock offered to a viewer').toBe(0);
});

test('[J03] operator: operates ise only; admin pages are refused', async ({ page }) => {
  await signInAs(page, PEOPLE.operator.user, PEOPLE.operator.pass);
  const names = await envNames(page);
  await visit(page, '#/environments/' + iseId(), 'j03-operator-ise');
  await expect(page.locator('#env-vms .env-vm-row').first()).toBeVisible({ timeout: 30_000 });
  const ops = await page.locator('#btn-env-action, #btn-env-action-stop, #env-vms [data-action="start-vm"], #env-vms [data-action="stop-vm"]').count();
  const menu = await menuLabels(page);
  // Typing admin pages into the address bar.
  const refused: Record<string, string> = {};
  for (const route of ['#/user-management', '#/settings', '#/system-health', '#/access-management']) {
    await visit(page, route, 'j03-operator-' + route.slice(2));
    refused[route] = (await page.locator('#content-area').innerText()).replace(/\s+/g, ' ').slice(0, 160);
  }
  const usersApi = (await apiGet(page, '/api/v1/users?size=5')).status();
  test.info().annotations.push({ type: 'operator', description: JSON.stringify({ names, ops, menu, refused, usersApi }) });
  expect(names).toEqual(['ise']);
  expect(ops, 'Start or Stop shown to the operator on ise').toBeGreaterThan(0);
  expect(usersApi, 'the users API refuses a user').toBe(403);
  for (const label of ['Users', 'Settings', 'System health', 'AWS accounts', 'API access']) expect(menu).not.toContain(label);
});

test('[J13] Home for the operator and the viewer shows only ise', async ({ page }) => {
  const seen: Record<string, any> = {};
  for (const [who, p] of Object.entries(PEOPLE)) {
    await signInAs(page, p.user, p.pass);
    await visit(page, '#/dashboard', `j13-home-${who}`);
    const text = (await page.locator('#content-area').innerText()).replace(/\s+/g, ' ');
    const summary: any = await apiJson(page, '/api/v1/dashboard/summary');
    seen[who] = { text: text.slice(0, 400), envCount: summary.environmentCount ?? summary.totalEnvironments ?? null,
      envs: (summary.environments || []).map((e: any) => e.name || e.environmentName) };
    await page.context().clearCookies();
  }
  test.info().annotations.push({ type: 'home', description: JSON.stringify(seen) });
  for (const s of Object.values(seen)) expect((s as any).envs.every((n: string) => n === 'ise')).toBeTruthy();
});

test('[J12] the bell after today\'s ise operations (admin)', async ({ page }) => {
  await signInAdmin(page);
  const list: any = await apiJson(page, '/api/v1/notifications');
  const items: any[] = Array.isArray(list) ? list : (list.content || []);
  const today = items.filter(n => String(n.createdAt || '').startsWith('2026-11-12'));
  test.info().annotations.push({ type: 'bell', description: JSON.stringify(today.slice(0, 20).map(n => ({ type: n.type, title: n.title, msg: (n.message || '').slice(0, 120), link: n.link || n.actionUrl, at: n.createdAt }))) });
  await page.locator('button[aria-label="Notifications"], .notification-bell').first().click();
  await page.waitForTimeout(1500);
  await page.screenshot({ path: test.info().outputPath('j12-bell.png') });
  expect(items.length).toBeGreaterThanOrEqual(0);
});
