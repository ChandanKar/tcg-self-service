import { test, expect, type APIRequestContext } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E13-T03: slide-out panels are modal dialogs — focus moves in and stays in, Escape while typing
 * keeps the draft, Escape elsewhere closes and returns focus, closed panels are out of the Tab order.
 */

declare const ContentRouter: any;
declare const MyAccount: any;

let accessId = '';

async function api(playwright: any, userId: string): Promise<APIRequestContext> {
  return playwright.request.newContext({
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    extraHTTPHeaders: { 'X-User-Id': userId, 'Content-Type': 'application/json' },
  });
}

test.describe('Slide-out dialogs', () => {
  test.beforeAll(async ({ playwright }) => {
    if (!devIds.admin || !devIds.user) return;
    const admin = await api(playwright, devIds.admin);
    const created = await admin.post('/api/v1/environments', {
      data: { name: `dialog-e2e-${Date.now()}`, displayName: `Dialog E2E ${Date.now()}`, cloudProvider: 'AWS' },
    });
    expect(created.ok()).toBeTruthy();
    const envId = (await created.json()).environmentId;
    const user = await (await admin.get(`/api/v1/users/${devIds.user}`)).json();
    const granted = await admin.post(`/api/v1/environments/${envId}/access`, {
      data: { userEmail: user.email, accessLevel: 'USER', durationDays: 3 },
    });
    expect(granted.ok()).toBeTruthy();
    accessId = (await granted.json()).accessId;
    await admin.dispose();
  });

  test.beforeEach(async ({ page }) => {
    test.skip(!devIds.admin || !devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID and TEST_DEV_ADMIN_ID');
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.waitForLoadState('networkidle');
  });

  test('My Account is a dialog that holds focus and returns it on Escape', async ({ page }) => {
    await page.locator('.user-profile-trigger').click();
    await page.locator('#my-account-btn').click();

    const panel = page.locator('#dynamicSlideoutPanel');
    await expect(panel).toHaveAttribute('role', 'dialog');
    await expect(panel).toHaveAttribute('aria-modal', 'true');
    // U09-T05: the dialog is named after the person, with "email · role" under the name.
    const me = await (await page.request.get('/api/v1/users/me/profile', { headers: { 'X-User-Id': devIds.user } })).json();
    await expect(page.locator('#dynamicSlideoutTitle')).toHaveText(me.displayName);
    const role = me.admin ? 'Administrator' : me.envAdmin ? 'Environment admin' : 'User';
    await expect(panel.locator('.ma-head-sub')).toHaveText(`${me.email} · ${role}`);
    await expect(panel.locator('.ma-avatar')).toHaveAttribute('aria-hidden', 'true');
    await expect(panel.getByRole('tab')).toHaveText(['Access', 'Cost', 'Notifications', 'Profile']);
    await expect(panel.getByRole('tab', { name: 'Access' })).toHaveAttribute('aria-selected', 'true');
    await expect.poll(() => page.evaluate(() =>
      !!document.getElementById('dynamicSlideoutPanel')?.contains(document.activeElement))).toBe(true);

    for (let i = 0; i < 30; i++) {
      await page.keyboard.press('Tab');
      expect(await page.evaluate(() =>
        !!document.getElementById('dynamicSlideoutPanel')?.contains(document.activeElement))).toBe(true);
    }

    await page.locator('#dynamicSlideoutPanel .close-btn').focus();
    await page.keyboard.press('Escape');
    await expect(panel).not.toHaveClass(/show/);
    await expect(page.locator('.user-profile-trigger')).toBeFocused();
  });

  test('Escape while typing an extension reason keeps the panel and the draft', async ({ page }) => {
    await page.evaluate(() => MyAccount.open('access'));
    const extend = page.locator(`[data-ma-action="extend"][data-access-id="${accessId}"]`);
    await expect(extend).toBeVisible({ timeout: 10_000 });
    // The panel slides in and its lists load after it opens; click once it has settled.
    await expect(page.locator('#dynamicSlideoutPanel')).toHaveClass(/show/);
    await page.waitForLoadState('networkidle');
    await extend.click();

    const reason = page.locator('#ma-extend-reason');
    await reason.fill('Need one more week of testing');
    await reason.press('Escape');
    await expect(page.locator('#dynamicSlideoutPanel')).toHaveClass(/show/);
    await expect(reason).toHaveValue('Need one more week of testing');
  });

  test('old tab names still work: Overview opens Access, Activity goes to My activity', async ({ page }) => {
    await page.evaluate(() => MyAccount.open('overview'));
    await expect(page.locator('#ma-tab-access')).toHaveAttribute('aria-selected', 'true');
    await expect(page.locator('#ma-tab-overview, #ma-tab-activity')).toHaveCount(0);
    await page.evaluate(() => MyAccount.open('activity'));
    await expect(page).toHaveURL(/#\/activity-logs$/);
    await expect(page.locator('#dynamicSlideoutPanel')).not.toHaveClass(/show/);
  });

  test('Profile: signed in with, role, team, shortcuts and Sign out', async ({ page }) => {
    await page.route(/\/api\/v1\/users\/me\/profile$/, async route => {
      const real = await (await route.fetch()).json();
      await route.fulfill({ json: { ...real, admin: false, envAdmin: false, authMethod: 'ENTRA_ID', teamNames: ['Payments'] } });
    });
    await page.evaluate(() => MyAccount.open('profile'));
    const profile = page.locator('#dynamicSlideoutPanel .ma-profile');
    await expect(profile.locator('.ma-profile-row')).toHaveText([
      /^Signed in withMicrosoft \(Entra ID\)$/, /^RoleUser$/, /^TeamPayments$/, /^Previous sign-in/, /^Keyboard shortcuts\?\s*to see them$/]);
    await expect(profile.getByRole('button', { name: 'Sign out' })).toBeVisible();
  });

  test('closed panels are not in the Tab order', async ({ page }) => {
    await page.evaluate(() => MyAccount.open());
    await page.locator('#dynamicSlideoutPanel .close-btn').click();
    await expect(page.locator('#dynamicSlideoutPanel')).not.toHaveClass(/show/);

    await page.locator('body').focus();
    for (let i = 0; i < 40; i++) {
      await page.keyboard.press('Tab');
      expect(await page.evaluate(() => !!document.activeElement?.closest('.slideout-panel'))).toBe(false);
    }
  });
});
