import { test, expect, type Page, type Route } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E34-T05: API tokens in My Account (hidden while tokens are off; a new token is shown once and
 * leaves the page when its dialog closes) and the admin API access page (every token, revoke any,
 * service accounts). The token APIs are faked in the browser.
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
  await page.route(/\/api\/v1\/api-tokens(\?.*)?$/, route => json(route, {
    content: [{ token: MINE, ownerUserId: 'u-9', ownerName: 'Pat <b>Lee</b>', ownerEmail: 'pat@example.com', ownerServiceAccount: false }],
    page: { size: 50, number: 0, totalElements: 1, totalPages: 1 } }));
  await page.route(/\/api\/v1\/api-tokens\/t-1$/, route => { calls.push({ adminRevoke: true }); return json(route, { status: 'revoked' }); });
  await page.route(/\/api\/v1\/service-accounts(\?.*)?$/, route => {
    if (route.request().method() === 'POST') {
      calls.push({ account: route.request().postDataJSON() });
      return json(route, { userId: 'sa-2' }, 201);
    }
    return json(route, [{ userId: 'sa-1', name: 'ci-deployer', email: 'ci-deployer@service.local', role: 'USER',
      ownerUserId: 'u-9', ownerName: 'Pat', active: true, createdAt: new Date().toISOString(), activeTokens: 1 }]);
  });
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined' && typeof MyAccount !== 'undefined');
  return calls;
}

test.describe('API tokens', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('the My Account tab is hidden while tokens are off', async ({ page }) => {
    await setUp(page, false);
    await page.evaluate(() => MyAccount.open('overview'));
    await expect(page.locator('#ma-tab-notifications')).toBeVisible();
    await page.waitForTimeout(500);
    await expect(page.locator('#ma-tab-api-tokens')).toHaveCount(0);
  });

  test('a new token is shown once and is gone from the page after its dialog closes', async ({ page }) => {
    const calls = await setUp(page, true);
    await page.evaluate(() => MyAccount.open('overview'));
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

  test('admins see every token with its owner, revoke any and create service accounts', async ({ page }) => {
    const calls = await setUp(page, true);
    await page.evaluate(() => { location.hash = '#/api-access'; });

    const row = page.locator('#api-admin-tokens tr[data-token="t-1"]');
    await expect(row).toContainText('Pat <b>Lee</b>', { timeout: 15_000 });
    await expect(row.locator('b')).toHaveCount(0);
    await row.getByRole('button', { name: 'Revoke' }).click();
    await page.locator('#confirmBtn').click();
    await expect.poll(() => calls.some(c => c.adminRevoke)).toBe(true);

    await expect(page.locator('#api-admin-accounts tr[data-account="sa-1"]')).toContainText('ci-deployer');
    await page.getByRole('button', { name: 'New service account' }).click();
    const modal = page.locator('#serviceAccountModal');
    await modal.locator('#sa-name').fill('nightly-bot');
    await modal.locator('#sa-role').selectOption('ENV_ADMIN');
    await modal.locator('#sa-create').click();
    await expect.poll(() => calls.find(c => c.account)?.account?.name).toBe('nightly-bot');
    expect(calls.find(c => c.account).account.role).toBe('ENV_ADMIN');
  });
});
