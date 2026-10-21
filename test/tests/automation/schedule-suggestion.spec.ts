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
  // U12-T03: New schedule opens the schedule editor page.
  await page.evaluate(() => { location.hash = '#/automation-rules/new'; });
  await expect(page.locator('#rule-editor-view #ar-form')).toBeVisible({ timeout: 10_000 });
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
    await expect(panel).toContainText('Based on the last 4 weeks of use, in Asia/Kolkata');
    await expect(panel).toContainText('Mon–Fri 08:30–18:30');
    await expect(panel).toContainText('about $4.91 a week saved');
    await expect(panel).toContainText('Confidence high');
    expect(requested).toContain(`environmentId=${environmentId}`);
    expect(requested).toContain('timezone=Asia%2FKolkata');

    let posts = 0;
    page.on('request', r => { if (r.method() === 'POST' && /automation-rules$/.test(r.url())) posts++; });
    await page.locator('#ar-use-suggestion').click();
    await expect(panel).toBeHidden();
    // One set of times: the simple editor holds them.
    await expect(page.locator('#re-start')).toHaveValue('08:30');
    await expect(page.locator('#re-stop')).toHaveValue('18:30');
    await expect(page.locator('#re-days .ar-day-pill[data-day="FRI"]')).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('#re-days .ar-day-pill[data-day="SAT"]')).toHaveAttribute('aria-pressed', 'false');
    // The week follows the new times; nothing was saved.
    await expect(page.locator('#re-hours')).toContainText('Runs 50 hours a week instead of 168');
    expect(posts).toBe(0);
  });

  test('explains why there is no suggestion', async ({ page }) => {
    await openNewRule(page, environmentId);
    // Wait for the real server's answer: a failure then says whether the click asked at all
    // (VM run 0aa5089 once saw an empty panel; flaky list).
    const answered = page.waitForResponse(r => r.url().includes('/automation-rules/suggestion'), { timeout: 10_000 });
    await page.locator('#ar-suggest-btn').click();
    await answered;
    // The seeded environment has no VMs.
    await expect(page.locator('#ar-suggestion')).toContainText('This environment has no active VMs');
  });
});
