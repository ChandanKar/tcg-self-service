import { test, expect } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { withFeatures } from '../../fixtures/capabilities';

/**
 * E30-T05, U15-T03 (Rightsizing.dc.html): one recommendation per VM — the Kind of change and Agreement
 * filters (on the server), the change with its kind and what AWS thinks, and the alternatives with a
 * Graviton option that needs an ARM64 image and so offers no Resize safely….
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

const GRAVITON = {
  ...DISAGREE, vmId: 'vm-g', vmName: 'search-index', currentInstanceType: 'c5.2xlarge', suggestedInstanceType: 'c7g.xlarge',
  peakCpuUtilization: 26, estimatedMonthlySavings: 126, source: 'compute-optimizer', agreement: 'AGREE', reasons: ['AWS_OVER_PROVISIONED'],
  applicable: false, applicableReason: 'Requires an ARM64 image',
};

/** Serves the rows the server would keep for the kind and agreement filters, and records what was asked. */
async function openRightsizing(page: any) {
  const asked: string[] = [];
  await page.route(/\/api\/v1\/cost-management\/rightsizing\?/, (route: any) => {
    const params = new URL(route.request().url()).searchParams;
    const kind = params.get('kind') || '';
    const agreement = params.get('agreement') || '';
    asked.push(`${kind}|${agreement}|${params.get('size')}`);
    let rows = [DISAGREE, MODERNIZE, GRAVITON];
    if (kind === 'NEWER') rows = [MODERNIZE, GRAVITON];
    if (kind === 'GRAVITON') rows = [GRAVITON];
    if (kind === 'SMALLER') rows = [DISAGREE, GRAVITON];
    if (agreement === 'AWS') rows = rows.filter(r => r.agreement === 'AGREE' || r.agreement === 'ONLY_AWS');
    return route.fulfill({ status: 200, contentType: 'application/json', body: page1(rows) });
  });
  await withFeatures(page, { safeResize: true });
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/cost-management?tab=rightsizing'; });
  await expect(page.locator('#cost-rightsizing-table-body tr[data-rs-vm]')).toHaveCount(3, { timeout: 15_000 });
  return asked;
}

test.describe('Rightsizing suggestions (U15-T03)', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('rows: the change with its kind and what AWS thinks, peaks, savings and Resize safely…', async ({ page }) => {
    const asked = await openRightsizing(page);
    expect(asked[0]).toBe('||8'); // 8 a page, no filter
    const body = page.locator('#cost-rightsizing-table-body');
    const quiet = body.locator('tr[data-rs-vm="vm-d"]');
    await expect(quiet.locator('.rs-change')).toContainText('t3.xlarge → t3.large');
    await expect(quiet.locator('.rs-change')).toContainText('Smaller · AWS disagrees');
    await expect(quiet.locator('[data-agreement="DISAGREE"]')).toHaveText('AWS disagrees');
    await expect(quiet.locator('.rs-peak')).toHaveText('9% / —');
    await expect(quiet.locator('.rs-saves')).toHaveText('↓ saves $60');
    await expect(quiet.getByRole('button', { name: 'Resize safely…' })).toBeEnabled();
    await expect(quiet.getByRole('link', { name: 'quiet' })).toHaveAttribute('href', '#/environments/e?vm=vm-d');
    await expect(body.locator('tr[data-rs-vm="vm-g"] .rs-change')).toContainText('Smaller, Graviton · AWS agrees');
    await expect(body.locator('tr[data-rs-vm="vm-m"] .rs-change')).toContainText('Newer generation');
    await expect(page.locator('#rs-kind option')).toHaveText(['All suggestions', 'Smaller size', 'Newer generation', 'Graviton (ARM)']);
    await expect(page.locator('.rs-aside h2')).toHaveText('How a safe resize works');
    await expect(page.locator('#rs-safe-off')).toHaveCount(0);
  });

  test('Graviton (ARM) keeps only Graviton targets; AWS agrees keeps what Compute Optimizer confirms', async ({ page }) => {
    const asked = await openRightsizing(page);
    const rows = page.locator('#cost-rightsizing-table-body tr[data-rs-vm]');
    // Acceptance: Graviton (ARM) leaves only rows whose target is a Graviton family.
    await page.locator('#rs-kind').selectOption({ label: 'Graviton (ARM)' });
    await expect(rows).toHaveCount(1);
    await expect(rows.first()).toContainText('c7g.xlarge');
    await expect(page).toHaveURL(/[?&]kind=GRAVITON/);
    // Acceptance: AWS agrees leaves only rows Compute Optimizer confirms.
    await page.locator('#rs-kind').selectOption('');
    await page.locator('#rs-agreement').selectOption({ label: 'AWS agrees' });
    await expect(rows).toHaveCount(1);
    await expect(rows.first()).toContainText('search-index');
    await expect(page).toHaveURL(/[?&]agreement=AWS/);
    expect(asked).toEqual(expect.arrayContaining(['GRAVITON||8', '|AWS|8']));
  });
});

test('the kind filter and alternatives with a Graviton option', async ({ page }) => {
  test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  await openRightsizing(page);
  const body = page.locator('#cost-rightsizing-table-body');

  // filter to newer generations
  await page.locator('#rs-kind').selectOption({ label: 'Newer generation' });
  await expect(body).not.toContainText('quiet');
  await expect(body).toContainText('old-gen');

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
  // U20-T03: a larger bill reads as extra cost (red up arrow, words), never as a green negative saving.
  await expect(options.locator('tr[data-option="m7i.large"] .cost-delta-more')).toHaveText('↑ costs $3.50 more');
  await expect(options.locator('tr[data-option="m6a.large"] .cost-delta-save')).toHaveText('↓ saves $7.00');
  await expect(options).not.toContainText('$-');

  // back to all
  await page.locator('#rs-kind').selectOption({ label: 'All suggestions' });
  await expect(body).toContainText('quiet');
});
