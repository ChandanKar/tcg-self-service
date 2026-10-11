import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E19-T05: 'Suggest from usage' in the rule modal shows a 7x24 heat strip, the proposed windows
 * and the projected saving; 'Use these windows' fills the editor without saving. The suggestion
 * itself is faked (the smoke app has no usage history); the error path uses the real server.
 */

declare const ContentRouter: any;

function officeHours(): number[] {
  const share = new Array(168).fill(0);
  for (let d = 0; d < 5; d++) for (let h = 9; h < 18; h++) share[d * 24 + h] = 1;
  return share;
}

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

test.describe('Suggest from usage', () => {
  let environmentId = '';

  test.beforeAll(async ({ playwright }) => {
    environmentId = await seedEnvironment(playwright, 'Suggest');
  });

  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin') || !environmentId, 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('shows the usage, the windows and the saving, and fills the editor on request', async ({ page }) => {
    let requested = '';
    await page.route(/\/api\/v1\/automation-rules\/suggestion\?/, route => {
      requested = route.request().url();
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        environmentId, timezone: 'Asia/Kolkata', weeks: 4,
        windows: [{ daysOfWeek: ['MON', 'TUE', 'WED', 'THU', 'FRI'], startTime: '08:30', stopTime: '18:30' }],
        activityByHourOfWeek: officeHours(), confidence: 'HIGH', metricsCoveragePercent: 100,
        currentRunningHoursPerWeek: 168.0, suggestedHoursPerWeek: 50.0, estimatedWeeklySaving: 4.91,
        unpricedVmCount: 0, alwaysOnDays: [],
      }) });
    });
    await openNewRule(page, environmentId);
    await page.locator('#ar-timezone').selectOption('Asia/Kolkata');

    await page.locator('#ar-suggest-btn').click();
    const panel = page.locator('#ar-suggestion');
    await expect(panel.locator('.ar-heat-cell')).toHaveCount(168);
    await expect(panel).toContainText('Mon, Tue, Wed, Thu, Fri 08:30–18:30');
    await expect(panel).toContainText('about $4.91 a week saved');
    await expect(panel).toContainText('Confidence high');
    expect(requested).toContain(`environmentId=${environmentId}`);
    expect(requested).toContain('timezone=Asia%2FKolkata');

    let posts = 0;
    page.on('request', r => { if (r.method() === 'POST' && /automation-rules$/.test(r.url())) posts++; });
    await page.locator('#ar-use-suggestion').click();
    await expect(panel).toBeHidden();
    const windows = page.locator('#ar-windows .ar-window');
    await expect(windows).toHaveCount(1);
    await expect(windows.first().locator('.ar-start-time')).toHaveValue('08:30');
    await expect(windows.first().locator('.ar-stop-time')).toHaveValue('18:30');
    await expect(windows.first().locator('.ar-day-pill[data-day="SAT"]')).toHaveAttribute('aria-pressed', 'false');
    // The preview follows the new windows; nothing was saved.
    await expect(page.locator('#ar-preview li').first()).toContainText(/08:30|18:30/);
    expect(posts).toBe(0);
  });

  test('explains why there is no suggestion', async ({ page }) => {
    await openNewRule(page, environmentId);
    await page.locator('#ar-suggest-btn').click();
    // The seeded environment has no VMs.
    await expect(page.locator('#ar-suggestion')).toContainText('This environment has no active VMs');
  });
});
