import { test, expect, type Page, type Request } from '@playwright/test';
import * as fs from 'fs';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E11-T05: the Global Audit page sends exact instants and only the filters chosen, shows the
 * server's stats for the whole filtered set, lists every action from /audit/actions, and its CSV
 * export neutralises formulas. /audit/logs and /audit/logs/stats are faked in the browser.
 */

declare const ContentRouter: any;

const ROW = {
  logId: 'l1', userId: 'admin-001', userDisplayName: 'Test Admin', action: 'ENVIRONMENT_UPDATED',
  actionDisplay: 'Environment Updated', targetType: 'environment', targetName: '=HYPERLINK("x")',
  success: true, createdAt: new Date().toISOString(), details: '+SUM(A1)',
};

const STATS = { total: 120, failures: 7, successRate: 94,
  topUser: { id: 'admin-001', name: 'Test Admin', count: 80 }, topEnvironment: null };

async function open(page: Page, logRequests: Request[], statsRequests: Request[]) {
  await page.route(/\/api\/v1\/audit\/logs\/stats/, route => {
    statsRequests.push(route.request());
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(STATS) });
  });
  await page.route(/\/api\/v1\/audit\/logs\?/, route => {
    logRequests.push(route.request());
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      content: [ROW], page: { size: 100, number: 0, totalElements: 1, totalPages: 1 } }) });
  });
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => { location.hash = '#/audit-logs-all'; });
  await expect(page.locator('.all-logs-container')).toBeVisible({ timeout: 15_000 });
}

function params(req: Request) {
  return new URL(req.url()).searchParams;
}

test.describe('Global audit page', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('sends a 24-hour range as instants, no success flag, and shows the server stats', async ({ page }) => {
    const logs: Request[] = [];
    const stats: Request[] = [];
    await open(page, logs, stats);

    const p = params(logs[0]);
    const from = Date.parse(p.get('from')!);
    const to = Date.parse(p.get('to')!);
    expect(p.get('from')).toMatch(/T.*Z$/);
    expect(Math.round((to - from) / 3600000)).toBe(24);
    expect(p.has('success')).toBe(false);
    expect(p.has('startDate')).toBe(false);
    expect(params(stats[0]).get('from')).toBe(p.get('from'));

    const cards = page.locator('#all-logs-stats');
    await expect(cards).toContainText('120');
    await expect(cards).toContainText('94%');
    await expect(cards).toContainText('Test Admin (80)');
  });

  test('Failure sends success=false to both the list and the stats', async ({ page }) => {
    const logs: Request[] = [];
    const stats: Request[] = [];
    await open(page, logs, stats);

    await page.locator('#result-filter').selectOption('false');
    await expect.poll(() => stats.length).toBe(2);

    expect(params(logs[logs.length - 1]).get('success')).toBe('false');
    expect(params(stats[1]).get('success')).toBe('false');
  });

  test('the action dropdown lists every action the server knows', async ({ page }) => {
    const actions: string[] = await page.request.get('/api/v1/audit/actions', {
      headers: { 'X-User-Id': devIds.admin } }).then(r => r.json());
    await open(page, [], []);

    await expect(page.locator('#action-type-filter option')).toHaveCount(actions.length + 1);
    await expect(page.locator('#action-type-filter option[value="LOCK_EXTENDED"]')).toHaveText('Lock Extended');
  });

  test('the CSV export prefixes formula-like cells so they stay text', async ({ page }) => {
    await open(page, [], []);

    const download = page.waitForEvent('download');
    await page.locator('#export-logs-btn').click();
    const file = await (await download).path();
    const csv = fs.readFileSync(file!, 'utf8');

    expect(csv).toContain(`"'=HYPERLINK(""x"")"`);
    expect(csv).toContain(`"'+SUM(A1)"`);
  });

  test('an ENV_ADMIN never calls the ADMIN-only user list and gets a user search box', async ({ page }) => {
    // Present the signed-in admin as an ENV_ADMIN (role flags only) to the UI.
    await page.route(/\/api\/v1\/users\/me(\?.*)?$/, async route => {
      const response = await route.fetch();
      const me = await response.json();
      return route.fulfill({ response, body: JSON.stringify({ ...me, admin: false, envAdmin: true }) });
    });
    const userListCalls: string[] = [];
    page.on('request', req => { if (/\/api\/v1\/users(\?|$)/.test(new URL(req.url()).pathname + new URL(req.url()).search)) userListCalls.push(req.url()); });
    await open(page, [], []);

    await expect(page.locator('#user-search-input')).toBeVisible();
    await expect(page.locator('#user-filter')).toHaveCount(0);
    expect(userListCalls).toEqual([]);
  });
});

