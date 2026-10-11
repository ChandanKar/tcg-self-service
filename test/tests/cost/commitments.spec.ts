import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E27-T03: the Commitments panel on Cost Management (display only). The insights endpoint is
 * faked: data with risk flags, the empty state, and a failure that only this panel shows.
 */

declare const ContentRouter: any;

const INSIGHTS = {
  windowDays: 30,
  coverage: { snapshotDate: '2026-10-11', riCoveragePercent: 42.5, spCoveragePercent: 85, riUtilizationPercent: 95, spUtilizationPercent: 88 },
  scheduledHoursCoveredValue: 120, grossScheduledSaving: 400, scheduledAvoidedHours: 150,
  alwaysOnHourlyFloor: 2, floorVmCount: 20, floorNote: 'AWS EC2 VMs with a known price over the last 30 days; EKS node groups are not included',
  recommendations: [
    { type: 'COMPUTE_SP', term: 'ONE_YEAR', paymentOption: 'NO_UPFRONT', lookbackDays: 30, hourlyCommitment: 3.5, upfrontCost: 0,
      estimatedMonthlySavings: 300, estimatedSavingsPercent: 25, estimatedOnDemandCost: 1200, generatedOn: '2026-10-12', details: [] },
    { type: 'EC2_RI', term: 'ONE_YEAR', paymentOption: 'NO_UPFRONT', lookbackDays: 30, hourlyCommitment: null, upfrontCost: 0,
      estimatedMonthlySavings: 80, estimatedSavingsPercent: 20, estimatedOnDemandCost: 400, generatedOn: '2026-10-12', details: [] },
  ],
  riskFlags: [
    { code: 'OVER_COMMIT', message: 'Recommendation exceeds the always-on floor; schedules may leave the commitment under-used', recommendationType: 'COMPUTE_SP' },
    { code: 'UNDER_UTILISED', message: 'Existing commitments under-utilised (Savings Plans below 90%)', recommendationType: null },
  ],
};

async function open(page: Page) {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/cost-management'; });
}

test.describe('Commitments panel', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('shows coverage, the floor against the recommendations, risk flags and the table', async ({ page }) => {
    await page.route(/\/cost-management\/commitments\/insights$/, r => r.fulfill({ json: INSIGHTS }));
    await open(page);

    const panel = page.locator('.commitments-panel');
    await expect(panel).toBeVisible({ timeout: 15_000 });
    await expect(panel.locator('.commitments-kpis')).toContainText('85.0%');
    await expect(panel.locator('.commitments-kpis')).toContainText('$120.00');
    await expect(panel.locator('#cost-commitments-floor')).toContainText('$2.00/h');
    await expect(panel.locator('#cost-chart-commitments svg')).toHaveCount(1);
    await expect(panel.locator('.commitments-flags li')).toHaveCount(2);
    await expect(panel.locator('[data-flag="OVER_COMMIT"]')).toContainText('Compute Savings Plan');
    const rows = panel.locator('#cost-commitments-table tbody tr');
    await expect(rows).toHaveCount(2);
    await expect(rows.first()).toContainText('$3.50/h');
    await expect(rows.first()).toContainText('One Year');
    await expect(rows.nth(1)).toContainText('EC2 Reserved Instances');
    await expect(panel.locator('.commitments-note')).toContainText('this page never buys anything');
  });

  test('without recommendations it says they come on Mondays', async ({ page }) => {
    await page.route(/\/cost-management\/commitments\/insights$/, r => r.fulfill({ json: {
      ...INSIGHTS, coverage: null, recommendations: [], riskFlags: [], alwaysOnHourlyFloor: null, floorVmCount: 0,
      scheduledHoursCoveredValue: null, grossScheduledSaving: null } }));
    await open(page);

    const panel = page.locator('.commitments-panel');
    await expect(panel.locator('.commitments-empty')).toContainText('captured every Monday', { timeout: 15_000 });
    await expect(panel.locator('.commitments-empty a')).toHaveAttribute('href', '#/cost-setup');
    await expect(panel.locator('.commitments-flags')).toHaveCount(0);
    await expect(panel.locator('#cost-commitments-floor')).toContainText('—');
  });

  test('a failing insights request errors only this panel, and Retry loads it', async ({ page }) => {
    let calls = 0;
    await page.route(/\/cost-management\/commitments\/insights$/, r => {
      calls++;
      return calls === 1 ? r.fulfill({ status: 500, json: { message: 'boom' } }) : r.fulfill({ json: INSIGHTS });
    });
    await open(page);

    const slot = page.locator('[data-cost-panel="commitments"]');
    await expect(slot).toContainText("Couldn't load commitment insights", { timeout: 15_000 });
    await expect(page.locator('.cost-kpi-grid').first()).toBeVisible();
    await slot.locator('.cost-retry').click();
    await expect(slot.locator('.commitments-panel')).toBeVisible();
  });
});
