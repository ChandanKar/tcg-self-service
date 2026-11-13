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

  test('row text and actions: role names, Reset password for password sign-in, Access to Grant access', async ({ page }) => {
    await openList(page, crowd());
    await page.locator('#user-search').fill('Ops service');
    const ops = page.locator('#users-table-body tr', { hasText: 'Ops service' });
    await expect(ops.locator('td').nth(2)).toHaveText('Password');
    await expect(ops.getByRole('button', { name: 'Reset password for Ops service' })).toBeVisible();
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

  /** U13-T04 (UserDialogs.dc.html). */
  test('Change role: three choices, one request with both flags; the last administrator stays', async ({ page }) => {
    const users = crowd();
    await openList(page, users);
    const sent: any[] = [];
    await page.route(/\/api\/v1\/users\/u-(2|1)\/roles$/, route => {
      sent.push(route.request().postDataJSON());
      if (route.request().url().includes('/u-1/')) {
        return route.fulfill({ status: 400, contentType: 'application/json',
          body: JSON.stringify({ error: 'Validation', message: 'There must always be at least one administrator.' }) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...users[37], admin: true, envAdmin: false }) });
    });
    await page.locator('#user-search').fill('Sanjay');
    await page.getByRole('button', { name: 'Change role of Sanjay Rao' }).click();
    const dialog = page.locator('#changeRoleModal');
    await expect(dialog.locator('.modal-title')).toHaveText('Change role for Sanjay Rao');
    await expect(dialog).toContainText('person2@x.com · signs in with Microsoft');
    await expect(dialog.locator('.um-role-choice b')).toHaveText(['User', 'Environment admin', 'Administrator']);
    await expect(dialog.locator('input[value="ENV_ADMIN"]')).toBeChecked();
    await expect(dialog).toContainText('Sees instance IDs.');
    await dialog.locator('input[value="ADMIN"]').check();
    await dialog.getByRole('button', { name: 'Save role' }).click();
    await expect(page.getByText('Role saved.')).toBeVisible();
    expect(sent).toEqual([{ admin: true, envAdmin: false }]);

    await page.locator('#user-search').fill('Meera');
    await page.getByRole('button', { name: 'Change role of Meera Iyer' }).click();
    await page.locator('#changeRoleModal input[value="USER"]').check();
    await page.locator('#changeRoleModal').getByRole('button', { name: 'Save role' }).click();
    await expect(page.locator('#um-role-error')).toHaveText('There must always be at least one administrator.');
    await expect(page.locator('#changeRoleModal')).toBeVisible();
  });

  /** U25-T04: the server makes the password; the dialog asks first, then shows it once. */
  test('Reset password: asks first, then shows the username and the generated password once', async ({ page }) => {
    await openList(page, crowd());
    const calls: string[] = [];
    await page.route(/\/api\/v1\/users\/u-5\/password\/reset$/, route => {
      calls.push(route.request().method());
      return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ username: 'person5@x.com', password: 'Kq7-hT2m.Wx9_pRz' }) });
    });
    await page.locator('#user-search').fill('Ops');
    await page.getByRole('button', { name: 'Reset password for Ops service' }).click();
    const dialog = page.locator('#resetPasswordModal');
    await expect(dialog.locator('.modal-title')).toHaveText('Reset the password for Ops service?');
    await expect(dialog).toContainText('They will be signed out everywhere and choose a new password at their next sign-in.');
    await expect(dialog.locator('#um-reset-password')).toBeHidden();
    expect(calls).toEqual([]);

    await dialog.getByRole('button', { name: 'Reset password' }).click();
    await expect(dialog.locator('.modal-title')).toHaveText('New password for Ops service');
    await expect(dialog.locator('#um-reset-username')).toHaveText('person5@x.com');
    await expect(dialog.locator('#um-reset-password')).toHaveText('Kq7-hT2m.Wx9_pRz');
    await expect(dialog).toContainText('Shown once. Pass it on; they choose their own at next sign-in.');
    await expect(dialog.getByRole('button', { name: 'Copy the password' })).toBeVisible();
    await expect(dialog.getByRole('button', { name: 'Cancel' })).toBeHidden();
    expect(calls).toEqual(['POST']);

    await dialog.getByRole('button', { name: 'Done' }).click();
    await expect(dialog).toHaveCount(0);
    await expect(page.getByText('Kq7-hT2m.Wx9_pRz')).toHaveCount(0);
  });

  test('Reset password: Cancel changes nothing; a refusal shows in the dialog', async ({ page }) => {
    await openList(page, crowd());
    let calls = 0;
    await page.route(/\/api\/v1\/users\/u-5\/password\/reset$/, route => {
      calls++;
      return route.fulfill({ status: 400, contentType: 'application/json',
        body: JSON.stringify({ error: 'Validation', message: 'This person has no email address to sign in with' }) });
    });
    await page.locator('#user-search').fill('Ops');
    await page.getByRole('button', { name: 'Reset password for Ops service' }).click();
    const dialog = page.locator('#resetPasswordModal');
    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(dialog).toHaveCount(0);
    expect(calls).toBe(0);

    await page.getByRole('button', { name: 'Reset password for Ops service' }).click();
    await dialog.getByRole('button', { name: 'Reset password' }).click();
    await expect(dialog.locator('#um-reset-error')).toHaveText('This person has no email address to sign in with');
    await expect(dialog.getByRole('button', { name: 'Reset password' })).toBeEnabled();
    expect(calls).toBe(1);
  });

  test('Reset password fits a phone: no sideways scroll, 44 px buttons', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await openList(page, crowd());
    await page.route(/\/api\/v1\/users\/u-5\/password\/reset$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ username: 'a-very-long-username-for-a-phone@example-company.co.uk', password: 'Kq7-hT2m.Wx9_pRz' }) }));
    await page.locator('#user-search').fill('Ops');
    await page.getByRole('button', { name: 'Reset password for Ops service' }).click();
    const dialog = page.locator('#resetPasswordModal');
    await dialog.getByRole('button', { name: 'Reset password' }).click();
    await expect(dialog.locator('#um-reset-password')).toHaveText('Kq7-hT2m.Wx9_pRz');
    const wide = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(wide).toBeLessThanOrEqual(0);
    for (const name of ['Copy the password', 'Done']) {
      const box = await dialog.getByRole('button', { name }).boundingBox();
      expect(box!.height).toBeGreaterThanOrEqual(44);
    }
  });

  test('Deactivate explains itself and is not offered for yourself', async ({ page }) => {
    const users = crowd();
    users.push(person(99, { userId: devIds.admin, displayName: 'Test Admin', admin: true }));
    await openList(page, users);
    await page.locator('#user-search').fill('Test Admin');
    await page.getByRole('button', { name: 'Deactivate Test Admin' }).click();
    const dialog = page.locator('#deactivateUserModal');
    await expect(dialog.locator('.modal-title')).toHaveText('Deactivate Test Admin?');
    await expect(dialog).toContainText('Test can no longer sign in. The audit log keeps the account');
    await expect(dialog.locator('#um-deactivate-why')).toHaveText("You can't deactivate yourself.");
    await expect(dialog.getByRole('button', { name: 'Deactivate' })).toBeDisabled();
    await dialog.getByRole('button', { name: 'Cancel' }).click();

    let deleted = '';
    await page.route(/\/api\/v1\/users\/u-10$/, route => {
      deleted = route.request().method();
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    });
    await page.locator('#user-search').fill('Person 10');
    await page.getByRole('button', { name: 'Deactivate Person 10' }).click();
    await page.locator('#deactivateUserModal').getByRole('button', { name: 'Deactivate' }).click();
    await expect(page.getByText('Person 10 deactivated.')).toBeVisible();
    expect(deleted).toBe('DELETE');
  });
});
