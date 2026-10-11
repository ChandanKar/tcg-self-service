import { test, expect } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/** E26-T03: the Total Monthly Cost tile says how much of the total is the EKS control-plane fee. */

declare const ContentRouter: any;

function summary(eksControlPlaneCost: number) {
  return JSON.stringify({ totalMonthlyCost: 1272, previousPeriodCost: 0, monthOverMonthChangePercent: null,
    idleWasteMonthlyCost: 0, idleVmCount: 0, rightsizingPotentialSavings: 0, rightsizingCandidateCount: 0,
    totalVmCount: 2, costKnownVmCount: 2, generatedAt: new Date().toISOString(), scaleUpCandidateCount: 0,
    approximatePriceVmCount: 0, eksControlPlaneCost });
}

for (const [fee, expected] of [[72, /incl\. \$72\.00 EKS control plane/], [0, null]] as const) {
  test(`the total tile ${fee ? 'shows' : 'omits'} the EKS control-plane fee`, async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await page.route(/\/api\/v1\/cost-management\/summary(\?|$)/, route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: summary(fee) }));
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/cost-management'; });

    const grid = page.locator('.cost-kpi-grid');
    await expect(grid).toContainText('$1,272.00', { timeout: 15_000 });
    if (expected) {
      await expect(grid).toContainText(expected);
    } else {
      await expect(grid).not.toContainText('EKS control plane');
    }
  });
}
