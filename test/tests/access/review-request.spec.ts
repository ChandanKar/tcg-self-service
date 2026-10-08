import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E04-T07 (M20): reviewers see the scope, requested duration and current access of a request,
 * and every approval goes through a dialog where they can keep the requested duration, pick
 * another, or grant with no expiry.
 *
 * Needs dev-mode identities: TEST_DEV_USER_ID / TEST_DEV_ADMIN_ID, plus 'user-002' from
 * src/test/resources/db/reset-test-data.sql as a second requester.
 */

declare const ContentRouter: any;

const DAY_MS = 24 * 60 * 60 * 1000;
const SECOND_USER = 'user-002';

async function api(playwright: any, userId: string): Promise<APIRequestContext> {
  return playwright.request.newContext({
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    extraHTTPHeaders: { 'X-User-Id': userId, 'Content-Type': 'application/json' },
  });
}

interface Seed {
  envId: string;
  groupName: string;
  extensionRequestId: string;
  currentExpiresAt: number;
  newRequestId: string;
}

/**
 * A fresh environment with group "web-<n>": the user holds USER on the group for 5 days and asks
 * to extend it by 30 days; user-002 asks for 30 days on the whole environment.
 */
async function seed(playwright: any): Promise<Seed> {
  const admin = await api(playwright, devIds.admin);
  const stamp = Date.now();
  const env = await (await admin.post('/api/v1/environments', {
    data: { name: `review-e2e-${stamp}`, displayName: `Review E2E ${stamp}`, cloudProvider: 'AWS' },
  })).json();
  const groupName = `web-${stamp}`;
  const groupRes = await admin.post(`/api/v1/environments/${env.environmentId}/groups`, {
    data: { name: groupName, displayName: groupName, sequencePosition: 1 },
  });
  expect(groupRes.ok()).toBeTruthy();
  const group = await groupRes.json();

  const user = await (await admin.get(`/api/v1/users/${devIds.user}`)).json();
  const grantRes = await admin.post('/api/v1/access-grants', {
    data: { userEmail: user.email, environmentId: env.environmentId, accessLevel: 'USER',
            scopeType: 'GROUP', groupIds: [group.groupId], durationDays: 5 },
  });
  expect(grantRes.ok()).toBeTruthy();
  const grant = (await grantRes.json())[0];

  const requester = await api(playwright, devIds.user);
  const ext = await requester.post(`/api/v1/environments/${env.environmentId}/access-requests`, {
    data: { accessLevel: 'USER', businessJustification: 'Release testing runs longer',
            durationDays: 30, scopeType: 'GROUP', groupId: group.groupId },
  });
  expect(ext.ok()).toBeTruthy();

  const second = await api(playwright, SECOND_USER);
  const fresh = await second.post(`/api/v1/environments/${env.environmentId}/access-requests`, {
    data: { accessLevel: 'USER', businessJustification: 'Joining the release team', durationDays: 30 },
  });
  expect(fresh.ok()).toBeTruthy();

  const seeded = {
    envId: env.environmentId,
    groupName,
    extensionRequestId: (await ext.json()).requestId,
    currentExpiresAt: new Date(grant.expiresAt).getTime(),
    newRequestId: (await fresh.json()).requestId,
  };
  await Promise.all([admin.dispose(), requester.dispose(), second.dispose()]);
  return seeded;
}

async function requestStatus(playwright: any, requestId: string): Promise<string> {
  const admin = await api(playwright, devIds.admin);
  const r = await (await admin.get(`/api/v1/access-requests/${requestId}`)).json();
  await admin.dispose();
  return r.status;
}

async function grantOf(playwright: any, envId: string, userId: string): Promise<any> {
  const admin = await api(playwright, devIds.admin);
  const grants = await (await admin.get(`/api/v1/environments/${envId}/access`)).json();
  await admin.dispose();
  return grants.find((g: any) => g.userId === userId && g.status === 'ACTIVE');
}

async function goTo(page: Page, hash: string) {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate((h) => { location.hash = h; }, hash);
}

async function openAccessManagementPending(page: Page) {
  await goTo(page, '#/access-management');
  await page.locator('#pending-tab').click();
}

const day = (page: Page, ms: number) => page.evaluate((t) =>
  new Date(t).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }), ms);

test.describe('Reviewing access requests', () => {
  test.beforeEach(async () => {
    test.skip(!devIds.admin || !devIds.user || !canSignIn('admin'),
      'Needs TEST_DEV_USER_ID and TEST_DEV_ADMIN_ID (dev mode) to seed requests');
  });

  test('Pending Requests shows scope, duration, current access and the extension pill', async ({ page, playwright }) => {
    const s = await seed(playwright);
    await goTo(page, '#/pending-requests');

    const row = page.locator('#pending-requests-table tr', {
      has: page.locator(`[data-request-id="${s.extensionRequestId}"]`),
    });
    await expect(row).toBeVisible({ timeout: 10_000 });
    await expect(row.locator('[data-col="scope"]')).toHaveText(`Group ${s.groupName}`);
    await expect(row.locator('[data-col="duration"]')).toHaveText('30 days requested');
    await expect(row.locator('[data-col="current"]')).toContainText(`USER until ${await day(page, s.currentExpiresAt)}`);
    await expect(row.locator('.ra-extension-pill')).toHaveText('Extension');

    const fresh = page.locator('#pending-requests-table tr', {
      has: page.locator(`[data-request-id="${s.newRequestId}"]`),
    });
    await expect(fresh.locator('[data-col="scope"]')).toHaveText('Whole environment');
    await expect(fresh.locator('[data-col="current"]')).toHaveText('None');
    await expect(fresh.locator('.ra-extension-pill')).toHaveCount(0);
  });

  test('Access Management: Approve opens a dialog and approves nothing until confirmed; Escape cancels',
    async ({ page, playwright }) => {
      const s = await seed(playwright);
      await openAccessManagementPending(page);

      const card = page.locator(`.access-request-card[data-request-id="${s.extensionRequestId}"]`);
      await expect(card).toBeVisible({ timeout: 10_000 });
      await expect(card.locator('[data-col="scope"]')).toHaveText(`Group ${s.groupName}`);
      await expect(card.locator('[data-col="duration"]')).toHaveText('30 days requested');
      await expect(card.locator('.ra-extension-pill')).toBeVisible();

      await card.locator('[data-action="approve"]').click();
      const dialog = page.locator('#approveRequestModal');
      await expect(dialog).toBeVisible();
      await expect(dialog.locator('.ra-approve-summary')).toContainText(`Group ${s.groupName}`);
      await expect(dialog.locator('#approveDuration')).toHaveValue('requested');
      await expect(dialog.locator('#approveDuration')).toBeFocused();
      await expect(dialog.locator('#approveDurationHelp')).toContainText('added to the current expiry');
      expect(await requestStatus(playwright, s.extensionRequestId)).toBe('PENDING');

      await page.keyboard.press('Escape');
      await expect(dialog).toBeHidden();
      expect(await requestStatus(playwright, s.extensionRequestId)).toBe('PENDING');
    });

  test('"No expiry" grants without an expiry even though 30 days were requested', async ({ page, playwright }) => {
    const s = await seed(playwright);
    await openAccessManagementPending(page);

    const card = page.locator(`.access-request-card[data-request-id="${s.newRequestId}"]`);
    await card.locator('[data-action="approve"]').click();
    await page.locator('#approveDuration').selectOption('none');
    await page.locator('#confirmApprove').click();
    await expect(page.locator('#approveRequestModal')).toBeHidden({ timeout: 10_000 });
    await expect(card).toHaveCount(0);

    expect(await requestStatus(playwright, s.newRequestId)).toBe('APPROVED');
    const grant = await grantOf(playwright, s.envId, SECOND_USER);
    expect(grant).toBeTruthy();
    expect(grant.expiresAt).toBeNull();
  });

  test('"As requested" on an extension adds the requested days to the current expiry', async ({ page, playwright }) => {
    const s = await seed(playwright);
    await goTo(page, '#/pending-requests');

    await page.locator(`[data-action="approve"][data-request-id="${s.extensionRequestId}"]`).click();
    await expect(page.locator('#approveDuration')).toHaveValue('requested');
    await page.locator('#approveComments').fill('Approved for the release window');
    await page.locator('#confirmApprove').click();
    await expect(page.locator('#approveRequestModal')).toBeHidden({ timeout: 10_000 });

    expect(await requestStatus(playwright, s.extensionRequestId)).toBe('APPROVED');
    const admin = await api(playwright, devIds.admin);
    const grants = await (await admin.get(`/api/v1/environments/${s.envId}/access`)).json();
    await admin.dispose();
    const grant = grants.find((g: any) => g.userId === devIds.user && g.scopeType === 'GROUP');
    // E04-T05: old expiry (5 days away) + 30 days.
    expect(Math.abs(new Date(grant.expiresAt).getTime() - (s.currentExpiresAt + 30 * DAY_MS))).toBeLessThan(60_000);
  });

  test('a request reviewed meanwhile by someone else shows a warning and drops off the list', async ({ page, playwright }) => {
    const s = await seed(playwright);
    await openAccessManagementPending(page);

    const card = page.locator(`.access-request-card[data-request-id="${s.newRequestId}"]`);
    await card.locator('[data-action="approve"]').click();
    await expect(page.locator('#approveRequestModal')).toBeVisible();

    // Another reviewer denies it while the dialog is open.
    const admin = await api(playwright, devIds.admin);
    expect((await admin.post(`/api/v1/access-requests/${s.newRequestId}/deny`, { data: { notes: 'No' } })).ok()).toBeTruthy();
    await admin.dispose();

    await page.locator('#confirmApprove').click();
    await expect(page.getByText('Someone else already reviewed this request')).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('#approveRequestModal')).toBeHidden();
    await expect(card).toHaveCount(0);
    expect(await requestStatus(playwright, s.newRequestId)).toBe('DENIED');
  });
});
