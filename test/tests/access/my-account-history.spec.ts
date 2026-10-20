import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E04-T08 (LOW-ACC-3, LOW-ACC-4): My Account keeps the original granter and names the admin who
 * last edited a grant, shows extensions and edits as activity events, and "Request again" on an
 * ended group grant asks for the same group and level.
 *
 * Needs dev-mode identities (TEST_DEV_USER_ID / TEST_DEV_ADMIN_ID). A second admin is onboarded
 * through the API; in dev mode X-User-Id also accepts an email.
 */

declare const MyAccount: any;

async function api(playwright: any, userId: string): Promise<APIRequestContext> {
  return playwright.request.newContext({
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    extraHTTPHeaders: { 'X-User-Id': userId, 'Content-Type': 'application/json' },
  });
}

async function newEnvironment(admin: APIRequestContext, label: string): Promise<{ id: string; name: string }> {
  const stamp = Date.now();
  const name = `${label} ${stamp}`;
  const res = await admin.post('/api/v1/environments', {
    data: { name: `${label.toLowerCase().replace(/\W+/g, '-')}-${stamp}`, displayName: name, cloudProvider: 'AWS' },
  });
  expect(res.ok()).toBeTruthy();
  return { id: (await res.json()).environmentId, name };
}

async function openMyAccount(page: Page, tab: 'access') {
  await signIn(page, 'user');
  await page.waitForFunction(() => typeof MyAccount !== 'undefined');
  // Opening the first page closes any open panel; wait for it, or the panel can be shut right away.
  await expect(page.locator('#content-area h1').first()).toBeVisible({ timeout: 15_000 });
  await page.evaluate((t) => MyAccount.open(t), tab);
}

test.describe('My Account: who granted, who changed, and requesting again', () => {
  let userEmail = '';
  let adminName = '';

  test.beforeAll(async ({ playwright }) => {
    if (!devIds.admin || !devIds.user) return;
    const admin = await api(playwright, devIds.admin);
    userEmail = (await (await admin.get(`/api/v1/users/${devIds.user}`)).json()).email;
    adminName = (await (await admin.get(`/api/v1/users/${devIds.admin}`)).json()).displayName;
    await admin.dispose();
  });

  test.beforeEach(async () => {
    test.skip(!devIds.admin || !devIds.user || !canSignIn('user'),
      'Needs TEST_DEV_USER_ID and TEST_DEV_ADMIN_ID (dev mode) to seed grants');
  });

  test('an edit by another admin keeps "Granted by" and adds "Updated by"',
    async ({ page, playwright }) => {
      const admin = await api(playwright, devIds.admin);
      const env = await newEnvironment(admin, 'History Edit');
      const carolEmail = `carol-e2e-${Date.now()}@example.com`;
      const onboarded = await admin.post('/api/v1/users', {
        data: { email: carolEmail, displayName: 'Carol E2E', admin: true },
      });
      expect(onboarded.ok()).toBeTruthy();
      const granted = await admin.post(`/api/v1/environments/${env.id}/access`, {
        data: { userEmail, accessLevel: 'VIEWER' },
      });
      const accessId = (await granted.json()).accessId;
      await admin.dispose();

      const carol = await api(playwright, carolEmail);
      const edited = await carol.patch(`/api/v1/access-grants/${accessId}`, {
        data: { accessLevel: 'VIEWER', notes: 'Moved to the payments team' },
      });
      expect(edited.ok()).toBeTruthy();
      await carol.dispose();

      await openMyAccount(page, 'access');
      const card = page.locator('.ma-card', { hasText: env.name });
      await expect(card).toBeVisible({ timeout: 10_000 });
      await expect(card.locator('.ma-grant-by')).toContainText(`Granted by ${adminName}`);
      await expect(card.locator('.ma-updated')).toContainText('Updated by Carol E2E on');
      await expect(card.locator('.ma-grant-level')).toHaveText('View only');
      await expect(card.locator('.ma-grant-until')).toHaveText('No end date');
    });

  test('an approved extension keeps the original granter on the card', async ({ page, playwright }) => {
    const admin = await api(playwright, devIds.admin);
    const env = await newEnvironment(admin, 'History Extend');
    await admin.post(`/api/v1/environments/${env.id}/access`, { data: { userEmail, accessLevel: 'USER', durationDays: 3 } });
    const requester = await api(playwright, devIds.user);
    const request = await requester.post(`/api/v1/environments/${env.id}/access-requests`, {
      data: { accessLevel: 'USER', businessJustification: 'Release testing runs longer', durationDays: 7 },
    });
    expect(request.ok()).toBeTruthy();
    const requestId = (await request.json()).requestId;
    await requester.dispose();
    const approved = await admin.post(`/api/v1/access-requests/${requestId}/approve`, { data: {} });
    expect(approved.ok()).toBeTruthy();
    await admin.dispose();

    // The card still names the original granter, not the reviewer as an editor, and now runs 10 days.
    await openMyAccount(page, 'access');
    const card = page.locator('.ma-card', { hasText: env.name });
    await expect(card.locator('.ma-grant-by')).toContainText(`Granted by ${adminName}`, { timeout: 10_000 });
    await expect(card.locator('.ma-updated')).toHaveCount(0);
    await expect(card.locator('.ma-grant-until')).toHaveText(/^Until \d{1,2} \w{3}( \d{4})? \(10 days\)$/);
  });

  test('"Request again" on an ended group grant preselects the same group and level', async ({ page, playwright }) => {
    const admin = await api(playwright, devIds.admin);
    const env = await newEnvironment(admin, 'History Again');
    const groupName = `web-${Date.now()}`;
    const group = await (await admin.post(`/api/v1/environments/${env.id}/groups`, {
      data: { name: groupName, displayName: groupName, sequencePosition: 1 },
    })).json();
    const grants = await (await admin.post('/api/v1/access-grants', {
      data: { userEmail, environmentId: env.id, accessLevel: 'USER', scopeType: 'GROUP', groupIds: [group.groupId] },
    })).json();
    expect((await admin.delete(`/api/v1/access-grants/${grants[0].accessId}`)).ok()).toBeTruthy();
    await admin.dispose();

    await openMyAccount(page, 'access');
    await page.getByRole('button', { name: /^Show \d+ ended grants?$/ }).click();
    const row = page.locator('.ma-ended-row', { hasText: env.name });
    await row.locator('[data-ma-action="request-again"]').click();

    // U09-T02: the one request form on Request access, with the ended grant's group and level.
    await expect(page).toHaveURL(/#\/request-access\?env=/);
    const form = page.locator('#ra-form-card');
    await expect(form.locator('#ra-form-title')).toHaveText(`Request access to ${env.name}`, { timeout: 10_000 });
    await expect(form.locator('#ra-group')).toHaveValue(group.groupId);
    await expect(form.locator('#ra-group option:checked')).toContainText(groupName);
    await expect(form.getByRole('radio', { name: /Operator/ })).toBeChecked();

    await form.getByLabel('Why you need it').fill('Back on the web release team');
    await form.getByRole('button', { name: 'Send request' }).click();
    await expect(page.getByRole('tab', { name: /^Your requests/ })).toHaveAttribute('aria-selected', 'true', { timeout: 10_000 });

    const requester = await api(playwright, devIds.user);
    const mine = await (await requester.get('/api/v1/access-requests/my')).json();
    await requester.dispose();
    const pending = mine.find((r: any) => r.environmentId === env.id && r.status === 'PENDING');
    expect(pending).toBeTruthy();
    expect(pending.scopeType).toBe('GROUP');
    expect(pending.scopeId).toBe(group.groupId);
    expect(pending.requestedAccessLevel).toBe('USER');
  });
});
