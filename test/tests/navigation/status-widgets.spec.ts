import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E13-T07: the top-bar sync pill shows the real last-sync time, and the operation banner follows
 * an operation that keeps running after its dialog is closed. Server responses are faked.
 */

declare const ContentRouter: any;
declare const VmOperations: any;

async function fakeSyncStatus(page: Page, body: object | null, status = 200) {
  await page.route('**/api/v1/monitoring/sync-status', route => body
    ? route.fulfill({ status, json: body })
    : route.fulfill({ status, json: { message: 'Forbidden' } }));
}

async function ready(page: Page) {
  await signIn(page, 'user');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.waitForLoadState('networkidle');
}

test.describe('Status widgets', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  });

  test('the sync pill shows the last sync time and is not stale at 7 minutes', async ({ page }) => {
    await fakeSyncStatus(page, { lastSyncTime: new Date(Date.now() - 7 * 60_000).toISOString(),
      syncInProgress: false, syncErrors: 0 });
    await ready(page);
    const pill = page.locator('#sync-indicator');
    await expect(pill).toBeVisible();
    await expect(page.locator('#sync-time')).toHaveText('7m ago');
    await expect(pill).not.toHaveClass(/stale/);
  });

  test('a sync older than 15 minutes is shown as stale', async ({ page }) => {
    await fakeSyncStatus(page, { lastSyncTime: new Date(Date.now() - 20 * 60_000).toISOString(),
      syncInProgress: false, syncErrors: 0 });
    await ready(page);
    await expect(page.locator('#sync-indicator')).toHaveClass(/stale/);
  });

  test('never synced: "Not synced" with a grey dot, not the green one (U04-T01)', async ({ page }) => {
    await fakeSyncStatus(page, { lastSyncTime: null, syncInProgress: false, syncErrors: 0 });
    await ready(page);
    await expect(page.locator('#sync-time')).toHaveText('Not synced');
    await expect(page.locator('#sync-indicator')).toHaveClass(/never/);
    await expect(page.locator('#sync-indicator .sync-dot')).toHaveCSS('background-color', 'rgb(212, 218, 229)');
  });

  test('a forbidden sync status hides the pill without an error toast', async ({ page }) => {
    await fakeSyncStatus(page, null, 403);
    await ready(page);
    await expect(page.locator('#sync-indicator')).toBeHidden();
    await expect(page.locator('#notification-container .toast-error')).toHaveCount(0);
  });

  test('closing the progress dialog leaves a banner; Details re-opens the dialog', async ({ page }) => {
    let polls = 0;
    const running = () => ({ executionId: 'exec-e2e', status: 'RUNNING', progressPercentage: 50,
      details: [{ status: 'COMPLETED', vmName: 'web-1' }, { status: 'IN_PROGRESS', vmName: 'web-2' }] });
    await page.route('**/api/v1/environments/env-e2e/operations', route =>
      route.fulfill({ json: { executionId: 'exec-e2e', status: 'PENDING' } }));
    await page.route('**/api/v1/environments/env-e2e/operations/exec-e2e', route => {
      polls++;
      return route.fulfill({ json: polls < 6 ? running() : { ...running(), status: 'COMPLETED',
        details: [{ status: 'COMPLETED' }, { status: 'COMPLETED' }] } });
    });
    await ready(page);

    await page.evaluate(() => { VmOperations.startEnvironment('env-e2e', 'E2E env').catch(() => {}); });
    await expect(page.locator('#operationProgressModal')).toBeVisible();
    await expect(page.locator('#operation-banner')).toBeHidden();

    await page.locator('#btn-close-progress').click();
    await expect(page.locator('#operationProgressModal')).toBeHidden();
    const banner = page.locator('#operation-banner');
    await expect(banner).toBeVisible();
    await expect(page.locator('#operation-description')).toHaveText('Starting E2E env');
    await expect(page.locator('#operation-count')).toHaveText('1 of 2 done');

    await page.locator('#view-operation-details').click();
    await expect(page.locator('#operationProgressModal')).toBeVisible();
    await expect(banner).toBeHidden();
    await page.locator('#btn-close-progress').click();

    // The fake completes after a few polls; the banner goes away.
    await expect(banner).toBeHidden({ timeout: 20_000 });
  });
});

/**
 * U17-T02: when a page's requests fail (500 with a ref), the content area shows the shared
 * "didn't load" panel with Try again, never a blank area. The shell's own calls still answer.
 * Left out: the two forms (Add people, Grant access), and Templates and API access (switched off here).
 */
test.describe('Load failures', () => {
  test.describe.configure({ timeout: 240_000 });

  test('every page whose requests fail shows the couldn’t-load panel', async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await expect(page.locator('#content-area h1').first()).toBeVisible({ timeout: 15_000 });
    const envId = await page.evaluate(async () => {
      const r = await fetch('/api/v1/environments');
      const j = await r.json();
      return ((Array.isArray(j) ? j : j.content) || [])[0]?.environmentId || '';
    });
    const shell = /\/api\/v1\/(users\/me(\/capabilities)?|assistant\/status|access-requests\/pending|notifications\/count|monitoring\/sync-status|approval-requests\/status)$/;
    await page.route(/\/api\/v1\//, route => {
      const path = new URL(route.request().url()).pathname;
      if (route.request().method() !== 'GET' || shell.test(path)) return route.continue();
      return route.fulfill({ status: 500, json: { error: 'Internal Server Error', message: 'An unexpected error occurred.', ref: '0badc0de' } });
    });
    const pages = ['#/dashboard', '#/my-environments', '#/request-access', '#/activity-logs', '#/user-management', '#/access-management',
      '#/vm-registry', '#/audit-logs-all', '#/automation-rules', '#/automation-rules/new', '#/cost-management', '#/savings', '#/budgets',
      '#/waste', '#/waste/fixes', '#/teams', '#/aws-accounts', '#/chargeback', '#/approvals', '#/approval-policies',
      '#/cost-setup', '#/system-health', '#/settings'];
    if (envId) pages.push('#/environments/' + envId);
    const blank: string[] = [];
    for (const hash of pages) {
      // Through Help (which makes no request), so the same page as before still reloads.
      await page.evaluate(() => { location.hash = '#/help'; });
      await expect(page.locator('#help-search')).toBeVisible();
      await page.evaluate(h => { location.hash = h; }, hash);
      const panel = page.locator('#content-area .ui-state-failed').first();
      try {
        await expect(panel).toBeVisible({ timeout: 8_000 });
        await expect(panel.getByRole('button', { name: 'Try again' })).toBeVisible();
        await expect(panel).toContainText("didn't load");
      } catch (e) {
        blank.push(`${hash}: ${(await page.locator('#content-area').innerText()).replace(/\s+/g, ' ').slice(0, 100)}`);
      }
    }
    expect(blank, blank.join('\n')).toEqual([]);
  });
});
