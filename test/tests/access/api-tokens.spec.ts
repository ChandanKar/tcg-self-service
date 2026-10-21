import { test, expect, type Page, type Route } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { withFeatures } from '../../fixtures/capabilities';

/**
 * E34-T05: API tokens in My Account (hidden while tokens are off; a new token is shown once and
 * leaves the page when its dialog closes) and the admin API access page (every token, revoke any,
 * service accounts). The token APIs are faked in the browser. U14-T04 (ApiAccess.dc.html): tabs
 * Tokens / Service accounts / Webhooks, scopes in words, Switch on, and a switched-off card.
 */

declare const ContentRouter: any;
declare const MyAccount: any;

const SECRET = 'vmc_AbCdEf12_' + 'x'.repeat(43);
const MINE = { id: 't-1', name: 'old-ci', prefix: 'Zz9Yy8Xx', scopes: ['environments:read'], kind: 'PERSONAL',
  expiresAt: new Date(Date.now() + 10 * 86400_000).toISOString(), lastUsedAt: null, createdAt: new Date().toISOString(), revokedAt: null };

const json = (route: Route, body: unknown, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function setUp(page: Page, enabled: boolean) {
  const calls: any[] = [];
  let mine = [MINE];
  await page.route(/\/api\/v1\/me\/api-tokens(\/[^/?]+)?$/, route => {
    const req = route.request();
    if (!enabled) return json(route, { message: 'API tokens are not enabled' }, 404);
    if (req.method() === 'POST') {
      calls.push({ create: req.postDataJSON() });
      mine = [{ ...MINE, id: 't-2', name: 'nightly', prefix: 'AbCdEf12' }, ...mine];
      return json(route, { token: SECRET, id: 't-2', name: 'nightly', prefix: 'AbCdEf12', scopes: ['environments:read'] }, 201);
    }
    if (req.method() === 'DELETE') {
      calls.push({ revoke: req.url() });
      return json(route, { status: 'revoked' });
    }
    return json(route, mine);
  });
  await page.route(/\/api\/v1\/api-tokens(\?.*)?$/, route => { calls.push({ list: true }); return json(route, {
    content: [{ token: { ...MINE, scopes: ['environments:read', 'operations:write'], lastUsedAt: new Date().toISOString() },
      ownerUserId: 'u-9', ownerName: 'Pat <b>Lee</b>', ownerEmail: 'pat@example.com', ownerServiceAccount: false }],
    page: { size: 50, number: 0, totalElements: 1, totalPages: 1 } }); });
  await page.route(/\/api\/v1\/api-tokens\/t-1$/, route => { calls.push({ adminRevoke: true }); return json(route, { status: 'revoked' }); });
  let revived = false;
  await page.route(/\/api\/v1\/service-accounts(\?.*)?$/, route => {
    if (route.request().method() === 'POST') {
      calls.push({ account: route.request().postDataJSON() });
      return json(route, { userId: 'sa-2' }, 201);
    }
    calls.push({ accounts: true });
    return json(route, [{ userId: 'sa-1', name: 'ci-deployer', email: 'ci-deployer@service.local', role: 'USER',
      ownerUserId: 'u-9', ownerName: 'Pat', active: true, createdAt: new Date().toISOString(), activeTokens: 1 },
      { userId: 'sa-3', name: 'access-sync', email: 'access-sync@service.local', role: 'ENV_ADMIN',
      ownerUserId: 'u-9', ownerName: 'Pat', active: revived, createdAt: new Date().toISOString(), activeTokens: 0 }]);
  });
  await page.route(/\/api\/v1\/service-accounts\/sa-3\/reactivate$/, route => {
    calls.push({ reactivate: 'sa-3' });
    revived = true;
    return json(route, { userId: 'sa-3', name: 'access-sync', active: true, activeTokens: 0 });
  });
  await page.route(/\/api\/v1\/service-accounts\/sa-1\/tokens$/, route => {
    calls.push({ saToken: route.request().postDataJSON() });
    return json(route, { token: SECRET, id: 't-9', name: 'deploy', prefix: 'AbCdEf12', scopes: ['environments:read'],
      expiresAt: new Date(Date.now() + 90 * 86400_000).toISOString() }, 201);
  });
  await withFeatures(page, { apiTokens: enabled }); // U02-T03: the page follows the switch
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined' && typeof MyAccount !== 'undefined');
  // Opening the first page closes any open panel; let it settle before opening My Account.
  await expect(page.locator('#content-area h1').first()).toBeVisible({ timeout: 15_000 });
  await page.waitForLoadState('networkidle');
  return calls;
}

test.describe('API tokens', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('the My Account tab is hidden while tokens are off', async ({ page }) => {
    await setUp(page, false);
    await page.evaluate(() => MyAccount.open('access'));
    await expect(page.locator('#ma-tab-notifications')).toBeVisible();
    await page.waitForTimeout(500);
    await expect(page.locator('#ma-tab-api-tokens')).toHaveCount(0);
  });

  test('a new token is shown once and is gone from the page after its dialog closes', async ({ page }) => {
    const calls = await setUp(page, true);
    await page.evaluate(() => MyAccount.open('access'));
    await page.locator('#ma-tab-api-tokens').click();
    await expect(page.locator('#my-api-tokens tr[data-token="t-1"]')).toContainText('old-ci');

    await page.getByRole('button', { name: 'New token' }).click();
    const create = page.locator('#apiTokenCreateModal');
    await create.locator('#api-token-name').fill('nightly');
    await create.locator('#api-token-create').click();
    await expect(create.locator('#api-token-error')).toHaveText('Choose at least one scope.');
    await expect(create.locator('#api-scope-admin')).toBeVisible(); // an admin may grant admin
    await expect(create.locator('#api-scope-assistant-read')).toBeVisible(); // the MCP assistant scope (E36-T01)
    await create.locator('#api-scope-environments-read').check();
    await create.locator('#api-token-create').click();

    const reveal = page.locator('#apiTokenRevealModal');
    await expect(reveal.locator('#api-token-value')).toHaveValue(SECRET);
    await expect(reveal).toContainText('You will not see this again');
    expect(calls[0]).toEqual({ create: { name: 'nightly', scopes: ['environments:read'], expiresInDays: 30 } });

    await reveal.getByRole('button', { name: 'Done' }).click();
    await expect(page.locator('#apiTokenRevealModal')).toHaveCount(0);
    expect(await page.evaluate(s => document.documentElement.outerHTML.includes(s), SECRET)).toBe(false);
    await expect(page.locator('#my-api-tokens tr[data-token="t-2"]')).toContainText('vmc_AbCdEf12_');
  });

  test('admins see every token with its owner and what it can do, revoke any and create service accounts', async ({ page }) => {
    const calls = await setUp(page, true);
    await page.evaluate(() => { location.hash = '#/api-access'; });

    // Acceptance: the Tokens tab lists tokens with prefix, owner, scopes in words, last used and expiry.
    await expect(page.getByRole('tab', { name: 'Tokens' })).toHaveAttribute('aria-selected', 'true', { timeout: 15_000 });
    const row = page.locator('#api-admin-tokens tr[data-token="t-1"]');
    await expect(row).toContainText('Pat <b>Lee</b>', { timeout: 15_000 });
    await expect(row.locator('b')).toHaveCount(0);
    await expect(row).toContainText('vmc_Zz9Yy8Xx…');
    await expect(row).toContainText('Read environments, start and stop');
    await expect(row).toContainText(/Today \d\d:\d\d/);
    expect(calls.some(c => c.accounts)).toBe(false); // the other tabs load when opened
    await row.getByRole('button', { name: /^Revoke/ }).click();
    await page.locator('#confirmBtn').click();
    await expect.poll(() => calls.some(c => c.adminRevoke)).toBe(true);

    await page.getByRole('tab', { name: 'Service accounts' }).click();
    await expect(page).toHaveURL(/#\/api-access\?tab=accounts$/);
    await expect(page.getByRole('button', { name: 'New token', exact: true })).toBeHidden();
    await expect(page.locator('#api-admin-accounts [data-account="sa-1"]')).toContainText('ci-deployer');
    await expect(page.locator('#api-admin-accounts [data-account="sa-1"]')).toContainText('Owner Pat · 1 active token');
    await page.getByRole('button', { name: 'New service account' }).click();
    const modal = page.locator('#serviceAccountModal');
    await modal.locator('#sa-name').fill('nightly-bot');
    await modal.locator('#sa-role').selectOption('ENV_ADMIN');
    await modal.locator('#sa-create').click();
    await expect.poll(() => calls.find(c => c.account)?.account?.name).toBe('nightly-bot');
    expect(calls.find(c => c.account).account.role).toBe('ENV_ADMIN');
  });

  test('service accounts: a new token is shown once in the page; Switch on keeps old tokens revoked', async ({ page }) => {
    const calls = await setUp(page, true);
    await page.evaluate(() => { location.hash = '#/api-access?tab=accounts'; });
    const off = page.locator('#api-admin-accounts [data-account="sa-3"]');
    await expect(off).toContainText('Switched off', { timeout: 15_000 });
    await expect(off).toContainText('Environment admin');
    await expect(off).toContainText('no tokens');

    await page.locator('#api-admin-accounts [data-account="sa-1"]').getByRole('button', { name: 'New token for ci-deployer' }).click();
    const create = page.locator('#apiTokenCreateModal');
    await create.locator('#api-token-name').fill('deploy');
    await create.locator('#api-scope-environments-read').check();
    await create.locator('#api-token-create').click();
    const reveal = page.locator('#api-reveal');
    await expect(reveal).toContainText('Token created for ci-deployer');
    await expect(reveal.locator('#api-reveal-value')).toHaveText(SECRET);
    await expect(reveal).toContainText("Copy it now; it isn't shown again.");
    await expect(reveal).toContainText('ends in 90 days');
    await expect(page.locator('#apiTokenRevealModal')).toHaveCount(0);
    await reveal.getByRole('button', { name: 'Done' }).click();
    await expect(page.locator('#api-reveal')).toHaveCount(0);
    expect(await page.evaluate(s => document.documentElement.outerHTML.includes(s), SECRET)).toBe(false);

    // Acceptance: Switch on shows Active; the server keeps its old tokens revoked.
    await off.getByRole('button', { name: 'Switch on access-sync' }).click();
    await expect.poll(() => calls.some(c => c.reactivate === 'sa-3')).toBe(true);
    await expect(page.locator('#api-admin-accounts [data-account="sa-3"] .api-state')).toHaveText('Active');
    await expect(page.locator('#notification-container').filter({ hasText: 'earlier tokens stay revoked' })).toBeVisible();
  });

  test('switched off: a card says so and no token or account list is asked for', async ({ page }) => {
    const calls = await setUp(page, false);
    await page.evaluate(() => { location.hash = '#/api-access'; });
    // Acceptance: the switched-off card shows and no tab content is requested.
    await expect(page.locator('#api-access-off h2')).toHaveText('API tokens are switched off', { timeout: 15_000 });
    await expect(page.locator('#api-access-off').getByRole('link', { name: 'Setup guide' })).toHaveAttribute('href', '#/help/api-access');
    await expect(page.getByRole('tab')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'New token', exact: true })).toHaveCount(0);
    await page.waitForLoadState('networkidle');
    expect(calls.filter(c => c.list || c.accounts)).toEqual([]);
  });
});
