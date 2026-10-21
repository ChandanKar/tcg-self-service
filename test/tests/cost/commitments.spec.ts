import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { withFeatures } from '../../fixtures/capabilities';

/**
 * E27-T03, U15-T04 (Commitments.dc.html): the Commitments tab (display only): four coverage and use
 * figures with bars, what AWS suggests buying with the over-commit risk on its row, and the aside on
 * schedules and the always-running floor. The insights endpoint is faked: data with risk flags, the
 * empty state, a failure that only this panel shows, and the tab hidden while reservations are off.
 */

declare const ContentRouter: any;

const INSIGHTS = {
  windowDays: 30,
  coverage: { snapshotDate: '2026-10-11', riCoveragePercent: 42.5, spCoveragePercent: 38, riUtilizationPercent: 95, spUtilizationPercent: 81 },
  scheduledHoursCoveredValue: 120, grossScheduledSaving: 400, scheduledAvoidedHours: 150,
  alwaysOnHourlyFloor: 2, floorVmCount: 20, floorNote: 'AWS EC2 VMs with a known price over the last 30 days; EKS node groups are not included',
  recommendations: [
    { type: 'COMPUTE_SP', term: 'ONE_YEAR', paymentOption: 'NO_UPFRONT', lookbackDays: 30, hourlyCommitment: 3.5, upfrontCost: 0,
      estimatedMonthlySavings: 300, estimatedSavingsPercent: 25, estimatedOnDemandCost: 1200, generatedOn: '2026-10-12', details: [] },
    { type: 'EC2_RI', term: 'ONE_YEAR', paymentOption: 'NO_UPFRONT', lookbackDays: 30, hourlyCommitment: null, upfrontCost: 0,
      estimatedMonthlySavings: 80, estimatedSavingsPercent: 20, estimatedOnDemandCost: 400, generatedOn: '2026-10-12',
      details: [{ instanceFamily: 'c6i', instanceType: 'c6i.xlarge', region: 'ap-south-1', count: 4, estimatedMonthlySavings: 80 }] },
  ],
  riskFlags: [
    { code: 'OVER_COMMIT', message: 'Recommendation exceeds the always-on floor; schedules may leave the commitment under-used', recommendationType: 'COMPUTE_SP' },
    { code: 'UNDER_UTILISED', message: 'Existing commitments under-utilised (Savings Plans below 90%)', recommendationType: null },
  ],
};

async function open(page: Page, reservations = true) {
  await withFeatures(page, { costReservations: reservations }); // U15-T04: the tab needs cost.reservations.enabled
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  // Let the first page open first: a hash change during the router's first route could load the page twice.
  await expect(page.locator('#content-area h1').first()).toBeVisible({ timeout: 15_000 });
  await page.evaluate(() => { location.hash = '#/cost-management?tab=commitments'; });
}

test.describe('Commitments panel', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('four figures with bars, what AWS suggests buying with its risk, and the schedules aside', async ({ page }) => {
    await page.route(/\/cost-management\/commitments\/insights$/, r => r.fulfill({ json: INSIGHTS }));
    await open(page);

    const panel = page.locator('.commitments-panel');
    await expect(panel).toBeVisible({ timeout: 15_000 });
    // Acceptance: 38% Savings Plans coverage reads 38% with a bar at 38%.
    const sp = panel.locator('[data-kpi="sp-coverage"]');
    await expect(sp.locator('.cost-kpi-value')).toHaveText('38%');
    await expect(sp.locator('.commit-bar-cover')).toHaveAttribute('style', 'width:38%');
    await expect(panel.locator('[data-kpi="sp-used"]')).toContainText('19% of the commitment unused');
    await expect(panel.locator('[data-kpi="sp-used"] .commit-bar-low')).toHaveCount(1);
    await expect(panel.locator('[data-kpi="ri-used"] .commit-bar-good')).toHaveCount(1);

    const rows = panel.locator('#cost-commitments-table tbody tr');
    await expect(rows).toHaveCount(2);
    await expect(rows.first()).toContainText('Compute Savings Plan');
    await expect(rows.first()).toContainText('Covers EC2 in any region, family or size');
    await expect(rows.first()).toContainText('1 year · no upfront');
    await expect(rows.first()).toContainText('$3.50');
    await expect(rows.first()).toContainText('$300');
    // Acceptance: an OVER_COMMIT recommendation shows the risk sentence on its row.
    await expect(rows.first().locator('[data-flag="OVER_COMMIT"]'))
      .toHaveText('More than the always-running floor: schedules would waste part of it');
    await expect(rows.nth(1)).toContainText('Reserved Instance');
    await expect(rows.nth(1)).toContainText('4 × c6i.xlarge, ap-south-1');
    await expect(rows.nth(1).locator('[data-flag]')).toHaveCount(0);
    await expect(panel.locator('#cost-commit-recs-title + small')).toHaveText('Based on 30 days of use · generated 12 Oct');

    const aside = panel.locator('.commit-aside');
    await expect(aside).toContainText('Schedules here stopped VMs for 150 h in the last 30 days, saving $400');
    await expect(aside.locator('#cost-commitments-floor')).toContainText('$2.00 / hour');
    await expect(aside.locator('#cost-commitments-floor')).toContainText('20 VMs run even in the quietest hours');
    await expect(aside.locator('#cost-commit-covered')).toHaveText('$120 of stopped hours were already paid for by commitments.');
    await expect(aside.locator('[data-flag="UNDER_UTILISED"]')).toContainText('goes unused');
    await expect(panel.locator('.commitments-note')).toContainText('Read-only here: buy or change them in the AWS console.');
  });

  test('the tab is hidden, and nothing asked for, while reservations are off', async ({ page }) => {
    let asked = 0;
    await page.route(/\/cost-management\/commitments\/insights$/, r => { asked++; return r.fulfill({ json: INSIGHTS }); });
    await open(page, false);

    await expect(page.getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'true', { timeout: 15_000 });
    await expect(page.getByRole('tab', { name: 'Commitments' })).toHaveCount(0);
    expect(asked).toBe(0);
  });

  test('without recommendations it says they come on Mondays', async ({ page }) => {
    await page.route(/\/cost-management\/commitments\/insights$/, r => r.fulfill({ json: {
      ...INSIGHTS, coverage: null, recommendations: [], riskFlags: [], alwaysOnHourlyFloor: null, floorVmCount: 0,
      scheduledHoursCoveredValue: null, grossScheduledSaving: null } }));
    await open(page);

    const panel = page.locator('.commitments-panel');
    await expect(panel.locator('.commitments-empty')).toContainText('captured every Monday', { timeout: 15_000 });
    await expect(panel.locator('[data-kpi="sp-coverage"] .cost-kpi-value')).toHaveText('—');
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
    await expect(slot).toContainText("Commitment insights didn't load", { timeout: 15_000 });
    await expect(page.getByRole('tab', { name: 'Commitments' })).toHaveAttribute('aria-selected', 'true'); // the page rendered
    await slot.getByRole('button', { name: 'Try again' }).click();
    await expect(slot.locator('.commitments-panel')).toBeVisible();
  });
});
