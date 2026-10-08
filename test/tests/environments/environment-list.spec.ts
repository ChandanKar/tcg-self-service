import { test, expect } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E10-T05: the My Environments list takes the lock holder from the list payload (no lock
 * request per row) and titles rows with the display name, the slug shown underneath. The
 * page endpoint is faked in the browser.
 */

declare const ContentRouter: any;
declare const DestructiveConfirm: any;

const ENVS = [
  { environmentId: 'env-list-1', name: 'pay-qa', displayName: 'Payments QA', description: null, isActive: true,
    serviceType: 'EC2', groupCount: 2, vmCount: 3, runningVmCount: 1, regions: ['ap-south-1'],
    metadata: '{"defaultCloudProvider":"AWS"}', locked: true, lockedByUserId: 'someone-else',
    lockedByDisplayName: 'Riya Sharma', lockedAt: new Date().toISOString() },
  { environmentId: 'env-list-2', name: 'billing', displayName: 'billing', description: null, isActive: true,
    serviceType: 'EC2', groupCount: 1, vmCount: 0, runningVmCount: 0, regions: [], metadata: null,
    locked: false, lockedByUserId: null, lockedByDisplayName: null, lockedAt: null },
];

test.describe('My Environments list', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  });

  test('rows use the payload lock holder and the display name; no per-row lock request', async ({ page }) => {
    await page.route(/\/api\/v1\/environments\/page(\?.*)?$/, route => route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ content: ENVS, page: { size: 10, number: 0, totalElements: 2, totalPages: 1 } }),
    }));
    const lockRequests: string[] = [];
    page.on('request', req => {
      if (/\/environments\/env-list-\d\/lock/.test(req.url())) lockRequests.push(req.url());
    });

    await signIn(page, 'user');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/my-environments'; });

    const locked = page.locator('#content-area tr', { hasText: 'Payments QA' });
    await expect(locked).toBeVisible({ timeout: 10_000 });
    await expect(locked).toContainText('pay-qa');          // the slug, underneath
    await expect(locked).toContainText('Riya Sharma');     // holder from the payload
    await expect(page.locator('#content-area tr', { hasText: 'billing' })).toContainText('Unlocked');
    expect(lockRequests).toEqual([]);
  });
});

test.describe('Deactivate environment dialog', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('says deactivate and reactivate, never permanent (E10-T06)', async ({ page }) => {
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof DestructiveConfirm !== 'undefined');

    await page.evaluate(() => DestructiveConfirm.confirmDeleteEnvironment('Payments QA', 3, () => {}));

    const dialog = page.locator('.modal.show');
    await expect(dialog).toContainText('Deactivate environment');
    await expect(dialog).toContainText('can be reactivated later');
    await expect(dialog).toContainText('Its active lock is released');
    await expect(dialog).not.toContainText(/permanent|cannot be undone/i);
  });
});
