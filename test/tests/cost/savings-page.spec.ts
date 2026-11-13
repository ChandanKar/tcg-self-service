import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E17-T07, U15-T06 (Savings.dc.html): the Savings page. Four tiles, Saved by bars, the efficiency
 * line and the environments table with Team render; a failing panel shows its own Retry; the table
 * sorts; environment rows link to the environment; the covered-hours note shows only when the
 * summary reports covered entries; export and rebuild work from the header; and non-admins are
 * refused. Breakdown rows and failures are faked in the browser; the rest hits the smoke app
 * (test schema, no AWS).
 */

declare const ContentRouter: any;

async function openSavings(page: Page, role: 'admin' | 'user' = 'admin') {
  await signIn(page, role);
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => { location.hash = '#/savings'; });
}

const ENV_ROWS = [
  { key: 'env-a', label: 'Alpha', hours: 10, gross: 5, net: 4, team: null },
  { key: 'env-b', label: 'Bravo', hours: 30, gross: 20, net: 18, team: 'Payments' }
];

const now = new Date();
const previous = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1));
const previousMonth = `${previous.getUTCFullYear()}-${String(previous.getUTCMonth() + 1).padStart(2, '0')}`;

test.describe('Savings page', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('renders the tiles, Saved by, the efficiency line and the sortable environments with their team', async ({ page }) => {
    await page.route(/\/api\/v1\/savings\/by-environment/, route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ENV_ROWS) }));
    await page.route(/\/api\/v1\/savings\/efficiency\/trend/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify([{ date: '2026-08-01', efficiencyPercent: 62 }, { date: '2026-10-01', efficiencyPercent: 81 }]) }));
    await openSavings(page);

    await expect(page.locator('.savings-tile')).toHaveCount(4, { timeout: 15_000 });
    await expect(page.locator('.savings-tile .cost-kpi-label')).toHaveText(['Saved, net', 'Still on the table', 'Cost efficiency', 'Based on']);
    await expect(page.locator('[data-kpi="net"]')).toContainText('before running storage and IPs');
    await expect(page.locator('.savings-cause > span:first-child')).toHaveText(['Schedules', 'Idle auto-stop', 'Leases ending', 'Stopped by hand', 'Rightsizing']);
    await expect(page.locator('#savings-trend-svg')).toHaveAttribute('aria-label', 'Cost efficiency from 62% to 81% over 90 days');
    await expect(page.locator('.cost-panel-error')).toHaveCount(0);

    const envTable = page.locator('.savings-envs');
    const firstCell = envTable.locator('tbody tr').first().locator('td').first();
    // Default sort is net, largest first.
    await expect(firstCell).toHaveText('Bravo');
    // Acceptance: each row shows its team or a dash.
    await expect(envTable.locator('tbody tr').first().locator('.savings-team')).toHaveText('Payments');
    await envTable.getByRole('button', { name: 'Net' }).click();
    await expect(firstCell).toHaveText('Alpha');
    await expect(envTable.locator('tbody tr').first().locator('.savings-team')).toHaveText('—');
    await expect(envTable.getByRole('link', { name: 'Alpha' })).toHaveAttribute('href', '#/environments/env-a');

    // One Cost entry in the menu (U21-T07): Savings is reached beside the title and marks Cost management.
    await expect(page.getByRole('navigation', { name: 'Cost pages' }).locator('[aria-current="page"]')).toHaveText('Savings');
    await expect(page.locator('.sidebar-menu-link[data-content="cost-management"]')).toHaveClass(/active/);
  });

  test('a failing trend request shows Retry on its panel only', async ({ page }) => {
    let fail = true;
    await page.route(/\/api\/v1\/savings\/efficiency\/trend/, route => fail
      ? route.fulfill({ status: 500, contentType: 'application/json', body: '{"message":"boom"}' })
      : route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{ date: '2026-10-01', efficiencyPercent: 70 },
        { date: '2026-10-02', efficiencyPercent: 71 }]) }));
    await openSavings(page);

    await expect(page.locator('.cost-panel-error')).toHaveCount(1, { timeout: 15_000 });
    await expect(page.locator('.cost-panel-error')).toContainText("The efficiency trend didn't load");
    await expect(page.locator('.savings-causes')).toBeVisible();

    fail = false;
    await page.locator('[data-action="savings-retry"]').click();
    await expect(page.locator('#savings-trend-svg')).toBeVisible();
    await expect(page.locator('.cost-panel-error')).toHaveCount(0);
  });

  test('Still on the table shows the parts it adds up from, and no AWS median (U23-T06)', async ({ page }) => {
    await page.route(/\/api\/v1\/savings\/summary/, async route => {
      const response = await route.fetch();
      const body = await response.json();
      body.potentialSavings = 1051.25;
      body.potentialParts = { idle: 612.4, rightsizing: 390.85, waste: 48 };
      await route.fulfill({ response, body: JSON.stringify(body) });
    });
    await page.route(/\/api\/v1\/savings\/efficiency\/trend/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify([{ date: '2026-10-01', efficiencyPercent: 70 }, { date: '2026-10-02', efficiencyPercent: 71 }]) }));
    await openSavings(page);

    const table = page.locator('[data-kpi="table"]');
    await expect(table.locator('.savings-tile-value')).toHaveText('$1,051', { timeout: 15_000 });
    // U24-T04: with how it counts (Rightsizing here leaves out VMs already counted as idle).
    await expect(table.locator('.cost-kpi-note')).toHaveText('Idle VMs $612 · Rightsizing $391 · Waste $48. Each VM counted once, by its larger saving.');
    await expect(page.locator('#savings-trend-svg')).toBeVisible();
    await expect(page.getByText(/median/i)).toHaveCount(0);
  });

  test('the covered-hours note follows the summary', async ({ page }) => {
    await page.route(/\/api\/v1\/savings\/summary/, async route => {
      const response = await route.fetch();
      const body = await response.json();
      body.fullyCoveredEntries = 3;
      await route.fulfill({ response, body: JSON.stringify(body) });
    });
    await openSavings(page);
    await expect(page.locator('.savings-note')).toContainText('Savings Plans or Reserved Instances', { timeout: 15_000 });
  });

  test('changing the month shows that month in the tiles and the cause bars', async ({ page }) => {
    await page.route(/\/api\/v1\/savings\/summary\?month=/, async route => {
      const month = new URL(route.request().url()).searchParams.get('month');
      const response = await route.fetch();
      const body = await response.json();
      // The previous month gets its own figures.
      if (month === previousMonth) {
        body.realisedNet = 1846; body.realisedGross = 2071;
        body.byCause = [{ key: 'SCHEDULE', label: 'Schedules', net: 1120, gross: 1200, hours: 300 }];
      }
      await route.fulfill({ response, body: JSON.stringify(body) });
    });
    await openSavings(page);
    await expect(page.locator('.savings-tile')).toHaveCount(4, { timeout: 15_000 });
    const second = await page.locator('#savings-month option').nth(1).getAttribute('value');
    const request = page.waitForRequest(r => r.url().includes(`/savings/summary?month=${second}`));
    await page.locator('#savings-month').selectOption(second!);
    await request;
    await expect(page).toHaveURL(new RegExp(`#/savings\\?month=${second}`));
    // Acceptance: the four tiles and the cause bars show the chosen month's figures.
    await expect(page.locator('[data-kpi="net"] .savings-tile-value')).toHaveText('$1,846');
    await expect(page.locator('[data-kpi="net"]')).toContainText('$2,071 before running storage and IPs');
    await expect(page.locator('.savings-cause[data-cause="SCHEDULE"]')).toContainText('$1,120');
  });

  test('Export month, Excel downloads the ledger workbook; Rebuild the last 30 days asks first', async ({ page }) => {
    let rebuilt = 0;
    await page.route(/\/api\/v1\/savings\/backfill\?days=30$/, route => { rebuilt++; return route.fulfill({ status: 200, json: {} }); });
    await openSavings(page);
    await expect(page.locator('.savings-tile')).toHaveCount(4, { timeout: 15_000 });
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Export month, Excel' }).click();
    expect((await download).suggestedFilename()).toMatch(/^savings-ledger-\d{4}-\d{2}\.xlsx$/);

    await page.getByRole('button', { name: 'Data actions' }).click();
    await page.getByRole('menuitem', { name: 'Rebuild the last 30 days' }).click();
    await expect(page.locator('.modal.show')).toContainText('Rebuild the savings ledger for the last 30 days?');
    expect(rebuilt).toBe(0);
    await page.locator('#confirmBtn').click();
    await expect.poll(() => rebuilt).toBe(1);
  });

  test('a short window keeps every section: By environment fits its rows and pages, nothing is squeezed (U20-T01)', async ({ page }) => {
    await page.setViewportSize({ width: 1366, height: 657 });
    const rows = Array.from({ length: 40 }, (_, i) => ({ key: `env-${i}`, label: `Env ${String(i).padStart(2, '0')}`,
      hours: 40 - i, gross: 400 - i, net: 300 - i, team: null }));
    await page.route(/\/api\/v1\/savings\/by-environment/, route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(rows) }));
    await openSavings(page);

    const panel = page.locator('.savings-envs');
    await expect(panel.locator('tbody tr').first()).toBeVisible({ timeout: 15_000 });
    await expect(panel.locator('.list-panel-foot .pager')).toBeVisible();
    const fit = await page.evaluate(() => {
      const body = document.querySelector('.savings-envs .list-panel-body')!.getBoundingClientRect();
      const shown = [...document.querySelectorAll('.savings-envs tbody tr')]
        .filter(r => { const b = r.getBoundingClientRect(); return b.top >= body.top && b.bottom <= body.bottom + 1; }).length;
      const main = document.getElementById('mainContent')!;
      return { shown, rows: document.querySelectorAll('.savings-envs tbody tr').length, overflow: main.scrollHeight - main.clientHeight };
    });
    // Acceptance: at least 5 rows at 1366x657, every row of the page in view, and the page itself does not scroll.
    expect(fit.shown).toBeGreaterThanOrEqual(5);
    expect(fit.shown).toBe(fit.rows);
    expect(fit.overflow).toBeLessThanOrEqual(0);
    await expect(page.locator('.savings-causes')).toBeVisible();
    await expect(page.locator('.savings-efficiency')).toBeVisible();
  });

  test('non-admins are refused', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await openSavings(page, 'user');
    await expect(page.locator('#content-area')).toContainText('Savings is for administrators', { timeout: 15_000 });
    await expect(page.locator('#savings-link')).toBeHidden();
  });
});
