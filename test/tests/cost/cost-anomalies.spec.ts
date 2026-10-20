import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E21-T04: cost anomalies under the environment's cost card (with owner feedback) and in the
 * Cost Management Anomalies panel, whose failure does not blank the page. The anomalies are faked
 * in the browser (the smoke app has no AWS).
 */

declare const ContentRouter: any;

function anomaly(patch: Record<string, unknown> = {}) {
  return {
    anomalyId: 'an-1', environmentId: 'env-a', environmentName: 'Payments', anomalyStart: '2026-10-08',
    anomalyEnd: '2026-10-09', totalImpact: 140, totalExpected: 20, maxScore: 87.5,
    topCause: 'Amazon Elastic Compute Cloud - Compute / BoxUsage:m5.large', feedback: null, canGiveFeedback: true,
    ...patch,
  };
}

async function open(page: Page, hash: string) {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(h => { location.hash = h; }, hash);
}

test.describe('Cost anomalies', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('a recent anomaly is the Cost alert on the environment page, with Review for administrators', async ({ page, playwright }) => {
    const environmentId = await seedEnvironment(playwright, 'Anomaly env');
    test.skip(!environmentId, 'Could not seed an environment');
    const asked: string[] = [];
    await page.route(new RegExp(`/api/v1/environments/${environmentId}/cost-anomalies`), route => {
      asked.push(route.request().url());
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([anomaly({ environmentId })]) });
    });
    await open(page, `#/environments/${environmentId}`);

    // U07-T07: the last 2 days only; the 90-day table moves to Insights (U08-T08).
    const alert = page.locator('#env-alerts [data-kind="cost"]');
    await expect(alert).toContainText('Spend was 8× usual: $160.00 against about $20.00.', { timeout: 15_000 });
    await expect(alert).toContainText('Top cause: Amazon Elastic Compute Cloud - Compute / BoxUsage:m5.large.');
    await expect(alert.getByRole('link', { name: 'Review' })).toHaveAttribute('href', '#/cost-management');
    expect(asked.every(u => u.includes('days=2'))).toBeTruthy();
    await expect(page.locator('.anomaly-env-card')).toHaveCount(0);
  });

  test('an environment without anomalies shows no Cost alert', async ({ page, playwright }) => {
    const environmentId = await seedEnvironment(playwright, 'Quiet env');
    test.skip(!environmentId, 'Could not seed an environment');
    await open(page, `#/environments/${environmentId}`);
    await expect(page.locator('#env-cost-card')).toBeVisible({ timeout: 15_000 });
    await page.waitForLoadState('networkidle');
    await expect(page.locator('#env-alerts [data-kind="cost"]')).toHaveCount(0);
  });

  test('Cost Management lists anomalies across environments and filters them', async ({ page }) => {
    await page.route(/\/api\/v1\/cost-anomalies\?days=30/, route => route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify({ content: [
        anomaly(), anomaly({ anomalyId: 'an-2', environmentId: 'env-b', environmentName: 'Search', totalImpact: 55 }),
        anomaly({ anomalyId: 'an-3', environmentId: null, environmentName: null, canGiveFeedback: true }),
      ], page: { size: 100, number: 0, totalElements: 3, totalPages: 1 } }),
    }));
    await page.route(/\/api\/v1\/cost-anomalies\/monitors$/, route => route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify([
        { monitorArn: 'arn:1', monitorName: 'tcg-env-payments', environmentId: 'env-a' },
        { monitorArn: 'arn:2', monitorName: 'services', environmentId: null },
      ]),
    }));
    await open(page, '#/cost-management');

    const panel = page.locator('[data-cost-panel="anomalies"]');
    await expect(panel).toContainText('1 monitor linked to environments', { timeout: 15_000 });
    await expect(panel.locator('tbody tr')).toHaveCount(3);
    await expect(panel).toContainText('Unlinked monitor');
    await panel.locator('#cost-anomaly-env').selectOption('env-b');
    await expect(page.locator('[data-cost-panel="anomalies"] tbody tr')).toHaveCount(1);
    await expect(page.locator('[data-cost-panel="anomalies"]')).toContainText('$55.00');
  });

  test('a failing anomalies request shows Retry on that panel only', async ({ page }) => {
    await page.route(/\/api\/v1\/cost-anomalies\?days=30/, route =>
      route.fulfill({ status: 500, contentType: 'application/json', body: '{"message":"boom"}' }));
    await open(page, '#/cost-management');

    await expect(page.locator('[data-cost-panel="anomalies"] .cost-panel-error')).toContainText("Couldn't load cost anomalies.",
      { timeout: 15_000 });
    await expect(page.locator('[data-cost-panel="kpi"] .dashboard-kpi')).toHaveCount(4);
    await expect(page.locator('.cost-panel-error')).toHaveCount(1);
  });
});
