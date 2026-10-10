import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { delayRoute } from '../../fixtures/mock-api';

/**
 * E02-T06..T09: a page the user has left must never draw over the page they moved to, and must
 * leave no timers, window listeners or charts behind. One block per migrated page.
 */

declare const ContentRouter: any;
declare const $: any;

const SUMMARY = '/api/v1/dashboard/summary';

async function ready(page: Page) {
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined' && typeof ContentRouter.onLeave === 'function');
}

async function goTo(page: Page, route: string) {
  await page.evaluate((r) => { location.hash = '#/' + r; }, route);
  await page.waitForFunction((r) => location.hash === '#/' + r, route);
}

/** Number of jQuery handlers on window for resize.<namespace>. */
function resizeHandlers(page: Page, namespace: string) {
  return page.evaluate((ns) => {
    const events = ($ as any)._data(window, 'events') || {};
    return (events.resize || []).filter((h: any) => h.namespace === ns).length;
  }, namespace);
}

const header = (page: Page) => page.locator('#content-area .content-header h1').first();

test.describe('Dashboard lifecycle', () => {
  test.beforeEach(async () => {
    test.skip(!canSignIn('user'), 'No sign-in configured (TEST_DEV_USER_ID or TEST_USERNAME/TEST_PASSWORD in test/.env)');
  });

  test('Auto On never repaints over another page and stops polling after leaving', async ({ page }) => {
    await page.clock.install();
    await signIn(page, 'user');
    await ready(page);
    await goTo(page, 'dashboard');
    await expect(header(page)).toHaveText('Dashboard');
    await page.locator('#dashboard-auto-refresh-toggle').click();
    await expect(page.locator('#dashboard-auto-refresh-toggle')).toContainText('Auto On');

    await goTo(page, 'my-environments');
    await expect(header(page)).toHaveText('My Environments');
    let summaryCalls = 0;
    page.on('request', (r) => { if (r.url().includes(SUMMARY)) summaryCalls++; });

    await page.clock.runFor(35_000);

    await expect(header(page)).toHaveText('My Environments');
    expect(summaryCalls).toBe(0);
    expect(await resizeHandlers(page, 'dashboardCharts')).toBe(0);
  });

  test('a silent refresh keeps the search box text and filter', async ({ page }) => {
    await page.clock.install();
    let call = 0;
    await page.route(`**${SUMMARY}`, async (route) => {
      call++;
      try {
        const response = await route.fetch();
        const body = await response.json();
        // Change the data on refreshes so the dashboard re-renders.
        if (call > 1) body.summary = { ...(body.summary || {}), _refreshMarker: call };
        await route.fulfill({ response, json: body });
      } catch {
        // The page dropped this request (an overlapping refresh under the fake clock, or the
        // test ending): its response is already disposed, so there is nothing to fulfil.
      }
    });

    await signIn(page, 'user');
    await ready(page);
    await goTo(page, 'dashboard');
    await expect(header(page)).toHaveText('Dashboard');
    await page.locator('#dashboard-env-search').fill('prod');
    await page.locator('#dashboard-auto-refresh-toggle').click();

    const before = call;
    await page.clock.runFor(31_000);
    await expect.poll(() => call).toBeGreaterThan(before);
    await expect(page.locator('#dashboard-env-search')).toHaveValue('prod');
  });

  test('a slow dashboard load that finishes after leaving does not render', async ({ page }) => {
    await signIn(page, 'user');
    await ready(page);
    await goTo(page, 'my-environments');
    await expect(header(page)).toHaveText('My Environments');

    await delayRoute(page, `**${SUMMARY}`, 2_000);
    await goTo(page, 'dashboard');
    await page.waitForTimeout(300);
    await goTo(page, 'request-access');
    await page.waitForTimeout(2_500);

    await expect(page).toHaveURL(/#\/request-access$/);
    await expect(header(page)).not.toHaveText('Dashboard');
  });
});

test.describe('Environments lifecycle', () => {
  let envId = '';
  const envName = `Takeover E2E ${Date.now()}`;

  test.beforeAll(async ({ playwright }) => {
    if (!devIds.admin) return;
    const admin = await playwright.request.newContext({
      baseURL: process.env.BASE_URL || 'http://localhost:8080',
      extraHTTPHeaders: { 'X-User-Id': devIds.admin, 'Content-Type': 'application/json' },
    });
    const created = await admin.post('/api/v1/environments', {
      data: { name: `takeover-e2e-${Date.now()}`, displayName: envName, cloudProvider: 'AWS' },
    });
    expect(created.ok()).toBeTruthy();
    envId = (await created.json()).environmentId;
    await admin.dispose();
  });

  test.beforeEach(async ({ page }) => {
    test.skip(!devIds.admin, 'Needs TEST_DEV_ADMIN_ID (dev mode) to seed an environment');
    await signIn(page, 'admin');
    await ready(page);
  });

  test('an operation-status event after leaving Environment Detail does not repaint it', async ({ page }) => {
    await goTo(page, `environments/${envId}`);
    await expect(page.locator('#content-area')).toContainText(envName, { timeout: 10_000 });

    await page.evaluate((id) => window.dispatchEvent(new CustomEvent('vm-operation-status', { detail: { envId: id } })), envId);
    await goTo(page, 'request-access');
    await page.waitForTimeout(2_000);

    await expect(page).toHaveURL(/#\/request-access$/);
    await expect(page.locator('#env-detail-view')).toHaveCount(0);
    // (Request Access lists requestable environments, so the name itself may appear there.)
    await expect(header(page)).toContainText('Request Environment Access');
  });

  test('a slow Environment Detail load finishing after leaving does not render', async ({ page }) => {
    await delayRoute(page, `**/api/v1/environments/${envId}*`, 2_000);
    await goTo(page, `environments/${envId}`);
    await page.waitForTimeout(300);
    await goTo(page, 'request-access');
    await page.waitForTimeout(2_800);

    await expect(page.locator('#env-detail-view')).toHaveCount(0);
    // (Request Access lists requestable environments, so the name itself may appear there.)
    await expect(header(page)).toContainText('Request Environment Access');
  });

  test("leaving Environments removes its delegated handlers, so other pages' [data-action=view] are untouched", async ({ page }) => {
    await goTo(page, 'my-environments');
    await expect(header(page)).toHaveText('My Environments', { timeout: 10_000 });
    await goTo(page, `environments/${envId}`);
    await expect(page.locator('#content-area')).toContainText(envName, { timeout: 10_000 });

    await goTo(page, 'user-management');
    await page.waitForTimeout(500);

    const leftover = await page.evaluate(() => {
      const events = ($ as any)._data(document.getElementById('content-area'), 'events') || {};
      return Object.values(events).flat().filter((h: any) => /^env(List|Detail)$/.test(h.namespace)).length;
    });
    expect(leftover).toBe(0);
  });
});

test.describe('Logs and Access Requests lifecycle', () => {
  test.beforeEach(async () => {
    test.skip(!canSignIn('admin'), 'Needs an admin sign-in (TEST_DEV_ADMIN_ID or TEST_ADMIN_USERNAME/PASSWORD)');
  });

  for (const route of ['activity-logs', 'audit-logs-all']) {
    test(`slow ${route} requests finishing after leaving do not take over the page`, async ({ page }) => {
      await signIn(page, 'admin');
      await ready(page);
      await goTo(page, 'dashboard');
      await expect(header(page)).toHaveText('Dashboard');

      await delayRoute(page, '**/api/v1/audit/**', 3_000);
      await goTo(page, route);
      await page.waitForTimeout(500);
      await goTo(page, 'dashboard');
      await page.waitForTimeout(3_500);

      await expect(page).toHaveURL(/#\/dashboard$/);
      await expect(header(page)).toHaveText('Dashboard');
    });
  }

  test('a slow Request Access load finishing after leaving does not render', async ({ page }) => {
    await signIn(page, 'admin');
    await ready(page);
    await goTo(page, 'dashboard');
    await expect(header(page)).toHaveText('Dashboard');

    await delayRoute(page, '**/api/v1/access-requests/**', 3_000);
    await goTo(page, 'request-access');
    await page.waitForTimeout(500);
    await goTo(page, 'dashboard');
    await page.waitForTimeout(3_500);

    await expect(header(page)).toHaveText('Dashboard');
  });

  test("leaving Request Access removes its handlers, so a later [data-action=cancel] is not Access Requests'", async ({ page }) => {
    await signIn(page, 'admin');
    await ready(page);
    await goTo(page, 'request-access');
    await expect(header(page)).toContainText('Request Environment Access', { timeout: 10_000 });
    await goTo(page, 'dashboard');
    await expect(header(page)).toHaveText('Dashboard');

    const leftover = await page.evaluate(() => {
      const events = ($ as any)._data(document.getElementById('content-area'), 'events') || {};
      return Object.values(events).flat().filter((h: any) => h.namespace === 'reqAccess').length;
    });
    expect(leftover).toBe(0);

    let cancelCalls = 0;
    page.on('request', (r) => { if (r.method() === 'DELETE' && r.url().includes('/access-requests/')) cancelCalls++; });
    await page.evaluate(() => {
      const b = document.createElement('button');
      b.setAttribute('data-action', 'cancel');
      b.setAttribute('data-request-id', 'not-a-real-request');
      b.id = 'stray-cancel';
      document.getElementById('content-area')!.appendChild(b);
    });
    await page.locator('#stray-cancel').click();
    await page.waitForTimeout(500);
    await expect(page.locator('#confirmModal')).toHaveCount(0);
    expect(cancelCalls).toBe(0);
  });
});

test.describe('Late responses never take over (all routes)', () => {
  const ROUTES = ['my-environments', 'request-access', 'pending-requests', 'activity-logs', 'user-management',
    'access-management', 'vm-registry', 'automation-rules', 'audit-logs-all', 'cost-management', 'system-health'];

  test.beforeEach(async () => {
    test.skip(!canSignIn('admin'), 'Needs an admin sign-in (TEST_DEV_ADMIN_ID or TEST_ADMIN_USERNAME/PASSWORD)');
  });

  for (const route of ROUTES) {
    test(`${route}: a response arriving after the user left does not render`, async ({ page }) => {
      await signIn(page, 'admin');
      await ready(page);
      await goTo(page, 'dashboard');
      await expect(header(page)).toHaveText('Dashboard');

      // Slow every API call; the dashboard summary (registered later, so it wins) stays fast.
      await delayRoute(page, '**/api/v1/**', 3_000);
      await page.route(`**${SUMMARY}`, (r) => r.continue());

      await goTo(page, route);
      await page.waitForTimeout(300);
      await goTo(page, 'dashboard');
      await expect(header(page)).toHaveText('Dashboard', { timeout: 10_000 });
      await page.waitForTimeout(3_500);

      await expect(page).toHaveURL(/#\/dashboard$/);
      await expect(header(page)).toHaveText('Dashboard');
    });
  }

  test('System Health: the reload after Trigger Sync does not fire once the user has left', async ({ page }) => {
    await signIn(page, 'admin');
    await ready(page);
    await goTo(page, 'system-health');
    await expect(page.locator('#trigger-sync-btn')).toBeVisible({ timeout: 15_000 });
    await page.route('**/api/v1/monitoring/**', (r) =>
      r.request().method() === 'POST' ? r.fulfill({ status: 200, contentType: 'application/json', body: '{}' }) : r.continue());

    await page.locator('#trigger-sync-btn').click();
    await goTo(page, 'dashboard');
    await page.waitForTimeout(3_800);

    await expect(page).toHaveURL(/#\/dashboard$/);
    await expect(header(page)).toHaveText('Dashboard');
  });

  test("leaving User Management removes its row handlers, so a later [data-action=deactivate] is not its", async ({ page }) => {
    await signIn(page, 'admin');
    await ready(page);
    await goTo(page, 'user-management');
    await expect(header(page)).toContainText('User Management', { timeout: 10_000 });
    await goTo(page, 'dashboard');
    await expect(header(page)).toHaveText('Dashboard');

    await page.evaluate(() => {
      const a = document.createElement('a');
      a.href = '#';
      a.setAttribute('data-action', 'deactivate');
      a.setAttribute('data-user-id', 'user-001');
      a.id = 'stray-deactivate';
      a.textContent = 'Deactivate';
      document.getElementById('content-area')!.appendChild(a);
    });
    await page.locator('#stray-deactivate').click();
    await page.waitForTimeout(500);
    await expect(page.locator('#confirmModal')).toHaveCount(0);
  });
});

test.describe('Cost Management lifecycle', () => {
  test.beforeEach(async () => {
    test.skip(!canSignIn('admin'), 'Needs an admin sign-in (TEST_DEV_ADMIN_ID or TEST_ADMIN_USERNAME/PASSWORD)');
  });

  test('slow cost requests finishing after leaving do not take over the page', async ({ page }) => {
    await signIn(page, 'admin');
    await ready(page);
    await goTo(page, 'dashboard');
    await expect(header(page)).toHaveText('Dashboard');

    await delayRoute(page, '**/api/v1/cost-management/**', 3_000);
    await goTo(page, 'cost-management');
    await page.waitForTimeout(500);
    await goTo(page, 'dashboard');
    await page.waitForTimeout(4_000);

    await expect(page).toHaveURL(/#\/dashboard$/);
    await expect(header(page)).toHaveText('Dashboard');
  });

  test('leaving Cost Management removes its resize handler and apply-button binding', async ({ page }) => {
    await signIn(page, 'admin');
    await ready(page);
    await goTo(page, 'cost-management');
    await expect(header(page)).toHaveText('Cost Management', { timeout: 15_000 });
    await page.waitForTimeout(1_500);

    await goTo(page, 'dashboard');
    await expect(header(page)).toHaveText('Dashboard');

    expect(await resizeHandlers(page, 'costManagementCharts')).toBe(0);
    const applyHandlers = await page.evaluate(() => {
      const events = ($ as any)._data(document.getElementById('content-area'), 'events') || {};
      return (events.click || []).filter((h: any) => h.namespace === 'rightsizingApply').length;
    });
    expect(applyHandlers).toBe(0);
  });
});
