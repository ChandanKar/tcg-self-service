import { test, expect, type Page, type Route } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { withFeatures } from '../../fixtures/capabilities';

/**
 * E37-T03: carbon next to cost. The environment cost card shows last month's attributed emissions
 * and a sparkline; the cost page's Carbon footprint panel lists environments with the unallocated
 * row, coverage, a trend and an Excel export, and changes month. While carbon is off (404) nothing
 * shows and no error appears. The carbon endpoints are faked in the browser.
 */

declare const EnvCost: any;
declare const ContentRouter: any;

const ENV_ID = 'env-carbon-e2e';
const json = (route: Route, body: unknown, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

const months = Array.from({ length: 12 }, (_, i) => {
  const m = new Date(Date.UTC(2025, 9 + i, 1)).toISOString().slice(0, 7);
  return { month: m, mbmKg: i < 3 ? null : 10 + i, lbmKg: i < 3 ? null : 20 + i, cost: 100, kgPerDollar: 0.1 };
});
const ENV_CARBON = { environmentId: ENV_ID, months, latestMonth: '2026-09', latestMbmKg: 21, latestLbmKg: 31,
  coverageNote: 'Estimated by cost share from AWS Customer Carbon Footprint data, which AWS publishes monthly with a delay of several weeks.' };

const fleet = (month: string, scale: number) => ({
  month, availableMonths: ['2026-09', '2026-08'],
  rows: [
    { environmentId: 'e1', environmentName: 'Payments <b>prod</b>', mbmKg: 120 * scale, lbmKg: 200, cost: 1200, kgPerDollar: 0.1, unallocated: false },
    { environmentId: 'e2', environmentName: 'QA', mbmKg: 30 * scale, lbmKg: 50, cost: 600, kgPerDollar: 0.05, unallocated: false },
    { environmentId: null, environmentName: 'Unallocated', mbmKg: 50, lbmKg: 80, cost: null, kgPerDollar: null, unallocated: true }],
  totalMbmKg: 150 * scale + 50, totalLbmKg: 330, coveragePct: 75.0,
  trend: [{ month: '2026-08', attributedKg: 75, unallocatedKg: 50 }, { month: '2026-09', attributedKg: 150, unallocatedKg: 50 }]
});

async function mountCard(page: Page, carbon: { status: number; body: unknown }) {
  await page.route(new RegExp(`/environments/${ENV_ID}/cost$`), r => json(r, {
    environmentId: ENV_ID, scope: 'FULL', monthStart: '2026-10-01', monthToDateEstimated: 63, monthToDateActual: null, actualDays: 0,
    changePercent: null, forecastMonthEnd: null, daily: [], topVms: [], savingsMtd: null, scheduleStatus: null, leaseStatus: null }));
  await page.route(new RegExp(`/environments/${ENV_ID}/carbon$`), r => json(r, carbon.body, carbon.status));
  await withFeatures(page, { carbon: true }); // U02-T03: switched on for this page
  await signIn(page, 'user');
  await page.waitForFunction(() => typeof EnvCost !== 'undefined' && typeof ContentRouter !== 'undefined');
  await page.waitForLoadState('networkidle');
  await page.evaluate(envId => {
    document.body.insertAdjacentHTML('beforeend',
      '<section id="env-cost-card" style="position:fixed;top:0;left:0;right:0;z-index:20000;background:#fff"></section>');
    EnvCost.render({ environmentId: envId });
  }, ENV_ID);
  await expect(page.locator('#env-cost-mtd')).toContainText('$63.00');
}

test.describe('Carbon on the environment cost card', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  });

  test("shows last month's emissions in one line with the caveat", async ({ page }) => {
    await mountCard(page, { status: 200, body: ENV_CARBON });

    // U07-T06: one note under the cost in "About this environment"; the trend lives in Insights.
    const line = page.locator('#env-carbon');
    await expect(line).toContainText('Carbon (Sep 2026)');
    await expect(page.locator('#env-carbon-kg')).toHaveText('21 kg CO₂e');
    await expect(line).toContainText('market-based');
    await expect(line).toHaveAttribute('title', /publishes monthly with a delay/);
  });

  test('is absent while carbon is off', async ({ page }) => {
    await mountCard(page, { status: 404, body: { error: 'Not Found', message: 'Carbon data is not enabled' } });

    await page.waitForLoadState('networkidle');
    await expect(page.locator('#env-carbon')).toHaveCount(0);
    await expect(page.locator('.toast, .notification-toast')).toHaveCount(0);
  });
});

test.describe('Carbon footprint panel', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  async function openCostPage(page: Page, enabled: boolean) {
    const asked: string[] = [];
    await page.route(/\/api\/v1\/cost-management\/carbon(\?.*)?$/, route => {
      const month = new URL(route.request().url()).searchParams.get('month');
      asked.push(month || '');
      if (!enabled) return json(route, { error: 'Not Found', message: 'Carbon data is not enabled' }, 404);
      return json(route, month === '2026-08' ? fleet('2026-08', 0.5) : fleet('2026-09', 1));
    });
    await withFeatures(page, { carbon: true }); // U02-T03: switched on for this page
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    // Let the first page open before moving on: a hash change during the router's first route could
    // load Cost Management twice (and ask for carbon twice).
    await expect(page.locator('#content-area h1').first()).toBeVisible({ timeout: 15_000 });
    await page.evaluate(() => { location.hash = '#/cost-management?tab=carbon'; });
    await expect(page.locator('#cost-report')).toBeVisible({ timeout: 15_000 });
    return asked;
  }

  test('market- and location-based totals, the matched share, six months, environments and Excel; changes month (U15-T04)', async ({ page }) => {
    const asked = await openCostPage(page, true);

    const panel = page.locator('.carbon-panel');
    const figures = panel.locator('#cost-carbon-summary');
    await expect(figures.locator('[data-kpi="mbm"]')).toContainText('200 kg CO₂e');
    await expect(figures.locator('[data-kpi="lbm"]')).toContainText('330 kg CO₂e');
    await expect(figures.locator('[data-kpi="matched"] .cost-kpi-value')).toHaveText('75%');
    await expect(figures.locator('.carbon-strip-label')).toHaveText(['Aug', 'Sep']);
    await expect(figures.locator('.carbon-strip')).toHaveAttribute('aria-label', 'Emissions over the last 2 months, from 125 to 200 kg');
    const rows = panel.locator('#cost-carbon-table tbody tr');
    await expect(rows).toHaveCount(3);
    await expect(rows.first().locator('td').first()).toHaveText('Payments <b>prod</b>');
    await expect(rows.first().locator('td').last()).toHaveText('10.0'); // kg per $100
    await expect(rows.last()).toHaveClass(/carbon-unallocated/);
    await expect(rows.last().locator('td').first()).toHaveText('Not matched to an environment');
    await expect(page.locator('#cost-tab-actions #cost-carbon-export')).toHaveText('Excel');
    await expect(page.locator('#cost-tab-actions #cost-carbon-export')).toHaveAttribute('href', /\/cost-management\/carbon\/export\?month=2026-09$/);

    // Acceptance: choosing another month reloads the table and totals for it.
    await page.locator('#cost-tab-actions #cost-carbon-month').selectOption('2026-08');
    await expect(page.locator('#cost-carbon-summary [data-kpi="mbm"]')).toContainText('125 kg CO₂e');
    await expect(page.locator('#cost-carbon-table tbody tr').first()).toContainText('60');
    await expect(page.locator('#cost-carbon-export')).toHaveAttribute('href', /month=2026-08$/);
    expect(asked).toEqual(['', '2026-08']);
  });

  test('is hidden without an error while carbon is off', async ({ page }) => {
    await openCostPage(page, false);

    // The capability says on but the server answers 404: the Carbon tab says there are no figures yet, without an error.
    await expect(page.getByRole('tab', { name: 'Carbon' })).toHaveAttribute('aria-selected', 'true', { timeout: 15_000 });
    await expect(page.locator('[data-cost-panel="carbon"]')).toBeAttached();
    await expect(page.locator('.carbon-panel')).toHaveCount(0);
    await expect(page.locator('[data-cost-panel="carbon"]')).toHaveText('No carbon figures yet. AWS publishes them once a month, a few months behind.');
    await expect(page.locator('[data-cost-panel="carbon"]')).not.toContainText("didn't load");
  });
});
