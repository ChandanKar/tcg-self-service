import { test, expect, type Page, type Request } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E12-T06: System health's reports ask for a true last-24-hours window (exact instants) and show the
 * server's success/failure counts; a failed sync shows an error toast. U14-T02 (SystemHealth.dc.html):
 * Now has four figures and the scheduled jobs (problems first or all, 10 a page); Reports is asked for
 * only when it is opened.
 */

declare const ContentRouter: any;

async function openHealth(page: Page, hash = '#/system-health') {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.waitForLoadState('networkidle');
  await page.evaluate(h => { location.hash = h; }, hash);
  await expect(page.locator('#system-health-view')).toBeVisible({ timeout: 15_000 });
}

const job = (jobName: string, name: string, cadence: string, status: string, position: number, lateBySeconds: number | null = null,
  lastRunAt: string | null = new Date(Date.now() - 3600000).toISOString()) =>
  ({ jobName, name, cadence, status, lastRunAt: status === 'OFF' ? null : lastRunAt, lateBySeconds, running: false, position });

/** The mockup's list as the server sends it: problems first. */
const JOBS = [
  job('cost_snapshot', 'Cost snapshot', 'Daily 03:00', 'LATE', 11, 7200),
  job('vm_state_sync', 'State sync', 'Every 5 min', 'ON_TIME', 0),
  job('vm_inventory_sync', 'Inventory sync', 'Hourly', 'ON_TIME', 4),
  job('automation_rules', 'Automation rules', 'Every minute', 'ON_TIME', 7),
  job('lease-sweep', 'Lease sweep', 'Every minute', 'ON_TIME', 18),
  job('access_expiration', 'Access expiry', 'Daily 01:00', 'ON_TIME', 10),
  job('budget_evaluation', 'Budget evaluation', 'Daily 05:30', 'ON_TIME', 20),
  job('waste_sweep', 'Waste sweep', 'Daily 04:00', 'ON_TIME', 22),
  job('savings_ledger', 'Savings ledger', 'Daily 03:30', 'ON_TIME', 19),
  job('vm_metrics_sync', 'Metrics sync', 'Every 5 min', 'ON_TIME', 1),
  job('idle_auto_stop', 'Idle auto-stop', 'Every 5 min', 'OFF', 8)];

test.describe('System Health', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('reports: a 24-hour instant window and the server success counts, asked for only when opened', async ({ page }) => {
    const reports: Request[] = [];
    page.on('request', req => { if (req.url().includes('/api/v1/audit/report')) reports.push(req); });
    await page.route('**/api/v1/audit/report?*', route => route.fulfill({
      json: { totalActions: 40, successfulActions: 30, failedActions: 10, actionCounts: {},
        userActivities: [], environmentActivities: [], recentLogs: [] } }));
    await page.route('**/api/v1/monitoring/sync', route => route.fulfill({ status: 500, json: { message: 'boom' } }));

    await openHealth(page);
    await expect(page.locator('#sh-jobs-table')).toBeVisible({ timeout: 10_000 });
    await page.waitForLoadState('networkidle');
    // Acceptance: the report requests are not made until Reports is opened.
    expect(reports.length).toBe(0);

    await page.getByRole('tab', { name: 'Reports' }).click();
    const summary = page.locator('.sh-summary-card');
    await expect(summary.locator('.sh-stat-value.text-success')).toHaveText('30');
    await expect(summary.locator('.sh-stat-value.text-danger')).toHaveText('10');
    await expect(summary).toContainText('75%');
    expect(reports.length).toBe(3);
    for (const req of reports) {
      const p = new URL(req.url()).searchParams;
      expect(p.has('startDate')).toBe(false);
      expect(Math.round((Date.parse(p.get('to')!) - Date.parse(p.get('from')!)) / 3600000)).toBe(24);
    }
    await expect(page).toHaveURL(/#\/system-health\?tab=reports$/);
    // The syncs belong to Now (U17-T10: the boards show no actions on the other tabs).
    await expect(page.locator('#trigger-sync-btn')).toBeHidden();

    await page.getByRole('tab', { name: 'Now' }).click();
    await page.locator('#trigger-sync-btn').click();
    await expect(page.getByText("The VM states couldn't be checked. Try again.")).toBeVisible();
  });

  test('U20-T09: Check VM states now says what it found; one state-changes table with where each came from', async ({ page }) => {
    await page.route('**/api/v1/monitoring/sync', route => route.fulfill({ json: { totalVmsSynced: 42, driftDetected: 2, syncErrors: 0 } }));
    const at = (min: number) => new Date(Date.now() - min * 60000).toISOString();
    const change = (i: number, outside: boolean) => ({ historyId: `h${i}`, vmId: `vm-${i}`, vmName: `web-${String(i).padStart(2, '0')}`,
      previousStatus: outside ? 'RUNNING' : 'STOPPED', newStatus: outside ? 'STOPPED' : 'RUNNING',
      changeSource: outside ? 'state_sync' : 'operation', drift: outside, changedByUsername: outside ? null : 'Asha Rao', changedAt: at(i) });
    await page.route('**/api/v1/monitoring/state-changes', route => route.fulfill({
      json: Array.from({ length: 14 }, (_, i) => change(i + 1, i % 4 === 0)) }));
    await page.route(/\/api\/v1\/monitoring\/drift-events\?/, route => route.fulfill({
      json: { content: [], page: { size: 20, number: 0, totalElements: 4, totalPages: 1 } } }));
    await openHealth(page);

    await expect(page.getByRole('button', { name: 'Check VM states now' })).toHaveAttribute('title', /EC2, EKS node groups and RDS/);
    await page.getByRole('button', { name: 'Check VM states now' }).click();
    await expect(page.getByText('Checked 42 VMs: 2 changed outside the platform.')).toBeVisible();

    await page.getByRole('tab', { name: 'Reports' }).click();
    const table = page.locator('#sh-changes-table');
    await expect(table.locator('tbody tr')).toHaveCount(10, { timeout: 10_000 });
    await expect(page.getByText('Drift Events (24h)')).toHaveCount(0); // one table, not two
    await expect(page.locator('.sh-changes-count')).toHaveText('4 changes outside the platform in 24 hours');
    await expect(table.locator('tbody tr').first()).toContainText('Outside the platform');
    await expect(table.locator('tbody tr').nth(1)).toContainText('Platform, by Asha Rao');
    await expect(page.locator('#sh-changes .pager-range')).toHaveText('1–10 of 14');
    await page.getByLabel('Only outside the platform').check();
    await expect(table.locator('tbody tr')).toHaveCount(4);
    await expect(table.locator('tbody tr.sh-change-outside')).toHaveCount(4);
  });

  test('now: four figures and the jobs, problems first; All follows the catalog; 10 a page', async ({ page }) => {
    await page.route('**/api/v1/monitoring/scheduler-health', route => route.fulfill({ json: JOBS }));
    await page.route('**/api/v1/monitoring/sync-status', route => route.fulfill({
      json: { lastSyncTime: new Date(Date.now() - 4 * 60000).toISOString(), totalVmsSynced: 112, syncInProgress: false } }));
    await page.route(/\/api\/v1\/audit\/logs\/stats/, route => route.fulfill({ json: { total: 230, failures: 2, successRate: 99 } }));
    await openHealth(page);

    const tiles = page.locator('.sh-tile');
    await expect(tiles).toHaveCount(4);
    await expect(tiles.nth(0)).toContainText('4 min ago');
    await expect(tiles.nth(0)).toContainText('112 VMs, every 5 min');
    await expect(tiles.nth(2)).toContainText('230');
    await expect(tiles.nth(2)).toContainText('99% succeeded');
    await expect(tiles.nth(2).getByRole('link', { name: 'Audit log' })).toHaveAttribute('href', '#/audit-logs-all');
    await expect(page.locator('#sh-tile-jobs')).toContainText('9/10');
    await expect(page.locator('#sh-tile-jobs')).toContainText('1 late');

    // Acceptance: a cost snapshot job 2 hours late is the first row with '2 h late' under Problems first.
    const rows = page.locator('#sh-jobs-table tbody tr');
    await expect(page.getByRole('button', { name: 'Problems first' })).toHaveAttribute('aria-pressed', 'true');
    await expect(rows).toHaveCount(10);
    await expect(rows.first()).toContainText('Cost snapshot');
    await expect(rows.first()).toContainText('Daily 03:00');
    await expect(rows.first().locator('.sh-pill')).toHaveText('2 h late');
    await expect(page.locator('#sh-jobs-body .pager-range')).toHaveText('1–10 of 11');
    await page.locator('#sh-jobs-body .pager-btn', { hasText: 'Next' }).click();
    await expect(rows).toHaveCount(1);
    await expect(rows.first().locator('.sh-pill')).toHaveText('Switched off');

    await page.getByRole('button', { name: 'All 11' }).click();
    await expect(page.getByRole('button', { name: 'All 11' })).toHaveAttribute('aria-pressed', 'true');
    await expect(rows.first()).toContainText('State sync');
    await expect(rows.nth(1)).toContainText('Metrics sync');
  });

  test('Sync EKS clusters shows only when an EKS environment exists', async ({ page }) => {
    let eks = false;
    await page.route(/\/api\/v1\/environments$/, async route => {
      if (route.request().method() !== 'GET') return route.continue();
      return route.fulfill({ json: [{ environmentId: 'e1', name: 'e1', displayName: 'Payments Dev', serviceType: eks ? 'EKS' : 'EC2', isActive: true }] });
    });
    await openHealth(page);
    await expect(page.locator('#sh-jobs-table, #sh-jobs-body .sh-note').first()).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole('button', { name: 'Check VM states now' })).toBeVisible();
    await expect(page.locator('#trigger-eks-sync-btn')).toBeHidden();

    eks = true;
    await page.evaluate(() => ContentRouter.reload());
    await expect(page.getByRole('button', { name: 'Sync EKS clusters' })).toBeVisible({ timeout: 10_000 });
  });

  test('Fleet use: asked for only when opened; lines, fleet state, coverage and risk rows that link to their fix (U14-T09)', async ({ page }) => {
    let summaries = 0;
    const MB = 1024 * 1024;
    const point = (label: string, cpu: number, mem: number, net: number) => ({ label, cpuUtilization: cpu, memoryUtilization: mem,
      networkInBytes: net * MB, networkOutBytes: 0, diskReadBytes: 0, diskWriteBytes: 0 });
    await page.route(/\/api\/v1\/dashboard\/summary$/, route => { summaries++; return route.fulfill({ json: { persona: 'ADMIN', summary: {},
      utilizationTrend: [point('06:00', 12, 44, 40), point('10:00', 41, 63, 230), point('17:00', 34, 59, 180)],
      vmStatusCounts: { RUNNING: 41, STOPPED: 66, STARTING: 3, ERROR: 2 }, coverage: { metricsPercent: 94 },
      riskCompliance: [{ tone: 'danger', title: 'EBS encryption', description: '9 volume(s) are not encrypted.', count: 9 },
        { tone: 'success', title: 'State drift', description: 'No state drift detected.', count: 0 },
        { tone: 'warning', title: 'Metric coverage', description: '94% of VMs have recent metric samples.', count: 3 }] } }); });
    await openHealth(page);
    summaries = 0; // Home, opened at sign-in, asks for the summary itself
    await expect(page.locator('#sh-tiles .sh-tile')).toHaveCount(4, { timeout: 10_000 });
    await page.waitForLoadState('networkidle');
    // Acceptance: System health asks for no /dashboard/summary until Fleet use opens.
    expect(summaries).toBe(0);

    await page.getByRole('tab', { name: 'Fleet use' }).click();
    await expect(page).toHaveURL(/#\/system-health\?tab=fleet$/);
    const fleet = page.locator('#sh-panel-fleet');
    await expect(fleet.locator('svg.fu-line')).toHaveCount(3, { timeout: 10_000 });
    await expect(fleet.locator('svg.fu-line-cpu')).toHaveAttribute('aria-label', 'Average CPU over 24 hours, from 12% to 34%, peak 41%');
    await expect(fleet).toContainText('Peak 41% at 10:00.');
    await expect(fleet.locator('#fu-state .fu-legend[data-state="Running"]')).toContainText('41');
    await expect(fleet.locator('#fu-state .fu-legend[data-state="Starting or stopping"]')).toContainText('3');
    await expect(fleet.locator('#fu-state .fu-legend[data-state="Failed"]')).toContainText('2');
    await expect(fleet.locator('#fu-state')).toContainText('112 VMs');
    await expect(fleet.locator('#fu-coverage')).toContainText('94%');
    const risks = fleet.locator('#fu-risk .fu-risk');
    await expect(risks).toHaveCount(2);
    await expect(risks.first()).toContainText('Unencrypted disks');
    await expect(fleet.locator('#fu-risk')).toContainText('12 things to look at');
    expect(summaries).toBe(1);
    // Acceptance: a risk row opens the page where it is fixed.
    await risks.first().click();
    await expect(page).toHaveURL(/#\/waste$/);
  });

  test('Regions (U19-T06/T08): the regions of each account, the last scan of each region, services a region does not offer', async ({ page }) => {
    let asked = 0;
    await page.route(/\/api\/v1\/monitoring\/regions$/, route => {
      asked++;
      return route.fulfill({ json: {
        accounts: [{ accountRef: 'd', accountName: 'Default account', enabled: ['ap-south-1', 'eu-west-1', 'me-central-1'],
          refused: ['me-central-1'], readAt: new Date(Date.now() - 3600000).toISOString(), limit: [] }],
        scans: [
          { kind: 'EC2 instances (inbox)', accountRef: 'd', accountName: 'Default account', region: 'eu-west-1', found: 4,
            scannedAt: new Date().toISOString(), error: null, refused: false },
          { kind: 'EC2 instances (inbox)', accountRef: 'd', accountName: 'Default account', region: 'me-central-1', found: 0,
            scannedAt: new Date().toISOString(), error: 'UnauthorizedOperation', refused: true }],
        notes: [{ service: 'Compute Optimizer', accountName: 'Default account', region: 'il-central-1', kind: 'NOT_IN_REGION',
          message: 'Compute Optimizer is not offered in il-central-1; that region is left out for it.', at: new Date().toISOString() }],
        excluded: ['ap-east-1'] } });
    });
    await openHealth(page);
    expect(asked).toBe(0);
    await page.getByRole('tab', { name: 'Regions' }).click();
    await expect(page).toHaveURL(/#\/system-health\?tab=regions$/);
    const panel = page.locator('#sh-panel-regions');
    await expect(panel.locator('.sh-region-account')).toContainText('3 enabled regions', { timeout: 10_000 });
    await expect(panel.locator('.sh-region-account')).toContainText('Refused access: me-central-1');
    await expect(panel).toContainText('Left out everywhere: ap-east-1');
    const rows = panel.locator('#sh-region-table tbody tr');
    await expect(rows).toHaveCount(2);
    await expect(rows.first()).toContainText('me-central-1');
    await expect(rows.first()).toContainText('Refused access');
    await expect(rows.nth(1)).toContainText('4 instances');
    await expect(panel.locator('.sh-region-notes li')).toContainText('Not offered there');
    await expect(panel.locator('.sh-region-notes li')).toContainText('Compute Optimizer · il-central-1');
  });

  test('Fleet use with no risk signals says nothing needs a look', async ({ page }) => {
    await page.route(/\/api\/v1\/dashboard\/summary$/, route => route.fulfill({ json: { persona: 'ADMIN', summary: {}, utilizationTrend: [],
      vmStatusCounts: {}, coverage: { metricsPercent: 100 }, riskCompliance: [{ tone: 'success', title: 'EBS encryption', description: 'ok', count: 0 }] } }));
    await openHealth(page, '#/system-health?tab=fleet');
    await expect(page.locator('#fu-risk .fu-clear')).toHaveText('Nothing needs a look.', { timeout: 10_000 });
    await expect(page.locator('.fu-empty')).toHaveCount(3);
  });
});

