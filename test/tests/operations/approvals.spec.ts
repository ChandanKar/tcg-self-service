import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E25-T06: the Approvals page (approve, deny with a reason), the waiting banner on the environment
 * page, and the policy page with its dry run. Approval requests are faked (no start may reach the
 * cloud from e2e); policies use the real API.
 */

declare const ContentRouter: any;

function request(over: any = {}) {
  return {
    requestId: 'req-1', requestType: 'START', environmentId: 'env-1', environmentName: 'Payments QA',
    requesterUserId: 'user-001', requesterName: 'Ravi', scopeSummary: 'a group, 8 h', estimatedCost: 1.54, requestedHours: 8,
    reasons: ['8 h is longer than 4 h'], approverMode: 'ANY', status: 'PENDING', statusReason: null,
    createdAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 86_400_000).toISOString(), decidedAt: null, executionId: null,
    approvers: [{ userId: 'admin-001', name: 'Admin', decision: null, reason: null, decidedAt: null },
      { userId: 'u-2', name: 'Bea', decision: null, reason: null, decidedAt: null }],
    canDecide: true, canCancel: false, ...over,
  };
}

async function go(page: Page, hash: string, role: 'admin' | 'user' = 'admin') {
  await signIn(page, role);
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(h => { location.hash = h; }, hash);
}

test.describe('Approvals', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('an approver approves one request and denies another with a reason', async ({ page }) => {
    const posted: any[] = [];
    await page.route(/\/api\/v1\/approval-requests\?view=to-review$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify([request(), request({ requestId: 'req-2', environmentName: 'Data lab', requestType: 'EXTEND', scopeSummary: '+240 min (15 h in total)' })]) }));
    await page.route(/\/api\/v1\/approval-requests\/[^/]+\/(approve|deny)$/, route => {
      posted.push({ url: new URL(route.request().url()).pathname, body: route.request().postDataJSON() });
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(request({ status: 'EXECUTED' })) });
    });
    await go(page, '#/approvals');

    const first = page.locator('.approval-card[data-request="req-1"]');
    await expect(first).toContainText('Start Payments QA', { timeout: 15_000 });
    await expect(first).toContainText('Asked by Ravi');
    await expect(first).toContainText('$1.54');
    await expect(first).toContainText('8 h is longer than 4 h');
    await expect(page.locator('.approval-card[data-request="req-2"]')).toContainText('Keep Data lab running longer');

    await first.locator('[data-action="approval-approve"]').click();
    await expect(page.locator('#confirmModal')).toContainText('It runs as Ravi');
    await page.locator('#confirmBtn').click();
    await expect.poll(() => posted.length).toBe(1);
    expect(posted[0].url).toContain('/req-1/approve');

    await page.locator('.approval-card[data-request="req-2"] [data-action="approval-deny"]').click();
    await page.locator('#promptInput').fill('Too long for a weekend');
    await page.locator('#promptSubmitBtn').click();
    await expect.poll(() => posted.length).toBe(2);
    expect(posted[1]).toEqual({ url: '/api/v1/approval-requests/req-2/deny', body: { reason: 'Too long for a weekend' } });
  });

  test('the environment page shows the waiting banner with Cancel', async ({ page, playwright }) => {
    const envId = await seedEnvironment(playwright, 'Waiting env');
    let cancelled = false;
    await page.route(/\/api\/v1\/approval-requests\?view=mine$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify(cancelled ? [request({ environmentId: envId, status: 'CANCELLED', canDecide: false })]
        : [request({ environmentId: envId, environmentName: 'Waiting env', canDecide: false, canCancel: true,
          approvers: [{ userId: 'a', name: 'Alice', decision: 'APPROVED' }, { userId: 'b', name: 'Bob', decision: null }] })]) }));
    await page.route(/\/api\/v1\/approval-requests\/req-1\/cancel$/, route => {
      cancelled = true;
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(request({ status: 'CANCELLED' })) });
    });
    await go(page, `#/environments/${envId}`);

    const banner = page.locator('#approval-waiting');
    await expect(banner).toContainText('Start Waiting env is waiting for approval by Bob.', { timeout: 15_000 });
    await banner.locator('[data-action="approval-cancel"]').click();
    await page.locator('#confirmBtn').click();
    await expect(page.locator('#approval-waiting')).toHaveCount(0, { timeout: 10_000 });
  });

  test('an admin creates a policy and the dry run explains the decision', async ({ page, playwright }) => {
    const envId = await seedEnvironment(playwright, 'Policy env');
    const name = `Big boxes ${Date.now()}`;
    await page.route(/\/api\/v1\/approval-policies\/evaluate$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ requiresApproval: true, outcome: 'REQUIRE_APPROVAL', reasons: ['24 h is longer than 12 h'],
        matches: [{ policyId: 'p', name, approverMode: 'ANY', reasons: ['24 h is longer than 12 h'] }] }) }));
    await go(page, '#/approval-policies');
    await expect(page.locator('#policy-table')).toBeVisible({ timeout: 15_000 });

    await page.locator('[data-action="policy-new"]').click();
    await page.locator('#pol-name').fill(name);
    await page.locator('#pol-scope').selectOption(`ENV:${envId}`);
    await page.locator('#pol-patterns').fill('*.8xlarge, p*');
    await page.locator('#pol-hours').fill('12');
    await page.locator('#pol-mode').selectOption('ALL');
    await page.locator('#pol-save').click();

    await page.locator('#policy-env-filter').selectOption(envId);
    const row = page.locator('#policy-table tbody tr', { hasText: name });
    await expect(row).toContainText('types *.8xlarge, p* · over 12 h', { timeout: 10_000 });
    await expect(row).toContainText('Environment admins (all)');

    await page.locator('#dry-env').selectOption(envId);
    await page.locator('#dry-hours').fill('24');
    await page.locator('[data-action="policy-dry-run"]').click();
    await expect(page.locator('#dry-outcome')).toContainText('Needs approval');
    await expect(page.locator('#dry-outcome')).toContainText('24 h is longer than 12 h');
  });
});
