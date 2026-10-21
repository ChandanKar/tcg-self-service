import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E19-T04 / U12-T03: the schedule editor's "This week" shows the hours the draft runs and its first
 * start from the server's preview (refreshed after edits, with the server's validation message when
 * the draft is invalid), and each schedule's card has its next run. Runs against the smoke app with
 * a seeded environment.
 */

declare const ContentRouter: any;

async function openNewRule(page: Page, environmentId: string) {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  // U12-T03: New schedule opens the schedule editor page.
  await page.evaluate(() => { location.hash = '#/automation-rules/new'; });
  await expect(page.locator('#rule-editor-view #ar-form')).toBeVisible({ timeout: 10_000 });
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

  test('the week previews the draft and explains an invalid one', async ({ page }) => {
    await openNewRule(page, environmentId);
    await page.locator('#ar-timezone').selectOption('Asia/Kolkata');

    // U12-T03 acceptance: Mon–Fri 08:00–20:00 in Asia/Kolkata.
    const hours = page.locator('#re-hours');
    await expect(hours).toContainText('Runs 60 hours a week instead of 168');
    await expect(hours).toContainText(/first start (Mon|Tue|Wed|Thu|Fri) \d{1,2} \w{3}, 08:00 IST/);
    await expect(page.locator('#re-week-strip .week-row')).toHaveCount(7);
    await expect(page.locator('#re-week-strip .week-row').nth(5).locator('.week-bar')).toHaveCount(0);

    // Only a stop on Saturdays: nothing starts, and the first run is a Saturday stop.
    for (const day of ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT']) {
      await page.locator(`#re-days .ar-day-pill[data-day="${day}"]`).click();
    }
    await page.locator('#re-start').fill('');
    await expect(hours).toContainText('Only stops: nothing is started on a schedule');
    await expect(hours).toContainText(/first stop Sat /);

    // An overlapping second window: the week shows the server's message.
    await page.getByRole('button', { name: 'Advanced: different times per day' }).click();
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
    await expect(page).toHaveURL(/#\/automation-rules$/, { timeout: 10_000 });

    // U12-T02: each schedule's line ends with "· next Stop today 20:00"; a new rule is listed first.
    const cell = page.locator('#ar-table-wrapper .ar-rule', { hasText: name }).locator('.ar-next-run');
    await expect(cell).toHaveText(/^(Stop|Start) (today|tomorrow|\w{3}|\d{1,2} \w{3}) \d\d:\d\d$/);
  });
});
