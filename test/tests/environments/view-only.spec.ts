import { test, expect, type APIRequestContext } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * UX simplification: the environment page shows Start/Stop only to people who can use them. A
 * viewer gets a view-only note with a way to ask for more; an operator gets the controls. The
 * VMs come before the cost and settings panels.
 */

declare const ContentRouter: any;

async function api(playwright: any, userId: string): Promise<APIRequestContext> {
  return playwright.request.newContext({
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    extraHTTPHeaders: { 'X-User-Id': userId, 'Content-Type': 'application/json' },
  });
}

async function environmentWithUserAt(playwright: any, level: 'VIEWER' | 'USER'): Promise<string> {
  const admin = await api(playwright, devIds.admin);
  const stamp = Date.now();
  const env = await (await admin.post('/api/v1/environments', {
    data: { name: `view-only-${level.toLowerCase()}-${stamp}`, displayName: `View ${level} ${stamp}`, cloudProvider: 'AWS' },
  })).json();
  await admin.post(`/api/v1/environments/${env.environmentId}/groups`, {
    data: { name: 'web', displayName: 'Web', sequencePosition: 1 },
  });
  const user = await (await admin.get(`/api/v1/users/${devIds.user}`)).json();
  await admin.post(`/api/v1/environments/${env.environmentId}/access`, {
    data: { userEmail: user.email, accessLevel: level, durationDays: 1 },
  });
  await admin.dispose();
  return env.environmentId;
}

async function open(page: any, envId: string) {
  await signIn(page, 'user');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate((id: string) => { location.hash = '#/environments/' + id; }, envId);
  await expect(page.locator('#env-detail-view .env-detail-summary')).toBeVisible({ timeout: 15_000 });
}

test.describe('Environment page by access level', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !devIds.admin || !canSignIn('user'), 'Needs TEST_DEV_USER_ID and TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('a viewer sees a view-only note and no start or stop controls', async ({ page, playwright }) => {
    const envId = await environmentWithUserAt(playwright, 'VIEWER');
    await open(page, envId);

    await expect(page.locator('.env-viewonly-note')).toContainText('view-only access');
    await expect(page.locator('.env-viewonly-note a')).toHaveAttribute('href', '#/request-access');
    await expect(page.locator('#btn-env-action')).toHaveCount(0);
    await expect(page.locator('[data-action="group-action"]')).toHaveCount(0);
    await expect(page.locator('[data-lock-action="acquire"]')).toHaveCount(0);
  });

  test('an operator gets Start All, a breadcrumb back to the list, and VMs before cost', async ({ page, playwright }) => {
    const envId = await environmentWithUserAt(playwright, 'USER');
    await open(page, envId);

    await expect(page.locator('.env-viewonly-note')).toHaveCount(0);
    await expect(page.locator('#btn-env-action')).toHaveText(/Start All/);
    await expect(page.locator('.env-breadcrumb a')).toHaveAttribute('href', '#/my-environments');
    const order = await page.locator('#env-detail-view .env-section-title').allTextContents();
    expect(order).toEqual(['Groups & VMs', 'Cost & settings']);
  });
});
