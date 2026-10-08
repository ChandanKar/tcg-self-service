import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E04-T09 (M21): User Management loads inactive users too, so the Inactive filter lists them and
 * Reactivate is reachable; the default view still shows active users only.
 *
 * Needs a dev-mode admin (TEST_DEV_ADMIN_ID) to onboard and deactivate a user through the API.
 */

declare const ContentRouter: any;

let userId = '';
let email = '';

async function api(playwright: any): Promise<APIRequestContext> {
  return playwright.request.newContext({
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    extraHTTPHeaders: { 'X-User-Id': devIds.admin, 'Content-Type': 'application/json' },
  });
}

async function openUserManagement(page: Page) {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/user-management'; });
  await expect(page.locator('#user-search')).toBeVisible({ timeout: 10_000 });
  // Narrow the table to the seeded user (a search keeps the status filter).
  await page.locator('#user-search').fill(email);
}

function rowOf(page: Page) {
  return page.locator('#content-area tr', { hasText: email });
}

test.describe('User Management: inactive users', () => {
  test.beforeEach(async ({ playwright }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode) to seed a user');
    email = `dora-e2e-${Date.now()}@example.com`;
    const admin = await api(playwright);
    const onboarded = await admin.post('/api/v1/users', { data: { email, displayName: 'Dora E2E' } });
    expect(onboarded.ok()).toBeTruthy();
    userId = (await onboarded.json()).user.userId;
    expect((await admin.delete(`/api/v1/users/${userId}`)).ok()).toBeTruthy();
    await admin.dispose();
  });

  test('the default view shows active users only', async ({ page }) => {
    await openUserManagement(page);
    await expect(page.locator('#filter-status')).toHaveValue('active');
    await expect(rowOf(page)).toHaveCount(0);
  });

  test('Inactive lists the deactivated user; Reactivate moves them to Active without a reload',
    async ({ page, playwright }) => {
      await openUserManagement(page);
      await page.locator('#filter-status').selectOption('inactive');
      const row = rowOf(page);
      await expect(row).toHaveCount(1);

      await row.locator('[data-bs-toggle="dropdown"]').click();
      await row.locator(`[data-action="reactivate"][data-user-id="${userId}"]`).click();
      await expect(page.getByText('User reactivated')).toBeVisible({ timeout: 10_000 });

      // Same page, filters kept: gone from Inactive, present under Active.
      await expect(page.locator('#filter-status')).toHaveValue('inactive');
      await expect(rowOf(page)).toHaveCount(0);
      await page.locator('#filter-status').selectOption('active');
      await expect(rowOf(page)).toHaveCount(1);

      const admin = await api(playwright);
      const user = await (await admin.get(`/api/v1/users/${userId}`)).json();
      await admin.dispose();
      expect(user.active).toBe(true);
    });
});
