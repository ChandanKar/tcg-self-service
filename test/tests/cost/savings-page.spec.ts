import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E17-T07: the Savings leadership page. KPI tiles, the two charts and the two breakdown tables
 * render; a failing panel shows its own Retry; the tables sort; environment rows link to the
 * environment; the covered-hours note shows only when the summary reports covered entries; and
 * non-admins are refused. Breakdown rows and failures are faked in the browser; the rest hits
 * the smoke app (test schema, no AWS).
 */

declare const ContentRouter: any;

async function openSavings(page: Page, role: 'admin' | 'user' = 'admin') {
  await signIn(page, role);
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => { location.hash = '#/savings'; });
}

const ENV_ROWS = [
  { key: 'env-a', label: 'Alpha', hours: 10, gross: 5, net: 4 },
  { key: 'env-b', label: 'Bravo', hours: 30, gross: 20, net: 18 }
];

test.describe('Savings page', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('renders KPIs, charts and sortable breakdowns with environment links', async ({ page }) => {
    await page.route(/\/api\/v1\/savings\/by-environment/, route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ENV_ROWS) }));
    await openSavings(page);

    await expect(page.locator('.savings-page .dashboard-kpi')).toHaveCount(4, { timeout: 15_000 });
    await expect(page.locator('.dashboard-kpi', { hasText: 'Cost Efficiency' })).toBeVisible();
    await expect(page.locator('#savings-cause-chart')).toBeVisible();
    await expect(page.locator('#savings-trend-chart')).toBeVisible();
    await expect(page.locator('.cost-panel-error')).toHaveCount(0);

    const envTable = page.locator('.cost-table-card', { hasText: 'By environment' });
    const firstCell = envTable.locator('tbody tr').first().locator('td').first();
    // Default sort is net, largest first.
    await expect(firstCell).toHaveText('Bravo');
    await envTable.getByRole('button', { name: 'Net' }).click();
    await expect(firstCell).toHaveText('Alpha');
    await expect(envTable.getByRole('link', { name: 'Alpha' })).toHaveAttribute('href', '#/environments/env-a');

    // The sidebar shows the page to admins.
    await expect(page.locator('#savings-link')).toBeVisible();
  });

  test('a failing daily request shows Retry on its panel only', async ({ page }) => {
    let fail = true;
    await page.route(/\/api\/v1\/savings\/daily/, route => fail
      ? route.fulfill({ status: 500, contentType: 'application/json', body: '{"message":"boom"}' })
      : route.continue());
    await openSavings(page);

    await expect(page.locator('.cost-panel-error')).toHaveCount(1, { timeout: 15_000 });
    await expect(page.locator('.cost-panel-error')).toContainText("Couldn't load daily savings.");
    await expect(page.locator('#savings-trend-chart')).toBeVisible();

    fail = false;
    await page.locator('[data-action="savings-retry"]').click();
    await expect(page.locator('#savings-cause-chart')).toBeVisible();
    await expect(page.locator('.cost-panel-error')).toHaveCount(0);
  });

  test('the covered-hours note follows the summary', async ({ page }) => {
    await page.route(/\/api\/v1\/savings\/summary/, async route => {
      const response = await route.fetch();
      const body = await response.json();
      body.fullyCoveredEntries = 3;
      await route.fulfill({ response, body: JSON.stringify(body) });
    });
    await openSavings(page);
    await expect(page.locator('.savings-note')).toContainText('Savings Plans/RIs', { timeout: 15_000 });
  });

  test('changing the month refetches for that month', async ({ page }) => {
    await openSavings(page);
    await expect(page.locator('.savings-page .dashboard-kpi')).toHaveCount(4, { timeout: 15_000 });
    const second = await page.locator('#savings-month option').nth(1).getAttribute('value');
    const request = page.waitForRequest(r => r.url().includes(`/savings/summary?month=${second}`));
    await page.locator('#savings-month').selectOption(second!);
    await request;
    await expect(page).toHaveURL(new RegExp(`#/savings\\?month=${second}`));
  });

  test('Export month downloads the ledger workbook', async ({ page }) => {
    await openSavings(page);
    await expect(page.locator('.savings-page .dashboard-kpi')).toHaveCount(4, { timeout: 15_000 });
    const download = page.waitForEvent('download');
    await page.locator('[data-action="savings-export"]').click();
    expect((await download).suggestedFilename()).toMatch(/^savings-ledger-\d{4}-\d{2}\.xlsx$/);
  });

  test('non-admins are refused', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await openSavings(page, 'user');
    await expect(page.locator('#content-area')).toContainText('Access denied. Admin only.', { timeout: 15_000 });
    await expect(page.locator('#savings-link')).toBeHidden();
  });
});
