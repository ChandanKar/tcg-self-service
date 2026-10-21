import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E22-T03, U15-T07 (Waste.dc.html): the Waste page. Against the smoke app (sweeps off) it explains how
 * to turn sweeps on and 'Check again now' is disabled; with faked findings it shows the total, type
 * cards that filter the list, a fix per row and a dismiss with a reason. The fixes waiting for
 * approval are on Waste › Fixes. Non-admins are refused.
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

  test('with sweeps off it says how to turn them on; Check again now is disabled with the reason', async ({ page }) => {
    await open(page);
    await expect(page.locator('#waste-summary')).toContainText('Waste sweeps are turned off', { timeout: 15_000 });
    // Acceptance: sweeps off, Check again now is disabled with the reason.
    const sweep = page.getByRole('button', { name: 'Check again now' });
    await expect(sweep).toBeDisabled();
    await expect(sweep).toHaveAttribute('title', 'Waste sweeps are turned off: set waste.sweep.enabled');
    await expect(page.locator('.waste-card .waste-card-name')).toHaveText(['Everything', 'Unattached volumes', 'Unused public IPs',
      'gp2 → gp3', 'Stopped over 30 days', 'Old snapshots']);
    await expect(page.locator('#waste-intro')).toContainText('Things in AWS that cost money and do nothing. Checked daily at 04:00 UTC;');
  });

  test('the total, a type card filter and a dismiss with a reason (U15-T07)', async ({ page }) => {
    const findingUrls: string[] = [];
    let dismissed: any = null;
    const ip = { ...FINDING, findingId: 'f-ip', findingType: 'UNASSOCIATED_IP', resourceId: 'eipalloc-07c2e9', resourceName: null,
      details: '{"publicIp":"13.0.0.1"}', estMonthlyCost: 3.65 };
    await page.route(/\/api\/v1\/waste\/summary$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ sweepEnabled: true, openCount: 2, monthlyTotal: 13.65, checked: 'Daily 04:00',
        lastSweepAt: new Date().toISOString(), byType: [
        { type: 'UNATTACHED_VOLUME', count: 1, monthly: 10 }, { type: 'UNASSOCIATED_IP', count: 1, monthly: 3.65 }] }) }));
    await page.route(/\/api\/v1\/waste\/findings\?/, route => {
      const url = new URL(route.request().url());
      findingUrls.push(url.toString());
      const type = url.searchParams.get('type');
      const rows = (dismissed ? [ip] : [FINDING, ip]).filter(f => !type || f.findingType === type);
      return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ content: rows, page: { size: 10, number: 0, totalElements: rows.length, totalPages: 1 } }) });
    });
    await page.route(/\/api\/v1\/waste\/findings\/f-1\/dismiss/, route => {
      dismissed = route.request().postDataJSON();
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...FINDING, status: 'DISMISSED' }) });
    });
    await open(page);

    await expect(page.locator('.waste-total')).toHaveText('$14 a month across 2 open findings', { timeout: 15_000 });
    await expect(page.locator('#waste-intro')).toContainText('Checked daily at 04:00 UTC; last check today.');
    await expect(page.getByRole('button', { name: 'Check again now' })).toBeEnabled();
    const volume = page.locator('#waste-findings tr[data-finding="f-1"]');
    await expect(volume).toContainText('vol-0abc');
    await expect(volume).toContainText('Unattached volume, 100 GiB');
    await expect(volume).toContainText('$10.00');
    await expect(volume).toContainText('40 days');
    expect(findingUrls[0]).toContain('size=10');

    // Acceptance: pressing Unused public IPs shows only IP findings and presses the card.
    await page.locator('.waste-card', { hasText: 'Unused public IPs' }).click();
    await expect(page.locator('.waste-card', { hasText: 'Unused public IPs' })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('.waste-card', { hasText: 'Everything' })).toHaveAttribute('aria-pressed', 'false');
    await expect(page.locator('#waste-findings tbody tr')).toHaveCount(1);
    await expect(page.locator('#waste-findings')).toContainText('Unused public IP 13.0.0.1');
    await page.locator('.waste-card', { hasText: 'Everything' }).click();
    await expect(page.locator('#waste-findings tbody tr')).toHaveCount(2);

    // Acceptance: Dismiss with a reason, and the row leaves the open list.
    await page.getByRole('button', { name: 'Dismiss vol-0abc' }).click();
    await page.locator('#promptInput').fill('kept for the DR drill');
    await page.locator('#promptSubmitBtn').click();
    await expect.poll(() => dismissed).toEqual({ reason: 'kept for the DR drill' });
    await expect(page.locator('#waste-findings tr[data-finding="f-1"]')).toHaveCount(0);
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

    // U15-T07: the list links to Waste › Fixes, where the fixes waiting for approval are.
    await expect(page.locator('.waste-foot')).toContainText('Fixes go through an approval and keep a snapshot first', { timeout: 15_000 });
    await page.getByRole('link', { name: 'Fixes · 2 waiting' }).click();
    await expect(page).toHaveURL(/#\/waste\/fixes$/);
    await expect(page.locator('.page-breadcrumb')).toContainText('Waste');
    await expect(page.locator('.sidebar-menu-link[data-content="waste"]')).toHaveClass(/active/);

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
    // Under load the dialog can still be fading over the panel: wait until it is gone.
    await expect(page.locator('.modal.show')).toHaveCount(0);

    // History: rollback offered for the executed gp3 migration.
    await panel.locator('summary').click();
    await panel.locator('[data-action="waste-rollback"]').click();
    await page.locator('#confirmBtn').click();
    await expect.poll(() => posted).toContain('/api/v1/waste/actions/a-done/rollback');
    await expect(page.locator('.modal.show')).toHaveCount(0);

    // Propose from the list.
    await page.evaluate(() => { location.hash = '#/waste'; });
    await page.getByRole('button', { name: 'Snapshot and delete: vol-0abc' }).click({ timeout: 15_000 });
    await expect(page.locator('.modal.show')).toContainText('Delete the volume (snapshot first): vol-0abc?');
    await page.locator('#confirmBtn').click();
    await expect.poll(() => posted).toContain('/api/v1/waste/findings/f-1/actions');
  });

  test('non-admins are refused', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await open(page, 'user');
    await expect(page.locator('#content-area')).toContainText('Waste is for administrators', { timeout: 15_000 });
    await expect(page.locator('#waste-link')).toBeHidden();
  });
});
