import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E30-T07: Apply lists the safe-resize checks, needs the restart option for a running VM,
 * follows the resize to its end, and shows why when it rolls back. AWS is never called: every
 * API the dialog uses is intercepted.
 */

declare const ContentRouter: any;

function page1(rows: any[]) {
  return JSON.stringify({ content: rows, page: { size: 10, number: 0, totalElements: rows.length, totalPages: 1 } });
}

const ROW = {
  vmId: 'vm-r', vmName: 'busy-api', environmentId: 'e', environmentName: 'Dev', currentInstanceType: 't3.xlarge',
  suggestedInstanceType: 't3.large', avgCpuUtilization: 4, peakCpuUtilization: 9, currentMonthlyCost: 120,
  estimatedMonthlyCostAfter: 60, estimatedMonthlySavings: 60, costKnown: true, source: 'cpu-threshold-rule', findingLevel: null,
  direction: 'SCALE_DOWN', vmStatus: 'RUNNING', priceApproximate: false, nodeCount: null, memoryDataAvailable: false,
  reasons: ['CPU_LOW'], agreement: 'ONLY_RULE', applicable: true, applicableReason: null, alternatives: [],
};

const PASSING = [
  { name: 'Safe resize', passed: true, message: 'Turned on' },
  { name: 'Environment lock', passed: true, message: "You hold the environment's lock" },
  { name: 'VM state', passed: true, message: 'Running: it will be stopped, resized and started again' },
  { name: 'Offered in the zone', passed: true, message: 't3.large is offered in ap-south-1a' },
];

async function openApply(page: Page, checks: any[], statuses: any[]) {
  const posts: any[] = [];
  await page.route(/\/api\/v1\/cost-management\/rightsizing\?/, route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: page1([ROW]) }));
  await page.route(/\/api\/v1\/cost-management\/rightsizing\/precheck\?/, route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(checks) }));
  await page.route(/\/api\/v1\/cost-management\/rightsizing\/apply$/, route => {
    posts.push(route.request().postDataJSON());
    return route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify({ resizeId: 'rz-1' }) });
  });
  let i = 0;
  await page.route(/\/api\/v1\/cost-management\/rightsizing\/resizes\/rz-1$/, route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(statuses[Math.min(i++, statuses.length - 1)]) }));
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/cost-management'; });
  await page.locator('#cost-rightsizing-table-body .rightsizing-apply-btn').click({ timeout: 15_000 });
  await expect(page.locator('#rightsizingApplyModal')).toBeVisible();
  return posts;
}

function status(s: string, stage: string, finished: boolean, errorMessage: string | null = null) {
  return { resizeId: 'rz-1', vmId: 'vm-r', oldInstanceType: 't3.xlarge', newInstanceType: 't3.large', status: s, stage,
    errorMessage, finished, restartRequested: true, originalStatus: 'RUNNING', updatedAt: null };
}

test.describe('Rightsizing Apply dialog', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('a running VM needs the restart option, then the resize is followed to completion', async ({ page }) => {
    test.setTimeout(60_000);
    const posts = await openApply(page, PASSING,
      [status('STOPPING', 'Stopping the VM', false), status('COMPLETED', 'Running on t3.large', true)]);
    const modal = page.locator('#rightsizingApplyModal');

    await expect(modal.locator('.rs-check')).toHaveCount(4);
    await expect(modal.locator('.rs-check.text-success')).toHaveCount(4);
    const confirm = modal.locator('#rs-apply-confirm');
    await expect(confirm).toBeDisabled();
    await modal.getByLabel('Stop, resize and start again').check();
    await expect(confirm).toBeEnabled();
    await confirm.click();

    await expect(modal.locator('.rs-status')).toHaveText('Stopping');
    await expect(modal.locator('.rs-status')).toHaveText('Completed', { timeout: 15_000 });
    await expect(modal.locator('.rs-stage')).toHaveText('Running on t3.large');
    expect(posts).toEqual([{ vmId: 'vm-r', targetInstanceType: 't3.large', restartIfRunning: true }]);
  });

  test('a rollback shows its reason as text', async ({ page }) => {
    test.setTimeout(60_000);
    await openApply(page, PASSING, [status('ROLLED_BACK', 'Back on t3.xlarge and running', true,
      'The VM did not start on t3.large: <b>InsufficientInstanceCapacity</b>')]);
    const modal = page.locator('#rightsizingApplyModal');
    await modal.getByLabel('Stop, resize and start again').check();
    await modal.locator('#rs-apply-confirm').click();

    await expect(modal.locator('.rs-status')).toHaveText('Rolled back', { timeout: 15_000 });
    await expect(modal.locator('.rs-error')).toHaveText('The VM did not start on t3.large: <b>InsufficientInstanceCapacity</b>');
    await expect(modal.locator('.rs-error b')).toHaveCount(0);
  });

  test('a failed check keeps Apply disabled', async ({ page }) => {
    await openApply(page, [...PASSING.slice(0, 3),
      { name: 'Offered in the zone', passed: false, message: 't3.large is not offered in ap-south-1a' }], []);
    const modal = page.locator('#rightsizingApplyModal');

    await expect(modal.locator('.rs-check.text-danger')).toContainText('not offered in ap-south-1a');
    await modal.getByLabel('Stop, resize and start again').check();
    await expect(modal.locator('#rs-apply-confirm')).toBeDisabled();
    await expect(modal.locator('#rs-mode-note')).toHaveText('Fix the failed checks to apply this change.');
  });
});
