import { test, expect, type APIRequestContext } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { withFeatures } from '../../fixtures/capabilities';

/**
 * UX simplification: the environment page shows Start/Stop only to people who can use them. A
 * viewer gets a view-only note with a way to ask for more; an operator gets the controls.
 * U07-T02 (EnvDetail.dc.html): the header has a meta line and Insights / History; the body is a
 * main column with the start order and an "About this environment" column; Spot, Teams and
 * webhooks load only when Settings is opened.
 */

declare const ContentRouter: any;

async function api(playwright: any, userId: string): Promise<APIRequestContext> {
  return playwright.request.newContext({
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    extraHTTPHeaders: { 'X-User-Id': userId, 'Content-Type': 'application/json' },
  });
}

async function environmentWithUserAt(playwright: any, level: 'VIEWER' | 'USER'): Promise<string> {
  const admin = await api(playwright, devIds.admin);
  const stamp = Date.now();
  const env = await (await admin.post('/api/v1/environments', {
    data: { name: `view-only-${level.toLowerCase()}-${stamp}`, displayName: `View ${level} ${stamp}`, cloudProvider: 'AWS' },
  })).json();
  await admin.post(`/api/v1/environments/${env.environmentId}/groups`, {
    data: { name: 'web', displayName: 'Web', sequencePosition: 1 },
  });
  const user = await (await admin.get(`/api/v1/users/${devIds.user}`)).json();
  await admin.post(`/api/v1/environments/${env.environmentId}/access`, {
    data: { userEmail: user.email, accessLevel: level, durationDays: 1 },
  });
  await admin.dispose();
  return env.environmentId;
}

async function open(page: any, envId: string) {
  await signIn(page, 'user');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate((id: string) => { location.hash = '#/environments/' + id; }, envId);
  await expect(page.locator('#env-detail-view .env-body')).toBeVisible({ timeout: 15_000 });
}

test.describe('Environment page by access level', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !devIds.admin || !canSignIn('user'), 'Needs TEST_DEV_USER_ID and TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('a viewer sees a view-only note and no start or stop controls', async ({ page, playwright }) => {
    const envId = await environmentWithUserAt(playwright, 'VIEWER');
    await open(page, envId);

    // U07-T03: the note says what they can do and when their access ends; Ask for operator access.
    await expect(page.locator('.env-viewonly-note')).toContainText('You can see this environment but not start or stop it.');
    await expect(page.locator('.env-viewonly-note')).toContainText(/Your view access ends on \d{1,2} [A-Z][a-z]{2}\./);
    await expect(page.locator('.env-viewonly-note').getByRole('link', { name: 'Ask for operator access' }))
      .toHaveAttribute('href', /^#\/request-access\?environmentId=/);
    await expect(page.locator('#btn-env-action')).toHaveCount(0);
    await expect(page.locator('[data-action="group-action"]')).toHaveCount(0);
    await expect(page.locator('[data-action="start-vm"], [data-action="stop-vm"]')).toHaveCount(0);
    await expect(page.locator('[data-lock-action="acquire"]')).toHaveCount(0);
    await expect(page.locator('#btn-env-action-stop')).toHaveCount(0);
    await expect(page.locator('#btn-env-insights')).toBeVisible();
    await expect(page.locator('#btn-operation-history')).toBeVisible();
  });

  test('an operator gets the start button, a breadcrumb back to the list, the start order and the About column', async ({ page, playwright }) => {
    const envId = await environmentWithUserAt(playwright, 'USER');
    await open(page, envId);

    await expect(page.locator('.env-viewonly-note')).toHaveCount(0);
    await expect(page.locator('#btn-env-action')).toHaveText(/Start (all|the \d+ stopped)/);
    await expect(page.locator('nav[aria-label="Breadcrumb"] a')).toHaveAttribute('href', '#/my-environments');
    await expect(page.locator('nav[aria-label="Breadcrumb"] a')).toHaveText('Environments');
    await expect(page.locator('.env-meta')).toContainText('AWS');
    await expect(page.getByRole('region', { name: 'Start order' })).toContainText('Web');
    await expect(page.getByRole('complementary', { name: 'About this environment' })).toContainText('VMs running');
    await expect(page.getByText('Cost & settings')).toHaveCount(0);
  });

  test('an administrator: Spot, Teams and webhooks are not asked for until Settings opens', async ({ page, playwright }) => {
    const envId = await environmentWithUserAt(playwright, 'USER');
    const asked: string[] = [];
    page.on('request', req => {
      if (/\/(spot-summary|chat-channels|webhooks)(\?|$)/.test(req.url())) asked.push(req.url());
    });
    await withFeatures(page, { spot: true, chat: true, webhooks: true });
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate((id: string) => { location.hash = '#/environments/' + id; }, envId);
    await expect(page.locator('#env-detail-view .env-body')).toBeVisible({ timeout: 15_000 });
    await page.waitForLoadState('networkidle');
    expect(asked).toEqual([]);

    await page.locator('#btn-env-settings').click();
    await expect(page.locator('.env-settings')).toBeVisible();
    await expect.poll(() => asked.length).toBeGreaterThan(0);
    await expect(page.locator('[data-action="env-settings-notify"]')).toBeVisible();
  });
});

/** U07-T03: the Groups and VMs table on faked VMs (the test schema has none that run). */
test.describe('Groups and VMs table', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !devIds.admin || !canSignIn('user'), 'Needs TEST_DEV_USER_ID and TEST_DEV_ADMIN_ID (dev mode)');
  });

  const at = (h: number, m: number) => { const d = new Date(); d.setHours(h, m, 0, 0); return d.toISOString(); };
  const vm = (id: string, name: string, status: string, o: any = {}) => ({ vmId: id, name, provider: 'AWS',
    region: 'ap-south-1', status, sequencePosition: 1, ...o });
  const groups = (ids: { db: string; app: string; web: string }) => [
    { group: { groupId: ids.db, name: 'db', displayName: 'Database', sequencePosition: 1 }, vms: [
      vm('v1', 'pay-db-primary', 'RUNNING', { privateIp: '10.20.1.11', instanceType: 'r6g.large', cpuAvg24h: 23.2, stateSince: at(8, 2) }),
      vm('v2', 'pay-db-replica', 'RUNNING', { privateIp: '10.20.1.12', instanceType: 'r6g.large', cpuAvg24h: 9, stateSince: at(8, 2) }),
      vm('v3', 'pay-cache', 'RUNNING', { privateIp: '10.20.1.20', instanceType: 't4g.medium', cpuAvg24h: 4, stateSince: at(8, 3) })] },
    { group: { groupId: ids.app, name: 'app', displayName: 'Application', sequencePosition: 2, dependsOnGroupIds: [ids.db] }, vms: [
      vm('v4', 'pay-api-01', 'RUNNING', { privateIp: '10.20.2.31', instanceType: 'm6i.large', cpuAvg24h: 31 }),
      vm('v5', 'pay-api-02', 'ERROR', { privateIp: '10.20.2.32', instanceType: 'm6i.large' })] },
    { group: { groupId: ids.web, name: 'web', displayName: 'Web', sequencePosition: 3, dependsOnGroupIds: [ids.app] }, vms: [
      vm('v6', 'web-01', 'STOPPED', { privateIp: '10.20.3.41', instanceType: 't3.medium' }),
      vm('v7', 'web-02', 'STOPPED', { privateIp: '10.20.3.42', instanceType: 't3.medium' })] },
  ];

  async function openWith(page: any, envId: string, role: 'user' | 'admin') {
    await page.route(/\/api\/v1\/environments\/[^/]+\/vms(\?.*)?$/, (route: any) =>
      route.fulfill({ json: groups({ db: 'g-db', app: 'g-app', web: 'g-web' }) }));
    await signIn(page, role);
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate((id: string) => { location.hash = '#/environments/' + id; }, envId);
    await expect(page.locator('#env-vms .env-group-row')).toHaveCount(3, { timeout: 15_000 });
  }

  test('an operator: 3 group rows, 7 VM rows with IP, size, running since and CPU; Retry for a failed VM', async ({ page, playwright }) => {
    const envId = await environmentWithUserAt(playwright, 'USER');
    await openWith(page, envId, 'user');

    const table = page.locator('#env-vms');
    await expect(table.locator('.env-vm-row')).toHaveCount(7);
    const primary = table.locator('.env-vm-row', { hasText: 'pay-db-primary' });
    await expect(primary.locator('.env-vm-ip')).toHaveText('10.20.1.11');
    await expect(primary.locator('.env-vm-size')).toHaveText('r6g.large');
    await expect(primary.locator('.env-vm-state')).toHaveText('Running since 08:02');
    await expect(primary.locator('.env-vm-cpu')).toHaveText('23%');
    await expect(table.locator('.env-vm-row', { hasText: 'web-01' }).locator('.env-vm-cpu')).toHaveText('—');

    const db = table.locator('.env-group-row[data-group-id="g-db"]');
    await expect(db.getByRole('button')).toHaveText(['Stop group']);
    const appRow = table.locator('.env-group-row[data-group-id="g-app"]');
    await expect(appRow).toContainText('Start order 2 · after Database');
    await expect(appRow.getByRole('button')).toHaveText(['Start group', 'Stop group']);

    const failed = table.locator('.env-vm-row', { hasText: 'pay-api-02' });
    await expect(failed.locator('.env-vm-state')).toHaveText('Failed to start');
    await expect(failed.getByRole('button', { name: 'Retry pay-api-02' })).toBeVisible();
    await expect(table).not.toContainText(/i-[0-9a-f]{8,17}/);
  });

  test('a viewer: Details on every VM, no start, stop or group buttons', async ({ page, playwright }) => {
    const envId = await environmentWithUserAt(playwright, 'VIEWER');
    await openWith(page, envId, 'user');

    await expect(page.locator('#env-vms').getByRole('button', { name: /^Details of / })).toHaveCount(7);
    await expect(page.locator('#env-vms [data-action="start-vm"], #env-vms [data-action="stop-vm"], #env-vms [data-action="group-action"]'))
      .toHaveCount(0);
  });
});

/** U07-T04: a large environment (EnvDetailLarge.dc.html), 54 faked VMs in 4 groups. */
test.describe('Large environment', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !devIds.admin || !canSignIn('user'), 'Needs TEST_DEV_USER_ID and TEST_DEV_ADMIN_ID (dev mode)');
  });

  const defs = [
    { id: 'g-db', name: 'Database', prefix: 'pm-db', states: 'RRRRRRRR' },
    { id: 'g-api', name: 'Application', prefix: 'pm-api', states: 'RRRRRRRRRRRRRRSSSS' },
    { id: 'g-web', name: 'Web', prefix: 'pm-web', states: 'RRRRRRRRRRRRSSSS' },
    { id: 'g-batch', name: 'Batch', prefix: 'pm-batch', states: 'RRRRRRRRRRES' },
  ];
  const STATUS: Record<string, string> = { R: 'RUNNING', S: 'STOPPED', E: 'ERROR' };
  const fake = defs.map((d, gi) => ({
    group: { groupId: d.id, name: d.name.toLowerCase(), displayName: d.name, sequencePosition: gi + 1 },
    vms: d.states.split('').map((c, i) => ({ vmId: `${d.prefix}-${i}`, name: `${d.prefix}-${String(i + 1).padStart(2, '0')}`,
      provider: 'AWS', region: 'ap-south-1', status: STATUS[c], sequencePosition: i + 1,
      privateIp: `10.40.${gi + 1}.${i + 11}`, instanceType: 'm6i.large' })),
  }));

  async function open(page: any, envId: string) {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.route(/\/api\/v1\/environments\/[^/]+\/vms(\?.*)?$/, (route: any) => route.fulfill({ json: fake }));
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate((id: string) => { location.hash = '#/environments/' + id; }, envId);
    await expect(page.getByRole('navigation', { name: 'Groups' })).toBeVisible({ timeout: 15_000 });
  }

  test('the summary strip, the group list and the failed VM first; paging, filters and no page scrollbar', async ({ page, playwright }) => {
    const envId = await environmentWithUserAt(playwright, 'USER');
    await open(page, envId);

    const summary = page.getByRole('region', { name: 'All 54 VMs in start order' });
    await expect(summary).toContainText('44 running');
    await expect(summary).toContainText('9 stopped');
    await expect(summary).toContainText('1 failed to start');
    await expect(summary).toContainText('Start order: Database → Application → Web → Batch');

    const groups = page.getByRole('navigation', { name: 'Groups' });
    await expect(groups).toContainText('4 groups');
    await expect(groups.locator('[data-group-id="g-api"]')).toContainText('2nd · Application14/18');
    // The first group with a problem is chosen first.
    await expect(groups.locator('[aria-current="true"]')).toHaveAttribute('data-group-id', 'g-batch');
    await page.getByRole('group', { name: 'Show' }).getByRole('button', { name: 'Problems' }).click();
    await expect(page.locator('#env-vms .env-vm-row')).toHaveCount(1);
    await expect(page.getByRole('button', { name: 'Retry pm-batch-11' })).toBeVisible();
    expect(await page.locator('#mainContent').evaluate((el: HTMLElement) => el.scrollWidth <= el.clientWidth)).toBeTruthy();

    await groups.locator('[data-group-id="g-api"]').click();
    await expect(page.locator('#env-group-panel-title')).toHaveText('Application · 18 VMs');
    await expect(page.locator('#env-vms .env-vm-row')).toHaveCount(10);
    const pager = page.locator('#env-vms .pager');
    await pager.getByRole('button', { name: 'Next' }).click();
    await expect(pager.locator('.pager-range')).toHaveText('11–18 of 18');
    await expect(pager.getByRole('button', { name: 'Next' })).toBeDisabled();
    await expect(page.locator('#env-vms .env-vm-row')).toHaveCount(8);

    await page.getByRole('group', { name: 'Show' }).getByRole('button', { name: 'Problems' }).click();
    await expect(page.locator('#env-vms')).toContainText('Nothing matches this filter');
  });

  test('a refresh after an operation keeps the group, filter and page', async ({ page, playwright }) => {
    const envId = await environmentWithUserAt(playwright, 'USER');
    await open(page, envId);
    await page.getByRole('navigation', { name: 'Groups' }).locator('[data-group-id="g-web"]').click();
    await page.getByRole('group', { name: 'Show' }).getByRole('button', { name: 'Running' }).click();
    await page.locator('#env-vms .pager').getByRole('button', { name: 'Next' }).click();
    await expect(page.locator('#env-vms .pager-range')).toHaveText('11–12 of 12');

    await page.evaluate((id: string) => window.dispatchEvent(new CustomEvent('vm-operation-status', { detail: { envId: id } })), envId);
    await page.waitForTimeout(1500);
    await expect(page.getByRole('navigation', { name: 'Groups' }).locator('[aria-current="true"]')).toHaveAttribute('data-group-id', 'g-web');
    await expect(page.getByRole('group', { name: 'Show' }).getByRole('button', { name: 'Running' })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('#env-vms .pager-range')).toHaveText('11–12 of 12');
  });
});

