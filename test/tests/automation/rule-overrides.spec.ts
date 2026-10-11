import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E19-T03: 'Skip next stop' on a schedule rule creates a STOP override around the next stop
 * (with a reason), the row shows the override, and the rule modal lists it with a remove button.
 * Runs against the smoke app; the rule is created through the API.
 */

declare const ContentRouter: any;

async function createRule(playwright: any, environmentId: string, name: string) {
  const admin = await playwright.request.newContext({
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    extraHTTPHeaders: { 'X-User-Id': devIds.admin, 'Content-Type': 'application/json' },
  });
  const response = await admin.post('/api/v1/automation-rules', {
    data: {
      environmentId, name, scopeType: 'ENVIRONMENT', triggerType: 'SCHEDULE', timezone: 'UTC', enabled: true,
      windows: [{ daysOfWeek: ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'], startTime: null, stopTime: '23:59' }],
    },
  });
  expect(response.ok()).toBeTruthy();
  const rule = await response.json();
  await admin.dispose();
  return rule;
}

async function openAutomation(page: Page) {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/automation-rules'; });
  await expect(page.locator('#automation-rules-view')).toBeVisible({ timeout: 10_000 });
}

test.describe('Rule overrides', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('skip the next stop, see it on the rule, then remove it', async ({ page, playwright }) => {
    const environmentId = await seedEnvironment(playwright, 'Overrides');
    test.skip(!environmentId, 'Could not seed an environment');
    const name = `Nightly ${Date.now()}`;
    const rule = await createRule(playwright, environmentId, name);
    expect(rule.nextStopAt).toBeTruthy();

    await openAutomation(page);
    await page.locator('#ar-filter-search').fill(name);
    const row = page.locator('#ar-table-wrapper tr', { hasText: name });
    await expect(row).toHaveCount(1);

    await row.getByRole('button', { name: `Skip next stop of ${name}` }).click();
    await expect(page.locator('#promptModal')).toBeVisible();
    await page.locator('#promptInput').fill('release tonight');
    const posted = page.waitForResponse(r => r.url().endsWith(`/automation-rules/${rule.ruleId}/overrides`)
      && r.request().method() === 'POST');
    await page.locator('#promptSubmitBtn').click();
    const body = (await posted).request().postDataJSON();
    expect(body.skipAction).toBe('STOP');
    expect(new Date(body.endsAt).getTime() - new Date(body.startsAt).getTime()).toBe(120_000);
    expect(new Date(rule.nextStopAt).getTime()).toBeGreaterThan(new Date(body.startsAt).getTime());

    await expect(row.locator('.ar-override-badge')).toHaveText('1 override');

    await row.locator('.ar-edit-btn').click();
    const overrides = page.locator('#ar-overrides');
    await expect(overrides).toContainText('Skip stop');
    await expect(overrides).toContainText('release tonight');
    await overrides.getByRole('button', { name: 'Remove override: release tonight' }).click();
    await expect(overrides).toContainText('None.');
    await page.locator('#automationRuleModal .btn-close').click();
    await expect(row.locator('.ar-override-badge')).toHaveCount(0);
  });
});
