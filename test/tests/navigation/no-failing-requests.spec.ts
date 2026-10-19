import { test, expect, type Page } from '@playwright/test';
import { devIds } from '../../fixtures/auth';

/**
 * U02-T03: opening the main pages sends no request that fails. Switched-off features are not asked
 * for, and panels a person cannot use are not loaded. Any /api/ answer of 400 or more fails the test.
 */

declare const ContentRouter: any;

const SECOND_USER_ID = process.env.TEST_DEV_SECOND_USER_ID || 'user-002';

async function visit(page: Page, userId: string, routes: string[], environmentId?: string) {
  const failures: string[] = [];
  page.on('response', r => {
    if (r.url().includes('/api/') && r.status() >= 400) failures.push(`${r.status()} ${r.request().method()} ${r.url()}`);
  });
  await page.context().setExtraHTTPHeaders({ 'X-User-Id': userId });
  await page.goto('/home');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  const all = environmentId ? [...routes, `environments/${environmentId}`] : routes;
  for (const route of all) {
    await page.evaluate((r: string) => { location.hash = '#/' + r; }, route);
    await expect(page.locator('#content-area')).not.toBeEmpty({ timeout: 15_000 });
    await page.waitForLoadState('networkidle');
  }
  return failures;
}

async function anEnvironmentSharedWithTheUsers(playwright: any): Promise<string> {
  const admin = await playwright.request.newContext({
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    extraHTTPHeaders: { 'X-User-Id': devIds.admin, 'Content-Type': 'application/json' },
  });
  const stamp = Date.now();
  const env = await (await admin.post('/api/v1/environments', {
    data: { name: `quiet-${stamp}`, displayName: `Quiet ${stamp}`, cloudProvider: 'AWS' } })).json();
  await admin.post(`/api/v1/environments/${env.environmentId}/groups`, { data: { name: 'web', displayName: 'Web', sequencePosition: 1 } });
  for (const [id, level] of [[devIds.user, 'USER'], [SECOND_USER_ID, 'VIEWER']]) {
    const user = await (await admin.get(`/api/v1/users/${id}`)).json();
    await admin.post(`/api/v1/environments/${env.environmentId}/access`, { data: { userEmail: user.email, accessLevel: level, durationDays: 1 } });
  }
  await admin.dispose();
  return env.environmentId;
}

test.describe('No failing background requests', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !devIds.user, 'Needs TEST_DEV_ADMIN_ID and TEST_DEV_USER_ID (dev mode)');
  });

  test('a user and a viewer browse without a failing request', async ({ page, playwright }) => {
    const envId = await anEnvironmentSharedWithTheUsers(playwright);
    for (const userId of [devIds.user, SECOND_USER_ID]) {
      const failures = await visit(page, userId, ['dashboard', 'my-environments', 'request-access', 'activity-logs'], envId);
      expect(failures, `failing requests for ${userId}`).toEqual([]);
    }
  });

  test('an administrator browses the admin pages without a failing request', async ({ page, playwright }) => {
    const envId = await anEnvironmentSharedWithTheUsers(playwright);
    const failures = await visit(page, devIds.admin,
      ['dashboard', 'my-environments', 'cost-management', 'api-access', 'aws-accounts', 'system-health'], envId);
    expect(failures).toEqual([]);
  });
});
