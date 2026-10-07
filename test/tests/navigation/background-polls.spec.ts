import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E02-T05: background polls (notification bell, Pending Requests badge) are silent on errors,
 * never redirect on 401, start only after sign-in and pause while the tab is hidden.
 */

declare const RealTime: any;

const COUNT_URL = '**/api/v1/notifications/count';
const PENDING_URL = '**/api/v1/access-requests/pending';

async function appReady(page: Page) {
  await page.waitForFunction(() => typeof RealTime !== 'undefined' && RealTime.activePolls().includes('notificationCount'));
}

const errorToasts = (page: Page) => page.locator('#notification-container .notification-toast.toast-error');
const warningToasts = (page: Page) => page.locator('#notification-container .notification-toast.toast-warning');

test.describe('Background polls', () => {
  test.beforeEach(async () => {
    test.skip(!canSignIn('user'), 'No sign-in configured (TEST_DEV_USER_ID or TEST_USERNAME/TEST_PASSWORD in test/.env)');
  });

  test('a failing bell poll shows no error toast, now or on the next 60 s tick', async ({ page }) => {
    await page.clock.install();
    let calls = 0;
    await page.route(COUNT_URL, (route) => { calls++; return route.fulfill({ status: 500, body: '{"error":"boom"}' }); });

    await signIn(page, 'user');
    await appReady(page);
    await page.clock.runFor(61_000);

    await expect.poll(() => calls).toBeGreaterThanOrEqual(2);
    await expect(errorToasts(page)).toHaveCount(0);
  });

  test('a 401 from a poll stops polling with one notice and no redirect', async ({ page }) => {
    await page.clock.install();
    await page.route(COUNT_URL, (route) => route.fulfill({ status: 401, body: '{"error":"Unauthorized"}' }));

    await signIn(page, 'user');
    await page.waitForFunction(() => typeof RealTime !== 'undefined');
    await expect(warningToasts(page)).toHaveCount(1, { timeout: 10_000 });
    await expect(warningToasts(page)).toContainText('session has expired');

    await page.clock.runFor(130_000);
    await expect(page).toHaveURL(/\/home/);
    await expect(warningToasts(page)).toHaveCount(1);
    expect(await page.evaluate(() => RealTime.activePolls())).toEqual([]);
  });

  test('the bell does not poll while the tab is hidden and catches up when visible', async ({ page }) => {
    await page.clock.install();
    let calls = 0;
    await page.route(COUNT_URL, (route) => { calls++; return route.fulfill({ status: 200, contentType: 'application/json', body: '{"count":0}' }); });

    await signIn(page, 'user');
    await appReady(page);
    const afterStart = calls;

    await page.evaluate(() => {
      Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await page.clock.runFor(125_000);
    expect(calls).toBe(afterStart);

    await page.evaluate(() => {
      Object.defineProperty(document, 'hidden', { configurable: true, get: () => false });
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await expect.poll(() => calls).toBe(afterStart + 1);
  });

  test('the bell is first polled only after the user is known', async ({ page }) => {
    const order: string[] = [];
    page.on('request', (r) => {
      const u = r.url();
      if (u.includes('/api/v1/users/me')) order.push('me');
      if (u.includes('/api/v1/notifications/count')) order.push('count');
    });

    await signIn(page, 'user');
    await appReady(page);

    expect(order.indexOf('me')).toBeGreaterThanOrEqual(0);
    expect(order.indexOf('count')).toBeGreaterThan(order.indexOf('me'));
  });

  test('the Pending Requests badge shows the count for env admins and hides at zero', async ({ page }) => {
    test.skip(!devIds.admin && !canSignIn('admin'), 'Needs an admin (env admin) sign-in');
    let pending: unknown[] = [{ requestId: 'a' }, { requestId: 'b' }, { requestId: 'c' }];
    await page.route(PENDING_URL, (route) => route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify(pending),
    }));

    await signIn(page, 'admin');
    await appReady(page);
    const badge = page.locator('.pending-count').first();
    await expect(badge).toHaveText('3', { timeout: 10_000 });
    await expect(badge).toBeVisible();

    pending = [];
    await page.evaluate(() => RealTime.updatePendingBadge());
    await expect(badge).toBeHidden();
  });
});
