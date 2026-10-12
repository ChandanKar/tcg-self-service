import { test, expect } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E30-T05: one recommendation per VM — the direction filter, the AWS agreement badge, and the
 * alternatives with a Graviton option that needs an ARM64 image and so offers no Apply.
 */

declare const ContentRouter: any;

function page1(rows: any[]) {
  return JSON.stringify({ content: rows, page: { size: 10, number: 0, totalElements: rows.length, totalPages: 1 } });
}

const MODERNIZE = {
  vmId: 'vm-m', vmName: 'old-gen', environmentId: 'e', environmentName: 'Dev', currentInstanceType: 'm5.large',
  suggestedInstanceType: 'm6a.large', avgCpuUtilization: 30, peakCpuUtilization: 55, currentMonthlyCost: 70,
  estimatedMonthlyCostAfter: 63, estimatedMonthlySavings: 7, costKnown: true, source: 'newer-generation', findingLevel: null,
  direction: 'MODERNIZE', vmStatus: 'STOPPED', priceApproximate: false, nodeCount: null, memoryDataAvailable: false,
  reasons: ['NEWER_GENERATION'], agreement: 'ONLY_RULE', applicable: true, applicableReason: null,
  alternatives: [
    { instanceType: 'm6a.large', kind: 'NEWER_GENERATION', source: 'newer-generation', architecture: 'x86_64',
      monthlyCostAfter: 63, monthlySavings: 7, performanceRisk: null, requiresArchitectureChange: false, applicable: true, applicableReason: null },
    { instanceType: 'm7i.large', kind: 'NEWER_GENERATION', source: 'newer-generation', architecture: 'x86_64',
      monthlyCostAfter: 73.5, monthlySavings: -3.5, performanceRisk: null, requiresArchitectureChange: false, applicable: true, applicableReason: null },
    { instanceType: 'm7g.large', kind: 'GRAVITON', source: 'compute-optimizer', architecture: 'arm64',
      monthlyCostAfter: 59.6, monthlySavings: 10.4, performanceRisk: 1.5, requiresArchitectureChange: true, applicable: false,
      applicableReason: 'Requires an ARM64 image' },
  ],
};

const DISAGREE = {
  vmId: 'vm-d', vmName: 'quiet', environmentId: 'e', environmentName: 'Dev', currentInstanceType: 't3.xlarge',
  suggestedInstanceType: 't3.large', avgCpuUtilization: 4, peakCpuUtilization: 9, currentMonthlyCost: 120,
  estimatedMonthlyCostAfter: 60, estimatedMonthlySavings: 60, costKnown: true, source: 'cpu-threshold-rule', findingLevel: null,
  direction: 'SCALE_DOWN', vmStatus: 'RUNNING', priceApproximate: false, nodeCount: null, memoryDataAvailable: false,
  reasons: ['CPU_LOW', 'MEMORY_UNKNOWN'], agreement: 'DISAGREE', applicable: true, applicableReason: null, alternatives: [],
};

test('direction filter, agreement badge and alternatives with a Graviton option', async ({ page }) => {
  test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  const directions: string[] = [];
  await page.route(/\/api\/v1\/cost-management\/rightsizing\?/, route => {
    const direction = new URL(route.request().url()).searchParams.get('direction') || '';
    directions.push(direction);
    const rows = direction === 'MODERNIZE' ? [MODERNIZE] : direction ? [] : [DISAGREE, MODERNIZE];
    return route.fulfill({ status: 200, contentType: 'application/json', body: page1(rows) });
  });
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/cost-management'; });

  const body = page.locator('#cost-rightsizing-table-body');
  await expect(body.locator('[data-agreement="DISAGREE"]')).toHaveText('AWS disagrees', { timeout: 15_000 });

  // filter to modernize
  const filter = page.locator('.rightsizing-filter');
  await filter.getByRole('button', { name: 'Modernize' }).click();
  await expect(filter.getByRole('button', { name: 'Modernize' })).toHaveAttribute('aria-pressed', 'true');
  await expect(body).not.toContainText('quiet');
  await expect(body).toContainText('Modernize');
  expect(directions).toContain('MODERNIZE');

  // the alternatives open under the row
  const toggle = body.getByRole('button', { name: 'Options (3)' });
  await expect(page.locator('.rightsizing-alternatives')).toBeHidden();
  await toggle.click();
  await expect(toggle).toHaveAttribute('aria-expanded', 'true');
  const options = page.locator('.rightsizing-alternatives');
  await expect(options).toBeVisible();
  const graviton = options.locator('tr[data-option="m7g.large"]');
  await expect(graviton).toContainText('Graviton (ARM64)');
  await expect(graviton.locator('.rightsizing-arch-warning')).toHaveCount(1);
  await expect(graviton).toContainText('Requires an ARM64 image');
  await expect(graviton.locator('.rightsizing-apply-btn')).toHaveCount(0);
  await expect(options.locator('tr[data-option="m7i.large"] .rightsizing-apply-btn')).toBeEnabled();

  // back to all
  await filter.getByRole('button', { name: 'All' }).click();
  await expect(body).toContainText('quiet');
});
