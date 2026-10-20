import { test, expect, type Page, type APIRequestContext } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * U06-T02: Home (Dashboard.dc.html) for everyone but administrators. The summary is faked: four
 * environment cards with name, slug, state chip, power strip, status line, cost and access; "Needs
 * you" tiles for a lease ending (Extend lease) and access ending (Ask for more time); no page
 * scrollbar at 1440x900; an empty state without environments.
 * U06-T03: "Running but barely used" (Review opens the VM on its environment) and "Your recent
 * activity" from /users/me/activity, with a message when it cannot load.
 */

declare const ContentRouter: any;

const pad = (n: number) => String(n).padStart(2, '0');
const hhmm = (d: Date) => `${pad(d.getHours())}:${pad(d.getMinutes())}`;
const group = (name: string, sequencePosition: number, s: Partial<Record<'running' | 'busy' | 'stopped' | 'problem' | 'unknown', number>>) => {
  const counts = { running: 0, busy: 0, stopped: 0, problem: 0, unknown: 0, ...s };
  return { groupId: `g-${name}`, name, sequencePosition, total: Object.values(counts).reduce((a, b) => a + b, 0), ...counts };
};

function summary(now: Date) {
  const leaseEnd = new Date(now.getTime() + (2 * 60 + 15) * 60_000);
  const lockUntil = new Date(now.getTime() + 90 * 60_000);
  const card = (o: any) => ({ serviceType: 'EC2', idleVms: 0, avgCpuUtilization: null, allocatedStorageGib: 0, locked: false,
    lockedByName: null, lockedByMe: null, lockExpiresAt: null, leaseEndsAt: null, nextStart: null, nextStop: null,
    transitionalVms: 0, startingVms: 0, failedVms: 0, stoppedVms: 0, ...o });
  return {
    leaseEnd, lockUntil,
    body: {
      persona: 'USER',
      summary: { environments: 5, totalVms: 26, runningVms: 8, stoppedVms: 16, idleVms: 0, driftedVms: 0, lockedEnvironments: 2,
        pendingAccessRequests: 0, monthToDateCost: 412.8 },
      utilization: {}, storage: {}, coverage: {}, utilizationTrend: [], vmStatusCounts: {}, regionCounts: {}, providerCounts: {},
      volumeTypeCounts: {}, topBusyVms: [], schedulerHealth: [],
      idleVms: [
        { vmId: 'vm-cache', name: 'pay-cache', environmentId: 'env-pay', environmentName: 'Payments Dev', status: 'RUNNING',
          cpuUtilization: 3.9, idleDurationMinutes: 360, hourlyRate: 0.0704, groupName: 'Application' },
        { vmId: 'vm-report', name: 'report-worker', environmentId: 'env-ana', environmentName: 'Analytics QA', status: 'RUNNING',
          cpuUtilization: 0.6, idleDurationMinutes: 120, hourlyRate: null, groupName: 'App' },
        { vmId: 'vm-off', name: 'old-box', environmentId: 'env-crm', environmentName: 'CRM UAT', status: 'STOPPED',
          cpuUtilization: 0, idleDurationMinutes: 900, hourlyRate: 0.1, groupName: 'All' },
      ], riskCompliance: [], recommendations: [],
      generatedAt: now.toISOString(),
      environments: [
        card({ environmentId: 'env-pay', name: 'payments-dev', displayName: 'Payments Dev', totalVms: 7, runningVms: 5,
          monthToDateCost: 186.4, myAccess: 'USER', leaseEndsAt: leaseEnd.toISOString(),
          groupStates: [group('Database', 1, { running: 3 }), group('Application', 2, { running: 2 }), group('Web', 3, { stopped: 2 })] }),
        card({ environmentId: 'env-ana', name: 'analytics-qa', displayName: 'Analytics QA', totalVms: 5, runningVms: 2,
          transitionalVms: 1, startingVms: 1, monthToDateCost: 121.75, myAccess: 'USER', locked: true, lockedByMe: true,
          lockedByName: 'Test User 1', lockExpiresAt: lockUntil.toISOString(),
          groupStates: [group('App', 1, { running: 2, busy: 1 }), group('Web', 2, { running: 0, stopped: 2 })] }),
        card({ environmentId: 'env-crm', name: 'crm-uat', displayName: 'CRM UAT', totalVms: 6, runningVms: 0,
          monthToDateCost: 64.1, myAccess: 'USER', groupStates: [group('All', 1, { stopped: 6 })] }),
        card({ environmentId: 'env-hr', name: 'hr-portal-stg', displayName: 'HR Portal Staging', totalVms: 6, runningVms: 0,
          monthToDateCost: 40.55, myAccess: 'VIEWER', locked: true, lockedByMe: false, lockedByName: 'Rahul Verma',
          groupStates: [group('App', 1, { stopped: 4 }), group('Web', 2, { stopped: 2 })] }),
        card({ environmentId: 'env-old', name: 'zz-archive', displayName: 'ZZ Archive', totalVms: 2, runningVms: 0,
          monthToDateCost: 0, myAccess: 'VIEWER', groupStates: [group('All', 1, { stopped: 2 })] }),
      ],
      needsYou: [
        { kind: 'LEASE_ENDING', environmentId: 'env-pay', environmentName: 'Payments Dev', at: leaseEnd.toISOString(), referenceId: 'lease-1' },
        { kind: 'ACCESS_ENDING', environmentId: 'env-crm', environmentName: 'CRM UAT',
          at: new Date(now.getTime() + 2 * 86_400_000).toISOString(), referenceId: 'acc-crm' },
      ],
    },
  };
}

function activity(now: Date) {
  const minutesAgo = (m: number) => new Date(now.getTime() - m * 60_000).toISOString();
  return [
    { kind: 'OPERATION', event: 'START', status: 'COMPLETED', environmentId: 'env-pay', environmentName: 'Payments Dev',
      totalTargets: 5, occurredAt: minutesAgo(1) },
    { kind: 'ACCESS', event: 'REQUESTED', environmentId: 'env-crm', environmentName: 'CRM UAT', accessLevel: 'USER',
      occurredAt: minutesAgo(2) },
    { kind: 'OPERATION', event: 'STOP', status: 'COMPLETED', environmentId: 'env-crm', environmentName: 'CRM UAT',
      totalTargets: 6, occurredAt: minutesAgo(3) },
    { kind: 'OPERATION', event: 'START', status: 'FAILED', environmentId: 'env-ana', environmentName: 'Analytics QA',
      totalTargets: 1, occurredAt: minutesAgo(4) },
  ];
}

async function openHome(page: Page, body: unknown, recent: unknown = []) {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.route(/\/api\/v1\/dashboard\/summary$/, r => r.fulfill({ json: body }));
  await page.route(/\/api\/v1\/users\/me\/activity/, r => recent === 'fail'
    ? r.fulfill({ status: 500, json: { message: 'boom' } }) : r.fulfill({ json: recent }));
  await signIn(page, 'user');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await expect(page.locator('#content-area .content-header h1').first()).toHaveText('Home', { timeout: 15_000 });
}

test.describe('Home', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  });

  test('the header line, four cards and the needs-you tiles fit 1440x900', async ({ page }) => {
    const now = new Date();
    const { body } = summary(now);
    await openHome(page, body, activity(now));

    await expect(page.locator('.page-summary')).toHaveText('7 of 26 VMs running across 5 environments · $412.80 this month');
    await expect(page.getByRole('link', { name: 'Request access to an environment' })).toHaveAttribute('href', '#/request-access');
    const cards = page.locator('.home-card');
    await expect(cards).toHaveCount(4);
    await expect(cards.nth(0)).toContainText('Analytics QA'); // locked by me first
    await expect(page.locator('.home-card', { hasText: 'ZZ Archive' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'See all as a list' })).toHaveAttribute('href', '#/my-environments');
    await expect(page.locator('.home-recent .home-row')).toHaveCount(4);
    expect(await page.locator('#mainContent').evaluate(el => el.scrollHeight <= el.clientHeight)).toBeTruthy();
  });

  test('running but barely used: running VMs only, CPU, price per hour, Review opens the VM', async ({ page }) => {
    const { body } = summary(new Date());
    await openHome(page, body);

    const idle = page.getByRole('region', { name: 'Running but barely used' });
    const rows = idle.locator('.home-row');
    await expect(rows).toHaveCount(2);
    await expect(rows.nth(0)).toContainText('pay-cache · Payments Dev · CPU 3.9%');
    await expect(rows.nth(0).locator('.home-idle-rate')).toHaveText('$0.07/h');
    await expect(rows.nth(1).locator('.home-idle-rate')).toHaveCount(0); // no price known
    await expect(idle).not.toContainText('old-box');
    await expect(rows.nth(0).getByRole('link', { name: 'Review pay-cache' }))
      .toHaveAttribute('href', '#/environments/env-pay?vm=vm-cache');
  });

  test('without barely-used VMs the activity panel takes the row', async ({ page }) => {
    const { body } = summary(new Date());
    await openHome(page, { ...body, idleVms: [] });

    await expect(page.getByRole('region', { name: 'Running but barely used' })).toHaveCount(0);
    await expect(page.locator('.home-lower')).toHaveClass(/home-lower-single/);
  });

  test('recent activity: time, words and result; Failed in red; All activity opens My Account', async ({ page }) => {
    const now = new Date();
    const { body } = summary(now);
    await openHome(page, body, activity(now));

    const recent = page.getByRole('region', { name: 'Your recent activity' });
    const rows = recent.locator('.home-row');
    await expect(rows).toHaveCount(4);
    await expect(rows.nth(0)).toContainText('Started Payments Dev (5 VMs)');
    await expect(rows.nth(0).locator('.home-result')).toHaveText('Done');
    await expect(rows.nth(0).locator('.home-row-time')).toHaveText(/^\d\d:\d\d$|^\w{3}$/);
    await expect(rows.nth(1)).toContainText('Asked for Operator access to CRM UAT');
    await expect(rows.nth(1).locator('.home-result')).toHaveText('Waiting');
    const failed = rows.nth(3).locator('.home-result');
    await expect(failed).toHaveText('Failed');
    expect(await failed.evaluate(el => getComputedStyle(el).color)).toBe('rgb(168, 51, 31)');

    await recent.getByRole('button', { name: 'All activity' }).click();
    await expect(page.locator('#ma-tab-activity')).toHaveAttribute('aria-selected', 'true');
  });

  test('recent activity that cannot load says so', async ({ page }) => {
    const { body } = summary(new Date());
    await openHome(page, body, 'fail');
    await expect(page.locator('#home-recent-body')).toHaveText('Activity is not available right now.');
  });

  test('no recent activity says what will appear', async ({ page }) => {
    const { body } = summary(new Date());
    await openHome(page, body, []);
    await expect(page.locator('#home-recent-body')).toContainText('Nothing yet.');
  });

  test('a card shows name, slug, chip, power strip, line, cost and access', async ({ page }) => {
    const now = new Date();
    const { body, leaseEnd, lockUntil } = summary(now);
    await openHome(page, body);

    const pay = page.locator('.home-card', { hasText: 'Payments Dev' });
    await expect(pay).toHaveAttribute('href', '#/environments/env-pay');
    await expect(pay.locator('.home-card-slug')).toHaveText('payments-dev');
    await expect(pay.locator('.chip')).toHaveText('Running');
    await expect(pay.getByRole('img', { name: '5 of 7 VMs running' })).toBeVisible();
    await expect(pay.locator('.home-card-line')).toHaveText(new RegExp(`^Web group stopped · stops at (\\w{3} )?${hhmm(leaseEnd)}$`));
    await expect(pay.locator('.home-card-foot')).toContainText('$186.40 this month');
    await expect(pay.locator('.home-card-access')).toHaveText('Operator');

    const analytics = page.locator('.home-card', { hasText: 'Analytics QA' });
    await expect(analytics.locator('.chip')).toHaveText('Starting');
    await expect(analytics.locator('.home-card-line')).toHaveText(new RegExp(`^Starting 1 VM · locked by you until (\\w{3} )?${hhmm(lockUntil)}$`));

    const hr = page.locator('.home-card', { hasText: 'HR Portal Staging' });
    await expect(hr.locator('.chip')).toHaveText('Stopped');
    await expect(hr.locator('.home-card-line')).toHaveText('Locked by Rahul Verma');
    await expect(hr.locator('.home-card-access')).toHaveText('View only');
  });

  test('needs-you: Extend lease links to the environment, Ask for more time opens My Account on Access', async ({ page }) => {
    const now = new Date();
    const { body, leaseEnd } = summary(now);
    await openHome(page, body);

    const needs = page.getByRole('region', { name: 'Needs you' });
    const lease = needs.locator('.needs-you-tile', { hasText: 'Extend lease' });
    await expect(lease).toHaveAttribute('href', '#/environments/env-pay?extendLease=1');
    await expect(lease).toContainText(new RegExp(`Payments Dev stops at (\\w{3} )?${hhmm(leaseEnd)}, in 2 h 1[45] min`));
    const access = needs.locator('.needs-you-tile', { hasText: 'Ask for more time' });
    await expect(access).toContainText('Your access to CRM UAT ends');

    await access.click();
    await expect(page.locator('#ma-tab-access')).toHaveAttribute('aria-selected', 'true');
  });

  test('needs-you shows two tiles and counts the rest, which open My Account on Access', async ({ page }) => {
    const now = new Date();
    const { body } = summary(now);
    const ending = (i: number) => ({ kind: 'ACCESS_ENDING', environmentId: `env-${i}`, environmentName: `Env ${i}`,
      at: new Date(now.getTime() + (i + 1) * 86_400_000).toISOString(), referenceId: `acc-${i}` });
    await openHome(page, { ...body, needsYou: [...body.needsYou, ending(1), ending(2), ending(3)] });

    await expect(page.locator('.needs-you-tile')).toHaveCount(2);
    const more = page.getByRole('button', { name: '3 more ending soon' });
    await more.click();
    await expect(page.locator('#ma-tab-access')).toHaveAttribute('aria-selected', 'true');
  });

  test('without environments: the empty state asks for access; no needs-you section', async ({ page }) => {
    const { body } = summary(new Date());
    await openHome(page, { ...body, environments: [], needsYou: [], summary: { ...body.summary, monthToDateCost: 0 } });

    await expect(page.locator('#home-empty-title')).toHaveText('You have no environments yet.');
    await expect(page.locator('.home-empty').getByRole('link', { name: 'Request access to an environment' })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Needs you' })).toHaveCount(0);
  });

  test('an administrator still gets the fleet dashboard', async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await expect(page.locator('#content-area .content-header h1').first()).toHaveText('Dashboard', { timeout: 15_000 });
    await expect(page.locator('.dashboard-kpi-grid')).toBeVisible();
    await expect(page.locator('.home-card')).toHaveCount(0);
  });

  test('Review: the environment opens with that VM\'s details, and the link is dropped from the address', async ({ page, playwright }) => {
    test.skip(!devIds.admin, 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    const { envId, vmId } = await environmentWithVm(playwright);
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(([e, v]) => { location.hash = `#/environments/${e}?vm=${v}`; }, [envId, vmId]);

    await expect(page.getByRole('dialog', { name: 'Web 01', exact: true })).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('#dynamicSlideoutPanel[data-variant="vm-details"] .vm-panel-fields')).toBeVisible();
    await expect.poll(() => page.evaluate(() => location.hash)).toBe(`#/environments/${envId}`);
  });
});

/** An environment with one registered VM (a made-up instance id; nothing in AWS is touched), viewable by the user. */
async function environmentWithVm(playwright: any): Promise<{ envId: string; vmId: string }> {
  const admin: APIRequestContext = await playwright.request.newContext({
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    extraHTTPHeaders: { 'X-User-Id': devIds.admin, 'Content-Type': 'application/json' },
  });
  const stamp = Date.now();
  const instance = 'i-0' + Array.from({ length: 16 }, () => Math.floor(Math.random() * 16).toString(16)).join('');
  const env = await (await admin.post('/api/v1/environments', {
    data: { name: `home-review-${stamp}`, displayName: `Home Review ${stamp}`, cloudProvider: 'AWS' },
  })).json();
  const group = await (await admin.post(`/api/v1/environments/${env.environmentId}/groups`, {
    data: { name: 'web', displayName: 'Web', sequencePosition: 1 },
  })).json();
  const vm = await (await admin.post(`/api/v1/environments/${env.environmentId}/vms`, {
    data: { groupId: group.groupId, name: 'web-01', displayName: 'Web 01', provider: 'AWS', region: 'ap-south-1',
      providerVmId: instance, sequencePosition: 1 },
  })).json();
  const user = await (await admin.get(`/api/v1/users/${devIds.user}`)).json();
  await admin.post(`/api/v1/environments/${env.environmentId}/access`, {
    data: { userEmail: user.email, accessLevel: 'VIEWER', durationDays: 1 },
  });
  await admin.dispose();
  return { envId: env.environmentId, vmId: vm.vmId };
}
