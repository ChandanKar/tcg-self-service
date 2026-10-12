import { test, expect } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/** E31-T04: the Spot opportunities panel lists savings and placement scores; it is hidden when empty. */

declare const ContentRouter: any;

function page1(rows: any[]) {
  return JSON.stringify({ content: rows, page: { size: 100, number: 0, totalElements: rows.length, totalPages: 1 } });
}

const ROWS = [
  { vmId: 'ng-1', name: 'workers', environmentId: 'e', environmentName: 'Dev EKS', kind: 'EKS_NODE_GROUP', instanceType: 'm6i.large',
    nodeCount: 3, onDemandMonthly: 207.36, spotMonthly: 86.4, savings: 120.96, placementScore: 8, eligible: true, reason: null },
  { vmId: 'vm-1', name: '<b>batch</b>', environmentId: 'e', environmentName: 'Dev EKS', kind: 'EC2', instanceType: 'm5.large',
    nodeCount: null, onDemandMonthly: 69.12, spotMonthly: 28.8, savings: 40.32, placementScore: null, eligible: false,
    reason: 'Requires relaunch as a Spot instance' },
];

async function openCost(page: any, rows: any[]) {
  await page.route(/\/api\/v1\/cost-management\/spot-opportunities\?/, (route: any) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: page1(rows) }));
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/cost-management'; });
}

test('Spot opportunities show savings, placement and who can convert', async ({ page }) => {
  test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  await openCost(page, ROWS);

  const table = page.locator('#cost-spot-table');
  await expect(table).toBeVisible({ timeout: 15_000 });
  const workers = table.locator('tr[data-spot-vm="ng-1"]');
  await expect(workers).toContainText('EKS node group · 3 nodes');
  await expect(workers.locator('.spot-score')).toHaveText('8/10');
  await expect(workers).toContainText('Can convert');
  const batch = table.locator('tr[data-spot-vm="vm-1"]');
  await expect(batch).toContainText('<b>batch</b>');
  await expect(batch.locator('b')).toHaveCount(0);
  await expect(batch).toContainText('Requires relaunch as a Spot instance');
});

test('with no opportunities the panel is not shown', async ({ page }) => {
  test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  await openCost(page, []);

  await expect(page.locator('#cost-rightsizing-table')).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('#cost-spot-table')).toHaveCount(0);
});
