import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E19-T01: a schedule rule has several time windows. The modal edits a list of windows (days,
 * start, stop, remove, 'Add window' up to 7), the server rejects overlapping windows, and the
 * table summarises the windows. Runs against the smoke app with a seeded environment.
 */

declare const ContentRouter: any;

async function openNewRule(page: Page, environmentId: string, name: string) {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/automation-rules'; });
  await expect(page.locator('#automation-rules-view')).toBeVisible({ timeout: 10_000 });
  const shown = page.evaluate(() => new Promise(resolve =>
    document.addEventListener('shown.bs.modal', () => resolve(true), { once: true })));
  await page.locator('#ar-new-rule-btn').click();
  await shown;
  await page.locator('#ar-environment').selectOption(environmentId);
  await page.locator('#ar-name').fill(name);
}

test.describe('Schedule windows', () => {
  let environmentId = '';

  test.beforeAll(async ({ playwright }) => {
    environmentId = await seedEnvironment(playwright, 'Windows');
  });

  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin') || !environmentId, 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('a rule with a weekday and a Saturday window is saved and summarised', async ({ page }) => {
    const name = `Two windows ${Date.now()}`;
    await openNewRule(page, environmentId, name);

    const windows = page.locator('#ar-windows .ar-window');
    await expect(windows).toHaveCount(1);
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

    await expect(page.locator('#automationRuleModal')).toBeHidden({ timeout: 10_000 });
    await expect(page.locator('#ar-table-wrapper tr', { hasText: name }))
      .toContainText('Mon-Fri 08:00-20:00; Sat 10:00-14:00');

    // Editing shows both windows again.
    await page.locator('#ar-table-wrapper tr', { hasText: name }).locator('.ar-edit-btn').click();
    await expect(page.locator('#ar-windows .ar-window')).toHaveCount(2);
    await expect(page.locator('#ar-windows .ar-window').nth(1).locator('.ar-start-time')).toHaveValue('10:00');
  });

  test('overlapping windows are refused with the day named', async ({ page }) => {
    await openNewRule(page, environmentId, `Overlap ${Date.now()}`);
    await page.locator('#ar-add-window').click();
    const second = page.locator('#ar-windows .ar-window').nth(1);
    await second.locator('.ar-day-pill[data-day="MON"]').click();
    await second.locator('.ar-start-time').fill('19:00');
    await second.locator('.ar-stop-time').fill('21:00');

    await page.locator('#ar-save-btn').click();

    await expect(page.getByText('Windows overlap on MON')).toBeVisible();
    await expect(page.locator('#automationRuleModal')).toBeVisible();
  });

  test('windows can be removed and at most seven added', async ({ page }) => {
    await openNewRule(page, environmentId, `Seven ${Date.now()}`);
    for (let i = 0; i < 6; i++) await page.locator('#ar-add-window').click();
    await expect(page.locator('#ar-windows .ar-window')).toHaveCount(7);
    await expect(page.locator('#ar-add-window')).toBeDisabled();

    await page.getByRole('button', { name: 'Remove window 3' }).click();
    await expect(page.locator('#ar-windows .ar-window')).toHaveCount(6);
    await expect(page.locator('#ar-add-window')).toBeEnabled();
    // The first window kept its values through the re-render.
    await expect(page.locator('#ar-windows .ar-window').first().locator('.ar-stop-time')).toHaveValue('20:00');
  });
});
