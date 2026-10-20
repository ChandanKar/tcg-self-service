import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * U10-T07 (AccessRequests.dc.html): Access requests shows one card per waiting request, oldest
 * first, with the part, how long and what the person has today; "Approve for" picks the length
 * (the requested one, 7 / 30 / 90 days or No end) and Approve decides it in place.
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

/** U10-T04: waiting requests left Access management for their own page (Access requests). */
async function openAccessManagementPending(page: Page) {
  await goTo(page, '#/access-management');
  await page.locator('#am-pending-link').click();
  await expect(page).toHaveURL(/#\/pending-requests$/);
}

/** The card of one request on Access requests. */
const cardOf = (page: Page, requestId: string) => page.locator(`.ar-card[data-request-id="${requestId}"]`);

/** "Operator until 26 Oct": the short end date the cards use. */
const until = (page: Page, ms: number) => page.evaluate((t) =>
  AccessLevels.endsText(new Date(t).toISOString()).text.split(' · ')[0], ms);

declare const AccessLevels: any;

test.describe('Reviewing access requests', () => {
  test.beforeEach(async () => {
    test.skip(!devIds.admin || !devIds.user || !canSignIn('admin'),
      'Needs TEST_DEV_USER_ID and TEST_DEV_ADMIN_ID (dev mode) to seed requests');
  });

  test('cards come oldest first with the reason, part, length and what they have today', async ({ page, playwright }) => {
    const s = await seed(playwright);
    await goTo(page, '#/pending-requests');
    await expect(page.locator('#content-area h1')).toHaveText('Access requests', { timeout: 10_000 });

    const ext = cardOf(page, s.extensionRequestId);
    await expect(ext).toBeVisible({ timeout: 10_000 });
    await expect(ext.locator('.ar-title')).toContainText('wants an extension on Review E2E');
    await expect(ext.locator('.ar-reason')).toHaveText('"Release testing runs longer"');
    await expect(ext.locator('[data-fact="scope"] dd')).toHaveText(`${s.groupName} group`);
    await expect(ext.locator('[data-fact="for"] dd')).toHaveText('30 more days');
    await expect(ext.locator('[data-fact="current"] dd')).toHaveText(`Operator until ${await until(page, s.currentExpiresAt)}`);
    await expect(ext.locator('[data-ar-for] option:checked')).toHaveText('30 more days');

    const fresh = cardOf(page, s.newRequestId);
    await expect(fresh.locator('.ar-title')).toContainText('wants operator access on Review E2E');
    await expect(fresh.locator('[data-fact="scope"] dd')).toHaveText('Whole environment');
    await expect(fresh.locator('[data-fact="for"] dd')).toHaveText('30 days');
    await expect(fresh.locator('[data-fact="current"] dd')).toHaveText('None');
    await expect(fresh.locator('[data-ar-for] option')).toHaveText(['30 days', '7 days', '90 days', 'No end']);

    // Oldest first: the cards' asked times never go back (two requests can share a second).
    const ids = await page.locator('.ar-card').evaluateAll(cards => cards.map(c => c.getAttribute('data-request-id')));
    const admin = await api(playwright, devIds.admin);
    const pending = await (await admin.get('/api/v1/access-requests/pending')).json();
    await admin.dispose();
    const asked = new Map(pending.map((r: any) => [r.requestId, new Date(r.createdAt).getTime()]));
    const times = ids.filter(id => asked.has(id)).map(id => asked.get(id) as number);
    expect(times.length).toBeGreaterThan(1);
    times.forEach((t, i) => { if (i > 0) expect(t).toBeGreaterThanOrEqual(times[i - 1]); });

    // The environment filter narrows the cards to one environment.
    await page.locator('#ar-env-filter').selectOption(s.envId);
    await expect(page.locator('.ar-card')).toHaveCount(2);
    await expect(page.locator('#ar-env-filter option').first()).toHaveText(/^All \(\d+ waiting\)$/);
  });

  test('Access management links here; "Approve for 7 days" sends durationDays=7 and the card goes', async ({ page, playwright }) => {
    const s = await seed(playwright);
    await openAccessManagementPending(page);

    const card = cardOf(page, s.newRequestId);
    await expect(card).toBeVisible({ timeout: 10_000 });
    await card.locator('[data-ar-for]').selectOption('7');
    const [req] = await Promise.all([
      page.waitForRequest(r => r.method() === 'POST' && r.url().endsWith(`/access-requests/${s.newRequestId}/approve`)),
      card.locator('[data-action="approve"]').click(),
    ]);
    expect(req.postDataJSON()).toEqual({ durationDays: 7 });
    await expect(page.getByText('Access approved.')).toBeVisible({ timeout: 10_000 });
    await expect(card).toHaveCount(0);

    expect(await requestStatus(playwright, s.newRequestId)).toBe('APPROVED');
    const grant = await grantOf(playwright, s.envId, SECOND_USER);
    expect(Math.abs(new Date(grant.expiresAt).getTime() - (Date.now() + 7 * DAY_MS))).toBeLessThan(5 * 60_000);
  });

  test('"No end" grants without an expiry even though 30 days were requested', async ({ page, playwright }) => {
    const s = await seed(playwright);
    await goTo(page, '#/pending-requests');

    const card = cardOf(page, s.newRequestId);
    await card.locator('[data-ar-for]').selectOption('none');
    await card.locator('[data-action="approve"]').click();
    await expect(card).toHaveCount(0, { timeout: 10_000 });

    expect(await requestStatus(playwright, s.newRequestId)).toBe('APPROVED');
    const grant = await grantOf(playwright, s.envId, SECOND_USER);
    expect(grant).toBeTruthy();
    expect(grant.expiresAt).toBeNull();
  });

  test('the requested length on an extension adds the days to the current expiry', async ({ page, playwright }) => {
    const s = await seed(playwright);
    await goTo(page, '#/pending-requests');

    const card = cardOf(page, s.extensionRequestId);
    await expect(card.locator('[data-ar-for]')).toHaveValue('30');
    await card.locator('[data-action="approve"]').click();
    await expect(card).toHaveCount(0, { timeout: 10_000 });

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
    await goTo(page, '#/pending-requests');
    const card = cardOf(page, s.newRequestId);
    await expect(card).toBeVisible({ timeout: 10_000 });

    // Another reviewer denies it while the page is open.
    const admin = await api(playwright, devIds.admin);
    expect((await admin.post(`/api/v1/access-requests/${s.newRequestId}/deny`, { data: { notes: 'No' } })).ok()).toBeTruthy();
    await admin.dispose();

    await card.locator('[data-action="approve"]').click();
    await expect(page.getByText('Someone else already reviewed this request')).toBeVisible({ timeout: 10_000 });
    await expect(card).toHaveCount(0);
    expect(await requestStatus(playwright, s.newRequestId)).toBe('DENIED');
  });

  test('Deny… opens the decline dialog; the reason is required and the requester gets it (U10-T08)', async ({ page, playwright }) => {
    const s = await seed(playwright);
    await goTo(page, '#/pending-requests');
    const card = cardOf(page, s.newRequestId);
    await card.locator('[data-action="deny"]').click();

    const dialog = page.locator('#declineRequestModal');
    await expect(dialog).toBeVisible();
    await expect(dialog.locator('.modal-title')).toHaveText("Decline Test's request");
    await expect(dialog.locator('.acd-sub')).toHaveText(/^Use Review E2E \d+ for 30 days · "Joining the release team"$/);
    await expect(dialog.locator('#ar-decline-reason')).toBeFocused();
    await expect(dialog.locator('#ar-decline')).toBeDisabled();
    await dialog.locator('#ar-decline-reason').fill('   ');
    await expect(dialog.locator('#ar-decline')).toBeDisabled();
    expect(await requestStatus(playwright, s.newRequestId)).toBe('PENDING');

    const reason = 'CRM UAT is frozen until the 4.1 sign-off.';
    await dialog.locator('#ar-decline-reason').fill(reason);
    await dialog.locator('#ar-decline').click();
    await expect(page.getByText('Request declined.')).toBeVisible({ timeout: 10_000 });
    await expect(dialog).toBeHidden();
    await expect(card).toHaveCount(0);
    expect(await requestStatus(playwright, s.newRequestId)).toBe('DENIED');

    const requester = await api(playwright, SECOND_USER);
    const envName = (await (await requester.get(`/api/v1/access-requests/${s.newRequestId}`)).json()).environmentName;
    await expect.poll(async () => {
      const body = await (await requester.get('/api/v1/notifications')).json();
      return (Array.isArray(body) ? body : (body.content || [])).map((n: any) => n.message);
    }, { timeout: 10_000 }).toContain(`Your request to use ${envName} was declined: ${reason}`);
    await requester.dispose();
  });

  test('declining without a reason is refused by the server', async ({ playwright }) => {
    const s = await seed(playwright);
    const admin = await api(playwright, devIds.admin);
    const res = await admin.post(`/api/v1/access-requests/${s.newRequestId}/deny`, { data: { notes: ' ' } });
    expect(res.status()).toBe(400);
    expect((await res.json()).message).toBe('Add a reason so the person knows what to do next');
    await admin.dispose();
    expect(await requestStatus(playwright, s.newRequestId)).toBe('PENDING');
  });
});

/**
 * U09-T02: the requester's side. Request access lists the environments one can ask for with the form
 * beside them (no dialog); the request carries its length and shows under Your requests with who
 * decides it; it can be cancelled there.
 */
test.describe('Request access (requester)', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID and TEST_DEV_ADMIN_ID (dev mode)');
  });

  async function freshEnv(playwright: any, label: string): Promise<{ id: string; name: string }> {
    const admin = await api(playwright, devIds.admin);
    const stamp = Date.now();
    const name = `${label} ${stamp}`;
    const env = await (await admin.post('/api/v1/environments', {
      data: { name: `${label.toLowerCase().replace(/\W+/g, '-')}-${stamp}`, displayName: name, description: 'For the request form', cloudProvider: 'AWS' },
    })).json();
    await admin.dispose();
    return { id: env.environmentId, name };
  }

  test('pick, fill the form beside the list, send 30 days, see it waiting with its reviewer, cancel it', async ({ page, playwright }) => {
    const env = await freshEnv(playwright, 'Ask Form');
    const posted: any[] = [];
    page.on('request', r => { if (r.method() === 'POST' && r.url().endsWith(`/environments/${env.id}/access-requests`)) posted.push(r.postDataJSON()); });
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(id => { location.hash = `#/request-access?env=${id}`; }, env.id);

    await expect(page.locator('#content-area h1')).toContainText('Request access', { timeout: 15_000 });
    await expect(page.getByRole('tab', { name: 'Request', exact: true })).toHaveAttribute('aria-selected', 'true');
    const row = page.locator(`[data-ra-env="${env.id}"]`);
    await expect(row).toHaveAttribute('aria-pressed', 'true');
    await expect(row).toContainText('For the request form · 0 VMs');
    const form = page.locator('#ra-form-card');
    await expect(form.locator('#ra-form-title')).toHaveText(`Request access to ${env.name}`);
    await expect(page.locator('#requestAccessModal')).toHaveCount(0);
    await expect(form.getByRole('radio', { name: /Operator/ })).toBeChecked();
    await expect(form.getByLabel('For')).toHaveValue('30');

    await form.getByRole('button', { name: 'Send request' }).click();
    await expect(form.locator('#ra-reason-error')).toHaveText('Say a little more: at least 10 characters.');
    expect(posted).toHaveLength(0);

    await form.getByLabel('Why you need it').fill('Regression testing for release 4.2');
    await form.getByLabel('For').selectOption('30');
    await form.getByRole('button', { name: 'Send request' }).click();
    await expect.poll(() => posted.length).toBe(1);
    expect(posted[0]).toMatchObject({ accessLevel: 'USER', durationDays: 30, scopeType: 'ENVIRONMENT' });

    await expect(page.getByRole('tab', { name: /^Your requests · \d+$/ })).toHaveAttribute('aria-selected', 'true', { timeout: 10_000 });
    const mine = page.locator('.ra-request', { hasText: env.name });
    await expect(mine.locator('.ra-chip')).toHaveText('Waiting');
    await expect(mine).toContainText(`Operator on ${env.name}, 30 days · sent`);
    await expect(mine).toContainText(/ago to .+/);

    await mine.getByRole('button', { name: 'Cancel request' }).click();
    await page.locator('#confirmBtn').click();
    await expect(page.locator('.ra-request', { hasText: env.name }).locator('.ra-chip')).toHaveText('Cancelled', { timeout: 10_000 });
  });

  test('a search with no match says so and clears', async ({ page }) => {
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/request-access'; });
    const search = page.getByLabel('Search environments');
    await expect(search).toBeVisible({ timeout: 15_000 });
    await search.fill('zz-no-such-environment-zz');
    await expect(page.locator('#ra-env-list')).toContainText('No environments match "zz-no-such-environment-zz".');
    await page.getByRole('button', { name: 'Clear search' }).click();
    await expect(page.locator('#ra-env-list .ra-env').first()).toBeVisible();
    await expect(page.locator('#ra-env-list .ra-env')).toHaveCount(await page.locator('#ra-env-list .ra-env').count());
    expect(await page.locator('#ra-env-list .ra-env').count()).toBeLessThanOrEqual(10);
  });

  test('10 environments to a page with a pager, and no page scrollbar at 1440x900', async ({ page, playwright }) => {
    for (let i = 0; i < 11; i++) await freshEnv(playwright, `Fill ${i}`);   // more than a page, whatever is there
    await page.setViewportSize({ width: 1440, height: 900 });
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/request-access'; });
    await expect(page.locator('#ra-env-list .ra-env')).toHaveCount(10, { timeout: 15_000 });
    await expect(page.locator('#ra-env-pager')).toContainText('Showing 1–10');
    expect(await page.evaluate(() => {
      const el = document.getElementById('mainContent') || document.documentElement;
      return el.scrollHeight <= el.clientHeight + 1 && document.documentElement.scrollHeight <= window.innerHeight + 1;
    })).toBe(true);
  });
});
