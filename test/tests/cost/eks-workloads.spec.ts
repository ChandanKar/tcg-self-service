import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E26-T05: namespace and workload cost of an EKS environment, from Split Cost Allocation Data.
 * The workload endpoints are faked: on Cost Management (admin, environment picker) and in the
 * environment cost card (Workloads section, EKS only).
 */

declare const ContentRouter: any;
declare const EnvCost: any;

const ENV_ID = 'eks-workloads-e2e';

const WORKLOADS = {
  environmentId: ENV_ID, days: 7, from: '2026-10-05', to: '2026-10-11',
  totalSplitCost: 50, unusedCost: 5, unusedShare: 9.1,
  namespaces: [
    { namespace: 'payments', splitCost: 40, unusedCost: 3, share: 72.7, workloads: [
      { namespace: 'payments', workload: 'api', splitCost: 30, unusedCost: 3, share: 54.5 },
      { namespace: 'payments', workload: 'worker', splitCost: 10, unusedCost: 0, share: 18.2 }] },
    { namespace: 'search', splitCost: 10, unusedCost: 2, share: 18.2, workloads: [
      { namespace: 'search', workload: 'indexer', splitCost: 10, unusedCost: 2, share: 18.2 }] },
  ],
};

test.describe('EKS workloads', () => {
  test('an admin picks an EKS environment, expands a namespace and changes the days', async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await page.route(/\/api\/v1\/environments$/, r => r.fulfill({ json: [
      { environmentId: 'ec2-env', name: 'web', displayName: 'Web', serviceType: 'EC2' },
      { environmentId: ENV_ID, name: 'prod-eks', displayName: 'Prod EKS', serviceType: 'EKS' },
      { environmentId: 'eks-2', name: 'dev-eks', displayName: 'Dev EKS', serviceType: 'eks' }] }));
    const asked: string[] = [];
    await page.route(/\/cost-management\/eks-workload-cost\?/, r => {
      asked.push(r.request().url());
      return r.fulfill({ json: WORKLOADS });
    });
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/cost-management'; });

    const panel = page.locator('#cost-eks-workloads');
    await expect(page.locator('#cost-eks-env option')).toHaveText(['Prod EKS', 'Dev EKS'], { timeout: 15_000 });
    await expect(panel.locator('.eks-namespace-row')).toHaveCount(2);
    await expect(panel.locator('.eks-namespace-row').first()).toContainText('payments');
    await expect(panel.locator('.eks-namespace-row').first()).toContainText('$40.00');
    await expect(panel.locator('.eks-namespace-row').nth(1)).toContainText('$10.00');
    await expect(panel.locator('.eks-unused-row')).toContainText('$5.00');

    await expect(panel.locator('.eks-workload-row').first()).toBeHidden();
    await panel.locator('.eks-ns-toggle').first().click();
    await expect(panel.locator('.eks-workload-row[data-ns-parent="0"]')).toHaveCount(2);
    await expect(panel.locator('.eks-workload-row[data-ns-parent="0"]').first()).toBeVisible();
    await expect(panel.locator('.eks-workload-row[data-ns-parent="1"]')).toBeHidden();

    await panel.locator('.eks-workloads-days').selectOption('30');
    await expect.poll(() => asked.some(u => u.includes(`environmentId=${ENV_ID}`) && u.includes('days=30'))).toBe(true);

    // Another environment keeps the chosen days.
    await page.locator('#cost-eks-env').selectOption('eks-2');
    await expect.poll(() => asked.some(u => u.includes('environmentId=eks-2') && u.includes('days=30'))).toBe(true);
  });

  test('without EKS environments the panel is not shown', async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await page.route(/\/api\/v1\/environments$/, r => r.fulfill({ json: [
      { environmentId: 'ec2-env', name: 'web', displayName: 'Web', serviceType: 'EC2' }] }));
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/cost-management'; });

    await expect(page.locator('.cost-kpi-grid')).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('#cost-eks-workloads')).toHaveCount(0);
  });

  async function mountCard(page: Page, serviceType: string, scope = 'FULL') {
    await page.route(new RegExp(`/environments/${ENV_ID}/cost$`), r => r.fulfill({ json: {
      environmentId: ENV_ID, scope, monthStart: '2026-10-01', monthToDateEstimated: 63, monthToDateActual: null, actualDays: 0,
      changePercent: null, forecastMonthEnd: null, daily: [], topVms: [], savingsMtd: null,
      scheduleStatus: { ruleCount: 0 }, leaseStatus: null } }));
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof EnvCost !== 'undefined' && typeof ContentRouter !== 'undefined');
    await page.waitForLoadState('networkidle');
    await page.evaluate(([envId, type]) => {
      document.body.insertAdjacentHTML('beforeend',
        '<section id="env-cost-card" style="position:fixed;top:0;left:0;right:0;z-index:20000;background:#fff"></section>');
      EnvCost.render({ environmentId: envId, serviceType: type });
    }, [ENV_ID, serviceType]);
    await expect(page.locator('#env-cost-mtd')).toContainText('$63.00');
  }

  test('an EKS environment card opens its workloads; empty data explains how to turn it on', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    let calls = 0;
    await page.route(new RegExp(`/environments/${ENV_ID}/eks-workload-cost\\?days=7$`), r => {
      calls++;
      return r.fulfill({ json: { ...WORKLOADS, namespaces: [], unusedCost: 0, totalSplitCost: 0, unusedShare: 0 } });
    });
    await mountCard(page, 'EKS');

    expect(calls).toBe(0); // loaded only when opened
    await page.locator('#env-eks-workloads-toggle').click();
    await expect(page.locator('#env-eks-workloads .eks-workloads-empty')).toContainText('Split Cost Allocation Data');
    await page.locator('#env-eks-workloads-toggle').click();
    await expect(page.locator('#env-eks-workloads')).toBeHidden();
    expect(calls).toBe(1);
  });

  test('EC2 environments and group-only views get no workloads section', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await mountCard(page, 'EC2');
    await expect(page.locator('#env-eks-workloads-toggle')).toHaveCount(0);

    await page.unroute(new RegExp(`/environments/${ENV_ID}/cost$`));
    await page.route(new RegExp(`/environments/${ENV_ID}/cost$`), r => r.fulfill({ json: {
      environmentId: ENV_ID, scope: 'GROUPS', monthToDateEstimated: 13, daily: [], topVms: [], scheduleStatus: { ruleCount: 0 } } }));
    await page.evaluate(envId => EnvCost.render({ environmentId: envId, serviceType: 'EKS' }), ENV_ID);
    await expect(page.locator('#env-cost-mtd')).toContainText('$13.00');
    await expect(page.locator('#env-eks-workloads-toggle')).toHaveCount(0);
  });
});
