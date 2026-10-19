import { test, expect, type Page, type APIRequestContext } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * U04-T01: the app shell of TopBar.dc.html and SideNav.dc.html. A 56 px top bar and a 240 px side
 * menu stay in place while the main area scrolls; the menu's sections never collapse, its rows are
 * 30 px and at 1440x900 an administrator sees every link without a scrollbar. Manage is for
 * environment admins and admins, Cost and Administration for admins only.
 */

declare const ContentRouter: any;

const base = () => process.env.BASE_URL || 'http://localhost:8080';

async function api(playwright: any, userId: string): Promise<APIRequestContext> {
  return playwright.request.newContext({ baseURL: base(), extraHTTPHeaders: { 'X-User-Id': userId, 'Content-Type': 'application/json' } });
}

async function open(page: Page, role: 'admin' | 'user', hash = '#/dashboard') {
  await page.setViewportSize({ width: 1440, height: 900 });
  await signIn(page, role);
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(h => { location.hash = h; }, hash);
  await expect(page.locator('#content-area h1').first()).toBeVisible({ timeout: 15_000 });
}

test.describe('App shell', () => {
  test('an administrator sees all four sections and every link, with no menu scrollbar', async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await open(page, 'admin');

    for (const [title, text] of [['#operations-title', 'Workspace'], ['#manage-title', 'Manage'],
      ['#cost-title', 'Cost'], ['#admin-title', 'Administration']]) {
      await expect(page.locator(title)).toHaveText(text);
      await expect(page.locator(title)).toBeVisible();
    }
    await expect(page.locator('#sidebar [aria-expanded]')).toHaveCount(0);
    await expect(page.locator('#sidebar button')).toHaveCount(0);

    const labels = await page.locator('#sidebar .sidebar-menu-link:visible > span:not(.badge)').allTextContents();
    expect(labels).toEqual(expect.arrayContaining(['Home', 'Environments', 'Request access', 'My activity',
      'Access requests', 'Access management', 'VM registry', 'Automation rules', 'Approval policies', 'Budgets',
      'Audit log', 'Cost management', 'Savings', 'Waste', 'Chargeback', 'Users', 'Teams', 'AWS accounts',
      'API access', 'Settings', 'System health']));
    expect(labels).not.toContain('Cost setup');

    const menu = await page.locator('#sidebar').evaluate(el => ({ scroll: el.scrollHeight, client: el.clientHeight, width: el.getBoundingClientRect().width }));
    expect(menu.scroll).toBeLessThanOrEqual(menu.client);
    expect(menu.width).toBe(240);

    const row = await page.locator('.sidebar-menu-link[data-content="vm-registry"]').boundingBox();
    expect(row!.height).toBe(30);
    const bar = await page.locator('.top-navbar').boundingBox();
    expect(bar!.height).toBe(56);
  });

  test('the top bar holds Help, notifications and the account with initials, name and role', async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await open(page, 'admin');
    await expect(page.locator('#topbar-help-btn')).toHaveAttribute('href', '#/help');
    await expect(page.locator('#notification-bell-btn')).toBeVisible();
    await expect(page.locator('.top-navbar .user-role')).toHaveText('Administrator');
    await expect(page.locator('.top-navbar .user-avatar')).toHaveCSS('width', '34px');
    await expect(page.locator('#toggleSidebar')).toBeHidden();
    await expect(page.locator('.top-navbar .logo')).toHaveText('Self-Service');
  });

  test('the top bar and menu stay put while a long page scrolls; the document never scrolls', async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await open(page, 'admin', '#/help');
    await page.evaluate(() => {
      const filler = document.createElement('div');
      filler.id = 'shell-filler';
      filler.style.height = '3000px';
      document.getElementById('content-area')!.appendChild(filler);
    });
    const main = page.locator('#mainContent');
    await main.evaluate(el => { el.scrollTop = 1500; });
    expect(await main.evaluate(el => el.scrollTop)).toBeGreaterThan(1000);

    const bar = await page.locator('.top-navbar').boundingBox();
    const menu = await page.locator('#sidebar').boundingBox();
    expect(bar!.y).toBe(0);
    expect(menu!.y).toBe(56);
    const doc = await page.evaluate(() => {
      const el = document.scrollingElement!;
      return { v: el.scrollHeight - el.clientHeight, h: el.scrollWidth - el.clientWidth, top: el.scrollTop };
    });
    expect(doc).toEqual({ v: 0, h: 0, top: 0 });
  });

  test('an environment admin sees Workspace and Manage; a user sees Workspace only', async ({ page, playwright }) => {
    test.skip(!devIds.admin || !devIds.user || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID and TEST_DEV_USER_ID (dev mode)');
    const admin = await api(playwright, devIds.admin);
    const promoted = await admin.patch('/api/v1/users/dev-user-001/roles', { data: { admin: false, envAdmin: true } });
    expect(promoted.ok(), await promoted.text()).toBeTruthy();
    await admin.dispose();

    await page.setViewportSize({ width: 1440, height: 900 });
    await page.context().setExtraHTTPHeaders({ 'X-User-Id': 'dev-user-001' });
    await page.goto('/home#/dashboard');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await expect(page.locator('#manage-menu')).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('#operations-menu')).toBeVisible();
    await expect(page.locator('#cost-section')).toBeHidden();
    await expect(page.locator('#admin-section')).toBeHidden();
    await expect(page.locator('.top-navbar .user-role')).toHaveText('Environment admin');

    const other = await page.context().browser()!.newPage();
    await other.setViewportSize({ width: 1440, height: 900 });
    await other.context().setExtraHTTPHeaders({ 'X-User-Id': devIds.user });
    await other.goto(base() + '/home#/dashboard');
    await other.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await expect(other.locator('#operations-menu')).toBeVisible({ timeout: 15_000 });
    await expect(other.locator('#manage-section')).toBeHidden();
    await expect(other.locator('#cost-section')).toBeHidden();
    await expect(other.locator('#admin-section')).toBeHidden();
    await expect(other.locator('.top-navbar .user-role')).toHaveText('User');
    await other.close();
  });

  test('Cost management links to Cost setup, which left the menu', async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await open(page, 'admin', '#/cost-management');
    await expect(page.locator('.cost-toolbar a[href="#/cost-setup"]')).toBeVisible({ timeout: 15_000 });
  });
});
