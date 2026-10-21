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

    // U11-T07: Settings opens the settings page; Teams and webhooks are asked for on Notifications.
    await page.locator('#btn-env-settings').click();
    await expect(page.locator('#es-panel')).toBeVisible({ timeout: 15_000 });
    await page.waitForLoadState('networkidle');
    expect(asked).toEqual([]);
    await page.locator('[data-es-section="notifications"]').click();
    await expect.poll(() => asked.length).toBeGreaterThan(0);
    await expect(page.locator('#es-notify')).toBeVisible();
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

/** U07-T07: every alert in one stack under the header, most urgent first (EnvAlerts.dc.html). */
test.describe('Environment alerts', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !devIds.admin || !canSignIn('user'), 'Needs TEST_DEV_USER_ID and TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('budget, lock, lease, drift and sync in order; red ones are alerts; nothing else on a quiet page', async ({ page, playwright }) => {
    const envId = await environmentWithUserAt(playwright, 'USER');
    const now = Date.now();
    const iso = (min: number) => new Date(now + min * 60000).toISOString();
    await page.route(/\/api\/v1\/environments\/[^/]+\/budget$/, (route: any) => route.fulfill({ json: {
      budgetId: 'b', monthlyAmount: 300, month: '2026-10', spendToDate: 320, percentUsed: 107, startFrozenUntil: iso(24 * 60) } }));
    await page.route(/\/api\/v1\/environments\/[^/]+\/lock$/, (route: any) => route.fulfill({ json: {
      isLocked: true, lockedByUserId: 'rahul', lockedByDisplayName: 'Rahul Verma', lockedAt: iso(-90), lockReason: 'Load test prep',
      expiresAt: iso(120), canAcquire: false, canRelease: false, canExtend: false, canBreak: false, lockExpiryEnabled: true } }));
    await page.route(/\/api\/v1\/environments\/[^/]+\/lease$/, (route: any) => route.fulfill({ json: {
      startedAt: iso(-60), endsAt: iso(12), permissions: { canExtend: true }, participants: [] } }));
    await page.route(/\/api\/v1\/environments\/[^/]+\/lease-policy$/, (route: any) => route.fulfill({ json: {
      enabled: true, effectiveDefaultMinutes: 120, effectiveMaxMinutes: 480, platformMaxMinutes: 720, canEdit: false } }));
    await page.route(/\/api\/v1\/environments\/[^/]+\/vms(\?.*)?$/, (route: any) => route.fulfill({ json: [
      { group: { groupId: 'g1', name: 'web', displayName: 'Web', sequencePosition: 1 }, vms: [
        { vmId: 'v1', name: 'crm-web-01', provider: 'AWS', status: 'RUNNING', sequencePosition: 1, stateDriftDetected: true, lastStateSyncAt: iso(-120) },
        { vmId: 'v2', name: 'crm-web-02', provider: 'AWS', status: 'STOPPED', sequencePosition: 2, lastStateSyncAt: iso(-125) }] }] }));
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate((id: string) => { location.hash = '#/environments/' + id; }, envId);

    const stack = page.locator('#env-alerts .env-alert');
    await expect(stack).toHaveCount(5, { timeout: 15_000 });
    expect(await stack.evaluateAll(els => els.map(e => e.getAttribute('data-kind')))).toEqual(['budget', 'lock', 'lease', 'drift', 'sync']);
    await expect(stack.nth(0)).toHaveAttribute('role', 'alert');
    await expect(stack.nth(1)).toHaveAttribute('role', 'alert');
    await expect(stack.nth(2)).toHaveAttribute('role', 'status');
    await expect(stack.nth(1)).toContainText('Locked by Rahul Verma');
    await expect(stack.nth(1)).toContainText('“Load test prep”');
    await expect(stack.nth(1)).toContainText('You can see it but not start or stop VMs.');
    await expect(stack.nth(2)).toContainText(/Stops in 1[12] minutes\./);
    await expect(stack.nth(2).getByRole('button', { name: 'Extend' })).toBeVisible();
    await expect(stack.nth(3)).toContainText('1 VM changed outside the platform: crm-web-01 is not in the state the platform last set.');
    await expect(stack.nth(4)).toContainText('VM states may be out of date. They were last read from AWS 2 hours ago');
    await expect(stack.nth(4).getByRole('link')).toHaveCount(0); // System health is for administrators
    await expect(page.locator('#btn-env-action')).toBeDisabled();

    // Details opens that VM (faked here, so only the request is checked).
    const asked = page.waitForRequest(r => /\/environments\/[^/]+\/vms\/v1$/.test(r.url()));
    await stack.nth(3).getByRole('button', { name: 'Details' }).click();
    await asked;
  });

  test('a quiet page has an empty stack that takes no space', async ({ page, playwright }) => {
    const envId = await environmentWithUserAt(playwright, 'USER');
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate((id: string) => { location.hash = '#/environments/' + id; }, envId);
    await expect(page.locator('#env-detail-view .env-body')).toBeVisible({ timeout: 15_000 });
    await page.waitForLoadState('networkidle');
    await expect(page.locator('#env-alerts')).toBeEmpty();
    await expect(page.locator('#env-alerts')).toBeHidden();
  });
});

/** U07-T08: an EKS environment shows node groups (EksEnv.dc.html), faked here. */
test.describe('EKS environment', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !devIds.admin || !canSignIn('user'), 'Needs TEST_DEV_USER_ID and TEST_DEV_ADMIN_ID (dev mode)');
  });

  const ng = (gid: string, seq: number, name: string, status: string, running: number, total: number, type: string, cap = 'ON_DEMAND') => ({
    group: { groupId: gid, name, displayName: name, sequencePosition: seq },
    vms: [{ vmId: `v-${gid}`, name, provider: 'AWS_EKS', region: 'ap-south-1', status, sequencePosition: 1,
      eksNodeGroup: { instanceType: type, desiredWhenRunning: total, currentNodes: running, capacityType: cap } }],
  });

  async function open(page: any, envId: string, groups: any[]) {
    await page.route(/\/api\/v1\/environments\/[^/?]+$/, async (route: any) => {
      const real = await route.fetch();
      const body = await real.json();
      await route.fulfill({ response: real, json: { ...body, serviceType: 'EKS', eksClusterName: 'partner-qa-eks' } });
    });
    await page.route(/\/api\/v1\/environments\/[^/]+\/vms(\?.*)?$/, (route: any) => route.fulfill({ json: groups }));
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate((id: string) => { location.hash = '#/environments/' + id; }, envId);
    await expect(page.locator('.env-eks-table')).toBeVisible({ timeout: 15_000 });
  }

  test('an operator: node groups with nodes, size, running of when started, capacity and Scale to 0 / Start', async ({ page, playwright }) => {
    const envId = await environmentWithUserAt(playwright, 'USER');
    await open(page, envId, [
      ng('g-sys', 1, 'system-nodes', 'RUNNING', 3, 3, 'm6i.large'),
      ng('g-app', 2, 'app-nodes', 'RUNNING', 2, 2, 'c6i.xlarge', 'SPOT'),
      ng('g-batch', 3, 'batch-nodes', 'STOPPED', 0, 2, 'c6i.2xlarge'),
    ]);

    await expect(page.locator('.env-meta')).toContainText('AWS EKS · cluster partner-qa-eks');
    await expect(page.locator('#btn-env-action-stop')).toHaveText('Scale all to 0');
    await expect(page.getByRole('region', { name: 'Start order' })).toHaveCount(0);
    const rows = page.locator('.env-eks-row');
    await expect(rows).toHaveCount(3);
    await expect(rows.nth(0).locator('.env-eks-count')).toHaveText('3 / 3');
    await expect(rows.nth(0).locator('.pip.on')).toHaveCount(3);
    await expect(rows.nth(0).getByRole('button', { name: 'Scale system-nodes to 0' })).toHaveText('Scale to 0');
    await expect(rows.nth(1).locator('.env-eks-capacity')).toHaveText('Spot, falls back to on-demand');
    await expect(rows.nth(2).locator('.env-eks-count')).toHaveText('0 / 2');
    await expect(rows.nth(2).getByRole('button', { name: 'Start batch-nodes' })).toHaveText('Start');
    await expect(page.getByRole('complementary', { name: 'About this environment' })).toContainText('5 of 7 nodes running');
  });

  test('a viewer on a cluster scaled to 0 reads "Scaled to 0" without actions', async ({ page, playwright }) => {
    const envId = await environmentWithUserAt(playwright, 'VIEWER');
    await open(page, envId, [ng('g-sys', 1, 'system-nodes', 'STOPPED', 0, 3, 'm6i.large'), ng('g-batch', 2, 'batch-nodes', 'STOPPED', 0, 2, 'c6i.2xlarge')]);

    await expect(page.locator('.env-eks-row .env-eks-state')).toHaveText(['Scaled to 0', 'Scaled to 0']);
    await expect(page.locator('.env-eks-row').getByRole('button')).toHaveCount(0);
    await expect(page.locator('#btn-env-action-stop, #btn-env-action')).toHaveCount(0);
  });
});

test.describe('Environment insights', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !devIds.admin || !canSignIn('user'), 'Needs TEST_DEV_USER_ID and TEST_DEV_ADMIN_ID (dev mode)');
  });

  // U08-T08: one side panel for 24 h or 7 days; the advice for everyone, Stop for operators only.
  const insights = (window: string) => ({
    environmentId: 'x', totalVms: 7, runningVms: 5, totalAllocatedStorageGib: 1229, latestMetricSampleTime: new Date(Date.now() - 4 * 60000).toISOString(),
    window, volumeTypeCounts: { gp3: 6, gp2: 1 },
    groups: [{ groupId: 'g1', name: 'Database', sequencePosition: 1, totalVms: 3, runningVms: 3, avgCpuUtilization: 12 },
      { groupId: 'g2', name: 'Application', sequencePosition: 2, totalVms: 2, runningVms: 2, avgCpuUtilization: 30 }],
    busiestVms: [{ vmId: 'v-api', name: 'pay-api-01', cpuUtilization: 50, avgCpuWindow: window === '7d' ? 24 : 31 },
      { vmId: 'v-cache', name: 'pay-cache', cpuUtilization: 4, avgCpuWindow: 4 }],
    idleVmRows: [{ vmId: 'v-cache', name: 'pay-cache', idleDurationMinutes: 370, cpuUtilization: 3.9 }],
    actions: [{ kind: 'STOP_IDLE', vmId: 'v-cache', vmName: 'pay-cache', hourlyRate: 0.07, detail: 'Idle for 6 h' },
      { kind: 'RESIZE', vmId: 'v-api', vmName: 'pay-api-01', recommendedType: 'm6i.medium', monthlySavings: 31, detail: 'CPU peaked at 18%' },
      { kind: 'SCHEDULE_WEEKENDS', detail: '2 VMs ran idle for about 48 h over the weekend' }],
  });

  async function openInsights(page: any, envId: string, asked: string[]) {
    await page.route(/\/api\/v1\/environments\/[^/]+\/insights/, (route: any) => {
      const window = new URL(route.request().url()).searchParams.get('window') || '24h';
      asked.push(window);
      return route.fulfill({ json: insights(window) });
    });
    await page.route(/\/api\/v1\/environments\/[^/]+\/cost-anomalies/, (route: any) => route.fulfill({ json: [] }));
    await open(page, envId);
    await page.locator('#btn-env-insights').click();
    const panel = page.locator('#dynamicSlideoutPanel[data-variant="env-insights"]');
    await expect(panel.locator('.ins-todo')).toHaveCount(3, { timeout: 10_000 });
    return panel;
  }

  test('an operator: what to do with Stop, idle now, busiest, groups, storage; 7 days reloads', async ({ page, playwright }) => {
    const envId = await environmentWithUserAt(playwright, 'USER');
    const asked: string[] = [];
    const panel = await openInsights(page, envId, asked);
    await expect(panel.locator('#dynamicSlideoutTitle')).toHaveText(/ insights$/);
    await expect(panel.locator('.ins-sub')).toHaveText('7 VMs · 5 running · 1.2 TiB of disk · metrics up to 4 min ago');
    const todo = panel.locator('.ins-todo');
    await expect(todo.nth(0)).toContainText('Stop pay-cache.');
    await expect(todo.nth(0)).toContainText('$0.07/h');
    await expect(todo.nth(0).getByRole('button', { name: 'Stop' })).toBeVisible();
    await expect(todo.nth(1)).toContainText('Resize pay-api-01 to m6i.medium.');
    await expect(todo.nth(1)).toContainText('$31/mo');
    await expect(todo.nth(2).getByRole('link', { name: 'Edit schedule' })).toHaveCount(0);
    await expect(panel.locator('section[aria-labelledby="ins-idle-title"]')).toContainText('idle 6 h 10 min · CPU 3.9%');
    await expect(panel.locator('.ins-bar-row').first()).toContainText('31%');
    await expect(panel.locator('.ins-group-row')).toHaveCount(2);
    await expect(panel.locator('section[aria-labelledby="ins-disk-title"]')).toContainText('move to gp3');
    await expect(panel.locator('section[aria-labelledby="ins-spend-title"]')).toContainText('No unusual spend in the last 90 days.');

    await panel.getByRole('button', { name: '7 days' }).click();
    await expect(panel.locator('.ins-bar-row').first()).toContainText('24%');
    expect(asked).toEqual(['24h', '7d']);

    await todo.nth(0).getByRole('button', { name: 'Stop' }).click();
    await expect(page.locator('#operationConfirmModal .modal-title')).toHaveText(/^Stop (1 VM in .*|pay-cache)\?$/);
  });

  test('a viewer reads the advice without Stop or Edit schedule', async ({ page, playwright }) => {
    const envId = await environmentWithUserAt(playwright, 'VIEWER');
    const panel = await openInsights(page, envId, []);
    await expect(panel.locator('.ins-todo').nth(0)).toContainText('Stop pay-cache.');
    await expect(panel.getByRole('button', { name: 'Stop' })).toHaveCount(0);
    await expect(panel.getByRole('link', { name: 'Edit schedule' })).toHaveCount(0);
    await expect(panel.locator('.ins-todo').nth(1).getByRole('button', { name: 'Details' })).toBeVisible();
  });
});
