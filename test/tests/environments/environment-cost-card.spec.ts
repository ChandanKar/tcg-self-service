import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E18-T03: the cost card on the environment page. The /cost endpoint is faked in the browser
 * and the card is mounted with EnvCost.render: full and group scope, a failed request with
 * Retry, and a response that arrives after the user navigated away.
 */

declare const EnvCost: any;
declare const ContentRouter: any;

const ENV_ID = 'env-cost-e2e';

const FULL = {
  environmentId: ENV_ID, scope: 'FULL', monthStart: '2026-10-01',
  monthToDateEstimated: 63, monthToDateActual: 27, actualDays: 3,
  previousMonthSameDays: 48, changePercent: 25.0, forecastMonthEnd: 303, forecastBasis: 'run rate of the last 6 day(s)',
  daily: Array.from({ length: 10 }, (_, i) => ({ date: `2026-09-${String(27 + i > 30 ? i - 3 : 27 + i).padStart(2, '0')}`, estimated: 10, actual: null })),
  topVms: [{ vmId: 'v1', name: 'web-1', mtdCost: 50, status: 'RUNNING', costKnown: true },
           { vmId: 'v2', name: 'db-1', mtdCost: 13, status: 'STOPPED', costKnown: true }],
  savingsMtd: 4.5, savingsSource: 'idle auto-stop',
  scheduleStatus: { ruleCount: 1, nextStop: new Date(Date.now() + 3 * 3600000).toISOString(), nextStart: null },
  leaseStatus: null,
};

async function mount(page: Page) {
  await signIn(page, 'user');
  await page.waitForFunction(() => typeof EnvCost !== 'undefined' && typeof ContentRouter !== 'undefined');
  // Let the landing page finish routing first, so its navigation does not invalidate this mount.
  await page.waitForLoadState('networkidle');
  await page.evaluate(envId => {
    // A fixed overlay, above the app shell, so it is visible and clickable.
    document.body.insertAdjacentHTML('beforeend',
      '<section id="env-cost-card" style="position:fixed;top:0;left:0;right:0;z-index:20000;background:#fff"></section>');
    EnvCost.render({ environmentId: envId });
  }, ENV_ID);
}

test.describe('Environment cost card', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  });

  test('shows month to date, change, actuals, forecast, schedule, savings, top VMs and the trend', async ({ page }) => {
    await page.route(new RegExp(`/environments/${ENV_ID}/cost$`), r => r.fulfill({ json: FULL }));
    await mount(page);

    const card = page.locator('#env-cost-card');
    await expect(card.locator('#env-cost-mtd')).toContainText('$63.00');
    await expect(card.locator('.env-cost-change')).toContainText('25%');
    await expect(card).toContainText('Actual (3 days): $27.00');
    await expect(card).toContainText('If the current rate continues: $303.00');
    await expect(card.locator('#env-cost-schedule')).toContainText('next stop');
    await expect(card).toContainText('Saved $4.50 this month (idle auto-stop)');
    await expect(card).toContainText('web-1');
    await expect(card.locator('#env-cost-trend svg')).toHaveCount(1);
  });

  test('a group-only grant sees its groups without the trend', async ({ page }) => {
    await page.route(new RegExp(`/environments/${ENV_ID}/cost$`), r => r.fulfill({ json: {
      ...FULL, scope: 'GROUPS', monthToDateEstimated: 13, monthToDateActual: null, actualDays: 0,
      changePercent: null, forecastMonthEnd: null, daily: [], savingsMtd: null } }));
    await mount(page);

    const card = page.locator('#env-cost-card');
    await expect(card).toContainText('your groups only');
    await expect(card.locator('#env-cost-mtd')).toContainText('$13.00');
    await expect(card.locator('#env-cost-trend')).toHaveCount(0);
    await expect(card).not.toContainText('If the current rate continues');
  });

  test('a failed request shows Retry, which loads the card', async ({ page }) => {
    let calls = 0;
    await page.route(new RegExp(`/environments/${ENV_ID}/cost$`), r => {
      calls++;
      return calls === 1 ? r.fulfill({ status: 500, json: { message: 'boom' } }) : r.fulfill({ json: FULL });
    });
    await mount(page);

    await expect(page.locator('#env-cost-card')).toContainText("Couldn't load the cost");
    await page.locator('[data-action="env-cost-retry"]').click();
    await expect(page.locator('#env-cost-mtd')).toContainText('$63.00');
  });

  test('a response after navigating away is not rendered', async ({ page }) => {
    await page.route(new RegExp(`/environments/${ENV_ID}/cost$`), async r => {
      await new Promise(res => setTimeout(res, 1500));
      await r.fulfill({ json: FULL });
    });
    await mount(page);
    await page.evaluate(() => { location.hash = '#/my-environments'; });
    await page.waitForTimeout(2500);

    await expect(page.locator('#env-cost-card')).not.toContainText('$63.00');
  });
});
