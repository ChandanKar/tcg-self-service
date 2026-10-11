import { test, expect } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/** E26-T02: EKS node groups appear in the idle and rightsizing tables with their node count, and are not resized from here. */

declare const ContentRouter: any;

function page1(rows: any[]) {
  return JSON.stringify({ content: rows, page: { size: 10, number: 0, totalElements: rows.length, totalPages: 1 } });
}

test('EKS rows show their node count and no Apply button', async ({ page }) => {
  test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  await page.route(/\/api\/v1\/cost-management\/idle-waste\?/, route => route.fulfill({ status: 200, contentType: 'application/json',
    body: page1([{ vmId: 'ng-1', vmName: 'workers', environmentId: 'e', environmentName: 'Prod EKS', groupName: 'workers',
      monthlyCost: 359.42, costKnown: true, idleDurationMinutes: 600, idleSince: new Date().toISOString(), latestCpuUtilization: 1,
      monthlyIdleCost: 4.99, nodeCount: 3 }]) }));
  await page.route(/\/api\/v1\/cost-management\/rightsizing\?/, route => route.fulfill({ status: 200, contentType: 'application/json',
    body: page1([{ vmId: 'ng-1', vmName: 'workers', environmentId: 'e', environmentName: 'Prod EKS', currentInstanceType: 't3.xlarge',
      suggestedInstanceType: 't3.large', avgCpuUtilization: 8, peakCpuUtilization: 20, currentMonthlyCost: 359.42,
      estimatedMonthlyCostAfter: 179.71, estimatedMonthlySavings: 179.71, costKnown: true, source: 'cpu-threshold-rule',
      findingLevel: null, direction: 'SCALE_DOWN', vmStatus: 'STOPPED', priceApproximate: false, nodeCount: 3 }]) }));
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/cost-management'; });

  await expect(page.locator('#cost-idle-table-body .eks-nodes')).toHaveText('EKS · 3 nodes', { timeout: 15_000 });
  const rightsizing = page.locator('#cost-rightsizing-table-body');
  await expect(rightsizing.locator('.eks-nodes')).toHaveText('EKS · 3 nodes');
  await expect(rightsizing).toContainText('Change the node group in EKS');
  await expect(rightsizing.locator('.rightsizing-apply-btn')).toHaveCount(0);
});
