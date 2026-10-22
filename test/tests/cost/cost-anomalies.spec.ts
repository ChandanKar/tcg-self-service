import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E21-T04, U15-T05 (Anomalies.dc.html): cost anomalies as the environment's Cost alert and on the
 * Unusual spend tab (period and environment filters, spent / usual / top cause, 'Was this expected?'
 * radios, a ticket column), whose failure does not blank the page. The anomalies are faked in the
 * browser (the smoke app has no AWS).
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
  // Let the first page open first: a hash change during the router's first route could load the page twice.
  await expect(page.locator('#content-area h1').first()).toBeVisible({ timeout: 15_000 });
  await page.evaluate(h => { location.hash = h; }, hash);
}

const listBody = (rows: any[]) => JSON.stringify({ content: rows, page: { size: 100, number: 0, totalElements: rows.length, totalPages: 1 } });

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
    await expect(alert.getByRole('link', { name: 'Review' })).toHaveAttribute('href', '#/cost-management?tab=anomalies');
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
    await open(page, '#/cost-management?tab=anomalies');

    const panel = page.locator('[data-cost-panel="anomalies"]');
    await expect(panel).toContainText('1 monitor linked to environments', { timeout: 15_000 });
    await expect(panel.locator('tbody tr')).toHaveCount(3);
    await expect(panel).toContainText('Unlinked monitor');
    const first = panel.locator('tbody tr').first();
    await expect(first).toContainText('8–9 Oct · 2 days');
    await expect(first.locator('td').nth(1)).toHaveText('$160+$140'); // spent, and the extra under it
    await expect(first.locator('.anomaly-usual')).toHaveText('$20');
    await expect(panel.locator('.anomaly-foot')).toContainText('3 of 3 in the last 30 days');
    await page.locator('#cost-tab-actions #cost-anomaly-env').selectOption('env-b');
    await expect(page.locator('[data-cost-panel="anomalies"] tbody tr')).toHaveCount(1);
    await expect(page.locator('[data-cost-panel="anomalies"]')).toContainText('+$55');
    await expect(page.locator('[data-cost-panel="anomalies"] .anomaly-foot')).toContainText('1 of 3 in the last 30 days');
  });

  test('Planned is a radio: the verdict is stored and stays chosen after a reload (U15-T05)', async ({ page }) => {
    let stored: string | null = null;
    const posted: any[] = [];
    await page.route(/\/api\/v1\/cost-anomalies\?days=30/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: listBody([anomaly({ feedback: stored })]) }));
    await page.route(/\/api\/v1\/cost-anomalies\/an-1\/feedback$/, route => {
      posted.push(route.request().postDataJSON());
      stored = posted[posted.length - 1].value;
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ anomalyId: 'an-1', feedback: stored }) });
    });
    await open(page, '#/cost-management?tab=anomalies');

    const group = page.getByRole('radiogroup', { name: 'Payments: was this expected?' });
    await expect(group.getByRole('radio')).toHaveText(['Unexpected', 'Planned', 'Not unusual'], { timeout: 15_000 });
    await group.getByRole('radio', { name: 'Planned' }).click();
    await expect(group.getByRole('radio', { name: 'Planned' })).toHaveAttribute('aria-checked', 'true');
    await expect(group.getByRole('radio', { name: 'Unexpected' })).toHaveAttribute('aria-checked', 'false');
    expect(posted).toEqual([{ value: 'PLANNED_ACTIVITY' }]);
    // Arrow keys move between the choices.
    await group.getByRole('radio', { name: 'Planned' }).focus();
    await page.keyboard.press('ArrowRight');
    await expect(group.getByRole('radio', { name: 'Not unusual' })).toBeFocused();

    // Acceptance: after a reload the stored verdict is still chosen.
    await page.reload();
    await expect(page.getByRole('radiogroup', { name: 'Payments: was this expected?' }).getByRole('radio', { name: 'Planned' }))
      .toHaveAttribute('aria-checked', 'true', { timeout: 15_000 });
  });

  test('the period changes to 90 days and stays in the address; the intro reads the alert settings (U15-T05)', async ({ page }) => {
    const asked: string[] = [];
    await page.route(/\/api\/v1\/cost-anomalies\?days=/, route => {
      asked.push(new URL(route.request().url()).searchParams.get('days') || '');
      return route.fulfill({ status: 200, contentType: 'application/json', body: listBody([anomaly()]) });
    });
    await page.route(/\/api\/v1\/cost-anomalies\/settings$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ checked: 'Every 12 hours', minImpact: 10, feedbackToAws: false }) }));
    await open(page, '#/cost-management?tab=anomalies');

    await expect(page.locator('#cost-anomalies-intro')).toHaveText('AWS Cost Anomaly Detection, checked every 12 hours. '
      + 'Environment owners are told when the extra spend is $10 or more. Answers are kept with each alert.', { timeout: 15_000 });
    await page.locator('#cost-anomaly-period').selectOption({ label: 'Last 90 days' });
    await expect(page).toHaveURL(/[?&]period=90/);
    await expect(page.locator('.anomaly-foot')).toContainText('1 of 1 in the last 90 days');
    expect(asked).toEqual(['30', '90']);
  });

  test('a failing anomalies request shows Retry on that panel only', async ({ page }) => {
    await page.route(/\/api\/v1\/cost-anomalies\?days=30/, route =>
      route.fulfill({ status: 500, contentType: 'application/json', body: '{"message":"boom"}' }));
    await open(page, '#/cost-management?tab=anomalies');

    await expect(page.locator('[data-cost-panel="anomalies"] .cost-panel-error')).toContainText("Cost anomalies didn't load",
      { timeout: 15_000 });
    await expect(page.getByRole('tab', { name: 'Unusual spend' })).toHaveAttribute('aria-selected', 'true');
    await expect(page.locator('.cost-panel-error')).toHaveCount(1);
  });
});
