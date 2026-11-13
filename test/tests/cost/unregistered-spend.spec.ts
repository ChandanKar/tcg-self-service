import { test, expect } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * U22-T14 (staging review): fleet spend left out 129 instances found in AWS but not registered, and
 * nothing said so. Cost management names them under the title and opens Found in AWS. The discovery
 * endpoint is faked in the browser.
 */

declare const ContentRouter: any;

const item = (id: string, state: string, kind = 'EC2') =>
  ({ kind, ref: kind === 'EC2' ? `ref-${id}` : null, instanceId: id, name: id, accountRef: 'default', accountName: 'Main',
     region: 'ap-south-1', availabilityZone: 'ap-south-1a', instanceType: 't3.small', state, taggedEnvironment: null, taggedEnvironmentId: null });

test.describe('Spend outside the registry', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('Cost management says how many instances are not in the registry and opens Found in AWS', async ({ page }) => {
    await page.route(/\/api\/v1\/discovery\/found/, route => route.fulfill({ json: {
      items: [item('i-a', 'running'), item('i-b', 'stopped'), item('i-c', 'running'), item('eks-x', '', 'EKS')],
      sweptAt: new Date().toISOString(), unreachableRegions: [], ignoredCount: 0 } }));
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/cost-management'; });

    const line = page.locator('#cost-unregistered');
    // U23-T13: the EKS clusters Found in AWS lists are counted too.
    await expect(line).toHaveText('3 instances in AWS (2 running) and 1 EKS cluster are not in the registry, so their cost is not counted here. Review them',
      { timeout: 15_000 });
    await line.getByRole('button', { name: 'Review them' }).click();
    await expect(page.locator('#fia')).toBeVisible();
  });

  test('nothing is said when every instance and cluster is registered', async ({ page }) => {
    await page.route(/\/api\/v1\/discovery\/found/, route => route.fulfill({ json: {
      items: [], sweptAt: new Date().toISOString(), unreachableRegions: [], ignoredCount: 0 } }));
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/cost-management'; });
    await expect(page.locator('.cost-tabs-row')).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('#cost-unregistered')).toHaveText('');
  });
});
