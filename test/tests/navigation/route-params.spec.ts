import { test, expect, type Page, type APIRequestContext } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E02-T03: Environment Detail has its own URL (#/environments/<id>) that survives refresh, Back
 * and direct links; the legacy #/environment-detail hash redirects; ?query is preserved.
 *
 * Seeding needs dev-mode identities (TEST_DEV_USER_ID / TEST_DEV_ADMIN_ID): the admin creates an
 * environment and grants the user access through the API.
 */

declare const ContentRouter: any;

let envId = '';
const envName = `Route E2E ${Date.now()}`;

async function api(playwright: any, userId: string): Promise<APIRequestContext> {
  return playwright.request.newContext({
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    extraHTTPHeaders: { 'X-User-Id': userId, 'Content-Type': 'application/json' },
  });
}

async function waitForRouter(page: Page) {
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined' && typeof ContentRouter.query === 'function');
}

/** The seeded environment's Open link (U06-T05); searches by name, since earlier runs leave many E2E environments. */
async function findInList(page: Page) {
  await page.locator('#env-list-search').fill(envName);
  const view = page.locator(`#env-list-body a.env-open-link[href="#/environments/${envId}"]`);
  await expect(view).toBeVisible({ timeout: 10_000 });
  return view;
}

test.describe('Route params and deep links', () => {
  test.beforeAll(async ({ playwright }) => {
    if (!devIds.admin || !devIds.user) return;
    const admin = await api(playwright, devIds.admin);
    const created = await admin.post('/api/v1/environments', {
      data: { name: `route-e2e-${Date.now()}`, displayName: envName, cloudProvider: 'AWS' },
    });
    expect(created.ok()).toBeTruthy();
    envId = (await created.json()).environmentId;

    const user = await (await admin.get(`/api/v1/users/${devIds.user}`)).json();
    const granted = await admin.post(`/api/v1/environments/${envId}/access`, {
      data: { userEmail: user.email, accessLevel: 'USER', durationDays: 1 },
    });
    expect(granted.ok()).toBeTruthy();
    await admin.dispose();
  });

  test.beforeEach(async () => {
    test.skip(!devIds.admin || !devIds.user || !canSignIn('user'),
      'Needs TEST_DEV_USER_ID and TEST_DEV_ADMIN_ID (dev mode) to seed an environment');
  });

  test('View puts the id in the URL; refresh keeps the detail; Back returns to the list', async ({ page }) => {
    await signIn(page, 'user');
    await waitForRouter(page);
    await page.evaluate(() => { location.hash = '#/my-environments'; });

    const view = await findInList(page);
    await view.click();

    await expect(page).toHaveURL(new RegExp(`#/environments/${envId}$`));
    await expect(page.locator('#content-area')).toContainText(envName, { timeout: 10_000 });
    await expect(page.locator('.sidebar-menu-link[data-content="my-environments"]')).toHaveClass(/active/);

    await page.reload();
    await expect(page).toHaveURL(new RegExp(`#/environments/${envId}$`));
    await expect(page.locator('#content-area')).toContainText(envName, { timeout: 10_000 });

    await page.goBack();
    await expect(page).toHaveURL(/#\/my-environments$/);
    await findInList(page);
  });

  test('a direct link opens Environment Detail', async ({ page }) => {
    await page.context().setExtraHTTPHeaders({ 'X-User-Id': devIds.user });
    await page.goto(`/home#/environments/${envId}`);
    await expect(page.locator('#content-area')).toContainText(envName, { timeout: 10_000 });
  });

  test('the in-page Back button goes to #/my-environments', async ({ page }) => {
    await page.context().setExtraHTTPHeaders({ 'X-User-Id': devIds.user });
    await page.goto(`/home#/environments/${envId}`);
    await expect(page.locator('#content-area')).toContainText(envName, { timeout: 10_000 });
    await page.locator('#content-area a[href="#/my-environments"]').click();
    await expect(page).toHaveURL(/#\/my-environments$/);
  });

  test('the legacy #/environment-detail hash redirects to the list', async ({ page }) => {
    await signIn(page, 'user');
    await waitForRouter(page);
    await page.evaluate(() => { location.hash = '#/environment-detail'; });
    await expect(page).toHaveURL(/#\/my-environments$/);
    await expect(page.locator('#content-area')).not.toContainText('No environment specified');
  });

  test('ids are URL-encoded in the hash and decoded for the API call', async ({ page }) => {
    await signIn(page, 'user');
    await waitForRouter(page);
    const hash = await page.evaluate(() => ContentRouter.hashFor('environment-detail', { environmentId: 'env 1/x' }));
    expect(hash).toBe('#/environments/env%201%2Fx');

    const detailRequest = page.waitForRequest((r) => r.url().includes('/api/v1/environments/env%201%2Fx')
      || r.url().includes('/api/v1/environments/env%201/x'));
    await page.evaluate((h) => { location.hash = h; }, hash);
    const req = await detailRequest;
    expect(decodeURIComponent(new URL(req.url()).pathname)).toContain('/api/v1/environments/env 1/x');
  });

  test('Manage access opens Grant access with the environment chosen (U10-T06)', async ({ page }) => {
    test.skip(!canSignIn('admin'), 'Needs an admin sign-in');
    await page.context().setExtraHTTPHeaders({ 'X-User-Id': devIds.admin });
    await page.goto(`/home#/environments/${envId}`);
    await expect(page.locator('#content-area')).toContainText(envName, { timeout: 10_000 });

    await page.locator('#btn-env-manage-access').click();

    await expect(page).toHaveURL(new RegExp(`#/access-management/grant\\?environmentId=${envId}$`));
    await expect(page.locator('#content-area h1')).toHaveText('Grant access', { timeout: 10_000 });
    await expect(page.locator(`[data-ga-env-pick="${envId}"]`)).toBeChecked();
  });

  test('?query survives load and refresh (Cost Management page state)', async ({ page }) => {
    test.skip(!canSignIn('admin'), 'Needs an admin sign-in');
    await signIn(page, 'admin');
    await waitForRouter(page);
    await page.evaluate(() => { location.hash = '#/cost-management?idle=1'; });
    await page.waitForTimeout(1500);
    await expect(page).toHaveURL(/#\/cost-management\?idle=1$/);
    expect(await page.evaluate(() => ContentRouter.query().idle)).toBe('1');

    await page.reload();
    await waitForRouter(page);
    await page.waitForTimeout(1500);
    await expect(page).toHaveURL(/#\/cost-management\?idle=1$/);
  });
});

/**
 * U11-T06 (EnvSettings.dc.html): #/environments/<id>/settings[/<section>]. Details saved in one PUT,
 * sections in the address, Discard changes? before leaving unsaved edits, Deactivate after typing the
 * short name, and the role guard. Each test seeds its own environment.
 */
test.describe('Environment settings', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !devIds.user || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID and TEST_DEV_USER_ID (dev mode)');
  });

  async function seedEnv(playwright: any) {
    const admin = await api(playwright, devIds.admin);
    const stamp = Date.now();
    const env = await (await admin.post('/api/v1/environments', {
      data: { name: `settings-e2e-${stamp}`, displayName: `Settings E2E ${stamp}`, description: 'Before', cloudProvider: 'AWS' },
    })).json();
    await admin.dispose();
    return env;
  }

  async function open(page: Page, hash: string) {
    await signIn(page, 'admin');
    await waitForRouter(page);
    await page.evaluate(h => { location.hash = h; }, hash);
  }

  test('Details: one PUT with what changed, Settings saved, the section in the address', async ({ page, playwright }) => {
    const env = await seedEnv(playwright);
    const puts: any[] = [];
    page.on('request', r => { if (r.method() === 'PUT' && r.url().endsWith(`/environments/${env.environmentId}`)) puts.push(r.postDataJSON()); });
    await open(page, `#/environments/${env.environmentId}/settings`);

    await expect(page.locator('#content-area h1')).toHaveText(`${env.displayName} settings`, { timeout: 15_000 });
    await expect(page.locator('.page-breadcrumb')).toHaveText(new RegExp(`VM registry\\s*/\\s*${env.displayName}\\s*/\\s*Settings`));
    await expect(page.locator('.es-nav-item[aria-current="page"]')).toHaveText('Details');
    await expect(page.locator('#es-short')).toHaveValue(env.name);
    await expect(page.locator('#es-short')).toBeDisabled();
    await expect(page.locator('#es-save-btn')).toBeDisabled();
    await expect(page.locator('.es-tag code').first()).toHaveText(/managed-by$/);

    await page.locator('#es-description').fill('Development stack for the payments service');
    await expect(page.locator('#es-save-note')).toHaveText('You have unsaved changes.');
    await page.locator('#es-save-btn').click();
    await expect(page.getByText('Settings saved')).toBeVisible();
    expect(puts).toEqual([{ description: 'Development stack for the payments service' }]);
    await expect(page.locator('#es-save-btn')).toBeDisabled();

    const admin = await api(playwright, devIds.admin);
    expect((await (await admin.get(`/api/v1/environments/${env.environmentId}`)).json()).description)
      .toBe('Development stack for the payments service');
    await admin.dispose();

    // An unsaved edit asks before another section opens; the section is in the address.
    await page.locator('#es-display').fill('Renamed');
    await page.locator('[data-es-section="deactivate"]').click();
    await expect(page.locator('.modal.show .modal-title')).toHaveText('Discard changes?');
    await page.locator('.modal.show .btn-danger').click();
    await expect(page.locator('#es-panel h2')).toHaveText('Deactivate');
    await expect(page).toHaveURL(new RegExp(`#/environments/${env.environmentId}/settings/deactivate$`));
  });

  test('U19-T04: the AWS region is set in Details as a metadata patch; a bad code is refused before sending', async ({ page, playwright }) => {
    const env = await seedEnv(playwright);
    const puts: any[] = [];
    page.on('request', r => { if (r.method() === 'PUT' && r.url().endsWith(`/environments/${env.environmentId}`)) puts.push(r.postDataJSON()); });
    await open(page, `#/environments/${env.environmentId}/settings/details`);

    const field = page.getByLabel('AWS region');
    await expect(field).toHaveValue('', { timeout: 15_000 });
    await expect(page.locator('#es-region-help')).toHaveText('Not set: its VMs are found in every region the AWS account has enabled.');
    await field.fill('Mumbai');
    await page.locator('#es-save-btn').click();
    await expect(page.locator('#es-save-note')).toHaveText('Mumbai is not an AWS region code. Use a code such as eu-west-1.');
    await expect(field).toHaveAttribute('aria-invalid', 'true');
    expect(puts).toEqual([]);

    await field.fill('eu-west-1');
    await page.locator('#es-save-btn').click();
    await expect(page.getByText('Settings saved')).toBeVisible();
    expect(puts).toEqual([{ metadata: '{"region":"eu-west-1"}' }]);
    await expect(page.locator('#es-region-help')).toHaveText('AWS calls for this environment go to this region.');

    const admin = await api(playwright, devIds.admin);
    const saved = await (await admin.get(`/api/v1/environments/${env.environmentId}`)).json();
    expect(saved.region).toBe('eu-west-1');
    // The server checks the code too.
    const bad = await admin.put(`/api/v1/environments/${env.environmentId}`, { data: { metadata: '{"region":"mumbai"}' } });
    expect(bad.status()).toBe(400);
    await admin.dispose();
  });

  test('U19-T04: an environment whose region is not known says so, with Set the region for its admins', async ({ page, playwright }) => {
    const env = await seedEnv(playwright);
    // A cluster nothing gives a region: the page is told regionMissing.
    await page.route(new RegExp(`/api/v1/environments/${env.environmentId}$`), async route => {
      if (route.request().method() !== 'GET') return route.continue();
      const response = await route.fetch();
      const body = await response.json();
      return route.fulfill({ response, json: { ...body, serviceType: 'EKS', regionMissing: true } });
    });
    await open(page, `#/environments/${env.environmentId}`);

    const alert = page.locator('.env-alert[data-kind="region"]');
    await expect(alert).toContainText("This cluster's AWS region isn't known.", { timeout: 15_000 });
    await expect(alert.getByRole('link', { name: 'Set the region' }))
      .toHaveAttribute('href', `#/environments/${env.environmentId}/settings/details`);
  });

  test('Deactivate only after the short name is typed, then back to the registry', async ({ page, playwright }) => {
    const env = await seedEnv(playwright);
    await open(page, `#/environments/${env.environmentId}/settings/deactivate`);

    await expect(page.locator('#es-panel h2')).toHaveText('Deactivate', { timeout: 15_000 });
    await expect(page.locator('#es-panel')).toContainText('VMs keep running in AWS; stop them first if you want them off.');
    // E10-T06: deactivating can be undone, and the page never says otherwise.
    await expect(page.locator('#es-panel')).toContainText('An administrator can bring it back.');
    await expect(page.locator('#es-panel')).not.toContainText(/permanent|cannot be undone/i);
    const button = page.locator('#es-deactivate');
    await expect(button).toHaveText(`Deactivate ${env.displayName}`);
    await expect(button).toBeDisabled();
    await page.locator('#es-confirm').fill('not-the-name');
    await expect(button).toBeDisabled();
    await page.locator('#es-confirm').fill(env.name);
    await expect(button).toBeEnabled();
    await button.click();

    await expect(page).toHaveURL(/#\/vm-registry$/, { timeout: 10_000 });
    const admin = await api(playwright, devIds.admin);
    expect((await (await admin.get(`/api/v1/environments/${env.environmentId}`)).json()).isActive).toBe(false);

    // U20-T06: the message offers Undo to an administrator, which brings it straight back.
    await page.getByRole('button', { name: 'Undo' }).click();
    await expect(page.getByText(`${env.displayName} is active again.`)).toBeVisible();
    expect((await (await admin.get(`/api/v1/environments/${env.environmentId}`)).json()).isActive).toBe(true);
    await admin.dispose();
  });

  test('Groups and start order: moving a group saves the order in one request (U11-T07)', async ({ page, playwright }) => {
    const env = await seedEnv(playwright);
    const admin = await api(playwright, devIds.admin);
    const mk = async (name: string, pos: number, deps: string[] = []) => (await (await admin.post(
      `/api/v1/environments/${env.environmentId}/groups`, { data: { name, displayName: name, sequencePosition: pos, dependsOnGroupIds: deps } })).json()).groupId;
    const db = await mk('Database', 1);
    const app = await mk('Application', 2, [db]);
    await mk('Web', 3);
    await admin.dispose();
    const orders: any[] = [];
    page.on('request', r => { if (r.method() === 'PUT' && r.url().endsWith('/groups/order')) orders.push(r.postDataJSON()); });

    await open(page, `#/environments/${env.environmentId}/settings/groups`);
    const rows = page.locator('.es-group');
    await expect(rows).toHaveCount(3, { timeout: 15_000 });
    await expect(rows.locator('.es-group-name b')).toHaveText(['1st · Database', '2nd · Application', '3rd · Web']);
    await expect(rows.locator('.es-group-waits')).toHaveText(['Starts first', 'Waits for Database', 'Starts first']);
    await expect(page.getByRole('button', { name: 'Move Database up' })).toBeDisabled();

    await page.getByRole('button', { name: 'Move Web up' }).click();
    await expect(rows.locator('.es-group-name b')).toHaveText(['1st · Database', '2nd · Web', '3rd · Application']);
    expect(orders.length).toBe(1);

    // Application waits for Database: it cannot go above it, and the server's reason shows.
    await page.getByRole('button', { name: 'Move Application up' }).click();
    await expect(rows.locator('.es-group-name b')).toHaveText(['1st · Database', '2nd · Application', '3rd · Web']);
    await page.getByRole('button', { name: 'Move Application up' }).click();
    await expect(page.locator('#es-groups-error')).toHaveText('Application waits for Database, so it cannot start first');
    expect(app).toBeTruthy();
  });

  test('Running and stopping: no lease request while leases are off; Notifications says webhooks are off (U11-T07)', async ({ page, playwright }) => {
    const env = await seedEnv(playwright);
    const leaseCalls: string[] = [];
    page.on('request', r => { if (/\/lease-policy$/.test(r.url())) leaseCalls.push(r.url()); });
    await page.route(new RegExp(`/api/v1/environments/${env.environmentId}$`), async route => {
      const res = await route.fetch();
      const body = await res.json();
      return route.fulfill({ response: res, json: { ...body, leaseEnabled: null } });
    });
    await open(page, `#/environments/${env.environmentId}/settings/running`);
    await expect(page.locator('#es-leases-off')).toHaveText(
      'Leases are switched off for the platform: environments run until someone stops them.', { timeout: 15_000 });
    await expect(page.locator('#es-running-lines .es-line').last()).toContainText('Schedule:');
    expect(leaseCalls).toEqual([]);

    await page.evaluate(() => { (window as any).Capabilities.has = (f: string) => f !== 'webhooks' && f !== 'chat'; });
    await page.locator('[data-es-section="notifications"]').click();
    await expect(page.locator('#es-webhooks-off')).toHaveText(
      'Webhooks are switched off for the platform. An administrator can switch them on in the server configuration (needs a restart).');
    await expect(page.locator('#env-webhook-panel')).toHaveCount(0);
  });

  test('Running and stopping: the lease settings save from the section (U11-T07)', async ({ page, playwright }) => {
    const env = await seedEnv(playwright);
    const puts: any[] = [];
    await page.route(new RegExp(`/api/v1/environments/${env.environmentId}$`), async route => {
      const res = await route.fetch();
      return route.fulfill({ response: res, json: { ...(await res.json()), leaseEnabled: false } });
    });
    await page.route(new RegExp(`/api/v1/environments/${env.environmentId}/lease-policy$`), route => {
      if (route.request().method() === 'PUT') {
        puts.push(route.request().postDataJSON());
        return route.fulfill({ json: { enabled: true, defaultMinutes: 240, maxMinutes: 480, effectiveDefaultMinutes: 240,
          effectiveMaxMinutes: 480, platformMaxMinutes: 1440 } });
      }
      return route.fulfill({ json: { enabled: false, defaultMinutes: null, maxMinutes: null, effectiveDefaultMinutes: 480,
        effectiveMaxMinutes: 720, platformMaxMinutes: 1440 } });
    });
    await open(page, `#/environments/${env.environmentId}/settings/running`);
    await expect(page.locator('#lease-policy-enabled')).toBeVisible({ timeout: 15_000 });
    await page.locator('#lease-policy-enabled').check();
    await page.locator('#lease-policy-default').selectOption('240');
    await page.locator('#lease-policy-max').selectOption('120');
    await page.locator('#es-lease-save').click();
    await expect(page.locator('#lease-policy-error')).toHaveText('The default cannot be longer than the longest without approval.');
    await page.locator('#lease-policy-max').selectOption('480');
    await page.locator('#es-lease-save').click();
    await expect(page.getByText('Lease settings saved.')).toBeVisible();
    expect(puts).toEqual([{ enabled: true, defaultMinutes: 240, maxMinutes: 480 }]);
  });

  test('the registry opens it; users get the no-access page', async ({ page, playwright }) => {
    const env = await seedEnv(playwright);
    await open(page, `#/vm-registry?env=${env.environmentId}`);
    await expect(page.locator('#vr-sel-title')).toHaveText(env.displayName, { timeout: 15_000 });
    await page.locator('#vr-detail-head [data-action="vr-edit-environment"]').click();
    await expect(page).toHaveURL(new RegExp(`#/environments/${env.environmentId}/settings$`));
    await expect(page.locator('.sidebar-menu-link[data-content="vm-registry"]')).toHaveClass(/active/);

    await page.context().clearCookies();
    await signIn(page, 'user');
    await waitForRouter(page);
    await page.evaluate(h => { location.hash = h; }, `#/environments/${env.environmentId}/settings`);
    await expect(page.locator('[data-no-access] h1').first()).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('#es-panel')).toHaveCount(0);
  });
});
