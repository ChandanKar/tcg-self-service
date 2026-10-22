import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E19-T01 / U12-T03: a schedule rule has several time windows. The schedule editor page edits one
 * set of times for the chosen days, or, under "Advanced: different times per day", a list of
 * windows (days, start, stop, remove, 'Add times' up to 7); the server rejects overlapping windows
 * next to the times, and the schedule's card summarises the windows. Runs against the smoke app
 * with a seeded environment.
 */

declare const ContentRouter: any;

async function openNewRule(page: Page, environmentId: string, name: string) {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  // The first page first: a hash change before it renders can load the editor twice, and the second
  // render empties what the test typed.
  await expect(page.locator('#content-area h1').first()).toBeVisible({ timeout: 15_000 });
  await page.evaluate(() => { location.hash = '#/automation-rules/new'; });
  await expect(page.locator('#rule-editor-view #ar-form')).toBeVisible({ timeout: 10_000 });
  await page.waitForLoadState('networkidle');
  await page.locator('#ar-environment').selectOption(environmentId);
  await page.locator('#ar-name').fill(name);
}

async function advanced(page: Page) {
  await page.getByRole('button', { name: 'Advanced: different times per day' }).click();
  await expect(page.locator('#ar-windows .ar-window')).toHaveCount(1);
}

test.describe('Schedule windows', () => {
  let environmentId = '';

  test.beforeAll(async ({ playwright }) => {
    environmentId = await seedEnvironment(playwright, 'Windows');
  });

  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin') || !environmentId, 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('a rule with a weekday and a Saturday window is saved, summarised and opens in the advanced list', async ({ page }) => {
    const name = `Two windows ${Date.now()}`;
    await openNewRule(page, environmentId, name);
    await advanced(page);

    const windows = page.locator('#ar-windows .ar-window');
    await expect(page.locator('.ar-remove-window')).toHaveCount(0); // the only window cannot be removed
    await page.locator('#ar-add-window').click();
    await expect(windows).toHaveCount(2);
    // The new window's first day pill has focus, so keyboard users land in it.
    await expect(windows.nth(1).locator('.ar-day-pill[data-day="MON"]')).toBeFocused();
    await windows.nth(1).locator('.ar-day-pill[data-day="SAT"]').click();
    await expect(windows.nth(1).locator('.ar-day-pill[data-day="SAT"]')).toHaveAttribute('aria-pressed', 'true');
    await windows.nth(1).locator('.ar-start-time').fill('10:00');
    await windows.nth(1).locator('.ar-stop-time').fill('14:00');

    const saved = page.waitForResponse(r => r.url().endsWith('/api/v1/automation-rules') && r.request().method() === 'POST');
    await page.locator('#ar-save-btn').click();
    const body = (await saved).request().postDataJSON();
    expect(body.windows).toEqual([
      { daysOfWeek: ['MON', 'TUE', 'WED', 'THU', 'FRI'], startTime: '08:00', stopTime: '20:00' },
      { daysOfWeek: ['SAT'], startTime: '10:00', stopTime: '14:00' },
    ]);

    await expect(page).toHaveURL(/#\/automation-rules$/, { timeout: 10_000 });
    const card = page.locator('#ar-table-wrapper .ar-rule', { hasText: name });
    await expect(card).toContainText('Mon-Fri 08:00-20:00; Sat 10:00-14:00');

    // Editing opens the advanced list with both windows, and saving keeps them.
    await card.locator('.ar-edit-btn').click();
    await expect(page).toHaveURL(/#\/automation-rules\/[^/?]+$/);
    await expect(page.locator('#ar-windows .ar-window')).toHaveCount(2, { timeout: 10_000 });
    await expect(page.locator('#ar-windows .ar-window').nth(1).locator('.ar-start-time')).toHaveValue('10:00');
    await expect(page.locator('#re-hours')).toContainText('Runs 64 hours a week instead of 168');
    const put = page.waitForResponse(r => /\/api\/v1\/automation-rules\/[^/]+$/.test(r.url()) && r.request().method() === 'PUT');
    await page.locator('#ar-save-btn').click();
    const response = await put;
    expect(response.status()).toBe(200);
    expect(response.request().postDataJSON().windows).toHaveLength(2);
  });

  test('overlapping windows are refused next to the times', async ({ page }) => {
    await openNewRule(page, environmentId, `Overlap ${Date.now()}`);
    await advanced(page);
    await page.locator('#ar-add-window').click();
    const second = page.locator('#ar-windows .ar-window').nth(1);
    await second.locator('.ar-day-pill[data-day="MON"]').click();
    await second.locator('.ar-start-time').fill('19:00');
    await second.locator('.ar-stop-time').fill('21:00');

    await page.locator('#ar-save-btn').click();

    await expect(page.locator('#re-err-times')).toHaveText('Windows overlap on MON');
    await expect(page.locator('#rule-editor-view')).toBeVisible();
  });

  test('windows can be removed and at most seven added', async ({ page }) => {
    await openNewRule(page, environmentId, `Seven ${Date.now()}`);
    await advanced(page);
    for (let i = 0; i < 6; i++) await page.locator('#ar-add-window').click();
    await expect(page.locator('#ar-windows .ar-window')).toHaveCount(7);
    await expect(page.locator('#ar-add-window')).toBeDisabled();

    await page.getByRole('button', { name: 'Remove times 3' }).click();
    await expect(page.locator('#ar-windows .ar-window')).toHaveCount(6);
    await expect(page.locator('#ar-add-window')).toBeEnabled();
    // The first window kept its values through the re-render.
    await expect(page.locator('#ar-windows .ar-window').first().locator('.ar-stop-time')).toHaveValue('20:00');
  });
});
