import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E18-T04: My account > Cost lists the month-to-date cost of my environments. /users/me/cost is
 * faked in the browser: the total, one line per environment with its sparkline, the group-only
 * hint, the empty state, a failed load with Retry (other tabs unaffected) and keyboard navigation
 * onto the tab. U09-T05 (MyAccount.dc.html): "Month to date across the environments you can use"
 * and the total over the environments with a cost.
 */

declare const MyAccount: any;

const spark = (v: number) => Array.from({ length: 14 }, (_, i) => v + i);

const COST = {
  capped: false, totalEnvironments: 3, limit: 50,
  environments: [
    { environmentId: 'env-own', name: 'payments', displayName: 'Payments', scope: 'FULL', myLevel: 'ADMIN', owner: true,
      monthToDateEstimated: 120.5, changePercent: 12.0, sparkline: spark(5), runningVmCount: 2, totalVmCount: 3,
      scheduleRuleCount: 1, nextStop: new Date(Date.now() + 3600000).toISOString(), nextStart: null, leaseEndsAt: null, hint: null },
    { environmentId: 'env-view', name: 'billing', displayName: 'Billing', scope: 'FULL', myLevel: 'VIEWER', owner: false,
      monthToDateEstimated: 300, changePercent: -5.0, sparkline: spark(20), runningVmCount: 1, totalVmCount: 1,
      scheduleRuleCount: 0, nextStop: null, nextStart: null, leaseEndsAt: null, hint: null },
    { environmentId: 'env-groups', name: 'data', displayName: 'Data', scope: 'GROUPS', myLevel: null, owner: false,
      monthToDateEstimated: null, changePercent: null, sparkline: [], runningVmCount: 0, totalVmCount: 4,
      scheduleRuleCount: 0, nextStop: null, nextStart: null, leaseEndsAt: null,
      hint: 'Cost is shown per environment; you have access to some groups only' },
  ],
};

async function open(page: Page, tab: string) {
  await signIn(page, 'user');
  await page.waitForFunction(() => typeof MyAccount !== 'undefined');
  // Opening the first page closes any open panel; wait for it, or the panel can be shut right away.
  await expect(page.locator('#content-area h1').first()).toBeVisible({ timeout: 15_000 });
  await page.evaluate(t => MyAccount.open(t), tab);
}

test.describe('My Account cost tab', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  });

  test('the month-to-date total, then one line per environment with its cost and a sparkline', async ({ page }) => {
    await page.route(/\/api\/v1\/users\/me\/cost$/, r => r.fulfill({ json: COST }));
    await open(page, 'cost');

    await expect(page.locator('.my-account .ma-cost-label')).toHaveText('Month to date across the environments you can use');
    await expect(page.locator('#ma-cost-total')).toHaveText('$420.50');
    const rows = page.locator('.my-account .ma-cost-row');
    await expect(rows).toHaveCount(3);
    await expect(rows.nth(0)).toContainText('Payments');
    await expect(rows.nth(0).locator('.ma-cost-mtd')).toHaveText('$120.50');
    await expect(rows.nth(0).locator('svg.ma-spark')).toHaveAttribute('aria-label', /Payments: daily cost over 14 days, rising/);
    await expect(rows.nth(1).locator('.ma-cost-mtd')).toHaveText('$300.00');
    await expect(rows.nth(2)).toContainText('some groups only');
    await expect(rows.nth(2).locator('svg')).toHaveCount(0);
  });

  test('the tab loads on first open only', async ({ page }) => {
    let calls = 0;
    await page.route(/\/api\/v1\/users\/me\/cost$/, r => { calls++; return r.fulfill({ json: COST }); });
    await open(page, 'access');
    await page.waitForTimeout(500);
    expect(calls).toBe(0);

    await page.locator('#ma-tab-cost').click();
    await expect(page.locator('.my-account .ma-cost-row')).toHaveCount(3);
    await page.locator('#ma-tab-access').click();
    await page.locator('#ma-tab-cost').click();
    expect(calls).toBe(1);
  });

  test('a failed load shows Retry; the other tabs still work', async ({ page }) => {
    let calls = 0;
    await page.route(/\/api\/v1\/users\/me\/cost$/, r => {
      calls++;
      return calls === 1 ? r.fulfill({ status: 500, json: { message: 'boom' } }) : r.fulfill({ json: COST });
    });
    await open(page, 'cost');

    await expect(page.locator('.my-account .ma-error')).toContainText("Couldn't load your environments' cost");
    await page.locator('#ma-tab-profile').click();
    await expect(page.locator('#ma-tab-profile')).toHaveAttribute('aria-selected', 'true');
    await page.locator('#ma-tab-cost').click();
    await page.locator('.my-account [data-ma-action="retry"]').click();
    await expect(page.locator('.my-account .ma-cost-row')).toHaveCount(3);
  });

  test('empty: no environments yet, with a request-access button', async ({ page }) => {
    await page.route(/\/api\/v1\/users\/me\/cost$/, r => r.fulfill({ json: { ...COST, environments: [], totalEnvironments: 0 } }));
    await open(page, 'cost');

    await expect(page.locator('.my-account .ma-empty')).toContainText("You can't use any environment yet");
    await expect(page.locator('.my-account [data-ma-action="request-access"]')).toBeVisible();
  });

  test('ArrowRight from Access moves to Cost', async ({ page }) => {
    await page.route(/\/api\/v1\/users\/me\/cost$/, r => r.fulfill({ json: COST }));
    await open(page, 'access');

    await page.locator('#ma-tab-access').focus();
    await page.keyboard.press('ArrowRight');

    await expect(page.locator('#ma-tab-cost')).toBeFocused();
    await expect(page.locator('#ma-tab-cost')).toHaveAttribute('aria-selected', 'true');
    await expect(page.locator('.my-account .ma-cost-row')).toHaveCount(3);
  });
});
