import { test, expect, type Page, type Request } from '@playwright/test';
import * as fs from 'fs';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E11-T05 / U12-T08: the Audit log page sends exact instants and only the filters chosen, shows the
 * server's counts for the whole filtered set, filters by action category, reads each row as a
 * sentence, and its CSV export neutralises formulas. /audit/logs and /audit/logs/stats are faked in
 * the browser, except where an environment admin reads the real rows.
 */

declare const ContentRouter: any;

const ROW = {
  logId: 'l1', userId: 'admin-001', userDisplayName: 'Test Admin', action: 'ENVIRONMENT_UPDATED',
  actionDisplay: 'Environment Updated', targetType: 'environment', targetName: '=HYPERLINK("x")',
  success: true, createdAt: new Date().toISOString(), details: '+SUM(A1)',
};

const STATS = { total: 120, failures: 7, successRate: 94,
  topUser: { id: 'admin-001', name: 'Test Admin', count: 80 }, topEnvironment: null };

async function open(page: Page, logRequests: Request[], statsRequests: Request[], rows: any[] = [ROW], total = 1) {
  await page.route(/\/api\/v1\/audit\/logs\/stats/, route => {
    statsRequests.push(route.request());
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(STATS) });
  });
  await page.route(/\/api\/v1\/audit\/logs\?/, route => {
    logRequests.push(route.request());
    const n = Number(new URL(route.request().url()).searchParams.get('page') || 0);
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      content: rows, page: { size: 10, number: n, totalElements: total, totalPages: Math.ceil(total / 10) } }) });
  });
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => { location.hash = '#/audit-logs-all'; });
  await expect(page.locator('#audit-table')).toBeVisible({ timeout: 15_000 });
}

function params(req: Request) {
  return new URL(req.url()).searchParams;
}

test.describe('Global audit page', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('sends a 7-day range as instants, no success flag, and shows the server counts', async ({ page }) => {
    const logs: Request[] = [];
    const stats: Request[] = [];
    await open(page, logs, stats);

    const p = params(logs[0]);
    const from = Date.parse(p.get('from')!);
    const to = Date.parse(p.get('to')!);
    expect(p.get('from')).toMatch(/T.*Z$/);
    expect(Math.round((to - from) / 3600000)).toBe(24 * 7); // U12-T08: Last 7 days by default
    expect(p.has('success')).toBe(false);
    expect(p.has('startDate')).toBe(false);
    expect(params(stats[0]).get('from')).toBe(p.get('from'));

    const counts = page.locator('#all-logs-stats span');
    await expect(counts).toHaveText(['120 actions in 7 days', '94% succeeded', '7 failed']);
  });

  test('Failed only sends success=false to both the list and the counts', async ({ page }) => {
    const logs: Request[] = [];
    const stats: Request[] = [];
    await open(page, logs, stats);

    await page.locator('#result-filter').selectOption('false');
    await expect.poll(() => stats.length).toBe(2);

    expect(params(logs[logs.length - 1]).get('success')).toBe('false');
    expect(params(stats[1]).get('success')).toBe('false');
  });

  test('the action filter lists the categories; Locks and Failed only go to the list and the counts', async ({ page }) => {
    const categories: any[] = await page.request.get('/api/v1/audit/categories', {
      headers: { 'X-User-Id': devIds.admin } }).then(r => r.json());
    const logs: Request[] = [];
    const stats: Request[] = [];
    await open(page, logs, stats);

    await expect(page.locator('#category-filter option')).toHaveCount(categories.length + 1);
    await expect(page.locator('#category-filter option').first()).toHaveText('All actions');
    await expect(page.locator('#category-filter option[value="LOCKS"]')).toHaveText('Locks');
    await page.locator('#category-filter').selectOption('LOCKS');
    await page.locator('#result-filter').selectOption('false');
    await expect.poll(() => stats.length).toBe(3);
    for (const req of [logs[logs.length - 1], stats[stats.length - 1]]) {
      expect(params(req).get('category')).toBe('LOCKS');
      expect(params(req).get('success')).toBe('false');
      expect(params(req).has('action')).toBe(false);
    }
  });

  test('rows read as sentences with who did it, the AWS reason as a note, and 10 a page', async ({ page }) => {
    // Rows a page follow the window (U18-T03): at 1440 x 900 all ten fit.
    await page.setViewportSize({ width: 1440, height: 900 });
    const at = new Date().toISOString();
    const rows = [
      { logId: 'a', action: 'ACCESS_GRANTED', userDisplayName: 'Sanjay Rao', environmentName: 'Analytics QA', success: true, createdAt: at,
        details: 'Access granted to Neha Gupta (neha@x.com). Level: USER. Approved by Sanjay Rao (sanjay@x.com). For 7 days' },
      { logId: 'b', action: 'AUTOMATION_RULE_SKIPPED', targetName: 'UAT with the client', environmentName: 'CRM UAT', success: true,
        createdAt: at, details: 'Skipped: Environment locked by Priya Nair' },
      { logId: 'c', action: 'VM_START_FAILED', userDisplayName: 'Priya Nair', targetName: 'report-worker', environmentName: 'Analytics QA',
        success: false, createdAt: at, errorMessage: 'AWS: InsufficientInstanceCapacity' },
    ];
    const logs: Request[] = [];
    await open(page, logs, [], rows, 230);

    const tr = page.locator('#audit-table tbody tr');
    await expect(tr).toHaveCount(3);
    await expect(tr.nth(0).locator('td').nth(1)).toHaveText('Sanjay Rao');
    await expect(tr.nth(0).locator('.activity-what')).toHaveText('Granted Neha Gupta operator access, 7 days');
    await expect(tr.nth(1).locator('td').nth(1)).toHaveText('Schedule "UAT with the client"');
    await expect(tr.nth(1).locator('.activity-what')).toHaveText('Run skipped: environment was locked by Priya Nair');
    await expect(tr.nth(2).locator('.activity-note')).toHaveText('AWS: InsufficientInstanceCapacity');
    await expect(tr.nth(2).locator('.activity-chip')).toHaveText('Failed');
    await expect(tr.nth(0).locator('time')).toHaveText(/^Today \d\d:\d\d$/);

    expect(params(logs[0]).get('size')).toBe('10');
    await expect(page.locator('#all-logs-pagination .pager-range')).toHaveText('1–10 of 230');
    await page.locator('#all-logs-pagination [data-action="audit-page"]', { hasText: 'Next' }).click();
    await expect.poll(() => params(logs[logs.length - 1]).get('page')).toBe('1');
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

  test('the filters take one row; the export re-reads the period, follows the filters and is named after them (U20-T04)', async ({ page }) => {
    await page.setViewportSize({ width: 1366, height: 657 });
    const logs: Request[] = [];
    await open(page, logs, []);
    const tops = await page.locator('.audit-filters > select, .audit-filters > input').evaluateAll(els =>
      [...new Set(els.map(e => Math.round(e.getBoundingClientRect().top)))]);
    expect(tops).toHaveLength(1);

    await page.locator('#result-filter').selectOption('false');
    await expect.poll(() => params(logs[logs.length - 1]).get('success')).toBe('false');
    const before = logs.length;
    const clicked = Date.now();
    const download = page.waitForEvent('download');
    await page.locator('#export-logs-btn').click();
    const file = await download;
    // The list is refreshed with a period ending at the click, and the export asks the same.
    const after = logs.slice(before).map(params);
    expect(after.length).toBeGreaterThanOrEqual(2);
    after.forEach(p => {
      expect(p.get('success')).toBe('false');
      expect(Date.parse(p.get('to')!)).toBeGreaterThanOrEqual(clicked - 1000);
    });
    expect(file.suggestedFilename()).toMatch(/^audit-log-\d{4}-\d{2}-\d{2}-last-7-days-failed-only\.csv$/);
    await expect(page.getByText('Exported 1 action: Last 7 days · Failed only.')).toBeVisible();
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

  test('an environment admin reads real rows without instance IDs (U12-T08)', async ({ page, playwright }) => {
    const envAdminId = process.env.TEST_DEV_ENV_ADMIN_ID || 'dev-user-001';
    const admin = await playwright.request.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:8080',
      extraHTTPHeaders: { 'X-User-Id': devIds.admin, 'Content-Type': 'application/json' } });
    await admin.patch(`/api/v1/users/${envAdminId}/roles`, { data: { admin: false, envAdmin: true } });
    await admin.dispose();
    await page.context().setExtraHTTPHeaders({ 'X-User-Id': envAdminId });
    await page.goto('/home');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/audit-logs-all'; });
    await expect(page.locator('#audit-table')).toBeVisible({ timeout: 15_000 });
    await page.locator('#time-range-filter').selectOption('30d');
    await page.waitForLoadState('networkidle');
    const text = await page.locator('#audit-table').innerText();
    expect(text).not.toMatch(/\bi-[0-9a-f]{8,17}\b/);
  });
});
