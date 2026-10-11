import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E22-T03: the Waste page. Against the smoke app (sweeps off) it explains how to turn sweeps on;
 * with faked findings it shows totals per type, filters by clicking a card, and dismisses a
 * finding with a reason. Non-admins are refused.
 */

declare const ContentRouter: any;

async function open(page: Page, role: 'admin' | 'user' = 'admin') {
  await signIn(page, role);
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/waste'; });
}

const FINDING = {
  findingId: 'f-1', findingType: 'UNATTACHED_VOLUME', region: 'ap-south-1', resourceId: 'vol-0abc', resourceName: 'old-data',
  environmentId: null, environmentName: null, vmId: null, details: '{"sizeGib":100}', estMonthlyCost: 10,
  status: 'OPEN', firstSeenAt: new Date(Date.now() - 40 * 86_400_000).toISOString(), lastSeenAt: new Date().toISOString(),
  eligibleSince: new Date(Date.now() - 40 * 86_400_000).toISOString(), dismissedBy: null, dismissedReason: null,
};

test.describe('Waste page', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('with sweeps off it says how to turn them on', async ({ page }) => {
    await open(page);
    await expect(page.locator('#waste-summary')).toContainText('Waste sweeps are turned off', { timeout: 15_000 });
    await expect(page.locator('#waste-sweep-btn')).toBeDisabled();
    await expect(page.locator('.waste-card')).toHaveCount(5);
  });

  test('totals, a card filter and a dismiss with a reason', async ({ page }) => {
    const findingUrls: string[] = [];
    let dismissed: any = null;
    await page.route(/\/api\/v1\/waste\/summary$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ sweepEnabled: true, openCount: 2, monthlyTotal: 13.65, byType: [
        { type: 'UNATTACHED_VOLUME', count: 1, monthly: 10 }, { type: 'UNASSOCIATED_IP', count: 1, monthly: 3.65 }] }) }));
    await page.route(/\/api\/v1\/waste\/findings\?/, route => {
      findingUrls.push(route.request().url());
      return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ content: [FINDING], page: { size: 25, number: 0, totalElements: 1, totalPages: 1 } }) });
    });
    await page.route(/\/api\/v1\/waste\/findings\/f-1\/dismiss/, route => {
      dismissed = route.request().postDataJSON();
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...FINDING, status: 'DISMISSED' }) });
    });
    await open(page);

    await expect(page.locator('.waste-total')).toContainText('$13.65', { timeout: 15_000 });
    await expect(page.locator('#waste-sweep-btn')).toBeEnabled();
    await expect(page.locator('#waste-findings')).toContainText('old-data');
    await expect(page.locator('#waste-findings')).toContainText('$10.00');

    await page.locator('.waste-card', { hasText: 'Unattached volumes' }).click();
    await expect.poll(() => findingUrls.some(u => u.includes('type=UNATTACHED_VOLUME'))).toBe(true);
    await expect(page.locator('.waste-card', { hasText: 'Unattached volumes' })).toHaveAttribute('aria-pressed', 'true');

    await page.getByRole('button', { name: 'Dismiss vol-0abc' }).click();
    await page.locator('#promptInput').fill('kept for the DR drill');
    await page.locator('#promptSubmitBtn').click();
    await expect.poll(() => dismissed).toEqual({ reason: 'kept for the DR drill' });
  });

  test('non-admins are refused', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await open(page, 'user');
    await expect(page.locator('#content-area')).toContainText('Access denied. Admin only.', { timeout: 15_000 });
    await expect(page.locator('#waste-link')).toBeHidden();
  });
});
