import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/** E28-T11: Cost Management shows spend by AWS account only when there is more than one account. */

declare const ContentRouter: any;

async function open(page: Page, rows: any[]) {
  await page.route(/\/cost-management\/spend-by-account$/, r => r.fulfill({ json: rows }));
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/cost-management'; });
  await expect(page.locator('.cost-kpi-grid')).toBeVisible({ timeout: 15_000 });
}

test.describe('Spend by account', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('two accounts get a chart', async ({ page }) => {
    await open(page, [
      { dimensionKey: 'a', dimensionLabel: 'Default account', cost: 120, vmCount: 3 },
      { dimensionKey: 'b', dimensionLabel: 'Payments', cost: 80, vmCount: 2 }]);
    const slot = page.locator('[data-cost-panel="account"]');
    await expect(slot).toContainText('Spend by Account');
    await expect(slot).toContainText('2 AWS account(s)');
    await expect(slot.locator('#cost-chart-account svg')).toHaveCount(1);
  });

  test('a single account shows nothing extra', async ({ page }) => {
    await open(page, [{ dimensionKey: 'a', dimensionLabel: 'Default account', cost: 120, vmCount: 3 }]);
    await expect(page.locator('[data-cost-panel="account"]')).not.toContainText('Spend by Account');
  });
});
