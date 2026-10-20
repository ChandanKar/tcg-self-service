import { test, expect, type APIRequestContext } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { withFeatures } from '../../fixtures/capabilities';

/**
 * UX simplification: the environment page shows Start/Stop only to people who can use them. A
 * viewer gets a view-only note with a way to ask for more; an operator gets the controls.
 * U07-T02 (EnvDetail.dc.html): the header has a meta line and Insights / History; the body is a
 * main column with the start order and an "About this environment" column; Spot, Teams and
 * webhooks load only when Settings is opened.
 */

declare const ContentRouter: any;

async function api(playwright: any, userId: string): Promise<APIRequestContext> {
  return playwright.request.newContext({
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    extraHTTPHeaders: { 'X-User-Id': userId, 'Content-Type': 'application/json' },
  });
}

async function environmentWithUserAt(playwright: any, level: 'VIEWER' | 'USER'): Promise<string> {
  const admin = await api(playwright, devIds.admin);
  const stamp = Date.now();
  const env = await (await admin.post('/api/v1/environments', {
    data: { name: `view-only-${level.toLowerCase()}-${stamp}`, displayName: `View ${level} ${stamp}`, cloudProvider: 'AWS' },
  })).json();
  await admin.post(`/api/v1/environments/${env.environmentId}/groups`, {
    data: { name: 'web', displayName: 'Web', sequencePosition: 1 },
  });
  const user = await (await admin.get(`/api/v1/users/${devIds.user}`)).json();
  await admin.post(`/api/v1/environments/${env.environmentId}/access`, {
    data: { userEmail: user.email, accessLevel: level, durationDays: 1 },
  });
  await admin.dispose();
  return env.environmentId;
}

async function open(page: any, envId: string) {
  await signIn(page, 'user');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate((id: string) => { location.hash = '#/environments/' + id; }, envId);
  await expect(page.locator('#env-detail-view .env-body')).toBeVisible({ timeout: 15_000 });
}

test.describe('Environment page by access level', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !devIds.admin || !canSignIn('user'), 'Needs TEST_DEV_USER_ID and TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('a viewer sees a view-only note and no start or stop controls', async ({ page, playwright }) => {
    const envId = await environmentWithUserAt(playwright, 'VIEWER');
    await open(page, envId);

    await expect(page.locator('.env-viewonly-note')).toContainText('view-only access');
    await expect(page.locator('.env-viewonly-note a')).toHaveAttribute('href', /^#\/request-access\?env=/);
    await expect(page.locator('#btn-env-action')).toHaveCount(0);
    await expect(page.locator('[data-action="group-action"]')).toHaveCount(0);
    await expect(page.locator('[data-lock-action="acquire"]')).toHaveCount(0);
    await expect(page.locator('#btn-env-action-stop')).toHaveCount(0);
    await expect(page.locator('#btn-env-insights')).toBeVisible();
    await expect(page.locator('#btn-operation-history')).toBeVisible();
  });

  test('an operator gets the start button, a breadcrumb back to the list, the start order and the About column', async ({ page, playwright }) => {
    const envId = await environmentWithUserAt(playwright, 'USER');
    await open(page, envId);

    await expect(page.locator('.env-viewonly-note')).toHaveCount(0);
    await expect(page.locator('#btn-env-action')).toHaveText(/Start (all|the \d+ stopped)/);
    await expect(page.locator('nav[aria-label="Breadcrumb"] a')).toHaveAttribute('href', '#/my-environments');
    await expect(page.locator('nav[aria-label="Breadcrumb"] a')).toHaveText('Environments');
    await expect(page.locator('.env-meta')).toContainText('AWS');
    await expect(page.getByRole('region', { name: 'Start order' })).toContainText('Web');
    await expect(page.getByRole('complementary', { name: 'About this environment' })).toContainText('VMs running');
    await expect(page.getByText('Cost & settings')).toHaveCount(0);
  });

  test('an administrator: Spot, Teams and webhooks are not asked for until Settings opens', async ({ page, playwright }) => {
    const envId = await environmentWithUserAt(playwright, 'USER');
    const asked: string[] = [];
    page.on('request', req => {
      if (/\/(spot-summary|chat-channels|webhooks)(\?|$)/.test(req.url())) asked.push(req.url());
    });
    await withFeatures(page, { spot: true, chat: true, webhooks: true });
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate((id: string) => { location.hash = '#/environments/' + id; }, envId);
    await expect(page.locator('#env-detail-view .env-body')).toBeVisible({ timeout: 15_000 });
    await page.waitForLoadState('networkidle');
    expect(asked).toEqual([]);

    await page.locator('#btn-env-settings').click();
    await expect(page.locator('.env-settings')).toBeVisible();
    await expect.poll(() => asked.length).toBeGreaterThan(0);
    await expect(page.locator('[data-action="env-settings-notify"]')).toBeVisible();
  });
});
