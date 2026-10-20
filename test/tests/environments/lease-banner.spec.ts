import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E15-T06: the lease on the environment page, since U07-T05 the "Lease" fact in "About this
 * environment" with a chip dialog (LockLease.dc.html). Needs the app started with
 * LEASE_ENABLED=true; a lock with an expiry opens the lease, so no cloud call is involved.
 */

declare const ContentRouter: any;

let envId = '';
let api: APIRequestContext;

async function context(playwright: any, userId: string): Promise<APIRequestContext> {
  return playwright.request.newContext({
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    extraHTTPHeaders: { 'X-User-Id': userId, 'Content-Type': 'application/json' },
  });
}

async function openEnvironment(page: Page, role: 'user' | 'admin') {
  await signIn(page, role);
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(id => { location.hash = '#/environments/' + id; }, envId);
  await expect(page.locator('#env-fact-lease .lease-fact')).toBeVisible({ timeout: 15_000 });
}

test.describe('Lease fact', () => {
  test.beforeAll(async ({ playwright }) => {
    if (!devIds.admin || !devIds.user) return;
    api = await context(playwright, devIds.admin);
    const probe = await api.get('/api/v1/environments');
    test.skip(!probe.ok(), 'App not reachable');
    const created = await api.post('/api/v1/environments', {
      data: { name: `lease-e2e-${Date.now()}`, displayName: `Lease E2E ${Date.now()}`, cloudProvider: 'AWS' },
    });
    envId = (await created.json()).environmentId;
    const policy = await api.put(`/api/v1/environments/${envId}/lease-policy`,
      { data: { enabled: true, defaultMinutes: 60, maxMinutes: 120 } });
    test.skip(policy.status() === 404, 'Leases are off (start the app with LEASE_ENABLED=true)');
    const user = await (await api.get(`/api/v1/users/${devIds.user}`)).json();
    await api.post(`/api/v1/environments/${envId}/access`,
      { data: { userEmail: user.email, accessLevel: 'USER', durationDays: 1 } });
    const userApi = await context(playwright, devIds.user);
    const locked = await userApi.post(`/api/v1/environments/${envId}/lock/acquire`,
      { data: { reason: 'lease e2e', expectedDurationMinutes: 60 } });
    expect(locked.ok()).toBeTruthy();
    await userApi.dispose();
  });

  test.beforeEach(() => {
    test.skip(!devIds.admin || !devIds.user || !canSignIn('user') || !envId, 'Needs dev identities and leases on');
  });

  test('a user sees the end time and extends within the maximum', async ({ page }) => {
    await openEnvironment(page, 'user');
    const banner = page.locator('#env-fact-lease .lease-fact');
    await expect(banner.locator('.env-fact-value')).toContainText(/^Stops at \d\d:\d\d · /);
    await expect(banner.locator('.lease-countdown')).toContainText(/in (59|60|1 h) ?(min)?/);
    await expect(banner).toContainText('more without approval');
    await expect(banner.locator('[data-action="lease-settings"]')).toHaveCount(0);

    await banner.locator('[data-action="lease-extend"]').click();
    const chips = page.locator('#lease-extend-minutes');
    await expect(chips.getByRole('button')).toHaveText(['30 min', '1 h', '2 h', '8 h', '12 h']);
    await chips.getByRole('button', { name: '30 min' }).click();
    await expect(page.locator('#lease-extend-preview')).not.toContainText('approval');
    await expect(page.locator('#lease-extend-reason-wrap')).toBeHidden();
    await expect(page.locator('#lease-extend-submit')).toHaveText('Extend by 30 min');
    await expect(page.locator('#lease-extend-new-stop')).toHaveText(/^\d\d:\d\d( tomorrow)?$/);
    await page.locator('#lease-extend-submit').click();
    await expect(page.locator('#leaseExtendModal')).toBeHidden();
    await expect(banner.locator('.lease-countdown')).toContainText(/1 h (29|30) min/, { timeout: 10_000 });
  });

  test('beyond the maximum the owner approves from the same page', async ({ page, browser }) => {
    await openEnvironment(page, 'user');
    const banner = page.locator('#env-fact-lease .lease-fact');
    await banner.locator('[data-action="lease-extend"]').click();
    await page.locator('#lease-extend-minutes').getByRole('button', { name: '2 h', exact: true }).click();
    await expect(page.locator('#lease-extend-preview')).toContainText('Needs owner approval');
    await expect(page.locator('#lease-extend-submit')).toHaveText('Ask the owner');
    await page.locator('#lease-extend-reason').fill('Release rehearsal runs late');
    await page.locator('#lease-extend-submit').click();
    await expect(page.locator('#env-fact-lease .lease-pill')).toContainText('Waiting for approval', { timeout: 10_000 });

    const ownerPage = await browser.newPage();
    await openEnvironment(ownerPage, 'admin');
    await expect(ownerPage.locator('[data-action="lease-settings"]')).toBeVisible();
    await ownerPage.locator('#env-fact-lease [data-action="lease-approve"]').click();
    await expect(ownerPage.locator('#env-fact-lease .lease-pill')).toHaveCount(0, { timeout: 10_000 });
    await ownerPage.close();
  });

  test('My Account lists the running environment with Extend (E15-T07)', async ({ page }) => {
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof (window as any).MyAccount !== 'undefined' || typeof ContentRouter !== 'undefined');
    // Opening the first page closes any open panel; wait for it, or the panel can be shut right away.
    await expect(page.locator('#content-area h1').first()).toBeVisible({ timeout: 15_000 });
    await page.evaluate(() => (window as any).eval('MyAccount.open("access")'));
    const section = page.locator('#dynamicSlideoutPanel .ma-leases');
    await expect(section).toBeVisible({ timeout: 10_000 });
    const row = section.locator('.ma-lease', { has: page.locator(`[data-env-id="${envId}"]`) });
    await expect(row).toContainText('stops at');
    await expect(row.locator('[data-ma-action="lease-extend"]')).toBeVisible();
  });
});
