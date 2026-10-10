import { test, expect, type Request } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E12-T06: System Health asks for a true last-24-hours window (exact instants), shows the
 * server's success/failure counts, and a failed sync shows an error toast.
 */

declare const ContentRouter: any;

test.describe('System Health', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('uses a 24-hour instant window and the server success counts', async ({ page }) => {
    const reports: Request[] = [];
    page.on('request', req => { if (req.url().includes('/api/v1/audit/report')) reports.push(req); });
    await page.route('**/api/v1/audit/report?*', route => route.fulfill({
      json: { totalActions: 40, successfulActions: 30, failedActions: 10, actionCounts: {},
        userActivities: [], environmentActivities: [], recentLogs: [] } }));
    await page.route('**/api/v1/monitoring/sync', route => route.fulfill({ status: 500, json: { message: 'boom' } }));

    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.waitForLoadState('networkidle');
    await page.evaluate(() => { location.hash = '#/system-health'; });
    await expect(page.locator('#system-health-view')).toBeVisible({ timeout: 15_000 });

    for (const req of reports) {
      const p = new URL(req.url()).searchParams;
      expect(p.has('startDate')).toBe(false);
      expect(Math.round((Date.parse(p.get('to')!) - Date.parse(p.get('from')!)) / 3600000)).toBe(24);
    }
    expect(reports.length).toBe(3);

    const summary = page.locator('.sh-summary-card');
    await expect(summary.locator('.sh-stat-value.text-success')).toHaveText('30');
    await expect(summary.locator('.sh-stat-value.text-danger')).toHaveText('10');
    await expect(summary).toContainText('75%');

    await page.locator('#trigger-sync-btn').click();
    await expect(page.getByText('Failed to trigger sync')).toBeVisible();
  });
});
