import { test, expect, type Page, type Request, type Route } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E11-T06: My activity sends exact instants (no UTC calendar days) and never asks for more than
 * 500 rows. U09-T04 (MyActivity.dc.html): plain sentences grouped by day with a Done / Failed /
 * Sent chip and the failure reason under the sentence; period buttons; the Action group is
 * filtered on the server; 10 to a page; Export CSV.
 */

declare const ContentRouter: any;

const BASE = process.env.BASE_URL || 'http://localhost:8080';

async function open(page: Page) {
  await signIn(page, 'user');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => { location.hash = '#/activity-logs'; });
  await expect(page.locator('#content-area h1')).toHaveText('My activity', { timeout: 15_000 });
}

function at(daysAgo: number, hour: number, minute = 0): string {
  const d = new Date();
  d.setDate(d.getDate() - daysAgo);
  d.setHours(hour, minute, 0, 0);
  return d.toISOString();
}

/** Serve /audit/logs/my from a list, honouring page and size like the server. */
async function serve(page: Page, rows: any[], seen?: URLSearchParams[]) {
  await page.route('**/api/v1/audit/logs/my?**', (route: Route) => {
    const p = new URL(route.request().url()).searchParams;
    seen?.push(p);
    const number = Number(p.get('page') || 0);
    const size = Number(p.get('size') || 10);
    route.fulfill({ json: { content: rows.slice(number * size, number * size + size),
      page: { size, number, totalElements: rows.length, totalPages: Math.ceil(rows.length / size) } } });
  });
}

const row = (o: Record<string, unknown>) => ({ logId: String(Math.random()), success: true, environmentName: 'Analytics QA', ...o });

test.describe('My activity', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  });

  test('sends instants, the period and the action group, 10 a page and never more than 500', async ({ page }) => {
    const requests: Request[] = [];
    page.on('request', req => { if (req.url().includes('/api/v1/audit/logs/my')) requests.push(req); });
    // Rows a page follow the window (U18-T02): at 1440 x 900 all ten fit, so the first load is the only one.
    await page.setViewportSize({ width: 1440, height: 900 });
    await open(page);
    await expect(page.locator('#al-results')).toHaveAttribute('aria-busy', 'false');

    let p = new URL(requests[0].url()).searchParams;
    expect(p.get('from')).toMatch(/T.*Z$/);
    expect(p.has('startDate')).toBe(false);
    expect(p.get('size')).toBe('10');
    expect(Math.round((Date.parse(p.get('to')!) - Date.parse(p.get('from')!)) / 3600000)).toBe(24 * 7);
    await expect(page.getByRole('group', { name: 'Period' }).getByRole('button', { name: '7 days' })).toHaveAttribute('aria-pressed', 'true');

    await expect(page.locator('#al-action-filter option')).toHaveText(['All', 'Start and stop', 'Locks', 'Access']);
    await page.locator('#al-action-filter').selectOption({ label: 'Locks' });
    await expect.poll(() => requests.length).toBe(2);
    expect(new URL(requests[1].url()).searchParams.get('category')).toBe('LOCKS');

    await page.getByRole('button', { name: '24 hours' }).click();
    await expect.poll(() => requests.length).toBe(3);
    p = new URL(requests[2].url()).searchParams;
    expect(Math.round((Date.parse(p.get('to')!) - Date.parse(p.get('from')!)) / 3600000)).toBe(24);
    expect(p.get('category')).toBe('LOCKS');
    await expect(page.getByRole('button', { name: '24 hours' })).toHaveAttribute('aria-pressed', 'true');

    await page.getByRole('button', { name: 'Dates…' }).click();
    await expect(page.locator('#al-start-date-input')).toBeVisible();
    expect(requests.length).toBe(3);
    await expect(page.locator('#al-page-size-filter')).toHaveCount(0);
  });

  test('sentences by day with their result, and the reason under a failure', async ({ page }) => {
    await serve(page, [
      row({ action: 'OPERATION_COMPLETED', targetName: 'START', environmentName: 'Payments Dev', createdAt: at(0, 8, 2),
        details: 'Operation completed by user: u. Environment: Payments Dev. Total VMs: 5, Failed: 0' }),
      row({ action: 'LOCK_ACQUIRED', createdAt: at(0, 7, 58),
        details: 'Lock acquired by user: u. Environment: Analytics QA. Reason: Data migration rehearsal' }),
      row({ action: 'ACCESS_REQUESTED', createdAt: at(0, 7, 41), details: 'Access requested by u. Level: USER' }),
      row({ action: 'VM_START_FAILED', targetName: 'report-worker', success: false, createdAt: at(1, 14, 22),
        errorMessage: 'User: u. Error: InsufficientInstanceCapacity for r6i.xlarge in ap-south-1b' }),
    ]);
    await open(page);

    const days = page.locator('.activity-day');
    await expect(days).toHaveText(['Today', 'Yesterday']);
    const rows = page.locator('.activity-row');
    await expect(rows).toHaveCount(4);
    await expect(rows.nth(0)).toContainText('08:02');
    await expect(rows.nth(0).locator('.activity-what')).toHaveText('Started 5 VMs');
    await expect(rows.nth(0).locator('.activity-env')).toHaveText('Payments Dev');
    await expect(rows.nth(0).locator('.activity-chip')).toHaveText('Done');
    await expect(rows.nth(1).locator('.activity-what')).toHaveText('Locked · "Data migration rehearsal"');
    await expect(rows.nth(2).locator('.activity-what')).toHaveText('Asked for operator access');
    await expect(rows.nth(2).locator('.activity-chip')).toHaveText('Sent');
    await expect(rows.nth(3).locator('.activity-what')).toContainText('Started report-worker');
    await expect(rows.nth(3).locator('.activity-note')).toHaveText('InsufficientInstanceCapacity for r6i.xlarge in ap-south-1b');
    await expect(rows.nth(3).locator('.activity-chip')).toHaveText('Failed');
    await expect(page.locator('#al-count')).toHaveText('4 actions in the last 7 days');

    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Export CSV' }).click();
    expect((await download).suggestedFilename()).toMatch(/^my-activity-\d{4}-\d{2}-\d{2}\.csv$/);
    await expect(page.getByText('Exported 4 actions.')).toBeVisible();
  });

  test('30 actions show 10 to a page and the page needs no scrollbar at 1440x900', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    const rows = Array.from({ length: 30 }, (_, i) => row({ action: 'LOCK_RELEASED', createdAt: at(Math.floor(i / 4), 18, 59 - i) }));
    const seen: URLSearchParams[] = [];
    await serve(page, rows, seen);
    await open(page);

    await expect(page.locator('.activity-row')).toHaveCount(10);
    await expect(page.locator('#al-count')).toHaveText('30 actions in the last 7 days');
    await expect(page.locator('#al-pagination')).toBeVisible();
    const fits = await page.evaluate(() => {
      const el = document.getElementById('content-area')!;
      return document.documentElement.scrollHeight <= window.innerHeight && el.scrollHeight <= el.clientHeight + 1;
    });
    expect(fits).toBe(true);

    await page.locator('#al-pagination').getByRole('button', { name: 'Next' }).click();
    await expect.poll(() => seen.at(-1)?.get('page')).toBe('1');
    await expect(page.locator('.activity-row')).toHaveCount(10);
  });

  test('a real lock reads as a sentence, and Access then shows nothing', async ({ page, playwright }) => {
    const envId = await seedEnvironment(playwright, 'Activity', { grantUser: true });
    test.skip(!envId, 'Needs the dev admin to seed an environment');
    const user = await playwright.request.newContext({ baseURL: BASE,
      extraHTTPHeaders: { 'X-User-Id': devIds.user, 'Content-Type': 'application/json' } });
    expect((await user.post(`/api/v1/environments/${envId}/lock/acquire`,
      { data: { reason: 'Activity e2e rehearsal', expectedDurationMinutes: 30 } })).ok()).toBe(true);
    await expect.poll(async () => (await (await user.get(`/api/v1/audit/logs/my?environmentId=${envId}&category=LOCKS`)).json())
      .page?.totalElements ?? 0).toBe(1);
    await user.post(`/api/v1/environments/${envId}/lock/release`);
    await user.dispose();

    await open(page);
    await expect(page.locator(`#al-environment-filter option[value="${envId}"]`)).toHaveCount(1);
    await page.locator('#al-environment-filter').selectOption(envId);
    await page.locator('#al-action-filter').selectOption({ label: 'Locks' });
    await expect(page.locator('.activity-what').filter({ hasText: 'Locked · "Activity e2e rehearsal"' })).toHaveCount(1);
    await expect(page.locator('.activity-chip').first()).toHaveText('Done');

    await page.locator('#al-action-filter').selectOption({ label: 'Access' });
    await expect(page.getByText('No actions match these filters')).toBeVisible();
    // U17-T01: the message names the filters it applies.
    await expect(page.locator('.ui-state-filtered .ui-state-text')).toContainText(/^Environment Activity .* · Access: nothing of this kind/);
    await page.getByRole('button', { name: 'Show all actions' }).click();
    await expect(page.locator('#al-action-filter')).toHaveValue('');
  });

  test("a 500 shows didn't load, Try again and the server's ref (U17-T01)", async ({ page }) => {
    let fail = true;
    await page.route('**/api/v1/audit/logs/my?**', (route: Route) => fail
      ? route.fulfill({ status: 500, json: { error: 'Internal Server Error', message: 'An unexpected error occurred.', ref: '7f3a92c1' } })
      : route.fulfill({ json: { content: [], page: { size: 10, number: 0, totalElements: 0, totalPages: 0 } } }));
    await open(page);
    const panel = page.locator('#al-results .ui-state-failed');
    await expect(panel).toBeVisible();
    await expect(panel).toHaveAttribute('role', 'alert');
    await expect(panel.locator('.ui-state-title')).toHaveText("Your activity didn't load");
    await expect(panel).toContainText('Something failed on the server. The rest of the page still works.');
    await expect(panel.locator('.ui-state-ref')).toHaveText('ref 7f3a92c1');
    fail = false;
    await panel.getByRole('button', { name: 'Try again' }).click();
    await expect(page.locator('#al-results .ui-state-empty')).toBeVisible();
  });

  test('loading outlines stay hidden for the first 300 ms, then show (U17-T01)', async ({ page }) => {
    await open(page);
    const visibility = await page.evaluate(async () => {
      const box = document.createElement('div');
      document.getElementById('content-area')!.appendChild(box);
      (window as any).UiStates.mountLoading(box, 'rows');
      const outline = box.querySelector('.ui-loading') as HTMLElement;
      const early = getComputedStyle(outline).visibility;
      await new Promise(r => setTimeout(r, 450));
      const later = getComputedStyle(outline).visibility;
      const busy = box.getAttribute('aria-busy');
      box.remove();
      return { early, later, busy };
    });
    expect(visibility).toEqual({ early: 'hidden', later: 'visible', busy: 'true' });
  });
});
