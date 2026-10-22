import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E04-T06 (H15): editing a grant keeps its expiry unless the admin picks another one. The
 * modal used to reset Duration to "Permanent", so saving a notes-only edit cleared the expiry.
 * U10-T05 (AccessChange.dc.html): changing is the "Change access for <name>" dialog (level cards,
 * Ends: keep / 30 / 90 days / no end date, a note) and revoking the "Remove <name>'s access?" one.
 *
 * Needs dev-mode identities (TEST_DEV_USER_ID / TEST_DEV_ADMIN_ID) to seed a grant through the API.
 */

declare const AccessManagement: any;
declare const AccessChangeDialogs: any;
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
  await expect(page.locator('#accessChangeModal')).toBeVisible();
}

/** Save the Change access dialog and return the PATCH body it sent. */
async function saveAndCapture(page: Page): Promise<any> {
  const [request] = await Promise.all([
    page.waitForRequest(r => r.method() === 'PATCH' && r.url().includes(`/access-grants/${accessId}`)),
    page.locator('#acd-save').click(),
  ]);
  await expect(page.locator('#accessChangeModal')).toBeHidden({ timeout: 10_000 });
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

  test('opens on the current level and "Keep <date>", with what the grant is today', async ({ page }) => {
    await openEdit(page);
    const dialog = page.locator('#accessChangeModal');
    await expect(dialog.locator('.modal-title')).toHaveText(/^Change access for /);
    await expect(dialog.locator('.acd-sub')).toHaveText(/ · View only · ends \d{1,2} \w{3}/);
    await expect(dialog.getByRole('radio', { name: /View only/ })).toBeChecked();
    await expect(dialog.locator('.acd-level b')).toHaveText(['View only', 'Operator', 'Admin']);
    await expect(page.locator('#acd-ends')).toHaveValue('keep');
    await expect(page.locator('#acd-ends option').first()).toHaveText(/^Keep \d{1,2} \w{3}/);
  });

  test('a notes-only edit sends no expiry change and keeps the expiry', async ({ page, playwright }) => {
    const before = (await storedGrant(playwright)).expiresAt;
    expect(before).toBeTruthy();

    await openEdit(page);
    await page.locator('#acd-note').fill('Moved to the payments team');
    const body = await saveAndCapture(page);

    expect(body).toEqual({ accessLevel: 'VIEWER', notes: 'Moved to the payments team' });
    const after = await storedGrant(playwright);
    expect(after.expiresAt).toBe(before);
    expect(after.notes).toBe('Moved to the payments team');
  });

  test('a new level and "90 days from today" are sent and the row follows', async ({ page, playwright }) => {
    await openEdit(page);
    await page.locator('#accessChangeModal').getByRole('radio', { name: /Operator/ }).check();
    await page.locator('#acd-ends').selectOption('90');
    const body = await saveAndCapture(page);

    expect(body).toEqual({ accessLevel: 'USER', durationDays: 90 });
    const after = await storedGrant(playwright);
    expect(after.accessLevel).toBe('USER');
    expect(Math.abs(new Date(after.expiresAt).getTime() - (Date.now() + 90 * DAY_MS))).toBeLessThan(5 * 60_000);
    await expect(page.locator(`tr[data-access-id="${accessId}"] .access-level-badge`)).toHaveText('Operator');
  });

  test('"No end date" clears the expiry, and the next change offers "Keep no end date"', async ({ page, playwright }) => {
    await openEdit(page);
    await page.locator('#acd-ends').selectOption('none');
    const body = await saveAndCapture(page);

    expect(body.clearExpiry).toBe(true);
    expect((await storedGrant(playwright)).expiresAt).toBeNull();

    await page.locator(`[data-action="edit"][data-access-id="${accessId}"]`).click();
    await expect(page.locator('#acd-ends option').first()).toHaveText('Keep no end date');
    await expect(page.locator('#acd-ends option[value="none"]')).toHaveCount(0);
    await expect(page.locator('#acd-ends')).toHaveValue('keep');
  });

  test('saving without a change sends nothing', async ({ page }) => {
    await openEdit(page);
    let patched = false;
    page.on('request', r => { if (r.method() === 'PATCH') patched = true; });
    await page.locator('#acd-save').click();
    await expect(page.locator('#accessChangeModal')).toBeHidden();
    expect(patched).toBe(false);
  });

  test('one\'s own access cannot be changed: the notice and no Save', async ({ page }) => {
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof AccessChangeDialogs !== 'undefined');
    await page.evaluate(() => AccessChangeDialogs.openChange({ accessId: 'x', userDisplayName: 'Test Admin',
      environmentName: 'Analytics QA', accessLevel: 'ADMIN', scopeType: 'ENVIRONMENT' }, null, { mine: true }));
    await expect(page.locator('#accessChangeModal .acd-self')).toHaveText("You can't change your own access; another admin has to.");
    await expect(page.locator('#acd-save')).toBeDisabled();
  });

  test('Grant access from an environment opens the Grant access page with it chosen (U10-T06)', async ({ page }) => {
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof AccessManagement !== 'undefined');
    await expect(page.locator('#content-area h1').first()).toBeVisible({ timeout: 15_000 });
    await page.evaluate((id) => AccessManagement.openForEnvironment(id), envId);
    await expect(page.locator('#content-area h1')).toHaveText('Grant access', { timeout: 10_000 });
    await expect(page.locator(`[data-ga-env-pick="${envId}"]`)).toBeChecked();
    await expect(page.locator('#ga-duration')).toHaveValue('30');
  });

  test('Remove access: Keep access sends nothing; Remove access revokes the grant', async ({ page, playwright }) => {
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined' && typeof AccessManagement !== 'undefined');
    await page.evaluate(() => { location.hash = '#/access-management'; });
    await page.locator('#filter-environment').selectOption(envId);
    const revoke = page.locator(`[data-action="revoke"][data-access-id="${accessId}"]`);
    await expect(revoke).toBeVisible({ timeout: 10_000 });

    let deleted = 0;
    page.on('request', r => { if (r.method() === 'DELETE') deleted++; });
    await revoke.click();
    const dialog = page.locator('#accessRemoveModal');
    await expect(dialog).toHaveAttribute('role', 'alertdialog');
    await expect(dialog.locator('.modal-title')).toHaveText(/^Remove .+'s access\?$/);
    await expect(dialog).toContainText('can no longer see or use');
    await expect(dialog).toContainText('To give access back, grant it again.');
    await dialog.getByRole('button', { name: 'Keep access' }).click();
    await expect(dialog).toBeHidden();
    expect(deleted).toBe(0);

    await revoke.click();
    await page.locator('#accessRemoveModal').getByRole('button', { name: 'Remove access' }).click();
    await expect(page.locator('#accessRemoveModal')).toBeHidden({ timeout: 10_000 });
    await expect(page.locator(`tr[data-access-id="${accessId}"]`)).toHaveCount(0);
    expect((await storedGrant(playwright))).toBeUndefined();
  });
});

/**
 * U10-T06 (GrantAccess.dc.html): Grant access is a page. People as chips (from the user search),
 * environments whole or by group, the level and how long, and a preview of every person x
 * environment before "Grant access (N)". The preview and the people search are faked in the
 * first test; the second grants for real.
 */
test.describe('Grant access page', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !devIds.user || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID and TEST_DEV_USER_ID (dev mode)');
  });

  async function openGrant(page: Page, hash = '#/access-management/grant') {
    await page.setViewportSize({ width: 1440, height: 900 });
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await expect(page.locator('#content-area h1').first()).toBeVisible({ timeout: 15_000 });
    await page.evaluate((h) => { location.hash = h; }, hash);
    await expect(page.locator('#content-area h1')).toHaveText('Grant access', { timeout: 15_000 });
  }

  async function addPerson(page: Page, query: string, name: RegExp) {
    await page.locator('#ga-person-search').fill(query);
    await page.locator('#ga-person-menu').getByRole('option', { name }).click();
  }

  test('the preview tags each person and environment and the button counts the changes', async ({ page, playwright }) => {
    const seeding = await api(playwright);
    const env = await (await seeding.post('/api/v1/environments', {
      data: { name: `grant-preview-${Date.now()}`, displayName: `Grant Preview ${Date.now()}`, cloudProvider: 'AWS' } })).json();
    await seeding.dispose();
    const previews: any[] = [];
    await page.route(/\/api\/v1\/users\/search/, route => route.fulfill({ json: [
      { userId: 'p1', email: 'priya@example.com', displayName: 'Priya Nair' },
      { userId: 'p2', email: 'neha@example.com', displayName: 'Neha Gupta' }] }));
    await page.route(/\/api\/v1\/access-grants\/bulk\/preview$/, route => {
      previews.push(route.request().postDataJSON());
      const row = (userId: string, userName: string, environmentId: string, scopeName: string, outcome: string, extra = {}) => ({
        userId, userName, environmentId, scopeName, scopeType: 'ENVIRONMENT', outcome, resultLevel: 'USER',
        resultExpiresAt: new Date(Date.now() + 30 * DAY_MS).toISOString(), ...extra });
      return route.fulfill({ json: { people: 2, environments: 2, changes: 3, skipped: 1, rows: [
        row('p1', 'Priya Nair', 'e1', 'Payments Dev', 'NEW'),
        row('p1', 'Priya Nair', 'e2', 'CRM UAT', 'EXTEND', { currentLevel: 'USER', currentExpiresAt: new Date(Date.now() + 2 * DAY_MS).toISOString() }),
        row('p2', 'Neha Gupta', 'e1', 'Payments Dev', 'SKIP', { reason: 'Already has Admin, which is more' }),
        row('p2', 'Neha Gupta', 'e2', 'CRM UAT', 'UPGRADE', { currentLevel: 'VIEWER' })] } });
    });
    await openGrant(page, `#/access-management/grant?environmentId=${env.environmentId}`);
    await expect(page.locator('#ga-grant')).toHaveText('Nothing to grant');
    await expect(page.locator('#ga-grant')).toBeDisabled();

    await addPerson(page, 'pri', /Priya Nair/);
    await addPerson(page, 'neh', /Neha Gupta/);
    await expect(page.locator('.ga-chip')).toHaveCount(2);
    await page.locator('[data-ga-level="ADMIN"]').click();
    await page.locator('#ga-duration').selectOption('7');

    await expect(page.locator('.ga-row')).toHaveCount(4);
    await expect(page.locator('.ga-tag')).toHaveText(['New', 'Extends', 'No change', 'Upgrades']);
    await expect(page.locator('.ga-row[data-outcome="SKIP"] .ga-row-detail')).toHaveText('Already has Admin, which is more');
    await expect(page.locator('#ga-summary')).toHaveText('2 people × 2 environments: 3 changes, 1 skipped');
    await expect(page.locator('#ga-grant')).toHaveText('Grant access (3)');
    await expect(page.locator('#ga-grant')).toBeEnabled();
    // The page fits 1440x900 (the preview list scrolls inside its panel).
    expect(await page.evaluate(() => {
      const main = document.getElementById('mainContent')!;
      return main.scrollHeight <= main.clientHeight + 1;
    })).toBe(true);
    // The preview is debounced: wait for the request that carries the last choices.
    await expect.poll(() => previews.at(-1)).toMatchObject({ userEmails: ['priya@example.com', 'neha@example.com'],
      accessLevel: 'ADMIN', durationDays: 7, targets: [{ environmentId: env.environmentId, scopeType: 'ENVIRONMENT' }] });

    await page.locator('.ga-chip', { hasText: 'Neha Gupta' }).getByRole('button', { name: 'Remove Neha Gupta' }).click();
    await expect(page.locator('.ga-chip')).toHaveCount(1);
    await expect.poll(() => previews.at(-1).userEmails).toEqual(['priya@example.com']);
  });

  test('granting for real adds the access and goes back to the list', async ({ page, playwright }) => {
    const admin = await api(playwright);
    const env = await (await admin.post('/api/v1/environments', {
      data: { name: `grant-page-${Date.now()}`, displayName: `Grant Page ${Date.now()}`, cloudProvider: 'AWS' } })).json();
    const user = await (await admin.get(`/api/v1/users/${devIds.user}`)).json();
    await admin.dispose();

    await openGrant(page, `#/access-management/grant?environmentId=${env.environmentId}`);
    await addPerson(page, user.email.slice(0, 6), new RegExp(user.email.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
    await expect(page.locator('.ga-tag')).toHaveText(['New']);
    await page.locator('#ga-grant').click();
    await expect(page).toHaveURL(/#\/access-management$/, { timeout: 10_000 });
    await expect(page.getByText('Access granted to 1 person.')).toBeVisible();

    const check = await api(playwright);
    const grants = await (await check.get(`/api/v1/environments/${env.environmentId}/access`)).json();
    await check.dispose();
    const g = grants.find((x: any) => x.userId === devIds.user);
    expect(g.accessLevel).toBe('USER');
    expect(Math.abs(new Date(g.expiresAt).getTime() - (Date.now() + 30 * DAY_MS))).toBeLessThan(5 * 60_000);
  });

  test('users cannot open it', async ({ page }) => {
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/access-management/grant'; });
    await expect(page.locator('[data-no-access="role-env-admin"] h1')).toHaveText('Grant access is for environment admins', { timeout: 15_000 });
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
    // Let the first page finish opening, or the router can load Access management twice.
    await expect(page.locator('#content-area h1').first()).toBeVisible({ timeout: 15_000 });
    await page.waitForLoadState('networkidle');
    lists.length = 0;
    summaries = 0;
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
    await page.locator('#access-pagination').getByRole('button', { name: 'Next' }).click();
    await expect.poll(() => calls.lists.at(-1)?.get('page')).toBe('1');

    await page.locator('#access-search').fill('priya');
    await expect.poll(() => calls.lists.at(-1)?.get('search')).toBe('priya');
    expect(calls.lists.at(-1)?.get('page')).toBe('0');
    expect(calls.perEnvironment()).toBe(0);
  });
});
