import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E08-T11: the Cost Setup page. The status comes from the smoke app (no AWS call); the
 * pre-flight check is faked in the browser so the test never reaches AWS: a denied action shows
 * DENIED with a copyable policy, and probes are listed.
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
  await expect(page.locator('.cost-setup-table')).toBeVisible({ timeout: 15_000 });
}

test.describe('Cost Setup', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('shows every cost feature with its switch and property, before any check', async ({ page }) => {
    let checks = 0;
    await page.route(/\/api\/v1\/cost-management\/setup\/check/, route => { checks++; return route.abort(); });
    await openSetup(page);

    await expect(page.locator('.cost-setup-table tbody tr')).toHaveCount(6);
    const tagging = page.locator('tr[data-feature="tagging"]');
    await expect(tagging).toContainText('cost.tagging.enabled');
    await expect(tagging).toContainText('COST_TAGGING_ENABLED');
    await expect(tagging).toContainText(/tagged, \d+ untagged/);
    await expect(page.locator('tr[data-feature="actuals"]')).toContainText('Run the pre-flight check');
    expect(checks).toBe(0);
  });

  test('a denied action shows DENIED with a policy to copy, and the probes are listed', async ({ page }) => {
    await page.route(/\/api\/v1\/cost-management\/setup\/check/, route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(CHECK) }));
    await openSetup(page);

    await page.locator('#cost-setup-check-btn').click();

    const actuals = page.locator('tr[data-feature="actuals"]');
    await expect(actuals.locator('.cost-setup-denied')).toContainText('ce:GetCostAndUsage');
    await expect(actuals.locator('[data-action="cost-setup-copy-policy"]')).toBeVisible();
    await expect(page.locator('tr[data-feature="optimizer"] .cost-setup-ok')).toBeVisible();
    await expect(page.locator('.cost-setup-probes')).toContainText('Compute Optimizer enrollment');
    await expect(page.locator('.cost-setup-principal')).toContainText('self-service-user');
  });

  test('the sidebar and the Cost Management toolbar link to it', async ({ page }) => {
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await expect(page.locator('#cost-setup-link a')).toHaveAttribute('href', '#/cost-setup');
    await page.waitForLoadState('networkidle');
    await page.evaluate(() => { location.hash = '#/cost-management'; });
    await expect(page.locator('.cost-toolbar a[href="#/cost-setup"]')).toBeVisible({ timeout: 15_000 });
  });
});
