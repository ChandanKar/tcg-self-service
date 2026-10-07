import { test, expect, type Page, type APIRequestContext } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E02-T03: Environment Detail has its own URL (#/environments/<id>) that survives refresh, Back
 * and direct links; the legacy #/environment-detail hash redirects; ?query is preserved.
 *
 * Seeding needs dev-mode identities (TEST_DEV_USER_ID / TEST_DEV_ADMIN_ID): the admin creates an
 * environment and grants the user access through the API.
 */

declare const ContentRouter: any;

let envId = '';
const envName = `Route E2E ${Date.now()}`;

async function api(playwright: any, userId: string): Promise<APIRequestContext> {
  return playwright.request.newContext({
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    extraHTTPHeaders: { 'X-User-Id': userId, 'Content-Type': 'application/json' },
  });
}

async function waitForRouter(page: Page) {
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined' && typeof ContentRouter.query === 'function');
}

test.describe('Route params and deep links', () => {
  test.beforeAll(async ({ playwright }) => {
    if (!devIds.admin || !devIds.user) return;
    const admin = await api(playwright, devIds.admin);
    const created = await admin.post('/api/v1/environments', {
      data: { name: `route-e2e-${Date.now()}`, displayName: envName, cloudProvider: 'AWS' },
    });
    expect(created.ok()).toBeTruthy();
    envId = (await created.json()).environmentId;

    const user = await (await admin.get(`/api/v1/users/${devIds.user}`)).json();
    const granted = await admin.post(`/api/v1/environments/${envId}/access`, {
      data: { userEmail: user.email, accessLevel: 'USER', durationDays: 1 },
    });
    expect(granted.ok()).toBeTruthy();
    await admin.dispose();
  });

  test.beforeEach(async () => {
    test.skip(!devIds.admin || !devIds.user || !canSignIn('user'),
      'Needs TEST_DEV_USER_ID and TEST_DEV_ADMIN_ID (dev mode) to seed an environment');
  });

  test('View puts the id in the URL; refresh keeps the detail; Back returns to the list', async ({ page }) => {
    await signIn(page, 'user');
    await waitForRouter(page);
    await page.evaluate(() => { location.hash = '#/my-environments'; });

    const view = page.locator(`[data-action="view"][data-env-id="${envId}"]`);
    await expect(view).toBeVisible({ timeout: 10_000 });
    await view.click();

    await expect(page).toHaveURL(new RegExp(`#/environments/${envId}$`));
    await expect(page.locator('#content-area')).toContainText(envName, { timeout: 10_000 });
    await expect(page.locator('.sidebar-menu-link[data-content="my-environments"]')).toHaveClass(/active/);

    await page.reload();
    await expect(page).toHaveURL(new RegExp(`#/environments/${envId}$`));
    await expect(page.locator('#content-area')).toContainText(envName, { timeout: 10_000 });

    await page.goBack();
    await expect(page).toHaveURL(/#\/my-environments$/);
    await expect(page.locator(`[data-action="view"][data-env-id="${envId}"]`)).toBeVisible({ timeout: 10_000 });
  });

  test('a direct link opens Environment Detail', async ({ page }) => {
    await page.context().setExtraHTTPHeaders({ 'X-User-Id': devIds.user });
    await page.goto(`/home#/environments/${envId}`);
    await expect(page.locator('#content-area')).toContainText(envName, { timeout: 10_000 });
  });

  test('the in-page Back button goes to #/my-environments', async ({ page }) => {
    await page.context().setExtraHTTPHeaders({ 'X-User-Id': devIds.user });
    await page.goto(`/home#/environments/${envId}`);
    await expect(page.locator('#content-area')).toContainText(envName, { timeout: 10_000 });
    await page.locator('#content-area a[href="#/my-environments"]').click();
    await expect(page).toHaveURL(/#\/my-environments$/);
  });

  test('the legacy #/environment-detail hash redirects to the list', async ({ page }) => {
    await signIn(page, 'user');
    await waitForRouter(page);
    await page.evaluate(() => { location.hash = '#/environment-detail'; });
    await expect(page).toHaveURL(/#\/my-environments$/);
    await expect(page.locator('#content-area')).not.toContainText('No environment specified');
  });

  test('ids are URL-encoded in the hash and decoded for the API call', async ({ page }) => {
    await signIn(page, 'user');
    await waitForRouter(page);
    const hash = await page.evaluate(() => ContentRouter.hashFor('environment-detail', { environmentId: 'env 1/x' }));
    expect(hash).toBe('#/environments/env%201%2Fx');

    const detailRequest = page.waitForRequest((r) => r.url().includes('/api/v1/environments/env%201%2Fx')
      || r.url().includes('/api/v1/environments/env%201/x'));
    await page.evaluate((h) => { location.hash = h; }, hash);
    const req = await detailRequest;
    expect(decodeURIComponent(new URL(req.url()).pathname)).toContain('/api/v1/environments/env 1/x');
  });

  test('Manage access opens Access Management with the grant modal', async ({ page }) => {
    test.skip(!canSignIn('admin'), 'Needs an admin sign-in');
    await page.context().setExtraHTTPHeaders({ 'X-User-Id': devIds.admin });
    await page.goto(`/home#/environments/${envId}`);
    await expect(page.locator('#content-area')).toContainText(envName, { timeout: 10_000 });

    await page.locator('#btn-env-manage-access').click();

    await expect(page).toHaveURL(/#\/access-management$/);
    await expect(page.locator('#grant-modal-title')).toContainText('Grant Access', { timeout: 10_000 });
    await expect(page.locator('#grant-modal-title')).toBeVisible();
  });

  test('?query survives load and refresh (Cost Management page state)', async ({ page }) => {
    test.skip(!canSignIn('admin'), 'Needs an admin sign-in');
    await signIn(page, 'admin');
    await waitForRouter(page);
    await page.evaluate(() => { location.hash = '#/cost-management?idle=1'; });
    await page.waitForTimeout(1500);
    await expect(page).toHaveURL(/#\/cost-management\?idle=1$/);
    expect(await page.evaluate(() => ContentRouter.query().idle)).toBe('1');

    await page.reload();
    await waitForRouter(page);
    await page.waitForTimeout(1500);
    await expect(page).toHaveURL(/#\/cost-management\?idle=1$/);
  });
});
