import { test, expect } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E11-T11: dashboard search matches the display name as well as the slug, and the badge shows
 * the lock state (Open in green even when every VM is stopped). The real summary is fetched and
 * two environment rows are added to it in the browser.
 */

test.describe('Dashboard environments table', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  });

  test('search by display name, and the badge follows the lock', async ({ page }) => {
    await page.route(/\/api\/v1\/dashboard\/summary/, async route => {
      const response = await route.fetch();
      const summary = await response.json();
      const base = (summary.environments && summary.environments[0]) || {};
      summary.environments = [
        { ...base, environmentId: 'e11-pay', name: 'pay-qa', displayName: 'Payments QA', locked: false,
          runningVms: 0, totalVms: 3 },
        { ...base, environmentId: 'e11-bill', name: 'billing', displayName: 'Billing', locked: true,
          runningVms: 2, totalVms: 2 },
      ];
      return route.fulfill({ response, body: JSON.stringify(summary) });
    });
    await signIn(page, 'user');
    await expect(page.locator('#dashboard-env-rows .dashboard-env-row')).toHaveCount(2, { timeout: 15_000 });

    const payments = page.locator('#dashboard-env-rows .dashboard-env-row', { hasText: 'Payments QA' });
    await expect(payments.locator('.badge')).toHaveText('Open');
    await expect(payments.locator('.badge')).toHaveClass(/bg-success/);
    const billing = page.locator('#dashboard-env-rows .dashboard-env-row', { hasText: 'Billing' });
    await expect(billing.locator('.badge')).toHaveText('Locked');
    await expect(billing.locator('.badge')).toHaveClass(/bg-warning/);

    await page.locator('#dashboard-env-search').fill('payments');
    await expect(payments).toBeVisible();
    await expect(billing).toBeHidden();
    await page.locator('#dashboard-env-search').fill('pay-qa');
    await expect(payments).toBeVisible();
  });
});
