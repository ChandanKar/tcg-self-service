import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * U06-T02: Home (Dashboard.dc.html) for everyone but administrators. The summary is faked: four
 * environment cards with name, slug, state chip, power strip, status line, cost and access; "Needs
 * you" tiles for a lease ending (Extend lease) and access ending (Ask for more time); no page
 * scrollbar at 1440x900; an empty state without environments.
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
      volumeTypeCounts: {}, topBusyVms: [], idleVms: [], schedulerHealth: [], riskCompliance: [], recommendations: [],
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

async function openHome(page: Page, body: unknown) {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.route(/\/api\/v1\/dashboard\/summary$/, r => r.fulfill({ json: body }));
  await signIn(page, 'user');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await expect(page.locator('#content-area .content-header h1').first()).toHaveText('Home', { timeout: 15_000 });
}

test.describe('Home', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  });

  test('the header line, four cards and the needs-you tiles fit 1440x900', async ({ page }) => {
    const { body } = summary(new Date());
    await openHome(page, body);

    await expect(page.locator('.page-summary')).toHaveText('7 of 26 VMs running across 5 environments · $412.80 this month');
    await expect(page.getByRole('link', { name: 'Request access to an environment' })).toHaveAttribute('href', '#/request-access');
    const cards = page.locator('.home-card');
    await expect(cards).toHaveCount(4);
    await expect(cards.nth(0)).toContainText('Analytics QA'); // locked by me first
    await expect(page.locator('.home-card', { hasText: 'ZZ Archive' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'See all as a list' })).toHaveAttribute('href', '#/my-environments');
    expect(await page.locator('#mainContent').evaluate(el => el.scrollHeight <= el.clientHeight)).toBeTruthy();
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
});
