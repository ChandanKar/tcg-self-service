import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

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

  test('Set password: live checks, enabled only when both pass', async ({ page }) => {
    await openList(page, crowd());
    let body: any = null;
    await page.route(/\/api\/v1\/users\/u-5\/password$/, route => {
      body = route.request().postDataJSON();
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    });
    await page.locator('#user-search').fill('Ops');
    await page.getByRole('button', { name: 'Set password for Ops service' }).click();
    const dialog = page.locator('#setPasswordModal');
    await expect(dialog.locator('.modal-title')).toHaveText('Set a password for Ops service');
    const submit = dialog.getByRole('button', { name: 'Set password' });
    await expect(submit).toBeDisabled();
    await dialog.getByLabel('New password').fill('correct-horse-battery');
    await dialog.getByLabel('Type it again').fill('correct-horse-batter');
    await expect(dialog.locator('[data-check="length"]')).toHaveClass(/um-check-ok/);
    await expect(dialog.locator('[data-check="match"]')).not.toHaveClass(/um-check-ok/);
    await expect(submit).toBeDisabled();
    await dialog.getByLabel('Type it again').fill('correct-horse-battery');
    await expect(dialog.locator('[data-check="match"]')).toHaveClass(/um-check-ok/);
    await submit.click();
    await expect(page.getByText('Password set.')).toBeVisible();
    expect(body).toEqual({ password: 'correct-horse-battery' });
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

/**
 * U13-T05 (AddUsers.dc.html): Add people is a page. People by hand (several pasted at once) or from
 * the directory, one role, access rows, a summary from the server's preview, and one commit.
 * Runs against the smoke app (directory lookup off there; the directory test fakes it).
 */
test.describe('Add people', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  async function openAdd(page: Page) {
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/user-management'; });
    await page.getByRole('link', { name: 'Add people' }).click();
    await expect(page).toHaveURL(/#\/user-management\/add$/);
    await expect(page.locator('#add-people-view h1')).toHaveText('Add people', { timeout: 10_000 });
  }

  test('pasted addresses become rows; role and access give the summary; adding them returns to Users', async ({ page, playwright }) => {
    const envId = await seedEnvironment(playwright, 'Add people');
    const tag = Date.now();
    const a = `ada.${tag}@onboard.test`;
    const b = `bo.${tag}@onboard.test`;
    await openAdd(page);
    await expect(page.locator('#adp-summary')).toHaveText('Nobody to add yet.');
    await expect(page.locator('#adp-submit')).toBeDisabled();

    await page.locator('#adp-email').fill(`${a}; ${b}`);
    await page.locator('#adp-dept').fill('Engineering');
    await page.locator('#adp-project').fill('DLK-204');
    await page.getByRole('button', { name: 'Add to list' }).click();
    const rows = page.locator('#adp-people tbody tr');
    await expect(rows).toHaveCount(2);
    await expect(rows.nth(0).locator('.adp-chip')).toHaveText('By hand');
    await expect(rows.nth(1).getByLabel(/^Department for/)).toHaveValue('Engineering');

    await page.getByRole('radio', { name: /^Environment admin/ }).click();
    await page.getByRole('button', { name: 'Add environment' }).click();
    await page.locator('#adp-pick-env').selectOption(envId);
    await page.locator('#adp-pick-add').click();
    await expect(page.locator('.adp-access-row')).toHaveCount(1);
    await expect(page.locator('#adp-summary b')).toHaveText('Add 2 new people as Environment admin, 2 access grants', { timeout: 10_000 });
    await expect(page.locator('#adp-email-note')).toHaveText('Access is ready; no email is sent.'); // U13-T09: email is off here
    await expect(page.locator('#adp-submit')).toHaveText('Add 2 people');

    const sent = page.waitForResponse(r => r.url().includes('/users/bulk-onboard?dryRun=false'));
    await page.locator('#adp-submit').click();
    const body = (await sent).request().postDataJSON();
    expect(body.role).toBe('ENV_ADMIN');
    expect(body.people.map((p: any) => p.email)).toEqual([a, b]);
    expect(body.people[0]).toMatchObject({ department: 'Engineering', projectCode: 'DLK-204' });
    expect(body.access).toEqual([{ environmentId: envId, scopeType: 'ENVIRONMENT', groupIds: [], accessLevel: 'USER', durationDays: 30 }]);

    await expect(page).toHaveURL(/#\/user-management$/, { timeout: 10_000 });
    await expect(page.getByText('Added 2 people.')).toBeVisible();
    await page.locator('#user-search').fill(`${tag}@onboard.test`);
    const added = page.locator('#users-table-body tr');
    await expect(added).toHaveCount(2);
    await expect(added.first().locator('td').nth(1)).toHaveText('Environment admin');
    await expect(added.first().locator('.um-not-yet')).toHaveText('Not signed in yet');
  });

  test('U20-T07: by hand with an initial password; it is shown once, works once, and the person chooses their own', async ({ page, playwright }) => {
    const email = `pia.${Date.now()}@onboard.test`;
    await openAdd(page);
    await page.locator('#adp-email').fill(email);
    await page.locator('#adp-name').fill('Pia Sen');
    await page.getByRole('radio', { name: 'Email and a password' }).check();
    const password = page.locator('#adp-password');
    await expect(password).toBeVisible();
    await expect(password).toHaveValue(/^.{16}$/); // generated on choosing it
    await password.fill('short');
    await page.getByRole('button', { name: 'Add to list' }).click();
    await expect(page.locator('#adp-manual-error')).toContainText('at least 12 characters');
    await page.getByRole('button', { name: 'Generate' }).click();
    const given = await password.inputValue();
    expect(given).toHaveLength(16);
    await page.getByRole('button', { name: 'Add to list' }).click();
    await expect(page.locator('#adp-people tbody tr').first().locator('.adp-signin-cell')).toHaveText('Email and password');

    const sent = page.waitForResponse(r => r.url().includes('/users/bulk-onboard?dryRun=false'));
    await expect(page.locator('#adp-submit')).toHaveText('Add 1 person', { timeout: 10_000 });
    await page.locator('#adp-submit').click();
    expect((await sent).request().postDataJSON().people[0].initialPassword).toBe(given);
    const dialog = page.locator('#adp-passwords');
    await expect(dialog).toBeVisible();
    await expect(dialog.locator('tbody tr')).toHaveText([`${email}${given}`]);
    await dialog.getByRole('button', { name: 'Done' }).click();
    await expect(page).toHaveURL(/#\/user-management$/, { timeout: 10_000 });

    // The given password signs nobody in; it asks for their own, which then works.
    const anon = await playwright.request.newContext({ baseURL: process.env.BASE_URL });
    const first = await (await anon.post('/api/auth/login', { data: { username: email, password: given } })).json();
    expect(first).toMatchObject({ success: false, passwordChangeRequired: true });
    const changed = await anon.post('/api/auth/change-password', { data: { username: email, password: given, newPassword: 'pia-chose-this-one' } });
    expect(changed.status()).toBe(200);
    expect(await (await anon.post('/api/auth/login', { data: { username: email, password: given } })).status()).toBe(401);
    expect(await (await anon.post('/api/auth/login', { data: { username: email, password: 'pia-chose-this-one' } })).json())
      .toMatchObject({ success: true });
    await anon.dispose();
  });

  test('from the directory: two people, a team for one, two environments', async ({ page, playwright }) => {
    const admin = await api(playwright);
    const team = await (await admin.post('/api/v1/teams', { data: { displayName: `Data ${Date.now()}` } })).json();
    await admin.dispose();
    const env1 = await seedEnvironment(playwright, 'Directory one');
    const env2 = await seedEnvironment(playwright, 'Directory two');
    const tag = Date.now();
    await page.route(/\/api\/v1\/users\/me\/capabilities$/, async route => {
      const response = await route.fetch();
      const caps = await response.json();
      return route.fulfill({ response, body: JSON.stringify({ ...caps, features: { ...caps.features, directory: true } }) });
    });
    await page.route(/\/api\/v1\/directory\/search\?/, route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([
      { directoryObjectId: 'oid-n', displayName: 'Nikhil Banerjee', email: `nikhil.${tag}@corp.test`, alreadyInApp: false, department: 'Engineering' },
      { directoryObjectId: 'oid-r', displayName: 'Ritu Kapoor', email: `ritu.${tag}@corp.test`, alreadyInApp: false, department: 'Quality' }]) }));
    await openAdd(page);

    const search = page.getByRole('combobox', { name: 'Find in the Microsoft directory' });
    await search.fill('ni');
    await page.getByRole('option', { name: /Nikhil Banerjee/ }).click();
    await search.fill('ri');
    await expect(page.getByRole('option', { name: /Ritu Kapoor/ })).toBeVisible();
    await search.press('ArrowDown');
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    const rows = page.locator('#adp-people tbody tr');
    await expect(rows).toHaveCount(2);
    await expect(rows.nth(0).locator('.adp-chip')).toHaveText('Directory');
    await expect(rows.nth(1).getByLabel('Department for Ritu Kapoor')).toHaveValue('Quality');
    await rows.nth(0).getByLabel('Team for Nikhil Banerjee').selectOption(team.teamId);

    await page.getByRole('radio', { name: /^Environment admin/ }).click();
    for (const envId of [env1, env2]) {
      await page.getByRole('button', { name: 'Add environment' }).click();
      await page.locator('#adp-pick-env').selectOption(envId);
      await page.locator('#adp-pick-add').click();
    }
    await expect(page.locator('#adp-summary b')).toHaveText('Add 2 new people as Environment admin, 4 access grants', { timeout: 10_000 });
    await expect(page.locator('#adp-submit')).toHaveText('Add 2 people');
  });

  test('with the onboarding email on, the confirm line says each person gets an email (U13-T09)', async ({ page }) => {
    await page.route(/\/api\/v1\/users\/me\/capabilities$/, async route => {
      const response = await route.fetch();
      const caps = await response.json();
      return route.fulfill({ response, body: JSON.stringify({ ...caps, features: { ...caps.features, onboardingEmail: true } }) });
    });
    await openAdd(page);
    await page.locator('#adp-email').fill(`cy.${Date.now()}@onboard.test`);
    await page.getByRole('button', { name: 'Add to list' }).click();
    await expect(page.locator('#adp-email-note')).toHaveText('Each person gets an email with what they can use and how to sign in.',
      { timeout: 10_000 });
  });

  test('Cancel with people in the list asks before discarding them', async ({ page }) => {
    await openAdd(page);
    await page.locator('#adp-email').fill('keep.me@onboard.test');
    await page.getByRole('button', { name: 'Add to list' }).click();
    await page.locator('#adp-cancel').click();
    await expect(page.locator('#confirmModal')).toContainText('Discard the list?');
    await page.locator('#confirmModal').getByRole('button', { name: 'Cancel' }).click();
    await expect(page).toHaveURL(/#\/user-management\/add$/);
    await expect(page.locator('#adp-people tbody tr')).toHaveCount(1);
  });
});
