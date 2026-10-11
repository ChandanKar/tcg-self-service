import { test, expect, type Page, type APIRequestContext } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E13-T10: every button and link has an accessible name, My Account and the bell meet the 44px
 * touch target on a phone, and re-rendering My Account keeps the caret in the reason box.
 */

declare const ContentRouter: any;
declare const MyAccount: any;
declare const NotificationBell: any;

async function nameViolations(page: Page, include?: string) {
  let builder = new AxeBuilder({ page }).withRules(['button-name', 'link-name']);
  if (include) builder = builder.include(include);
  const result = await builder.analyze();
  return result.violations.flatMap(v => v.nodes.map(n => `${v.id}: ${n.target.join(' ')} ${n.html.slice(0, 120)}`));
}

async function settle(page: Page) {
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(500);
}

test.describe('Accessible names (admin)', () => {
  test.beforeEach(async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await settle(page);
  });

  for (const [hash, ready] of [
    ['#/vm-registry', '#content-area table'],
    ['#/access-management', '#content-area table'],
    ['#/user-management', '#content-area table'],
    ['#/my-environments', '#env-list-search'],
  ] as const) {
    test(`${hash} buttons and links are named`, async ({ page }) => {
      await page.evaluate(h => { location.hash = h; }, hash);
      await expect(page.locator(ready).first()).toBeVisible({ timeout: 15_000 });
      await settle(page);
      expect(await nameViolations(page)).toEqual([]);
    });
  }

  test('Environment Detail buttons are named', async ({ page }) => {
    const vm = (id: string, status: string) => ({ vmId: id, name: `vm-${id}`, provider: 'AWS', region: 'ap-south-1',
      status, sequencePosition: 1 });
    await page.route(/\/api\/v1\/environments\/[^/]+\/vms(\?.*)?$/, route => route.fulfill({ json: [
      { group: { groupId: 'g1', name: 'web', displayName: 'Web tier', sequencePosition: 1 },
        vms: [vm('a1', 'RUNNING'), vm('a2', 'STOPPED')] },
    ] }));
    await page.evaluate(() => { location.hash = '#/my-environments'; });
    await page.locator('#content-area [data-action="view"]').first().click();
    await expect(page.locator('[data-action="stop-vm"]').first()).toBeAttached({ timeout: 15_000 });
    await settle(page);
    expect(await nameViolations(page)).toEqual([]);
  });

  test('the open bell dropdown is named', async ({ page }) => {
    await page.locator('#notification-bell-wrapper button').first().click();
    await settle(page);
    expect(await nameViolations(page, '#notification-bell-wrapper')).toEqual([]);
  });
});

test.describe('Touch targets and caret (phone)', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  let accessId = '';
  test.beforeAll(async ({ playwright }) => {
    if (!devIds.admin || !devIds.user) return;
    const admin: APIRequestContext = await playwright.request.newContext({
      baseURL: process.env.BASE_URL || 'http://localhost:8080',
      extraHTTPHeaders: { 'X-User-Id': devIds.admin, 'Content-Type': 'application/json' },
    });
    const created = await admin.post('/api/v1/environments', {
      data: { name: `target-e2e-${Date.now()}`, displayName: `Target E2E ${Date.now()}`, cloudProvider: 'AWS' },
    });
    const envId = (await created.json()).environmentId;
    const user = await (await admin.get(`/api/v1/users/${devIds.user}`)).json();
    const granted = await admin.post(`/api/v1/environments/${envId}/access`, {
      data: { userEmail: user.email, accessLevel: 'USER', durationDays: 3 },
    });
    accessId = (await granted.json()).accessId;
    await admin.dispose();
  });

  test.beforeEach(async ({ page }) => {
    test.skip(!devIds.admin || !devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID and TEST_DEV_ADMIN_ID');
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof MyAccount !== 'undefined');
    await settle(page);
  });

  async function smallTargets(page: Page, selector: string) {
    return page.locator(selector).evaluateAll(els => els
      .filter(el => (el as HTMLElement).offsetParent !== null)
      .map(el => { const r = el.getBoundingClientRect(); return { text: (el.textContent || '').trim().slice(0, 30), w: r.width, h: r.height }; })
      .filter(b => b.w < 44 || b.h < 44));
  }

  test('My Account controls are at least 44px', async ({ page }) => {
    await page.evaluate(() => MyAccount.open('activity'));
    await expect(page.locator('#dynamicSlideoutPanel')).toHaveClass(/show/);
    await settle(page);
    expect(await smallTargets(page, '#dynamicSlideoutPanel .ma-filter, #dynamicSlideoutPanel .ma-link')).toEqual([]);
  });

  test('bell controls are at least 44px', async ({ page }) => {
    await page.locator('#notification-bell-wrapper button').first().click();
    await settle(page);
    expect(await smallTargets(page, '#notification-bell-wrapper .notification-read-btn')).toEqual([]);
  });

  test('re-rendering My Account keeps the caret in the reason box', async ({ page }) => {
    await page.evaluate(() => MyAccount.open('access'));
    const extend = page.locator(`[data-ma-action="extend"][data-access-id="${accessId}"]`);
    await expect(extend).toBeVisible({ timeout: 10_000 });
    await extend.click();
    const reason = page.locator('#ma-extend-reason');
    await reason.fill('Release testing needs another week');
    await reason.evaluate((el: HTMLTextAreaElement) => el.setSelectionRange(8, 8));

    await page.evaluate(() => MyAccount.refresh ? MyAccount.refresh() : MyAccount.open('access'));
    await settle(page);
    await expect(reason).toBeFocused();
    expect(await page.locator('#ma-extend-reason').evaluate((el: HTMLTextAreaElement) => el.selectionStart)).toBe(8);
  });
});
