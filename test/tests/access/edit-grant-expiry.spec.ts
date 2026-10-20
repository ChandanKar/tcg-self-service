import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E04-T06 (H15): editing a grant keeps its expiry unless the admin picks another one. The
 * modal used to reset Duration to "Permanent", so saving a notes-only edit cleared the expiry.
 *
 * Needs dev-mode identities (TEST_DEV_USER_ID / TEST_DEV_ADMIN_ID) to seed a grant through the API.
 */

declare const AccessManagement: any;
declare const ContentRouter: any;

const DAY_MS = 24 * 60 * 60 * 1000;
let envId = '';
let accessId = '';

async function api(playwright: any): Promise<APIRequestContext> {
  return playwright.request.newContext({
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    extraHTTPHeaders: { 'X-User-Id': devIds.admin, 'Content-Type': 'application/json' },
  });
}

async function storedGrant(playwright: any): Promise<any> {
  const admin = await api(playwright);
  const grants = await (await admin.get(`/api/v1/environments/${envId}/access`)).json();
  await admin.dispose();
  return grants.find((g: any) => g.accessId === accessId);
}

async function openEdit(page: Page) {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined' && typeof AccessManagement !== 'undefined');
  await page.evaluate(() => { location.hash = '#/access-management'; });
  await page.locator('#filter-environment').selectOption(envId);
  const edit = page.locator(`[data-action="edit"][data-access-id="${accessId}"]`);
  await expect(edit).toBeVisible({ timeout: 10_000 });
  await edit.click();
  await expect(page.locator('#grantAccessModal')).toBeVisible();
}

/** Save the modal and return the PATCH body it sent. */
async function saveAndCapture(page: Page): Promise<any> {
  const [request] = await Promise.all([
    page.waitForRequest(r => r.method() === 'PATCH' && r.url().includes(`/access-grants/${accessId}`)),
    page.locator('#btn-confirm-grant').click(),
  ]);
  await expect(page.locator('#grantAccessModal')).toBeHidden({ timeout: 10_000 });
  return request.postDataJSON();
}

test.describe.serial('Edit grant: expiry is kept unless changed', () => {
  test.beforeAll(async ({ playwright }) => {
    if (!devIds.admin || !devIds.user) return;
    const admin = await api(playwright);
    const created = await admin.post('/api/v1/environments', {
      data: { name: `edit-grant-e2e-${Date.now()}`, displayName: `Edit Grant E2E ${Date.now()}`, cloudProvider: 'AWS' },
    });
    expect(created.ok()).toBeTruthy();
    envId = (await created.json()).environmentId;
    const user = await (await admin.get(`/api/v1/users/${devIds.user}`)).json();
    const granted = await admin.post(`/api/v1/environments/${envId}/access`, {
      data: { userEmail: user.email, accessLevel: 'VIEWER', durationDays: 30 },
    });
    expect(granted.ok()).toBeTruthy();
    accessId = (await granted.json()).accessId;
    await admin.dispose();
  });

  test.beforeEach(async () => {
    test.skip(!devIds.admin || !devIds.user || !canSignIn('admin'),
      'Needs TEST_DEV_USER_ID and TEST_DEV_ADMIN_ID (dev mode) to seed a grant');
  });

  test('opens with "Keep current expiry" selected and shows the current expiry', async ({ page }) => {
    await openEdit(page);
    await expect(page.locator('#grant-duration')).toHaveValue('keep');
    await expect(page.locator('#grant-current-expiry')).toContainText('Currently expires');
  });

  test('a notes-only edit sends no expiry change and keeps the expiry', async ({ page, playwright }) => {
    const before = (await storedGrant(playwright)).expiresAt;
    expect(before).toBeTruthy();

    await openEdit(page);
    await page.locator('#grant-notes').fill('Moved to the payments team');
    const body = await saveAndCapture(page);

    expect(body.durationDays).toBeNull();
    expect(body.clearExpiry).toBeNull();
    const after = await storedGrant(playwright);
    expect(after.expiresAt).toBe(before);
    expect(after.notes).toBe('Moved to the payments team');
  });

  test('"30 days from today" sets the expiry 30 days from now', async ({ page, playwright }) => {
    await openEdit(page);
    await page.locator('#grant-duration').selectOption('30');
    const body = await saveAndCapture(page);

    expect(body.durationDays).toBe(30);
    expect(body.clearExpiry).toBeNull();
    const after = await storedGrant(playwright);
    expect(Math.abs(new Date(after.expiresAt).getTime() - (Date.now() + 30 * DAY_MS))).toBeLessThan(5 * 60_000);
  });

  test('"Permanent" clears the expiry, and the next edit says it is permanent', async ({ page, playwright }) => {
    await openEdit(page);
    await page.locator('#grant-duration').selectOption('permanent');
    const body = await saveAndCapture(page);

    expect(body.clearExpiry).toBe(true);
    expect((await storedGrant(playwright)).expiresAt).toBeNull();

    await page.locator(`[data-action="edit"][data-access-id="${accessId}"]`).click();
    await expect(page.locator('#grant-current-expiry')).toHaveText('Currently permanent');
    await expect(page.locator('#grant-duration')).toHaveValue('keep');
  });

  test('a new grant defaults to Permanent and cannot pick "Keep current expiry"', async ({ page }) => {
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof AccessManagement !== 'undefined');
    await page.evaluate((id) => AccessManagement.openForEnvironment(id), envId);
    await expect(page.locator('#grantAccessModal')).toBeVisible({ timeout: 10_000 });

    await expect(page.locator('#grant-duration')).toHaveValue('permanent');
    await expect(page.locator('#grant-duration option[value="keep"]')).toBeDisabled();
    await expect(page.locator('#grant-current-expiry')).toHaveText('');
  });
});

/**
 * U10-T04 (AccessManagement.dc.html): the grants list is one paged request to /access-grants plus
 * /access-grants/summary (no request per environment), with the counts, the Ending filter, the
 * History tab, Change / Revoke per row and "Your access" on one's own row. The API is faked.
 */
test.describe('Access management list', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  const soon = new Date(Date.now() + 2 * DAY_MS).toISOString();
  const later = new Date(Date.now() + 60 * DAY_MS).toISOString();
  const grant = (i: number, extra: Record<string, unknown> = {}) => ({
    accessId: `a-${i}`, userId: `u-${i}`, userDisplayName: `Person ${i}`, userEmail: `person${i}@example.com`,
    environmentId: 'env-am', environmentName: 'Analytics QA', accessLevel: i % 3 === 0 ? 'ADMIN' : 'USER',
    status: 'ACTIVE', scopeType: i === 2 ? 'GROUP' : 'ENVIRONMENT', scopeId: i === 2 ? 'g-1' : 'env-am',
    scopeName: i === 2 ? 'Application' : 'Analytics QA', grantedByUserName: 'Sanjay Rao',
    expiresAt: i === 1 ? soon : i === 4 ? null : later, ...extra,
  });

  async function fake(page: Page) {
    const lists: URLSearchParams[] = [];
    let summaries = 0;
    let perEnvironment = 0;
    await page.route(/\/api\/v1\/environments(\?.*)?$/, route => route.fulfill({ json: [
      { environmentId: 'env-am', name: 'analytics-qa', displayName: 'Analytics QA', viewerCanManageAccess: true }] }));
    await page.route(/\/api\/v1\/environments\/[^/]+\/access$/, route => { perEnvironment++; return route.fulfill({ json: [] }); });
    await page.route(/\/api\/v1\/access-grants\/summary/, route => { summaries++; return route.fulfill({ json:
      { total: 29, admins: 6, operators: 18, viewers: 5, endingWithin7Days: 3 } }); });
    await page.route(/\/api\/v1\/access-grants\?/, route => {
      const p = new URL(route.request().url()).searchParams;
      lists.push(p);
      const page = Number(p.get('page') || 0);
      const rows = Array.from({ length: 29 }, (_, i) => grant(i + 1, i === 5 ? { userId: devIds.admin } : {}))
        .slice(page * 10, page * 10 + 10);
      return route.fulfill({ json: { content: rows, page: { size: 10, number: page, totalElements: 29, totalPages: 3 } } });
    });
    await page.setViewportSize({ width: 1440, height: 900 });
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/access-management'; });
    await expect(page.locator('#access-table-body tr')).toHaveCount(10, { timeout: 15_000 });
    return { lists, summaries: () => summaries, perEnvironment: () => perEnvironment };
  }

  test('one paged request and the counts; rows read Person, Environment, Level, Part, Granted by, Ends', async ({ page }) => {
    const calls = await fake(page);
    expect(calls.lists).toHaveLength(1);
    expect(calls.lists[0].get('status')).toBe('ACTIVE');
    expect(calls.lists[0].get('size')).toBe('10');
    expect(calls.summaries()).toBe(1);
    expect(calls.perEnvironment()).toBe(0);

    await expect(page.locator('#content-area h1')).toHaveText('Access management');
    await expect(page.locator('#am-stats')).toHaveText(/29 grants\s*6 admins\s*18 operators\s*5 view only\s*3 end within 7 days/);
    await expect(page.locator('#access-table-head th')).toHaveText(['Person', 'Environment', 'Level', 'Part', 'Granted by', 'Ends', 'Actions']);
    const first = page.locator('#access-table-body tr').first();
    await expect(first.locator('td').nth(2)).toHaveText('Operator');
    await expect(first.locator('.am-ends')).toHaveClass(/soon/);
    await expect(page.locator('#access-table-body tr').nth(1).locator('td').nth(3)).toHaveText('Application group');
    await expect(page.locator('#access-table-body tr').nth(3).locator('.am-ends')).toHaveText('No end');
    await expect(first.getByRole('button', { name: /^Change Person 1's access/ })).toBeVisible();
    await expect(first.getByRole('button', { name: /^Revoke Person 1's access/ })).toBeVisible();
    // One's own grant cannot be changed here.
    const mine = page.locator('#access-table-body tr').nth(5);
    await expect(mine.locator('.am-own')).toHaveText('Your access');
    await expect(mine.locator('[data-action="edit"]')).toHaveCount(0);

    // Fits 1440x900 without a page scrollbar.
    expect(await page.evaluate(() => {
      const main = document.getElementById('mainContent')!;
      return main.scrollHeight <= main.clientHeight + 1 && document.documentElement.scrollHeight <= window.innerHeight;
    })).toBe(true);
  });

  test('the Ending count, the History tab, paging and search ask the server', async ({ page }) => {
    const calls = await fake(page);

    await page.locator('#am-ending').click();
    await expect.poll(() => calls.lists.at(-1)?.get('status')).toBe('ENDING');
    await expect(page.locator('#filter-status')).toHaveValue('ENDING');

    await page.getByRole('tab', { name: 'History' }).click();
    await expect.poll(() => calls.lists.at(-1)?.get('status')).toBe('ENDED');
    await expect(page.locator('#access-table-head th').last()).toHaveText('Ended');
    await expect(page.locator('#filter-status')).toBeHidden();

    await page.getByRole('tab', { name: 'Grants' }).click();
    await page.locator('#access-pagination').getByRole('button', { name: /^(2|Page 2|Go to page 2)$/ }).first().click();
    await expect.poll(() => calls.lists.at(-1)?.get('page')).toBe('1');

    await page.locator('#access-search').fill('priya');
    await expect.poll(() => calls.lists.at(-1)?.get('search')).toBe('priya');
    expect(calls.lists.at(-1)?.get('page')).toBe('0');
    expect(calls.perEnvironment()).toBe(0);
  });
});
