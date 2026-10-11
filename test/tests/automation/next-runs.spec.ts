import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E19-T04: the rule modal previews the next runs of the draft (refreshed after edits, with the
 * server's validation message when the draft is invalid), and the rules table has a Next run
 * column. Runs against the smoke app with a seeded environment.
 */

declare const ContentRouter: any;

async function openNewRule(page: Page, environmentId: string) {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/automation-rules'; });
  await expect(page.locator('#automation-rules-view')).toBeVisible({ timeout: 10_000 });
  const shown = page.evaluate(() => new Promise(resolve =>
    document.addEventListener('shown.bs.modal', () => resolve(true), { once: true })));
  await page.locator('#ar-new-rule-btn').click();
  await shown;
  await page.locator('#ar-environment').selectOption(environmentId);
}

test.describe('Next runs', () => {
  let environmentId = '';

  test.beforeAll(async ({ playwright }) => {
    environmentId = await seedEnvironment(playwright, 'Next runs');
  });

  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin') || !environmentId, 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('the modal previews the draft and explains an invalid one', async ({ page }) => {
    await openNewRule(page, environmentId);
    await page.locator('#ar-timezone').selectOption('Asia/Kolkata');

    const items = page.locator('#ar-preview li');
    await expect(items).toHaveCount(10);
    await expect(items.first()).toContainText('IST');
    await expect(page.locator('#ar-preview')).toContainText('— Stop');
    await expect(page.locator('#ar-preview')).toContainText('— Start');

    // Only a stop on Saturdays: every entry is a Saturday stop.
    const first = page.locator('#ar-windows .ar-window').first();
    for (const day of ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT']) {
      await first.locator(`.ar-day-pill[data-day="${day}"]`).click();
    }
    await first.locator('.ar-start-time').fill('');
    await expect(items.first()).toContainText('Sat');
    await expect(page.locator('#ar-preview li', { hasText: 'Start' })).toHaveCount(0);

    // An overlapping second window: the panel shows the server's message.
    await page.locator('#ar-add-window').click();
    const second = page.locator('#ar-windows .ar-window').nth(1);
    await second.locator('.ar-day-pill[data-day="SAT"]').click();
    await second.locator('.ar-stop-time').fill('20:00');
    await expect(page.locator('#ar-preview')).toContainText('Windows overlap on SAT');
  });

  test('saved rules show their next run in the table', async ({ page }) => {
    await openNewRule(page, environmentId);
    const name = `Next run ${Date.now()}`;
    await page.locator('#ar-name').fill(name);
    await page.locator('#ar-save-btn').click();
    await expect(page.locator('#automationRuleModal')).toBeHidden({ timeout: 10_000 });

    await page.locator('#ar-filter-search').fill(name);
    const cell = page.locator('#ar-table-wrapper tr', { hasText: name }).locator('.ar-next-run');
    await expect(cell).toContainText(/Stop|Start/);
    await expect(page.locator('#ar-table-wrapper th', { hasText: 'Next run' })).toHaveCount(1);
  });
});
