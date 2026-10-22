import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { fakeEnvironmentPage, pageBody } from '../../fixtures/mock-api';

/**
 * E10-T05: the list takes the lock holder from the list payload (no lock request per row).
 * U06-T05 (Environments.dc.html, Environments15.dc.html): "Environments" with the filtered set's
 * counts, search and an All / Running / Stopped switch sent as state=, rows of name and slug,
 * type, region, the grouped power strip, "Not locked" / "Locked by you" / the holder, the viewer's
 * access, this month's cost and Open; no Edit or Deactivate in rows; 15 rows fit 1440x900 with no
 * pager. The page endpoint is faked in the browser.
 */

declare const ContentRouter: any;
declare const Auth: any;

const group = (name: string, sequencePosition: number, running: number, stopped: number) =>
  ({ groupId: `g-${name}`, name, sequencePosition, total: running + stopped, running, busy: 0, stopped, problem: 0, unknown: 0 });

const ENVS = [
  { environmentId: 'env-list-1', name: 'pay-qa', displayName: 'Payments QA', description: null, isActive: true,
    serviceType: 'EC2', groupCount: 2, vmCount: 3, runningVmCount: 1, regions: ['ap-south-1'],
    metadata: '{"defaultCloudProvider":"AWS"}', locked: true, lockedByUserId: 'someone-else',
    lockedByDisplayName: 'Riya Sharma', lockedAt: new Date().toISOString(), myAccess: 'USER', monthToDateCost: 186.4,
    groupStates: [group('App', 1, 1, 1), group('Web', 2, 0, 1)] },
  { environmentId: 'env-list-2', name: 'billing', displayName: 'billing', description: null, isActive: true,
    serviceType: 'EKS', groupCount: 1, vmCount: 0, runningVmCount: 0, regions: [], metadata: null,
    locked: false, lockedByUserId: null, lockedByDisplayName: null, lockedAt: null, myAccess: 'VIEWER', monthToDateCost: 0,
    groupStates: [] },
];

/** Fake /environments/page with 3 VMs, 1 running unless the response says otherwise. */
const fakeList = (page: Page, respond: (url: URL) => { body: any; vms?: number; running?: number }) =>
  fakeEnvironmentPage(page, url => ({ vms: 3, running: 1, ...respond(url) }));

async function openList(page: Page, role: 'user' | 'admin' = 'user') {
  await signIn(page, role);
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/my-environments'; });
  await expect(page.locator('#content-area .page-header h1')).toHaveText('Environments', { timeout: 15_000 });
}

test.describe('Environments list', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  });

  test('U19-T08: a region filter once the VMs span regions, by name, sent as region=', async ({ page }) => {
    const seen = await fakeList(page, url => ({ body: pageBody(url.searchParams.get('region') === 'me-central-1' ? [ENVS[0]] : ENVS) }));
    await page.route(/\/api\/v1\/environments\/regions$/, route => route.fulfill({ json: ['ap-south-1', 'me-central-1'] }));
    await openList(page);

    const select = page.locator('#env-region-select');
    await expect(select).toBeVisible();
    await expect(select.locator('option')).toHaveText(['Every region', 'ap-south-1 · Mumbai', 'me-central-1 · UAE']);
    await select.selectOption('me-central-1');
    await expect.poll(() => seen.at(-1)?.searchParams.get('region')).toBe('me-central-1');
    await expect(page.locator('#env-list-body tr')).toHaveCount(1);
    expect(seen.at(-1)?.searchParams.get('page')).toBe('0');
  });

  test('no region filter while every VM is in one region', async ({ page }) => {
    await fakeList(page, () => ({ body: pageBody(ENVS) }));
    await page.route(/\/api\/v1\/environments\/regions$/, route => route.fulfill({ json: ['ap-south-1'] }));
    await openList(page);
    await expect(page.locator('#env-list-body tr')).toHaveCount(2);
    await expect(page.locator('#env-region-select')).toHaveCount(0);
  });

  test('rows: name and slug, type, region, power strip, lock, access, cost and Open; no per-row lock request', async ({ page }) => {
    await fakeList(page, () => ({ body: pageBody(ENVS), vms: 3, running: 1 }));
    const lockRequests: string[] = [];
    page.on('request', req => {
      if (/\/environments\/env-list-\d\/lock/.test(req.url())) lockRequests.push(req.url());
    });
    await openList(page);

    await expect(page.locator('.page-summary')).toHaveText('2 environments · 1 of 3 VMs running. Open one to start, stop or lock it.');
    const pay = page.locator('#env-list-body tr', { hasText: 'Payments QA' });
    await expect(pay.locator('.env-slug')).toHaveText('pay-qa');
    await expect(pay.locator('td.col-type')).toHaveText('AWS EC2');
    await expect(pay.locator('td.col-region')).toHaveText('ap-south-1');
    const strip = pay.locator('td.col-running').getByRole('img', { name: '1 of 3 VMs running' });
    await expect(strip).toBeVisible();
    await expect(strip.locator('.pip')).toHaveCount(3);
    await expect(strip.locator('.pip.on')).toHaveCount(1);
    await expect(pay.locator('td.col-lock')).toHaveText('Riya Sharma');
    await expect(pay.locator('td.col-access')).toHaveText('Operator');
    await expect(pay.locator('td.col-cost')).toHaveText('$186.40');
    await expect(pay.getByRole('link', { name: 'Open Payments QA' })).toHaveAttribute('href', '#/environments/env-list-1');
    await expect(pay.getByRole('link', { name: 'Payments QA', exact: true })).toHaveAttribute('href', '#/environments/env-list-1');

    const billing = page.locator('#env-list-body tr', { hasText: 'billing' });
    await expect(billing.locator('td.col-lock')).toHaveText('Not locked');
    await expect(billing.locator('td.col-access')).toHaveText('View only');
    await expect(billing.locator('td.col-type')).toHaveText('AWS EKS');
    await expect(billing.locator('.env-slug')).toHaveCount(0); // same as the name
    await expect(page.locator('#env-list-pagination')).toHaveText('2 environments');
    expect(lockRequests).toEqual([]);
  });

  test('an environment locked by the viewer reads "Locked by you"', async ({ page }) => {
    await signIn(page, 'user');
    const me = await page.evaluate(() => Auth.getUserId());
    await fakeList(page, () => ({ body: pageBody([{ ...ENVS[0], lockedByUserId: me, lockedByDisplayName: 'Me' }]) }));
    await openList(page);
    await expect(page.locator('#env-list-body tr').first().locator('td.col-lock')).toHaveText('Locked by you');
  });

  test('Running sends state=RUNNING, presses the button and shows the filtered counts', async ({ page }) => {
    const seen = await fakeList(page, url => url.searchParams.get('state') === 'RUNNING'
      ? { body: pageBody([ENVS[0]]), vms: 3, running: 1 }
      : { body: pageBody(ENVS), vms: 3, running: 1 });
    await openList(page);
    expect(seen.at(-1)!.searchParams.get('includeGroups')).toBe('true');
    expect(seen.at(-1)!.searchParams.get('state')).toBeNull();

    const show = page.getByRole('group', { name: 'Show' });
    await show.getByRole('button', { name: 'Running' }).click();
    await expect(show.getByRole('button', { name: 'Running' })).toHaveAttribute('aria-pressed', 'true');
    await expect(show.getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'false');
    await expect(page.locator('#env-list-body tr')).toHaveCount(1);
    await expect(page.locator('#env-list-summary')).toHaveText('1 environment · 1 of 3 VMs running');
    expect(seen.at(-1)!.searchParams.get('state')).toBe('RUNNING');
    expect(seen.at(-1)!.searchParams.get('page')).toBe('0');
  });

  test('a filter with no match says so and keeps the search and filter', async ({ page }) => {
    await fakeList(page, url => url.searchParams.get('state') === 'STOPPED'
      ? { body: pageBody([]), vms: 0, running: 0 } : { body: pageBody(ENVS) });
    await openList(page);

    await page.getByRole('group', { name: 'Show' }).getByRole('button', { name: 'Stopped' }).click();
    await expect(page.locator('#env-list-body')).toHaveText('No environments match this filter.');
    await expect(page.locator('#env-list-search')).toBeVisible();
    await expect(page.getByRole('group', { name: 'Show' })).toBeVisible();
  });

  test('15 environments fit 1440x900 with no pager; 16 bring Previous / Next', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    const many = Array.from({ length: 16 }, (_, i) => ({ ...ENVS[0], environmentId: `env-many-${i}`,
      name: `env-${String(i).padStart(2, '0')}`, displayName: `Environment ${String(i).padStart(2, '0')}` }));
    let total = 15;
    await fakeList(page, url => {
      const n = Number(url.searchParams.get('page') || 0);
      return { body: pageBody(many.slice(n * 15, n * 15 + 15).slice(0, total - n * 15), total, n) };
    });
    await openList(page);

    await expect(page.locator('#env-list-body tr')).toHaveCount(15);
    await expect(page.locator('#env-list-pagination')).toHaveText('15 environments');
    await expect(page.locator('#env-list-pagination button')).toHaveCount(0);
    expect(await page.locator('#mainContent').evaluate(el => el.scrollHeight <= el.clientHeight)).toBeTruthy();

    total = 16;
    await page.evaluate(() => ContentRouter.reload());
    await expect(page.locator('#env-list-pagination')).toContainText('1–15 of 16');
    await page.locator('#env-list-pagination').getByRole('button', { name: 'Next' }).click();
    await expect(page.locator('#env-list-body tr')).toHaveCount(1);
    await expect(page.locator('#env-list-pagination')).toContainText('16–16 of 16');
  });

  test('an environment admin sees no Edit or Deactivate in rows', async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await fakeList(page, () => ({ body: pageBody(ENVS) }));
    await openList(page, 'admin');

    await expect(page.locator('#env-list-body tr')).toHaveCount(2);
    await expect(page.locator('#env-list-body [data-action="edit-env"], #env-list-body [data-action="delete-env"]')).toHaveCount(0);
    await expect(page.locator('#env-list-body').getByRole('button')).toHaveCount(0);
  });
});

