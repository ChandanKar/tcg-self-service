import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E04-T09 (M21): User Management loads inactive users too, so the Inactive filter lists them and
 * Reactivate is reachable; the default view still shows active users only.
 *
 * Needs a dev-mode admin (TEST_DEV_ADMIN_ID) to onboard and deactivate a user through the API.
 */

declare const ContentRouter: any;

let userId = '';
let email = '';

async function api(playwright: any): Promise<APIRequestContext> {
  return playwright.request.newContext({
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    extraHTTPHeaders: { 'X-User-Id': devIds.admin, 'Content-Type': 'application/json' },
  });
}

async function openUserManagement(page: Page) {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/user-management'; });
  await expect(page.locator('#user-search')).toBeVisible({ timeout: 10_000 });
  // Narrow the table to the seeded user (a search keeps the status filter). The search runs 300 ms
  // after typing and re-renders the table: let it settle, or it detaches a menu the test opens.
  await page.locator('#user-search').fill(email);
  await page.waitForTimeout(500);
}

function rowOf(page: Page) {
  return page.locator('#content-area tr', { hasText: email });
}

test.describe('User Management: inactive users', () => {
  test.beforeEach(async ({ playwright }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode) to seed a user');
    email = `dora-e2e-${Date.now()}@example.com`;
    const admin = await api(playwright);
    const onboarded = await admin.post('/api/v1/users', { data: { email, displayName: 'Dora E2E' } });
    expect(onboarded.ok()).toBeTruthy();
    userId = (await onboarded.json()).user.userId;
    expect((await admin.delete(`/api/v1/users/${userId}`)).ok()).toBeTruthy();
    await admin.dispose();
  });

  test('the default view shows active users only', async ({ page }) => {
    await openUserManagement(page);
    await expect(page.locator('#filter-status')).toHaveValue('active');
    await expect(rowOf(page)).toHaveCount(0);
  });

  test('Inactive lists the deactivated user; Reactivate moves them to Active without a reload',
    async ({ page, playwright }) => {
      await openUserManagement(page);
      await page.locator('#filter-status').selectOption('inactive');
      const row = rowOf(page);
      await expect(row).toHaveCount(1);

      // U13-T03: Reactivate is a link on the row (no menu); the status filter says Deactivated.
      await expect(page.locator('#filter-status option:checked')).toHaveText('Deactivated');
      await row.getByRole('button', { name: 'Reactivate Dora E2E' }).click();
      await expect(page.getByText('User reactivated')).toBeVisible({ timeout: 10_000 });

      // Same page, filters kept: gone from Inactive, present under Active.
      await expect(page.locator('#filter-status')).toHaveValue('inactive');
      await expect(rowOf(page)).toHaveCount(0);
      await page.locator('#filter-status').selectOption('active');
      await expect(rowOf(page)).toHaveCount(1);

      const admin = await api(playwright);
      const user = await (await admin.get(`/api/v1/users/${userId}`)).json();
      await admin.dispose();
      expect(user.active).toBe(true);
    });
});

/**
 * U13-T03 (Users.dc.html): counts, 10 people a page, plain role names, Signs in with, and the row's
 * own actions. The user list is faked so the counts are known.
 */
test.describe('Users list', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  function person(i: number, over: Record<string, unknown> = {}) {
    return { userId: `u-${i}`, email: `person${i}@x.com`, displayName: `Person ${String(i).padStart(2, '0')}`, admin: false,
      envAdmin: false, active: true, serviceAccount: false, pendingFirstLogin: false, signInMethod: 'MICROSOFT',
      signsInWithMicrosoft: true, lastLoginAt: new Date().toISOString(), ...over };
  }

  async function openList(page: Page, users: any[]) {
    await page.route(/\/api\/v1\/users\?includeInactive=true$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify(users) }));
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/user-management'; });
    await expect(page.locator('#users-table')).toBeVisible({ timeout: 10_000 });
  }

  function crowd() {
    const users = Array.from({ length: 36 }, (_, i) => person(i + 10));
    users.push(person(1, { displayName: 'Meera Iyer', admin: true, envAdmin: true }));
    users.push(person(2, { displayName: 'Sanjay Rao', envAdmin: true }));
    users.push(person(3, { displayName: 'Asha Admin', admin: true }));
    users.push(person(4, { displayName: 'Ravi Env', envAdmin: true }));
    users.push(person(5, { displayName: 'Ops service', signInMethod: 'PASSWORD', signsInWithMicrosoft: false }));
    users.push(person(6, { displayName: 'Divya Ghosh', pendingFirstLogin: true, lastLoginAt: null }));
    users.push(person(7, { displayName: 'Gone', active: false }));
    return users;
  }

  test('42 active people: counts, 10 rows and a pager without a page scrollbar at 1440x900', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await openList(page, crowd());
    await expect(page.locator('#um-counts span')).toHaveText(['42 active', '2 administrators', '2 environment admins', "1 haven't signed in yet"]);
    await expect(page.locator('#users-table-body tr')).toHaveCount(10);
    await expect(page.locator('#user-pagination .pager-range')).toHaveText('1–10 of 42');
    const overflow = await page.evaluate(() => document.documentElement.scrollHeight - window.innerHeight);
    expect(overflow).toBeLessThanOrEqual(0);
    await page.locator('#user-pagination [data-action="um-page"]', { hasText: 'Next' }).click();
    await expect(page).toHaveURL(/#\/user-management\?page=2$/);
  });

  test('row text and actions: role names, Set password for password sign-in, Access to Grant access', async ({ page }) => {
    await openList(page, crowd());
    await page.locator('#user-search').fill('Ops service');
    const ops = page.locator('#users-table-body tr', { hasText: 'Ops service' });
    await expect(ops.locator('td').nth(2)).toHaveText('Password');
    await expect(ops.getByRole('button', { name: 'Set password for Ops service' })).toBeVisible();
    await expect(ops.getByRole('link', { name: /^Access for/ })).toHaveCount(0);

    await page.locator('#user-search').fill('Divya');
    const divya = page.locator('#users-table-body tr', { hasText: 'Divya Ghosh' });
    await expect(divya.locator('.um-not-yet')).toHaveText('Not signed in yet');
    await expect(divya.getByRole('link', { name: 'Access for Divya Ghosh' }))
      .toHaveAttribute('href', '#/access-management/grant?email=person6%40x.com&name=Divya%20Ghosh');

    await page.locator('#user-search').fill('Meera');
    const meera = page.locator('#users-table-body tr', { hasText: 'Meera Iyer' });
    await expect(meera.locator('td').nth(1)).toHaveText('Administrator');
    await expect(meera.getByRole('button', { name: 'Change role of Meera Iyer' })).toBeVisible();
    await expect(meera.getByRole('button', { name: 'Deactivate Meera Iyer' })).toBeVisible();
  });

  test('the Environment admin filter lists environment admins who are not administrators, and stays in the address', async ({ page }) => {
    await openList(page, crowd());
    await page.locator('#filter-role').selectOption('ENV_ADMIN');
    await expect(page.locator('#users-table-body tr')).toHaveCount(2);
    await expect(page.locator('#users-table-body')).toContainText('Sanjay Rao');
    await expect(page.locator('#users-table-body')).toContainText('Ravi Env');
    await expect(page.locator('#users-table-body')).not.toContainText('Meera Iyer');
    await expect(page).toHaveURL(/role=ENV_ADMIN/);
    await page.reload();
    await expect(page.locator('#filter-role')).toHaveValue('ENV_ADMIN', { timeout: 10_000 });
    await expect(page.locator('#users-table-body tr')).toHaveCount(2);
  });

  test('Change role sends both role flags', async ({ page }) => {
    const users = crowd();
    await openList(page, users);
    let sent: any = null;
    await page.route(/\/api\/v1\/users\/u-2\/roles$/, route => {
      sent = route.request().postDataJSON();
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...users[37], admin: true }) });
    });
    await page.locator('#user-search').fill('Sanjay');
    await page.getByRole('button', { name: 'Change role of Sanjay Rao' }).click();
    await page.locator('#changeRoleModal input[name="um-role"][value="ADMIN"]').check();
    await page.locator('#um-role-save').click();
    await expect.poll(() => sent).toEqual({ admin: true, envAdmin: false });
  });
});
