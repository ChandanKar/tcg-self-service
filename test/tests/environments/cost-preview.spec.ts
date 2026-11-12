import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E25-T02: the start dialog and the lease-extension dialog show what they will cost. The preview
 * API is faked (prices come from the server's reference data); a failing preview leaves the dialog
 * working without a cost line.
 */

declare const ContentRouter: any;
declare const Leases: any;

function preview(over: any = {}) {
  return {
    hours: 8, hourlyTotal: 0.19, estimatedTotal: 1.54, currency: 'USD', unknownPriceCount: 0, alreadyRunningCount: 0,
    approximate: false,
    vms: [{ vmId: 'a', name: 'web-1', instanceType: 'm5.large', nodeCount: 1, hourlyRate: 0.096, estimatedCost: 0.77, priceKnown: true, approximate: false },
      { vmId: 'b', name: 'web-2', instanceType: 'm5.large', nodeCount: 1, hourlyRate: 0.096, estimatedCost: 0.77, priceKnown: true, approximate: false }],
    ...over,
  };
}

async function openEnv(page: Page, envId: string) {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(id => { location.hash = `#/environments/${id}`; }, envId);
  await expect(page.locator('#btn-env-action')).toBeVisible({ timeout: 15_000 });
}

test.describe('Cost preview in dialogs', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('the start dialog shows the cost next to the time estimate', async ({ page, playwright }) => {
    const envId = await seedEnvironment(playwright, 'Priced env');
    const asked: string[] = [];
    await page.route(/\/operations\/cost-preview/, route => {
      asked.push(route.request().url());
      return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify(preview({ unknownPriceCount: 1, approximate: true })) });
    });
    await openEnv(page, envId);
    await page.locator('#btn-env-action').click();

    // U08-T02: the cost box of the start dialog (StartConfirm.dc.html). U22-T05: with no lease and no
    // schedule nothing stops the run, so the cost is per hour and the dialog says so (it said '$1.54 for 8 h').
    await expect(page.locator('#operationConfirmModal #op-confirm-cost')).toHaveText('$0.19 an hour');
    await expect(page.locator('#op-confirm-rate')).toHaveText('until someone stops them');
    await expect(page.locator('#op-confirm-run')).toHaveText('They run until someone stops them: no schedule stops this environment.');
    expect(asked[0]).not.toContain('extension=true');
    await expect(page.locator('#op-confirm-btn')).toBeEnabled();
  });

  test('a failing preview leaves the dialog without a cost line', async ({ page, playwright }) => {
    const envId = await seedEnvironment(playwright, 'Unpriced env');
    await page.route(/\/operations\/cost-preview/, route => route.fulfill({ status: 500, body: '' }));
    await openEnv(page, envId);
    await page.locator('#btn-env-action').click();
    await expect(page.locator('#operationConfirmModal')).toBeVisible();
    await expect(page.locator('#op-confirm-cost')).toHaveText('—', { timeout: 10_000 });
    await expect(page.locator('#op-confirm-rate')).toHaveText('No price known');
    await expect(page.locator('#op-confirm-btn')).toBeEnabled();
  });

  test('U08-T02: the start dialog says what starts, offers lengths, prices each and names the approver', async ({ page, playwright }) => {
    const envId = await seedEnvironment(playwright, 'Payments Dev');
    const iso = (min: number) => new Date(Date.now() + min * 60000).toISOString();
    await page.route(/\/api\/v1\/environments\/[^/]+\/vms(\?.*)?$/, route => route.fulfill({ json: [
      { group: { groupId: 'g-db', name: 'db', displayName: 'Database', sequencePosition: 1 }, vms: [
        { vmId: 'd1', name: 'db-01', provider: 'AWS', status: 'RUNNING', instanceType: 'm5.large', sequencePosition: 1, lastStateSyncAt: iso(-1) }] },
      { group: { groupId: 'g-web', name: 'web', displayName: 'Web', sequencePosition: 2 }, vms: [
        { vmId: 'w1', name: 'web-01', provider: 'AWS', status: 'STOPPED', instanceType: 't3.medium', sequencePosition: 1, lastStateSyncAt: iso(-1) },
        { vmId: 'w2', name: 'web-02', provider: 'AWS', status: 'STOPPED', instanceType: 't3.medium', sequencePosition: 2, lastStateSyncAt: iso(-1) }] }] }));
    await page.route(/\/api\/v1\/environments\/[^/]+\/lease$/, route => route.fulfill({ status: 404, body: '' }));
    await page.route(/\/api\/v1\/environments\/[^/]+\/lease-policy$/, route => route.fulfill({ json: {
      enabled: true, effectiveDefaultMinutes: 480, effectiveMaxMinutes: 1440, platformMaxMinutes: 1440, canEdit: false } }));
    await page.route(/\/operations\/cost-preview/, route => {
      const hours = Number(new URL(route.request().url()).searchParams.get('hours')) || 8;
      return route.fulfill({ json: preview({ hours, hourlyTotal: 0.09, estimatedTotal: 0.09 * hours }) });
    });
    const evaluated: any[] = [];
    await page.route(/\/approval-policies\/evaluate$/, route => {
      const body = route.request().postDataJSON();
      evaluated.push(body);
      return route.fulfill({ json: body.hours > 8
        ? { requiresApproval: true, blocked: false, warnings: [], matches: [{ policyId: 'p', name: 'Runs over 8 hours need the owner',
          approverMode: 'ENV_OWNER', reasons: ['24 h is longer than 8 h'], approverNames: ['Sanjay Rao'] }] }
        : { requiresApproval: false, blocked: false, warnings: [], matches: [] } });
    });
    let started: any = null;
    await page.route(/\/api\/v1\/environments\/[^/]+\/operations$/, route => {
      if (route.request().method() !== 'POST') return route.fallback();
      started = route.request().postDataJSON();
      return route.fulfill({ json: { approvalRequestId: 'req-9', approvers: ['Sanjay Rao'], reasons: ['24 h is longer than 8 h'] } });
    });
    await openEnv(page, envId);
    await page.locator('#btn-env-action').click();

    const dialog = page.locator('#operationConfirmModal');
    await expect(dialog.locator('.modal-title')).toHaveText(/^Start 2 VMs in Payments Dev \d+\?$/);
    await expect(dialog.locator('#op-confirm-sentence')).toContainText('The Web group starts now. Database is already running.');
    await expect(dialog.locator('.op-confirm-targets')).toContainText('web-01, web-02 · t3.medium');
    const chips = dialog.locator('#op-confirm-chips .choice-chip');
    await expect(chips).toHaveText(['2 hours', '8 hours', '24 hours']);
    await expect(chips.nth(1)).toHaveAttribute('aria-pressed', 'true');
    await expect(dialog.locator('#op-confirm-cost')).toHaveText('$0.72');
    await expect(dialog.locator('#op-confirm-rate')).toHaveText('$0.09 an hour for both');
    await expect(dialog.locator('#op-confirm-approval')).toHaveText('No approval needed');
    await expect(dialog.locator('#op-confirm-hint')).toContainText('You get a reminder 15 minutes before.');
    await expect(page.locator('#op-confirm-btn')).toHaveText('Start 2 VMs');

    await chips.nth(2).click();
    await expect(dialog.locator('#op-confirm-cost')).toHaveText('$2.16');
    await expect(dialog.locator('#op-confirm-approval')).toHaveText('Needs Sanjay Rao');
    await expect(dialog.locator('#op-confirm-approval-note')).toHaveText('Runs over 8 hours need the owner');
    await expect(page.locator('#op-confirm-btn')).toHaveText('Send for approval');
    expect(evaluated.map(e => e.hours)).toEqual([8, 24]);

    await page.locator('#op-confirm-btn').click();
    await expect.poll(() => started).toMatchObject({ operationType: 'START', leaseMinutes: 1440 });
    await expect(page.locator('.toast-message').filter({ hasText: 'Sent for approval to Sanjay Rao' })).toBeVisible();
  });

  test('the extension dialog shows the extra cost for the chosen length', async ({ page }) => {
    const asked: string[] = [];
    await page.route(/\/operations\/cost-preview/, route => {
      const url = new URL(route.request().url());
      asked.push(url.search);
      const hours = Number(url.searchParams.get('hours'));
      return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify(preview({ hours, estimatedTotal: 0.19 * hours, hourlyTotal: 0.19 })) });
    });
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof Leases !== 'undefined');
    await page.evaluate(() => {
      const now = Date.now();
      Leases.showExtendDialog('env-x', { startedAt: new Date(now - 3_600_000).toISOString(), endsAt: new Date(now + 600_000).toISOString() },
        { effectiveMaxMinutes: 10_000 }, () => {});
    });
    const cost = page.locator('#lease-extend-cost');
    await page.locator('#lease-extend-minutes').getByRole('button', { name: '2 h', exact: true }).click(); // U07-T05 chips
    await expect(cost).toHaveText('Extra ~ $0.38 for 2 h');
    expect(asked.at(-1)).toContain('extension=true');
    expect(asked.at(-1)).toContain('hours=2');
  });
});
