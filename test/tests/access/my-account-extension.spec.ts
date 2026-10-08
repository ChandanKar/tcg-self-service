import { test, expect, type APIRequestContext } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E04-T05 (M19): an extension adds the days to the current expiry. My Account previews the new
 * expiry while the user picks a length, the request is flagged as an extension, and approving
 * it moves the expiry from the old date (not from the approval time).
 *
 * Needs dev-mode identities (TEST_DEV_USER_ID / TEST_DEV_ADMIN_ID) to seed a grant through the API.
 */

declare const MyAccount: any;

const DAY_MS = 24 * 60 * 60 * 1000;
let envId = '';
let envName = '';
let accessId = '';
let expiresAt = 0;

async function api(playwright: any, userId: string): Promise<APIRequestContext> {
  return playwright.request.newContext({
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    extraHTTPHeaders: { 'X-User-Id': userId, 'Content-Type': 'application/json' },
  });
}

test.describe('My Account: request an extension', () => {
  test.beforeAll(async ({ playwright }) => {
    if (!devIds.admin || !devIds.user) return;
    const admin = await api(playwright, devIds.admin);
    envName = `Extend E2E ${Date.now()}`;
    const created = await admin.post('/api/v1/environments', {
      data: { name: `extend-e2e-${Date.now()}`, displayName: envName, cloudProvider: 'AWS' },
    });
    expect(created.ok()).toBeTruthy();
    envId = (await created.json()).environmentId;

    const user = await (await admin.get(`/api/v1/users/${devIds.user}`)).json();
    // 3 days: inside the 7-day extension window.
    const granted = await admin.post(`/api/v1/environments/${envId}/access`, {
      data: { userEmail: user.email, accessLevel: 'USER', durationDays: 3 },
    });
    expect(granted.ok()).toBeTruthy();
    const grant = await granted.json();
    accessId = grant.accessId;
    expiresAt = new Date(grant.expiresAt).getTime();
    await admin.dispose();
  });

  test.beforeEach(async () => {
    test.skip(!devIds.admin || !devIds.user || !canSignIn('user'),
      'Needs TEST_DEV_USER_ID and TEST_DEV_ADMIN_ID (dev mode) to seed a grant');
  });

  test('previews the new expiry from the current one, and approval extends from it', async ({ page, playwright }) => {
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof MyAccount !== 'undefined');
    await page.evaluate(() => MyAccount.open('access'));

    const extend = page.locator(`[data-ma-action="extend"][data-access-id="${accessId}"]`);
    await expect(extend).toBeVisible({ timeout: 10_000 });
    await extend.click();

    const formatted = (ms: number) => page.evaluate((t) =>
      new Date(t).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }), ms);

    // Default 30 days, counted from the current expiry (3 days away), not from today.
    const preview = page.locator('#ma-extend-new-expiry');
    await expect(preview).toHaveText(`New expiry if approved now: ${await formatted(expiresAt + 30 * DAY_MS)}`);

    await page.locator('#ma-extend-days').selectOption('7');
    await expect(preview).toHaveText(`New expiry if approved now: ${await formatted(expiresAt + 7 * DAY_MS)}`);

    await page.locator('#ma-extend-reason').fill('Release testing runs one more week.');
    await page.locator('.ma-extend button[type="submit"]').click();
    // Other specs may leave the same user with pending extensions: check this grant's card only.
    const card = page.locator('.ma-card', { hasText: envName });
    await expect(card.locator('.ma-pill', { hasText: 'Extension requested' })).toBeVisible({ timeout: 10_000 });

    const admin = await api(playwright, devIds.admin);
    const requests = await (await admin.get(`/api/v1/environments/${envId}/access-requests`)).json();
    const request = requests.find((r: any) => r.requesterId === devIds.user && r.status === 'PENDING');
    expect(request, 'the extension request is listed for reviewers').toBeTruthy();
    expect(request.extension).toBe(true);
    expect(request.durationDays).toBe(7);

    const approved = await admin.post(`/api/v1/access-requests/${request.requestId}/approve`, { data: {} });
    expect(approved.ok()).toBeTruthy();
    const grant = await approved.json();
    expect(grant.accessId).toBe(accessId);
    // Old expiry + 7 days (allow a minute of clock skew).
    expect(Math.abs(new Date(grant.expiresAt).getTime() - (expiresAt + 7 * DAY_MS))).toBeLessThan(60_000);
    await admin.dispose();
  });
});
