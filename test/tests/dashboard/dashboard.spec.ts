import { test, expect, type Page } from '@playwright/test';
import { loginAs, creds, hasUserCreds, signIn, canSignIn, devIds } from '../../fixtures/auth';

declare const ContentRouter: any;

test.describe('Dashboard', () => {
  test.beforeEach(async ({ page }) => {
    test.skip(!hasUserCreds(), 'TEST_USERNAME/TEST_PASSWORD not set in test/.env');
    await loginAs(page, creds.user.username, creds.user.password);
  });

  test('a user lands on Home with their environments or the empty state (U06-T02)', async ({ page }) => {
    await expect(page.locator('#dashboard-view.home-page')).toBeVisible();
    await expect(page.locator('#dashboard-view .content-header h1')).toHaveText('Home');
    await expect(page.locator('#home-envs-title, #home-empty-title')).toBeVisible();
  });

  test('global search box is present and accepts input', async ({ page }) => {
    const search = page.locator('#global-search');
    await expect(search).toBeVisible();
    await search.fill('prod');
    await expect(search).toHaveValue('prod');
  });

  test('sidebar reflects the signed-in user (no anonymous/guest state)', async ({ page }) => {
    // The workspace/admin menus are hidden until the user context resolves;
    // by the time the dashboard view is visible, at least the operations menu
    // (present for every authenticated role) must be shown.
    await expect(page.locator('#operations-menu')).toBeVisible();
  });
});

/**
 * U14-T01 (AdminDashboard.dc.html): the administrator's Home. The summary, the attention summary and
 * the month's daily spend are faked so counts, links and the plan line are known.
 */
test.describe('Administrator Home', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  const group = (running: number, stopped: number) => ({ groupName: 'app', running, stopped, transitioning: 0, failed: 0, unknown: 0, total: running + stopped });
  const env = (id: string, name: string, running: number, cost: number) => ({ environmentId: id, name: id, displayName: name,
    totalVms: running + 2, runningVms: running, stoppedVms: 2, monthToDateCost: cost, groupStates: [group(running, 2)] });
  const ENVS = [env('dl', 'Data Lake Dev', 6, 1402), env('bp', 'Billing Prod Mirror', 9, 1118), env('pd', 'Payments Dev', 5, 640),
    env('aq', 'Analytics QA', 3, 300), env('cu', 'CRM UAT', 0, 120), env('ma', 'Mobile API Test', 4, 90),
    env('hr', 'HR Portal Staging', 0, 40), env('xx', 'Sandbox', 1, 10)];
  const SUMMARY = { persona: 'ADMIN', summary: { environments: 8, totalVms: 44, runningVms: 28, stoppedVms: 16, idleVms: 0,
    driftedVms: 0, lockedEnvironments: 0, pendingAccessRequests: 3, monthToDateCost: 6284 }, environments: ENVS, needsYou: [] };
  const NONE = { accessRequests: { count: 0, oldestAt: null }, approvals: { count: 0, oldestAt: null },
    failedOperations: { count24h: 0, stuck: 0, environmentNames: [] }, vmsToReview: { drift: 0, pendingReview: 0 },
    budgets: { nearLimit: 0, top: null }, lateJobs: { count: 0, worst: null },
    health: { jobsEnabled: 35, jobsOnTime: 35, lastStateSyncAt: new Date(Date.now() - 4 * 60000).toISOString(), driftEvents24h: 0 },
    monthPlan: null };

  async function openHome(page: Page, attention: any, summary: any = SUMMARY) {
    const today = new Date();
    const days = Array.from({ length: today.getDate() }, (_, i) => ({
      date: `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(i + 1).padStart(2, '0')}`, estimatedCost: 300, actualCost: null }));
    await page.route(/\/api\/v1\/dashboard\/summary$/, route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(summary) }));
    await page.route(/\/api\/v1\/dashboard\/attention$/, route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(attention) }));
    await page.route(/\/api\/v1\/cost-management\/spend-trend/, route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(days) }));
    // U23-T01: the fleet's month, the figures Cost management and My account show.
    await page.route(/\/api\/v1\/cost-management\/month$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ monthToDate: summary.summary.monthToDateCost, forecast: 9950, dailyAverage: 300, averagedDays: 7,
        daysLeft: 12, billedDays: 0, estimatedDays: Math.max(0, today.getDate() - 1), firstDay: 1 }) }));
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/dashboard'; });
    await expect(page.locator('.admin-home .content-header h1')).toHaveText('Home', { timeout: 15_000 });
  }

  test('needs attention: counts link to the work; a late job is named on the health line', async ({ page }) => {
    await openHome(page, { ...NONE, accessRequests: { count: 3, oldestAt: new Date(Date.now() - 2 * 86400000).toISOString() },
      vmsToReview: { drift: 1, pendingReview: 2 },
      lateJobs: { count: 1, worst: { label: 'Cost snapshot', lateBySeconds: 7200 } },
      health: { ...NONE.health, jobsOnTime: 34 } });
    // Acceptance: 3 pending access requests: the tile shows 3 and links to the access requests page.
    const access = page.locator('.ah-tile[data-tile="access"]');
    await expect(access.locator('.ah-badge')).toHaveText('3');
    await expect(access).toContainText('Oldest waiting 2 days');
    await expect(access).toHaveAttribute('href', '#/pending-requests');
    await expect(page.locator('.ah-tile')).toHaveCount(3); // nothing for approvals, failures or budgets
    await expect(page.locator('.ah-tile[data-tile="vms"]')).toContainText('1 changed outside the platform, 24 h, 2 found by discovery');
    // Acceptance: a late cost snapshot job: the health line names it and links to System health.
    const health = page.locator('.ah-health');
    await expect(health).toHaveAttribute('href', '#/system-health');
    await expect(health).toHaveClass(/ah-health-warn/);
    await expect(health).toContainText('1 scheduled job is late. Cost snapshot, 2 h late · 34 of 35 scheduled jobs on time');
    await access.click();
    await expect(page).toHaveURL(/#\/pending-requests$/);
  });

  test('nothing to do: one quiet line; healthy platform; summary with the forecast', async ({ page }) => {
    await openHome(page, NONE);
    await expect(page.locator('.ah-all-clear')).toHaveText('Nothing needs you right now.');
    await expect(page.locator('.ah-tile')).toHaveCount(0);
    await expect(page.locator('.ah-health')).toContainText('Platform healthy. 35 of 35 scheduled jobs on time · state synced 4 min ago · 0 VMs changed outside the platform in 24 h'); // U22-T12
    await expect(page.locator('#ah-summary')).toContainText('28 of 44 VMs running across 8 environments · $6,284 this month, heading for $9,950');
  });

  test('U23-T10: a budget whose forecast passes its amount is named by the forecast', async ({ page }) => {
    await openHome(page, { ...NONE, budgets: { nearLimit: 1, top: { scopeName: 'ISE', percentUsed: 62, forecastPercent: 131 } } });
    const tile = page.locator('.ah-tile[data-tile="budgets"]');
    await expect(tile).toContainText('Budget heading over');
    await expect(tile).toContainText('ISE heading for 131% of its budget');
    await expect(tile).toHaveAttribute('href', '#/budgets');
  });

  test('fleet right now: the 7 busiest environments with strips, linking to each', async ({ page }) => {
    await openHome(page, NONE);
    const rows = page.locator('.ah-fleet-row');
    await expect(rows).toHaveCount(7);
    await expect(rows.first().locator('.ah-fleet-name')).toHaveText('Billing Prod Mirror');
    await expect(rows.first()).toHaveAttribute('href', '#/environments/bp');
    await expect(rows.first().locator('.power-strip')).toBeVisible();
    await expect(page.locator('.ah-fleet .ah-card-head a')).toHaveText('All 8 environments');
  });

  test('fleet right now never scrolls sideways: a row with many groups shows its strip as a bar (U20-T02)', async ({ page }) => {
    await page.setViewportSize({ width: 1366, height: 657 });
    const wide = { ...env('wd', 'Warehouse Integration Platform Development', 12, 50),
      groupStates: Array.from({ length: 24 }, (_, i) => ({ ...group(i % 2, 1 - (i % 2)), groupName: `g${i}`, sequencePosition: i })) };
    await openHome(page, NONE, { ...SUMMARY, environments: [wide, ...ENVS] });
    const row = page.locator('.ah-fleet-row[data-env="wd"]');
    await expect(row.locator('.power-bar')).toBeVisible();
    await expect(row.locator('.power-strip')).toHaveAttribute('aria-label', '12 of 24 VMs running');
    await expect(page.locator('.ah-fleet-row[data-env="bp"] .pip').first()).toBeVisible(); // narrow strips keep their lights
    const m = await page.evaluate(() => {
      const list = document.querySelector('.ah-fleet-list')!;
      const rows = [...document.querySelectorAll('.ah-fleet-row')];
      const count = rows.map(r => r.querySelector('.power-strip-count')!.getBoundingClientRect().right);
      return { sideways: list.scrollWidth - list.clientWidth, rowOver: rows.some(r => r.scrollWidth > r.clientWidth + 1),
        gap: list.getBoundingClientRect().right - Math.max(...count) };
    });
    expect(m.sideways).toBeLessThanOrEqual(0);
    expect(m.rowOver).toBe(false);
    // Acceptance: a gap between the counts and the scrollbar.
    expect(m.gap).toBeGreaterThanOrEqual(8);
  });

  test('spend: without budgets only Spent and Forecast; with budgets the plan line and its amount', async ({ page }) => {
    await openHome(page, NONE);
    const legend = page.locator('.ah-legend');
    // Acceptance: no budgets: Spent and Forecast only.
    await expect(legend).toContainText('Spent $6,284');
    await expect(legend).toContainText('Forecast $9,950');
    await expect(legend).not.toContainText('Budgets');
    // U20-T10: where the figure comes from and how the forecast is made.
    await expect(page.locator('#ah-spend-source')).toContainText('Estimated from instance prices.');
    await expect(page.locator('#ah-spend-source')).toContainText('Spent so far plus the average of the last');
    await expect(page.locator('.ah-spend-chart .ah-plan')).toHaveCount(0);
    await expect(page.locator('.ah-top-row')).toHaveCount(2);
    await expect(page.locator('.ah-top-row').first()).toContainText('Data Lake Dev');
    await expect(page.locator('.ah-top-row').first()).toContainText('$1,402');

    await page.unrouteAll({ behavior: 'ignoreErrors' });
    await page.goto('about:blank');
    await openHome(page, { ...NONE, monthPlan: { amount: 10000, budgetCount: 3 } });
    await expect(page.locator('.ah-legend')).toContainText('Budgets, $10,000');
    await expect(page.locator('.ah-spend-chart .ah-plan')).toHaveCount(1);
  });
});

