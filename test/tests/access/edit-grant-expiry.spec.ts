import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E04-T06 (H15): editing a grant keeps its expiry unless the admin picks another one. The
 * modal used to reset Duration to "Permanent", so saving a notes-only edit cleared the expiry.
 *
 * Needs dev-mode identities (TEST_DEV_USER_ID / TEST_DEV_ADMIN_ID) to seed a grant through the API.
 */

declare const AccessManagement: any;
declare const ContentRouter: any;

const DAY_MS = 24 * 60 * 60 * 1000;
let envId = '';
let accessId = '';

async function api(playwright: any): Promise<APIRequestContext> {
  return playwright.request.newContext({
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    extraHTTPHeaders: { 'X-User-Id': devIds.admin, 'Content-Type': 'application/json' },
  });
}

async function storedGrant(playwright: any): Promise<any> {
  const admin = await api(playwright);
  const grants = await (await admin.get(`/api/v1/environments/${envId}/access`)).json();
  await admin.dispose();
  return grants.find((g: any) => g.accessId === accessId);
}

async function openEdit(page: Page) {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined' && typeof AccessManagement !== 'undefined');
  await page.evaluate(() => { location.hash = '#/access-management'; });
  await page.locator('#filter-environment').selectOption(envId);
  const edit = page.locator(`[data-action="edit"][data-access-id="${accessId}"]`);
  await expect(edit).toBeVisible({ timeout: 10_000 });
  await edit.click();
  await expect(page.locator('#grantAccessModal')).toBeVisible();
}

/** Save the modal and return the PATCH body it sent. */
async function saveAndCapture(page: Page): Promise<any> {
  const [request] = await Promise.all([
    page.waitForRequest(r => r.method() === 'PATCH' && r.url().includes(`/access-grants/${accessId}`)),
    page.locator('#btn-confirm-grant').click(),
  ]);
  await expect(page.locator('#grantAccessModal')).toBeHidden({ timeout: 10_000 });
  return request.postDataJSON();
}

test.describe.serial('Edit grant: expiry is kept unless changed', () => {
  test.beforeAll(async ({ playwright }) => {
    if (!devIds.admin || !devIds.user) return;
    const admin = await api(playwright);
    const created = await admin.post('/api/v1/environments', {
      data: { name: `edit-grant-e2e-${Date.now()}`, displayName: `Edit Grant E2E ${Date.now()}`, cloudProvider: 'AWS' },
    });
    expect(created.ok()).toBeTruthy();
    envId = (await created.json()).environmentId;
    const user = await (await admin.get(`/api/v1/users/${devIds.user}`)).json();
    const granted = await admin.post(`/api/v1/environments/${envId}/access`, {
      data: { userEmail: user.email, accessLevel: 'VIEWER', durationDays: 30 },
    });
    expect(granted.ok()).toBeTruthy();
    accessId = (await granted.json()).accessId;
    await admin.dispose();
  });

  test.beforeEach(async () => {
    test.skip(!devIds.admin || !devIds.user || !canSignIn('admin'),
      'Needs TEST_DEV_USER_ID and TEST_DEV_ADMIN_ID (dev mode) to seed a grant');
  });

  test('opens with "Keep current expiry" selected and shows the current expiry', async ({ page }) => {
    await openEdit(page);
    await expect(page.locator('#grant-duration')).toHaveValue('keep');
    await expect(page.locator('#grant-current-expiry')).toContainText('Currently expires');
  });

  test('a notes-only edit sends no expiry change and keeps the expiry', async ({ page, playwright }) => {
    const before = (await storedGrant(playwright)).expiresAt;
    expect(before).toBeTruthy();

    await openEdit(page);
    await page.locator('#grant-notes').fill('Moved to the payments team');
    const body = await saveAndCapture(page);

    expect(body.durationDays).toBeNull();
    expect(body.clearExpiry).toBeNull();
    const after = await storedGrant(playwright);
    expect(after.expiresAt).toBe(before);
    expect(after.notes).toBe('Moved to the payments team');
  });

  test('"30 days from today" sets the expiry 30 days from now', async ({ page, playwright }) => {
    await openEdit(page);
    await page.locator('#grant-duration').selectOption('30');
    const body = await saveAndCapture(page);

    expect(body.durationDays).toBe(30);
    expect(body.clearExpiry).toBeNull();
    const after = await storedGrant(playwright);
    expect(Math.abs(new Date(after.expiresAt).getTime() - (Date.now() + 30 * DAY_MS))).toBeLessThan(5 * 60_000);
  });

  test('"Permanent" clears the expiry, and the next edit says it is permanent', async ({ page, playwright }) => {
    await openEdit(page);
    await page.locator('#grant-duration').selectOption('permanent');
    const body = await saveAndCapture(page);

    expect(body.clearExpiry).toBe(true);
    expect((await storedGrant(playwright)).expiresAt).toBeNull();

    await page.locator(`[data-action="edit"][data-access-id="${accessId}"]`).click();
    await expect(page.locator('#grant-current-expiry')).toHaveText('Currently permanent');
    await expect(page.locator('#grant-duration')).toHaveValue('keep');
  });

  test('a new grant defaults to Permanent and cannot pick "Keep current expiry"', async ({ page }) => {
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof AccessManagement !== 'undefined');
    await page.evaluate((id) => AccessManagement.openForEnvironment(id), envId);
    await expect(page.locator('#grantAccessModal')).toBeVisible({ timeout: 10_000 });

    await expect(page.locator('#grant-duration')).toHaveValue('permanent');
    await expect(page.locator('#grant-duration option[value="keep"]')).toBeDisabled();
    await expect(page.locator('#grant-current-expiry')).toHaveText('');
  });
});
