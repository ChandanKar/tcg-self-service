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

async function openMyAccount(page: Page, tab: 'access' | 'activity') {
  await signIn(page, 'user');
  await page.waitForFunction(() => typeof MyAccount !== 'undefined');
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

  test('an edit by another admin keeps "Granted by" and adds "Updated by" plus an Updated event',
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
      await expect(card.locator('.ma-card-foot')).toContainText(`Granted by ${adminName}`);
      await expect(card.locator('.ma-updated')).toContainText('Updated by Carol E2E on');

      await page.locator('[data-ma-tab="activity"]').first().click();
      const updated = page.locator('.ma-activity-item', { hasText: env.name }).filter({ hasText: 'Access updated by Carol E2E' });
      await expect(updated).toBeVisible({ timeout: 10_000 });
      await expect(updated.locator('.ma-pill')).toHaveText('Updated');
    });

  test('an approved extension appears as "Access extended" by the reviewer', async ({ page, playwright }) => {
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

    await openMyAccount(page, 'activity');
    const extended = page.locator('.ma-activity-item', { hasText: env.name }).filter({ hasText: `Access extended by ${adminName}` });
    await expect(extended).toBeVisible({ timeout: 10_000 });
    await expect(extended.locator('.ma-pill')).toHaveText('Extended');
    // The card still names the original granter, not the reviewer as an editor.
    await page.locator('[data-ma-tab="access"]').first().click();
    const card = page.locator('.ma-card', { hasText: env.name });
    await expect(card.locator('.ma-card-foot')).toContainText(`Granted by ${adminName}`);
    await expect(card.locator('.ma-updated')).toHaveCount(0);
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
    await page.locator('[data-ma-action="toggle-ended"]').click();
    const row = page.locator('.ma-ended-row', { hasText: env.name });
    await row.locator('[data-ma-action="request-again"]').click();

    const dialog = page.locator('#requestAccessModal');
    await expect(dialog).toBeVisible({ timeout: 10_000 });
    await expect(dialog.locator('#reqScopeGroup')).toBeChecked();
    await expect(dialog.locator('#reqGroupId')).toHaveValue(group.groupId);
    await expect(dialog.locator('#reqGroupId option:checked')).toContainText(groupName);
    await expect(dialog.locator('#accessLevel')).toHaveValue('USER');

    await dialog.locator('#requestReason').fill('Back on the web release team');
    await page.locator('#submitRequest').click();
    await expect(dialog).toBeHidden({ timeout: 10_000 });

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
