import { test, expect, type Page, type Route } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E35-T02: AWS's forecast next to the platform's on the cost page. The trend, forecast and
 * long-range endpoints are faked in the browser; the chart is checked through its ECharts option.
 */

declare const ContentRouter: any;
declare const echarts: any;

const day = (offset: number) => {
  const d = new Date(Date.UTC(2026, 9, 1 + offset));
  return d.toISOString().slice(0, 10);
};

const TREND = Array.from({ length: 20 }, (_, i) => ({ date: day(i), estimatedCost: 100 + i, actualCost: null }));
const FORECAST_POINTS = [20, 21, 22].map(i => ({ date: day(i), cost: 120 + i, isForecast: true }));
const AWS_DAILY = [20, 21, 22].map(i => ({ periodStart: day(i), periodEnd: day(i + 1), mean: 118 + i, lower: 108 + i, upper: 128 + i }));
const LONG = {
  awsGeneratedOn: '2026-10-12',
  awsMonthly: Array.from({ length: 18 }, (_, i) => {
    const m = new Date(Date.UTC(2026, 10 + i, 1)).toISOString().slice(0, 10);
    return { periodStart: m, periodEnd: m, mean: 3000 + i * 10, lower: 2800, upper: 3300 };
  }),
  platformRunRate: Array.from({ length: 18 }, (_, i) => ({ periodStart: new Date(Date.UTC(2026, 10 + i, 1)).toISOString().slice(0, 10), amount: 3100 })),
  averageDailyCost: 103.33,
};

const json = (route: Route, body: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });

async function open(page: Page, withAws: boolean) {
  await page.route(/\/api\/v1\/cost-management\/spend-trend/, route => json(route, TREND));
  await page.route(/\/api\/v1\/cost-management\/forecast\?/, route => json(route, {
    points: [...TREND.map(r => ({ date: r.date, cost: r.estimatedCost, isForecast: false })), ...FORECAST_POINTS],
    sufficientHistory: true, historyDaysUsed: 20, minHistoryDaysRequired: 14,
    awsDaily: withAws ? AWS_DAILY : [], awsGeneratedOn: withAws ? '2026-10-12' : null,
  }));
  await page.route(/\/api\/v1\/cost-management\/forecast\/long-range$/, route => json(route, LONG));
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/cost-management'; });
  await expect(page.locator('#cost-chart-trend svg')).toHaveCount(1, { timeout: 15_000 });
}

function seriesNames(page: Page) {
  return page.evaluate(() => echarts.getInstanceByDom(document.getElementById('cost-chart-trend')).getOption().series.map((s: any) => s.name));
}

test.describe('AWS cost forecast', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('the trend shows the platform forecast and the AWS mean with its interval band', async ({ page }) => {
    await open(page, true);
    await expect(page.locator('#cost-trend-subtitle')).toContainText('AWS forecast generated 2026-10-12; covers resources tagged tcg:managed-by');
    const names = await seriesNames(page);
    expect(names).toEqual(expect.arrayContaining(['Platform forecast', 'AWS forecast', 'AWS forecast (80% interval)']));
    const band = await page.evaluate(() => {
      const opt = echarts.getInstanceByDom(document.getElementById('cost-chart-trend')).getOption();
      const s = opt.series.find((x: any) => x.name === 'AWS forecast (80% interval)');
      return s.data.filter((v: any) => v !== null);
    });
    expect(band).toEqual([20, 20, 20]);
  });

  test('18 months shows AWS monthly points with the platform run-rate', async ({ page }) => {
    await open(page, true);
    await page.getByRole('button', { name: '18 months' }).click();
    await expect(page.locator('#cost-trend-subtitle')).toContainText("with the platform's run-rate");
    await expect.poll(() => seriesNames(page)).toEqual(expect.arrayContaining(['AWS forecast', 'Platform run-rate']));
    const bars = await page.evaluate(() => {
      const opt = echarts.getInstanceByDom(document.getElementById('cost-chart-trend')).getOption();
      return opt.series.find((x: any) => x.name === 'AWS forecast').data.length;
    });
    expect(bars).toBe(18);

    await page.getByRole('button', { name: '14 days' }).click();
    await expect.poll(() => seriesNames(page)).toContain('Platform forecast');
  });

  test('without an AWS forecast only the platform forecast shows, with a note', async ({ page }) => {
    await open(page, false);
    await expect(page.locator('#cost-trend-subtitle')).toContainText('no AWS forecast captured yet');
    const names = await seriesNames(page);
    expect(names).toContain('Platform forecast');
    expect(names).not.toContain('AWS forecast');
  });
});
