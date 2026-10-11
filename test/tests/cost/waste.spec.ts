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

  test('fix actions: propose, approve another admin proposal, type the IP, roll back', async ({ page }) => {
    const ip = { ...FINDING, findingId: 'f-ip', findingType: 'UNASSOCIATED_IP', resourceId: 'eipalloc-1', resourceName: null,
      details: '{"publicIp":"13.0.0.1"}', estMonthlyCost: 3.65 };
    const actions = [
      { actionId: 'a-mine', findingId: 'f-1', findingType: 'UNATTACHED_VOLUME', region: 'ap-south-1', resourceId: 'vol-0abc',
        actionType: 'DELETE_VOLUME', status: 'PROPOSED', proposedBy: 'admin-001', rollbackSupported: true, findingDetails: '{}' },
      { actionId: 'a-ip', findingId: 'f-ip', findingType: 'UNASSOCIATED_IP', region: 'ap-south-1', resourceId: 'eipalloc-1',
        actionType: 'RELEASE_IP', status: 'PROPOSED', proposedBy: 'someone-else', rollbackSupported: false,
        findingDetails: '{"publicIp":"13.0.0.1"}' },
      { actionId: 'a-done', findingId: 'f-x', findingType: 'GP2_VOLUME', region: 'ap-south-1', resourceId: 'vol-gp2',
        actionType: 'MIGRATE_GP3', status: 'EXECUTED', proposedBy: 'someone-else', rollbackSupported: true, findingDetails: null },
    ];
    const posted: string[] = [];
    await page.route(/\/api\/v1\/waste\/summary$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ sweepEnabled: true, openCount: 2, monthlyTotal: 13.65, byType: [] }) }));
    await page.route(/\/api\/v1\/waste\/findings\?/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ content: [FINDING, ip], page: { size: 25, number: 0, totalElements: 2, totalPages: 1 } }) }));
    await page.route(/\/api\/v1\/waste\/actions\/config$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: '{"enabled":true}' }));
    await page.route(/\/api\/v1\/waste\/actions$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify(actions) }));
    await page.route(/\/api\/v1\/waste\/(actions\/[^/]+\/(approve|rollback)|findings\/[^/]+\/actions)$/, route => {
      posted.push(new URL(route.request().url()).pathname);
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    });
    await open(page);

    const panel = page.locator('.waste-actions-card');
    await expect(panel).toContainText('Release IP', { timeout: 15_000 });
    // My own proposal: no Approve button.
    await expect(panel.locator('[data-action-id="a-mine"] [data-action="waste-approve"]')).toHaveCount(0);
    await expect(panel.locator('[data-action-id="a-mine"]')).toContainText('Waiting for another admin');

    // Releasing an IP needs the IP typed.
    await panel.locator('[data-action-id="a-ip"] [data-action="waste-approve"]').click();
    await expect(page.locator('#btn-destructive-confirm')).toBeDisabled();
    await page.locator('#confirm-text-input').fill('13.0.0.1');
    await page.locator('#btn-destructive-confirm').click();
    await expect.poll(() => posted).toContain('/api/v1/waste/actions/a-ip/approve');

    // History: rollback offered for the executed gp3 migration.
    await panel.locator('summary').click();
    await panel.locator('[data-action="waste-rollback"]').click();
    await page.locator('#confirmBtn').click();
    await expect.poll(() => posted).toContain('/api/v1/waste/actions/a-done/rollback');

    // Propose from the table.
    await page.getByRole('button', { name: 'Delete (snapshot first): vol-0abc' }).click();
    await page.locator('#confirmBtn').click();
    await expect.poll(() => posted).toContain('/api/v1/waste/findings/f-1/actions');
  });

  test('non-admins are refused', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await open(page, 'user');
    await expect(page.locator('#content-area')).toContainText('Access denied. Admin only.', { timeout: 15_000 });
    await expect(page.locator('#waste-link')).toBeHidden();
  });
});
