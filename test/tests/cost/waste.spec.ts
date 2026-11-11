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
      'gp2 → gp3', 'Stopped over 30 days', 'Old snapshots', 'Idle VMs']); // Idle VMs moved here in U18-T04
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

  test('Waste › Fixes: the steps, Approve for another admin, the proposer waits, type the IP, roll back (U15-T08)', async ({ page }) => {
    const me = devIds.admin;
    const ip = { ...FINDING, findingId: 'f-ip', findingType: 'UNASSOCIATED_IP', resourceId: 'eipalloc-1', resourceName: null,
      details: '{"publicIp":"13.0.0.1"}', estMonthlyCost: 3.65 };
    const today = new Date().toISOString();
    let rolledBack = false;
    const actions = () => [
      { actionId: 'a-mine', findingId: 'f-1', findingType: 'UNATTACHED_VOLUME', region: 'ap-south-1', resourceId: 'vol-0abc',
        actionType: 'DELETE_VOLUME', status: 'PROPOSED', proposedBy: me, proposedAt: today, rollbackSupported: true,
        findingDetails: '{"sizeGib":100}', reason: 'Left over from the 3.9 load test', proposedByName: 'Test Admin', monthlySaving: 10 },
      { actionId: 'a-ip', findingId: 'f-ip', findingType: 'UNASSOCIATED_IP', region: 'ap-south-1', resourceId: 'eipalloc-1',
        actionType: 'RELEASE_IP', status: 'PROPOSED', proposedBy: 'someone-else', proposedAt: today, rollbackSupported: false,
        findingDetails: '{"publicIp":"13.0.0.1"}', reason: null, proposedByName: 'Priya Nair', monthlySaving: 3.65 },
      { actionId: 'a-done', findingId: 'f-x', findingType: 'GP2_VOLUME', region: 'ap-south-1', resourceId: 'vol-gp2',
        actionType: 'MIGRATE_GP3', status: rolledBack ? 'ROLLED_BACK' : 'EXECUTED', proposedBy: 'someone-else', proposedAt: today,
        rollbackSupported: true, findingDetails: null, reason: null, proposedByName: 'Priya Nair', monthlySaving: 5,
        rolledBackAt: rolledBack ? today : null },
      { actionId: 'a-bad', findingId: 'f-y', findingType: 'UNATTACHED_VOLUME', region: 'ap-south-1', resourceId: 'vol-bad',
        actionType: 'DELETE_VOLUME', status: 'FAILED', proposedBy: 'someone-else', proposedAt: today, rollbackSupported: true,
        findingDetails: null, errorMessage: 'The volume is attached again', reason: null, proposedByName: 'Rahul Verma', monthlySaving: 8 },
    ];
    const posted: string[] = [];
    await page.route(/\/api\/v1\/waste\/summary$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ sweepEnabled: true, openCount: 2, monthlyTotal: 13.65, byType: [] }) }));
    await page.route(/\/api\/v1\/waste\/findings\?/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ content: [FINDING, ip], page: { size: 10, number: 0, totalElements: 2, totalPages: 1 } }) }));
    await page.route(/\/api\/v1\/waste\/actions\/config$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: '{"enabled":true}' }));
    await page.route(/\/api\/v1\/waste\/actions$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify(actions()) }));
    await page.route(/\/api\/v1\/waste\/actions\/[^/]+\/(approve|rollback)$/, route => {
      const path = new URL(route.request().url()).pathname;
      posted.push(path);
      if (path.endsWith('/rollback')) rolledBack = true;
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    });
    await open(page);

    // The list links to Waste › Fixes.
    await expect(page.locator('.waste-foot')).toContainText('Fixes go through an approval and keep a snapshot first', { timeout: 15_000 });
    await page.getByRole('link', { name: 'Fixes · 2 waiting' }).click();
    await expect(page).toHaveURL(/#\/waste\/fixes$/);
    await expect(page.locator('.page-breadcrumb')).toContainText('Waste');
    // One Cost entry in the menu since U21-T07: Waste and its Fixes mark Cost management.
    await expect(page.locator('.sidebar-menu-link[data-content="cost-management"]')).toHaveClass(/active/);
    await expect(page.locator('.fix-steps b')).toHaveText(['Proposed', 'Approved', 'Applied', 'Roll back']);

    const table = page.locator('#waste-fixes-table');
    // Acceptance: the proposer sees 'Another admin approves'; another admin sees Approve.
    const mine = table.locator('tr[data-action-id="a-mine"]');
    await expect(mine).toContainText('Delete volume, snapshot first');
    await expect(mine).toContainText('“Left over from the 3.9 load test”');
    await expect(mine).toContainText('You');
    await expect(mine.locator('.fix-tag')).toHaveText('Waiting for approval');
    await expect(mine.locator('.fix-note')).toHaveText('Another admin approves');
    await expect(mine.locator('[data-action="waste-approve"]')).toHaveCount(0);
    const theirs = table.locator('tr[data-action-id="a-ip"]');
    await expect(theirs).toContainText('Priya Nair');
    await expect(theirs.locator('.rs-saves')).toHaveText('$4');

    // Releasing an IP needs the IP typed.
    await theirs.getByRole('button', { name: 'Approve' }).click();
    await expect(page.locator('#btn-destructive-confirm')).toBeDisabled();
    await page.locator('#confirm-text-input').fill('13.0.0.1');
    await page.locator('#btn-destructive-confirm').click();
    await expect.poll(() => posted).toContain('/api/v1/waste/actions/a-ip/approve');
    await expect(page.locator('.modal.show')).toHaveCount(0);

    // A failed fix shows why.
    await table.locator('tr[data-action-id="a-bad"]').getByRole('button', { name: 'Details' }).click();
    await expect(page.locator('#wasteFixDetailsModal')).toContainText('The volume is attached again');
    await page.locator('#wasteFixDetailsModal [data-bs-dismiss="modal"]').first().click();
    await expect(page.locator('.modal.show')).toHaveCount(0);

    // Acceptance: Roll back on a done gp3 change, confirmed, then it reads Rolled back.
    const done = table.locator('tr[data-action-id="a-done"]');
    await expect(done.locator('.fix-tag')).toHaveText('Done');
    await done.getByRole('button', { name: 'Roll back' }).click();
    await page.locator('#confirmBtn').click();
    await expect.poll(() => posted).toContain('/api/v1/waste/actions/a-done/rollback');
    await expect(page.locator('#waste-fixes-table tr[data-action-id="a-done"] .fix-tag')).toHaveText('Rolled back');
  });

  test('Propose a fix from a row: the finding, the fix and a reason (U15-T08)', async ({ page }) => {
    let sent: any = null;
    await page.route(/\/api\/v1\/waste\/summary$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ sweepEnabled: true, openCount: 1, monthlyTotal: 10, byType: [] }) }));
    await page.route(/\/api\/v1\/waste\/findings\?/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ content: [FINDING], page: { size: 10, number: 0, totalElements: 1, totalPages: 1 } }) }));
    await page.route(/\/api\/v1\/waste\/actions\/config$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: '{"enabled":true}' }));
    await page.route(/\/api\/v1\/waste\/actions$/, route => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
    await page.route(/\/api\/v1\/waste\/findings\/f-1\/actions$/, route => {
      sent = route.request().postDataJSON();
      return sent.reason && sent.reason.length > 500
        ? route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ message: 'Keep the reason under 500 characters.' }) })
        : route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    });
    await open(page);

    await page.getByRole('button', { name: 'Snapshot and delete: vol-0abc' }).click({ timeout: 15_000 });
    const dialog = page.locator('#wasteProposeModal');
    await expect(dialog.locator('#propose-summary')).toHaveText('vol-0abc · Unattached volume, 100 GiB · unused 40 days · $10.00 a month');
    await expect(dialog.getByRole('radio', { name: /Delete the volume/ })).toBeChecked();
    await expect(dialog).toContainText('A snapshot is taken first, so it can be restored');
    await dialog.getByLabel('Reason').fill('Left over from the 3.9 load test');
    await dialog.getByRole('button', { name: 'Propose fix' }).click();
    await expect.poll(() => sent).toEqual({ type: 'DELETE_VOLUME', reason: 'Left over from the 3.9 load test' });
    await expect(page.locator('.modal.show')).toHaveCount(0);
  });

  test('Idle VMs: a card with the idle cost a month, the list by idle cost with Excel (moved from Cost management, U18-T04)', async ({ page }) => {
    await page.route(/\/api\/v1\/cost-management\/summary$/, r => r.fulfill({ json: { idleVmCount: 2, idleWasteMonthlyCost: 412.6 } }));
    await page.route(/\/api\/v1\/cost-management\/idle-waste\?/, r => r.fulfill({ json: { content: [
      { vmId: 'v-1', vmName: 'report-worker', environmentName: 'Analytics QA', groupName: 'Batch', monthlyCost: 300, costKnown: true,
        latestCpuUtilization: 1.2, idleSince: new Date(Date.now() - 3 * 86_400_000).toISOString(), monthlyIdleCost: 280 },
      { vmId: 'v-2', vmName: 'old-api', environmentName: 'CRM UAT', groupName: 'App', monthlyCost: 150, costKnown: true,
        latestCpuUtilization: 0.4, idleSince: new Date(Date.now() - 86_400_000).toISOString(), monthlyIdleCost: 132.6 }],
      page: { size: 10, number: 0, totalElements: 2, totalPages: 1 } } }));
    await open(page);
    const card = page.locator('[data-action="waste-type"][data-type="IDLE_VMS"]');
    await expect(card).toContainText('$413', { timeout: 15_000 });
    await expect(card).toContainText('Idle VMs');
    await expect(card).toContainText('2 barely used');
    await card.click();
    await expect(card).toHaveAttribute('aria-pressed', 'true');
    const rows = page.locator('#cost-idle-table-body tr');
    await expect(rows).toHaveCount(2);
    await expect(rows.first()).toContainText('report-worker');
    await expect(page.getByRole('link', { name: 'Excel' })).toHaveAttribute('href', /\/cost-management\/idle-waste\/export$/);
    await expect(page.locator('#waste-show-dismissed')).toHaveCount(0);
  });

  test('a short window: the type selectors take one row and the findings fit the height left, paged by the server (U20-T01)', async ({ page }) => {
    await page.setViewportSize({ width: 1366, height: 657 });
    const sizes: number[] = [];
    const all = Array.from({ length: 45 }, (_, i) => ({ ...FINDING, findingId: `f-${i}`, resourceId: `vol-${i}`, resourceName: `data-${i}` }));
    await page.route(/\/api\/v1\/waste\/summary$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ sweepEnabled: true, openCount: 45, monthlyTotal: 450, checked: 'Daily 04:00',
        lastSweepAt: new Date().toISOString(), byType: [{ type: 'UNATTACHED_VOLUME', count: 45, monthly: 450 }] }) }));
    await page.route(/\/api\/v1\/waste\/findings\?/, route => {
      const url = new URL(route.request().url());
      const size = Number(url.searchParams.get('size'));
      const number = Number(url.searchParams.get('page'));
      sizes.push(size);
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        content: all.slice(number * size, number * size + size),
        page: { size, number, totalElements: all.length, totalPages: Math.ceil(all.length / size) } }) });
    });
    await open(page);

    const panel = page.locator('.waste-table-card');
    await expect(panel.locator('.list-panel-foot .pager')).toBeVisible({ timeout: 15_000 });
    await expect.poll(() => sizes.length).toBeGreaterThanOrEqual(2);
    // The fitted page has replaced the first one (asked with the default 10).
    await expect.poll(() => panel.locator('tbody tr').count()).toBe(sizes[sizes.length - 1]);
    const fit = await page.evaluate(() => {
      const body = document.querySelector('.waste-table-card .list-panel-body')!.getBoundingClientRect();
      const rows = [...document.querySelectorAll('.waste-table-card tbody tr')];
      const shown = rows.filter(r => { const b = r.getBoundingClientRect(); return b.top >= body.top && b.bottom <= body.bottom + 1; }).length;
      const tops = new Set([...document.querySelectorAll('.waste-card')].map(c => Math.round(c.getBoundingClientRect().top)));
      const main = document.getElementById('mainContent')!;
      const scroller = document.querySelector('.waste-table-card .list-panel-body')!;
      return { shown, rows: rows.length, cardRows: tops.size, overflow: main.scrollHeight - main.clientHeight,
        sideways: scroller.scrollWidth - scroller.clientWidth };
    });
    // Acceptance: one row of selectors, at least 5 findings in view, the page asked for exactly that many, no sideways table.
    expect(fit.cardRows).toBe(1);
    expect(fit.shown).toBeGreaterThanOrEqual(5);
    expect(fit.shown).toBe(fit.rows);
    expect(sizes[sizes.length - 1]).toBe(fit.rows);
    expect(fit.overflow).toBeLessThanOrEqual(0);
    expect(fit.sideways).toBeLessThanOrEqual(0);
  });

  test('non-admins are refused', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await open(page, 'user');
    await expect(page.locator('#content-area')).toContainText('Waste is for administrators', { timeout: 15_000 });
    await expect(page.locator('#waste-link')).toBeHidden();
  });
});
