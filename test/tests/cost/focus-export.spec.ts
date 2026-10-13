import { test, expect } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/** E35-T03: the Export FOCUS CSV dialog downloads the chosen month and basis from the smoke app (test schema). */

declare const ContentRouter: any;

test.describe('FOCUS export', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('the dialog downloads the chosen month and basis', async ({ page }) => {
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/cost-management'; });
    await page.getByRole('button', { name: 'Export FOCUS CSV' }).click({ timeout: 15_000 });

    const modal = page.locator('#focusExportModal');
    await expect(modal.locator('#focus-month option')).toHaveCount(13);
    const month = await modal.locator('#focus-month option').nth(2).getAttribute('value');
    await modal.locator('#focus-month').selectOption(month!);
    await modal.locator('#focus-basis-actual').check();
    const downloading = page.waitForEvent('download');
    await modal.locator('#focus-download').click();
    const download = await downloading;

    expect(download.url()).toContain(`month=${month}`);
    expect(download.url()).toContain('basis=actual');
    expect(download.suggestedFilename()).toBe(`focus-actual-${month}.csv`);
    const path = await download.path();
    const text = require('fs').readFileSync(path, 'utf8');
    expect(text.startsWith('AvailabilityZone,BilledCost,BillingAccountId')).toBe(true);
  });
});
