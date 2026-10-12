import { test, expect } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E30-T03: the rightsizing table shows memory average and peak (a dash with a tooltip when the
 * CloudWatch agent is not reporting memory) and why each VM is listed.
 */

declare const ContentRouter: any;

function page1(rows: any[]) {
  return JSON.stringify({ content: rows, page: { size: 10, number: 0, totalElements: rows.length, totalPages: 1 } });
}

const BASE = { environmentId: 'e', environmentName: 'Dev', avgCpuUtilization: 4, peakCpuUtilization: 12,
  costKnown: true, source: 'cpu-threshold-rule', findingLevel: null, vmStatus: 'RUNNING', priceApproximate: false, nodeCount: null };

test('memory columns, the agent-missing dash and the reason chips', async ({ page }) => {
  test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  await page.route(/\/api\/v1\/cost-management\/rightsizing\?/, route => route.fulfill({ status: 200, contentType: 'application/json',
    body: page1([
      { ...BASE, vmId: 'vm-a', vmName: 'with-agent', currentInstanceType: 'm5.xlarge', suggestedInstanceType: 'm5.large',
        currentMonthlyCost: 140, estimatedMonthlyCostAfter: 70, estimatedMonthlySavings: 70, direction: 'SCALE_DOWN',
        avgMemoryUtilization: 21.5, peakMemoryUtilization: 34.25, memoryDataAvailable: true, reasons: ['CPU_LOW', 'MEMORY_LOW'] },
      { ...BASE, vmId: 'vm-b', vmName: 'no-agent', currentInstanceType: 't3.xlarge', suggestedInstanceType: 't3.large',
        currentMonthlyCost: 120, estimatedMonthlyCostAfter: 60, estimatedMonthlySavings: 60, direction: 'SCALE_DOWN',
        avgMemoryUtilization: null, peakMemoryUtilization: null, memoryDataAvailable: false, reasons: ['CPU_LOW', 'MEMORY_UNKNOWN'] },
      { ...BASE, vmId: 'vm-c', vmName: 'too-full', currentInstanceType: 'r5.xlarge', suggestedInstanceType: null,
        currentMonthlyCost: 180, estimatedMonthlyCostAfter: null, estimatedMonthlySavings: null, costKnown: false, direction: 'SCALE_DOWN',
        avgMemoryUtilization: 48, peakMemoryUtilization: 58, memoryDataAvailable: true,
        reasons: ['CPU_LOW', 'MEMORY_LOW', 'MEMORY_WOULD_NOT_FIT'] },
    ]) }));
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/cost-management'; });

  const table = page.locator('#cost-rightsizing-table');
  await expect(table.locator('thead')).toContainText('Mem avg', { timeout: 15_000 });
  await expect(table.locator('thead')).toContainText('Mem peak');

  const withAgent = page.locator('#cost-rightsizing-table-body tr', { hasText: 'with-agent' });
  await expect(withAgent).toContainText('21.5%');
  await expect(withAgent).toContainText('34.3%');
  await expect(withAgent.locator('.rightsizing-reason')).toHaveText(['CPU low', 'Memory low']);

  const noAgent = page.locator('#cost-rightsizing-table-body tr', { hasText: 'no-agent' });
  await expect(noAgent.locator('.rightsizing-mem-unknown')).toHaveCount(2);
  await expect(noAgent.locator('.rightsizing-mem-unknown').first()).toHaveAttribute('title', 'CloudWatch agent not reporting memory');
  await expect(noAgent.locator('[data-reason="MEMORY_UNKNOWN"]')).toHaveText('Memory unknown');

  const tooFull = page.locator('#cost-rightsizing-table-body tr', { hasText: 'too-full' });
  await expect(tooFull.locator('[data-reason="MEMORY_WOULD_NOT_FIT"]')).toHaveText('Memory would not fit');
  await expect(tooFull).toContainText('No suggestion');
  await expect(tooFull.locator('.rightsizing-apply-btn')).toHaveCount(0);
});
