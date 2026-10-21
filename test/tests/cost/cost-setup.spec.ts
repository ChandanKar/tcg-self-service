import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E08-T11, U15-T05 (CostSetup.dc.html): the Setup tab of Cost management. The status comes from the
 * smoke app (no AWS call); the pre-flight check is faked in the browser so the test never reaches
 * AWS: a refused action shows No permission with the fix and a copyable policy; probes, switches and
 * the estimate-vs-billing table sit under Details.
 */

declare const ContentRouter: any;

const CHECK = {
  credentialsConfigured: true,
  principalArn: 'arn:aws:iam::123456789012:user/self-service-user',
  checkedAt: new Date().toISOString(),
  features: [
    { key: 'actuals', label: 'Actual costs (Cost Explorer)', enabled: true, property: 'cost.actuals.enabled',
      envVar: 'COST_ACTUALS_ENABLED', lastRunAt: null, extra: {},
      checks: [{ action: 'ce:GetCostAndUsage', result: 'DENIED', hint: 'Grant ce:GetCostAndUsage (see the policy snippet)' }] },
    { key: 'optimizer', label: 'Compute Optimizer', enabled: true, property: 'cost.optimizer.enabled',
      envVar: 'COST_OPTIMIZER_ENABLED', lastRunAt: null, extra: {},
      checks: [{ action: 'compute-optimizer:GetEC2InstanceRecommendations', result: 'ALLOWED', hint: null }] },
  ],
  probes: [{ name: 'Compute Optimizer enrollment', result: 'DENIED', detail: 'Inactive in us-east-1 — opt in from the AWS console' }],
};

async function openSetup(page: Page) {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => { location.hash = '#/cost-setup'; });
  await expect(page.locator('.cost-setup-features')).toBeVisible({ timeout: 15_000 });
}

test.describe('Cost Setup', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('the cost tab bar with Setup selected; every feature with a status, before any check (U15-T05)', async ({ page }) => {
    let checks = 0;
    await page.route(/\/api\/v1\/cost-management\/setup\/check/, route => { checks++; return route.abort(); });
    await openSetup(page);

    // Acceptance: #/cost-setup shows the cost tab bar with Setup selected.
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Cost management');
    await expect(page.getByRole('tab', { name: 'Setup' })).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'false');
    await expect(page.locator('.sidebar-menu-link[data-content="cost-management"]')).toHaveClass(/active/);
    await expect(page.locator('.cost-setup-feature')).toHaveCount(8);
    await expect(page.locator('#cost-setup-headline')).toHaveText(/^\d of 8 cost features are on$/);
    await expect(page.locator('.cost-setup-summary')).toContainText('AWS permissions not checked yet');
    await expect(page.locator('#cost-setup-check-btn')).toHaveText('Check AWS permissions');
    await expect(page.locator('[data-feature="snapshot"] .cost-setup-name')).toHaveText('Daily cost estimate');
    await expect(page.locator('[data-feature="tagging"]').first()).toContainText(/tagged, \d+ untagged/);
    await expect(page.locator('.cost-setup-foot a')).toHaveAttribute('href', '#/settings?section=cost');

    // Details keeps what controls each feature.
    await page.locator('#cost-setup-details summary').click();
    const tagging = page.locator('.cost-setup-table tr[data-feature="tagging"]');
    await expect(tagging).toContainText('cost.tagging.enabled');
    await expect(tagging).toContainText('COST_TAGGING_ENABLED');
    expect(checks).toBe(0);

    // The other tabs open Cost management on their tab.
    await page.getByRole('tab', { name: 'Overview' }).click();
    await expect(page).toHaveURL(/#\/cost-management\?tab=overview$/);
  });

  test('a refused action: No permission with the fix sentence and a policy to copy; probes under Details (U15-T05)', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await page.route(/\/api\/v1\/cost-management\/setup\/check/, route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(CHECK) }));
    await openSetup(page);

    await page.locator('#cost-setup-check-btn').click();

    // Acceptance: ce:GetCostAndUsage refused, AWS billing figures says No permission and how to fix it.
    const actuals = page.locator('.cost-setup-feature[data-feature="actuals"]');
    await expect(actuals.locator('.cost-setup-name')).toHaveText('AWS billing figures');
    await expect(actuals.locator('.cost-setup-pill')).toHaveText('No permission');
    await expect(actuals.locator('.cost-setup-fix')).toHaveText('AWS refused ce:GetCostAndUsage. Ask your AWS admin to add it to the platform role.');
    await expect(page.locator('.cost-setup-feature[data-feature="optimizer"] .cost-setup-pill')).toHaveText('Working');
    await expect(page.locator('#cost-setup-headline')).toHaveText('1 of 2 cost features work');
    await expect(page.locator('.cost-setup-summary')).toContainText('Checked AWS permissions');
    await expect(page.locator('#cost-setup-check-btn')).toHaveText('Check again');
    await expect(page.locator('.cost-setup-principal')).toContainText('self-service-user');

    // Acceptance: Copy policy copies the policy JSON, as before.
    await actuals.getByRole('button', { name: 'Copy policy for ce:GetCostAndUsage' }).click();
    const copied = JSON.parse(await page.evaluate(() => navigator.clipboard.readText()));
    expect(copied.Statement[0].Action).toEqual(['ce:GetCostAndUsage']);

    await page.locator('#cost-setup-details summary').click();
    await expect(page.locator('.cost-setup-probes')).toContainText('Compute Optimizer enrollment');
    await expect(page.locator('.cost-setup-table tr[data-feature="actuals"] .cost-setup-denied')).toContainText('ce:GetCostAndUsage');
  });

  test('Details compares the estimate with AWS billing per environment (moved from Overview, U15-T05)', async ({ page }) => {
    await page.route(/\/api\/v1\/cost-management\/reconciliation\?/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify([{ environmentId: 'e1', environmentName: 'Payments', estimatedCost: 120, actualCost: 132,
        variancePercent: 10, daysInWindow: 30, daysWithActuals: 28 }]) }));
    await openSetup(page);
    await page.locator('#cost-setup-details summary').click();
    const row = page.locator('#cost-reconciliation-table tbody tr');
    await expect(row).toHaveCount(1);
    await expect(row).toContainText('Payments');
    await expect(row).toContainText('+10%');
    await expect(row).toContainText('28/30 days');
  });

  test('the Cost Management tabs link to it; the side menu no longer does (U04-T01, U15-T01)', async ({ page }) => {
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await expect(page.locator('#sidebar a[href="#/cost-setup"]')).toHaveCount(0);
    await page.waitForLoadState('networkidle');
    await page.evaluate(() => { location.hash = '#/cost-management'; });
    await expect(page.locator('.cost-tabs-row a[href="#/cost-setup"]')).toHaveText('Setup', { timeout: 15_000 });
  });
});
