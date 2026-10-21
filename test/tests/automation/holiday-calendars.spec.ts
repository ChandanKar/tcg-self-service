import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E19-T02: admins manage holiday calendars on the Automation page's 'Holiday calendars' tab
 * (create, add a date, import a CSV that reports its bad lines), and a schedule rule links to a
 * calendar with 'Don't start on holidays' or 'Skip all actions'. Runs against the smoke app.
 */

declare const ContentRouter: any;

async function openAutomation(page: Page) {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/automation-rules'; });
  await expect(page.locator('#automation-rules-view')).toBeVisible({ timeout: 10_000 });
}

async function createCalendar(page: Page, name: string) {
  await page.getByRole('tab', { name: 'Holiday calendars' }).click();
  await page.locator('#hc-new-btn').click();
  await page.locator('#hc-name').fill(name);
  await page.locator('#hc-save-btn').click();
  await expect(page.locator('#holidayCalendarModal')).toBeHidden();
  await expect(page.locator('#hc-detail-title')).toHaveText(name);
}

test.describe('Holiday calendars', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('an admin creates a calendar, adds a date and imports a CSV with a bad line', async ({ page }) => {
    const name = `India ${Date.now()}`;
    await openAutomation(page);
    await createCalendar(page, name);

    // U12-T04: Add date opens the form; the CSV imports as soon as it is chosen.
    await expect(page.locator('#hc-used')).toHaveText('No schedule uses it');
    await page.getByRole('button', { name: 'Add date' }).click();
    await page.locator('#hc-date').fill('2026-10-02');
    await page.locator('#hc-label').fill('Gandhi Jayanti');
    await page.locator('#hc-add-form button[type="submit"]').click();
    await expect(page.locator('#hc-dates-table tbody tr')).toHaveCount(1);
    await expect(page.locator('#hc-dates-table')).toContainText('Gandhi Jayanti');

    await page.locator('#hc-file').setInputFiles({
      name: 'holidays.csv', mimeType: 'text/csv',
      buffer: Buffer.from('2026-10-20,Dussehra\n2026-10-02,Gandhi Jayanti\n2026-13-40,Not a day\n2026-11-08,Diwali\n'),
    });

    await expect(page.locator('#hc-import-result')).toHaveText(
      'Imported: 2 added, 1 already there, 1 line skipped (row 3: "2026-13-40" is not a date).');
    await expect(page.locator('#hc-dates-table tbody tr')).toHaveCount(3);
    await expect(page.locator('#hc-calendars .hc-item', { hasText: name })).toContainText('3 dates');
  });

  test('a schedule rule links to a calendar and the table says so', async ({ page, playwright }) => {
    const environmentId = await seedEnvironment(playwright, 'Holidays');
    test.skip(!environmentId, 'Could not seed an environment');
    const calendar = `Rule cal ${Date.now()}`;
    await openAutomation(page);
    await createCalendar(page, calendar);
    await page.getByRole('tab', { name: 'Schedules' }).click();

    // U12-T03: New schedule opens the editor page; On holidays names the calendar in each choice.
    await page.locator('#ar-new-rule-btn').click();
    await expect(page.locator('#rule-editor-view #ar-form')).toBeVisible({ timeout: 10_000 });
    const ruleName = `Holiday rule ${Date.now()}`;
    await page.locator('#ar-environment').selectOption(environmentId);
    await page.locator('#ar-name').fill(ruleName);

    const holidays = page.getByLabel('On holidays');
    await expect(holidays.locator('option:checked')).toHaveText('Run as usual');
    await expect(holidays.locator('option', { hasText: `Don't start on ${calendar} holidays` })).toHaveCount(1);
    await holidays.selectOption({ label: `Don't start or stop on ${calendar} holidays` });

    const saved = page.waitForResponse(r => r.url().endsWith('/api/v1/automation-rules') && r.request().method() === 'POST');
    await page.locator('#ar-save-btn').click();
    const response = await saved;
    expect(response.status()).toBe(200);
    expect(response.request().postDataJSON()).toMatchObject({ holidayBehavior: 'SKIP_ALL' });
    expect(response.request().postDataJSON().holidayCalendarId).toBeTruthy();
    expect((await response.json()).holidayCalendarName).toBe(calendar);

    await expect(page.locator('#ar-table-wrapper .ar-rule', { hasText: ruleName })).toContainText(`off on ${calendar} holidays`);

    // The calendar now says a schedule follows it.
    await page.getByRole('tab', { name: 'Holiday calendars' }).click();
    await page.locator('#hc-calendars .hc-item', { hasText: calendar }).click();
    await expect(page.locator('#hc-used')).toHaveText('Used by 1 schedule');
  });
});
