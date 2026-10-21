import { test, expect } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/** E35-T03, U15-T01: Export › Month for finance downloads the chosen month and figures from the smoke app (test schema). */

declare const ContentRouter: any;

test.describe('FOCUS export', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('the dialog downloads the chosen month and basis', async ({ page }) => {
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/cost-management'; });
    await page.getByRole('button', { name: 'Export' }).click({ timeout: 15_000 });
    await page.getByRole('menuitem', { name: 'Month for finance, FOCUS CSV…' }).click();

    const modal = page.locator('#focusExportModal');
    await expect(modal.locator('.modal-title')).toHaveText('Month for finance');
    await expect(modal.locator('#focus-month option')).toHaveCount(13);
    await expect(modal.locator('#focus-basis-actual')).toBeChecked(); // AWS billing first
    await modal.locator('#focus-basis-estimated').check();
    const month = await modal.locator('#focus-month option').nth(2).getAttribute('value');
    await modal.locator('#focus-month').selectOption(month!);
    await modal.locator('#focus-basis-actual').check();
    await expect(modal.locator('#focus-download')).toHaveText('Download CSV');
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
