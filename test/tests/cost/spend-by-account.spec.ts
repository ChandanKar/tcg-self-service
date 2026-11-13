import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E28-T11, U15-T02 (CostOverview.dc.html): Overview's five figures, spend by environment, team, account
 * (only with more than one account) or VM type as bars, and the biggest savings to act on. The cost
 * API is faked in the browser.
 */

declare const ContentRouter: any;

const today = new Date();
const ym = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`;
const SUMMARY = { totalMonthlyCost: 9100, previousPeriodCost: 0, monthOverMonthChangePercent: null, idleWasteMonthlyCost: 410,
  idleVmCount: 6, rightsizingPotentialSavings: 1340, rightsizingCandidateCount: 9, totalVmCount: 112, costKnownVmCount: 110,
  generatedAt: today.toISOString(), scaleUpCandidateCount: 0, approximatePriceVmCount: 0, eksControlPlaneCost: 0 };
const page1 = (rows: any[]) => ({ content: rows, page: { size: 10, number: 0, totalElements: rows.length, totalPages: 1 } });

async function open(page: Page, accounts: any[]) {
  // This month so far: day 1 billed by AWS, the rest estimated.
  const trend = Array.from({ length: today.getDate() }, (_, i) => ({ date: `${ym}-${String(i + 1).padStart(2, '0')}`,
    estimatedCost: 100, actualCost: i === 0 ? 90 : null }));
  await page.route(/\/cost-management\/summary$/, r => r.fulfill({ json: SUMMARY }));
  // U23-T01: the server's month to date and forecast, the figures Home and My account show.
  const days = today.getDate();
  await page.route(/\/cost-management\/month$/, r => r.fulfill({ json: { monthToDate: 90 + (days - 1) * 100, forecast: 9950,
    billedDays: 1, estimatedDays: days - 1, averagedDays: 7, dailyAverage: 100, daysLeft: 0 } }));
  await page.route(/\/cost-management\/spend-trend\?/, r => r.fulfill({ json: trend }));
  await page.route(/\/cost-management\/spend-by-account$/, r => r.fulfill({ json: accounts }));
  await page.route(/\/cost-management\/spend-by-environment$/, r => r.fulfill({ json: [
    { dimensionKey: 'e1', dimensionLabel: 'Data Lake Dev', cost: 1402, vmCount: 6 },
    { dimensionKey: 'e2', dimensionLabel: 'Billing Prod Mirror', cost: 1118, vmCount: 9 }] }));
  await page.route(/\/cost-management\/spend-trend-by-team\?/, r => r.fulfill({ json: [
    { date: `${ym}-01`, team: 'Payments', estimatedCost: 300 }, { date: `${ym}-01`, team: 'Data', estimatedCost: 500 },
    { date: '2020-01-01', team: 'Data', estimatedCost: 9999 }] }));
  await page.route(/\/cost-management\/rightsizing\?/, r => r.fulfill({ json: page1([
    { vmId: 'v1', vmName: 'etl-1', environmentName: 'Data Lake Dev', currentInstanceType: 'r6i.4xlarge', suggestedInstanceType: 'r6i.2xlarge',
      peakCpuUtilization: 22, estimatedMonthlySavings: 540, costKnown: true, direction: 'SCALE_DOWN' }]) }));
  await page.route(/\/cost-management\/idle-waste\?/, r => r.fulfill({ json: page1([
    { vmId: 'v2', vmName: 'batch-7', environmentName: 'Billing Prod Mirror', monthlyIdleCost: 210, costKnown: true }]) }));
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/cost-management'; });
  await expect(page.locator('.cost-kpi-grid')).toBeVisible({ timeout: 15_000 });
}

test.describe('Cost overview', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('five figures: month to date with its share from AWS billing, forecast, idle, rightsizing, priced VMs', async ({ page }) => {
    await open(page, []);
    const tiles = page.locator('.cost-kpi');
    await expect(tiles).toHaveCount(5);
    const days = today.getDate();
    const mtd = 90 + (days - 1) * 100;
    await expect(page.locator('[data-kpi="mtd"] .cost-kpi-value')).toHaveText('$' + mtd.toLocaleString('en-US'));
    await expect(page.locator('[data-kpi="mtd"]')).toContainText(`${Math.round(100 / days)}% from AWS billing`);
    await expect(page.locator('[data-kpi="forecast"] .cost-kpi-value')).toHaveText('$9,950'); // the server's, as on Home
    await expect(page.locator('[data-kpi="idle"]')).toContainText('a month if they stay idle, 6 VMs barely used');
    await expect(page.locator('[data-kpi="rightsizing"]')).toContainText('$1,340');
    await expect(page.locator('[data-kpi="priced"]')).toContainText('110 of 112');
    await expect(page.locator('[data-kpi="priced"]')).toContainText('2 have no list price');
  });

  test('spend by environment as bars; by team this month; the account choice only with two accounts', async ({ page }) => {
    await open(page, [{ dimensionKey: 'a', dimensionLabel: 'Default account', cost: 120, vmCount: 3 }]);
    const panel = page.locator('[data-cost-panel="breakdown"]');
    await expect(panel.locator('.cost-bar-row').first()).toContainText('Data Lake Dev');
    await expect(panel.locator('.cost-bar-row').first()).toContainText('$1,402');
    await expect(panel.locator('#cost-by option')).toHaveText(['environment', 'team', 'VM type']);
    // Acceptance: By team lists teams by this month's cost.
    await panel.locator('#cost-by').selectOption('team');
    await expect(page).toHaveURL(/[?&]by=team/);
    await expect(page.locator('[data-cost-panel="breakdown"] .cost-bar-row')).toHaveCount(2);
    await expect(page.locator('[data-cost-panel="breakdown"] .cost-bar-row').first()).toContainText('Data');
    await expect(page.locator('[data-cost-panel="breakdown"] .cost-bar-row').first()).toContainText('$500');
    await expect(page.locator('[data-cost-panel="breakdown"]')).toContainText('this month');
  });

  test('two accounts add the account choice', async ({ page }) => {
    await open(page, [
      { dimensionKey: 'a', dimensionLabel: 'Default account', cost: 120, vmCount: 3 },
      { dimensionKey: 'b', dimensionLabel: 'Payments', cost: 80, vmCount: 2 }]);
    const panel = page.locator('[data-cost-panel="breakdown"]');
    await panel.locator('#cost-by').selectOption('account');
    await expect(page.locator('[data-cost-panel="breakdown"] .cost-bar-row')).toHaveText([/Default account\s+\$120/, /Payments\s+\$80/]);
  });

  test('the biggest savings across rightsizing and idle VMs', async ({ page }) => {
    await open(page, []);
    const rows = page.locator('#cost-savings-table tbody tr');
    await expect(rows).toHaveCount(2);
    await expect(rows.first()).toContainText('etl-1');
    await expect(rows.first()).toContainText('r6i.4xlarge → r6i.2xlarge, CPU peaks at 22%');
    await expect(rows.first()).toContainText('$540');
    await expect(rows.nth(1)).toContainText('batch-7');
    await expect(page.locator('.cost-savings').getByRole('link', { name: 'All 1 suggestion, Excel' }))
      .toHaveAttribute('href', /\/cost-management\/rightsizing\/export$/);
  });
});
