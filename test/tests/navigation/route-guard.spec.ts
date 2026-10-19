import { test, expect, type Page } from '@playwright/test';
import { devIds } from '../../fixtures/auth';

/**
 * U02-T01: every page opened by URL either works for the person's role or says plainly that it is not
 * for them ("<Page> is for environment admins" / "... is for administrators"), never a blank page.
 * Unknown addresses say the page doesn't exist instead of quietly opening Home.
 * Dev users: admin-001 administrator, dev-user-001 environment admin, user-001 and user-002 users.
 */

declare const ContentRouter: any;

const ENV_ADMIN_PAGES = ['pending-requests', 'access-management', 'vm-registry', 'automation-rules',
  'approval-policies', 'budgets', 'audit-logs-all'];
const ADMIN_PAGES = ['cost-management', 'savings', 'waste', 'chargeback', 'cost-setup', 'user-management',
  'teams', 'aws-accounts', 'api-access', 'settings', 'system-health'];
const OPEN_PAGES = ['dashboard', 'my-environments', 'request-access', 'activity-logs', 'help'];

const ENV_ADMIN_ID = process.env.TEST_DEV_ENV_ADMIN_ID || 'dev-user-001';
const SECOND_USER_ID = process.env.TEST_DEV_SECOND_USER_ID || 'user-002';

async function signInAs(page: Page, userId: string) {
  await page.context().setExtraHTTPHeaders({ 'X-User-Id': userId });
  await page.goto('/home');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
}

async function open(page: Page, route: string) {
  await page.evaluate((r: string) => { location.hash = '#/' + r; }, route);
  await expect(page.locator('#content-area')).not.toBeEmpty({ timeout: 15_000 });
}

async function expectNoAccess(page: Page, route: string, kind: string, title: RegExp) {
  await open(page, route);
  const panel = page.locator(`[data-no-access="${kind}"]`);
  await expect(panel, `${route} should say ${kind}`).toBeVisible();
  await expect(panel.locator('h1')).toHaveText(title);
  await expect(panel.getByRole('link', { name: 'Go to Home' })).toBeVisible();
}

async function expectPage(page: Page, route: string) {
  await open(page, route);
  await expect(page.locator('[data-no-access]'), `${route} should open`).toHaveCount(0);
}

test.describe('Pages people can open', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !devIds.user, 'Needs TEST_DEV_ADMIN_ID and TEST_DEV_USER_ID (dev mode)');
  });

  for (const [label, userId] of [['first user', () => devIds.user], ['second user', () => SECOND_USER_ID]] as const) {
    test(`a user is told which pages are not for them (${label})`, async ({ page }) => {
      const registryCalls: string[] = [];
      page.on('request', r => { if (/\/api\/v1\/environments\/[^/]+\/groups/.test(r.url())) registryCalls.push(r.url()); });
      await signInAs(page, userId());

      for (const route of OPEN_PAGES) await expectPage(page, route);
      for (const route of ENV_ADMIN_PAGES) await expectNoAccess(page, route, 'role-env-admin', /is for environment admins$/);
      for (const route of ADMIN_PAGES) await expectNoAccess(page, route, 'role-admin', /is for administrators$/);
      await expect(page.locator('[data-no-access] h1').first()).toBeVisible();
      expect(registryCalls, 'the VM registry loader never ran').toEqual([]);
    });
  }

  test('an environment admin opens the Manage pages and is told about the administrator ones', async ({ page, playwright }) => {
    // The test data is reseeded between runs, so make sure this user is an environment admin.
    const admin = await playwright.request.newContext({
      baseURL: process.env.BASE_URL || 'http://localhost:8080',
      extraHTTPHeaders: { 'X-User-Id': devIds.admin, 'Content-Type': 'application/json' },
    });
    const promoted = await admin.patch(`/api/v1/users/${ENV_ADMIN_ID}/roles`, { data: { admin: false, envAdmin: true } });
    expect(promoted.ok(), await promoted.text()).toBeTruthy();
    await admin.dispose();
    await signInAs(page, ENV_ADMIN_ID);
    for (const route of ENV_ADMIN_PAGES) await expectPage(page, route);
    for (const route of ADMIN_PAGES) await expectNoAccess(page, route, 'role-admin', /is for administrators$/);
  });

  test('an administrator opens every page', async ({ page }) => {
    await signInAs(page, devIds.admin);
    for (const route of [...OPEN_PAGES, ...ENV_ADMIN_PAGES, ...ADMIN_PAGES]) await expectPage(page, route);
  });

  test('an unknown address says the page does not exist', async ({ page }) => {
    await signInAs(page, devIds.user);
    await expectNoAccess(page, 'no-such-page', 'not-found', /This page doesn't exist/);
  });

  test("an environment someone cannot see says so, with a way to ask for access", async ({ page }) => {
    await signInAs(page, devIds.user);
    await open(page, 'environments/00000000-0000-0000-0000-000000000000');
    const panel = page.locator('[data-no-access="environment"]');
    await expect(panel).toBeVisible();
    await expect(panel.locator('h1')).toHaveText("You can't open this environment");
    await expect(panel.getByRole('link', { name: 'Request access' })).toHaveAttribute('href', '#/request-access');
  });
});
