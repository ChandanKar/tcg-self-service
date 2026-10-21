import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E08-T09: the Cost Management page survives a failing endpoint. One failed request shows an
 * error with Retry on its own panel while the rest render; Retry refetches only that request;
 * charts are not leaked on re-draw; leaving the page drops its resize handler and charts.
 * The failing /forecast and the backfill call are faked in the browser; everything else hits
 * the smoke app (test schema, estimated costs, no AWS).
 */

declare const ContentRouter: any;

async function openCostPage(page: Page) {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.waitForLoadState('networkidle');
  // Record global error toasts.
  // Notifications is a top-level const, not a window property: patch it by name.
  await page.evaluate(`(() => {
    window.__errorToasts = [];
    const original = Notifications.error;
    Notifications.error = (msg, ...rest) => { window.__errorToasts.push(msg); return original(msg, ...rest); };
  })()`);
  await page.evaluate(() => { location.hash = '#/cost-management'; });
  await expect(page.locator('#cost-report')).toBeVisible({ timeout: 15_000 });
}

test.describe('Cost Management resilience', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('a failing forecast shows Retry on the trend panel only, and Retry refetches only it', async ({ page }) => {
    let forecastCalls = 0;
    let forecastFails = true;
    const otherCalls: string[] = [];
    await page.route(/\/api\/v1\/cost-management\/forecast/, route => {
      forecastCalls++;
      return forecastFails
        ? route.fulfill({ status: 500, contentType: 'application/json', body: '{"message":"boom"}' })
        : route.fulfill({ status: 200, contentType: 'application/json',
            body: JSON.stringify({ sufficientHistory: false, minHistoryDaysRequired: 14, historyDaysUsed: 0, points: [] }) });
    });
    await openCostPage(page);

    const trend = page.locator('[data-cost-panel="trend"]');
    await expect(trend.locator('.cost-panel-error')).toContainText("Couldn't load the spend trend.");
    // Every other panel rendered.
    await expect(page.locator('[data-cost-panel="kpi"] .dashboard-kpi')).toHaveCount(4);
    await expect(page.locator('#cost-chart-env')).toBeVisible();
    await expect(page.locator('#cost-idle-table')).toBeVisible();
    await expect(page.locator('.cost-panel-error')).toHaveCount(1);
    // The panel explains the failure; no global "unexpected error" toast on top.
    expect(await page.evaluate(() => (window as any).__errorToasts)).toEqual([]);

    forecastFails = false;
    page.on('request', req => {
      if (req.url().includes('/cost-management/') && !req.url().includes('/forecast')) otherCalls.push(req.url());
    });
    await trend.locator('.cost-retry').click();

    await expect(page.locator('#cost-chart-trend svg')).toHaveCount(1);
    await expect(page.locator('.cost-panel-error')).toHaveCount(0);
    expect(forecastCalls).toBe(2);
    expect(otherCalls).toEqual([]); // the trend itself had loaded, so only /forecast was retried
  });

  test('backfilling twice leaves exactly one chart in the trend panel', async ({ page }) => {
    await page.route(/\/api\/v1\/cost-management\/snapshots\/backfill/, route => route.fulfill({ status: 200, body: '' }));
    await openCostPage(page);

    for (let i = 0; i < 2; i++) {
      const done = page.waitForResponse(r => r.url().includes('/cost-management/forecast'));
      // U15-T01: Data actions › Fill in the last 30 days, then confirm.
      await page.getByRole('button', { name: 'Data actions' }).click();
      await page.locator('#cost-backfill-btn').click();
      await page.locator('#confirmBtn').click();
      await done;
      await expect(page.locator('#cost-backfill-btn')).toBeEnabled();
    }

    await expect(page.locator('#cost-chart-trend svg')).toHaveCount(1);
    expect(await page.evaluate(() => (window as any).echarts.getInstanceByDom(document.getElementById('cost-chart-trend')) != null))
      .toBe(true);
  });

  test('leaving the page drops its resize handler and disposes its charts', async ({ page }) => {
    await openCostPage(page);
    await expect(page.locator('#cost-chart-env svg')).toHaveCount(1);
    const envChart = await page.evaluateHandle(() => document.getElementById('cost-chart-env'));

    await page.evaluate(() => { location.hash = '#/home'; });
    await expect(page.locator('#cost-report')).toHaveCount(0);

    const handlers = await page.evaluate(() => {
      const events = (window as any).jQuery._data(window, 'events') || {};
      return (events.resize || []).map((h: any) => h.namespace);
    });
    expect(handlers).not.toContain('costManagementCharts');
    expect(await page.evaluate(el => (window as any).echarts.getInstanceByDom(el as HTMLElement) == null, envChart)).toBe(true);
  });
});

/** U15-T01 (CostOverview.dc.html): tabs that load their own data, ?tab= in the address, two menus. */
test.describe('Cost management tabs and menus', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('Overview asks only for its own data; another tab loads when opened and stays in the address', async ({ page }) => {
    const asked: string[] = [];
    page.on('request', r => { if (/\/api\/v1\/(cost-management|cost-anomalies)\//.test(r.url())) asked.push(new URL(r.url()).pathname); });
    await openCostPage(page);
    await page.waitForLoadState('networkidle');
    // Acceptance: only Overview's keys are asked for, and the toolbar is two menu buttons.
    expect(asked.some(u => u.endsWith('/rightsizing'))).toBe(false);
    expect(asked.some(u => u.includes('/commitment') || u.includes('/reservations'))).toBe(false);
    expect(asked.some(u => u.startsWith('/api/v1/cost-anomalies'))).toBe(false);
    expect(asked.some(u => u.endsWith('/vm-detail'))).toBe(false);
    await expect(page.locator('.cost-toolbar .menu-button')).toHaveText(['Data actions', 'Export']);
    await expect(page.getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'true');

    await page.getByRole('tab', { name: 'Rightsizing' }).click();
    await expect(page).toHaveURL(/#\/cost-management\?tab=rightsizing$/);
    await expect(page.locator('#cost-rightsizing-table')).toBeVisible({ timeout: 10_000 });
    expect(asked.some(u => u.endsWith('/rightsizing'))).toBe(true);

    // Acceptance: a tab opened directly is selected and loads.
    await page.evaluate(() => { location.hash = '#/cost-management?tab=commitments'; });
    await expect(page.getByRole('tab', { name: 'Commitments' })).toHaveAttribute('aria-selected', 'true', { timeout: 15_000 });
    await expect(page.locator('[data-cost-panel="reservations"]')).toBeVisible();
  });

  test('the menus open with the keyboard and close with Escape; data actions confirm first', async ({ page }) => {
    let backfills = 0;
    await page.route(/\/api\/v1\/cost-management\/snapshots\/backfill/, route => { backfills++; return route.fulfill({ status: 200, json: {} }); });
    await openCostPage(page);
    const exportBtn = page.getByRole('button', { name: 'Export' });
    await exportBtn.focus();
    await page.keyboard.press('ArrowDown');
    await expect(exportBtn).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByRole('menuitem', { name: 'This page as PDF' })).toBeFocused();
    await page.keyboard.press('ArrowDown');
    await expect(page.getByRole('menuitem', { name: 'This page as PNG' })).toBeFocused();
    await expect(page.getByRole('menuitem', { name: 'VM cost detail, Excel' })).toHaveAttribute('href', /\/cost-management\/vm-detail\/export$/);
    await page.keyboard.press('Escape');
    await expect(exportBtn).toHaveAttribute('aria-expanded', 'false');
    await expect(exportBtn).toBeFocused();

    await page.getByRole('button', { name: 'Data actions' }).click();
    await page.getByRole('menuitem', { name: 'Fill in the last 30 days' }).click();
    await expect(page.locator('.modal.show')).toContainText("Estimates each of the last 30 days' cost");
    await page.locator('.modal.show .btn-secondary, .modal.show [data-bs-dismiss="modal"]').first().click();
    await expect(page.locator('.modal.show')).toHaveCount(0);
    expect(backfills).toBe(0);
  });
});

