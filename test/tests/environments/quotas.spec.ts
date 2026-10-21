import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E25-T08: team quotas in the UI. The start dialog projects the team's usage (warning style near
 * the limit; Start disabled when a BLOCK quota would be exceeded), the Teams page edits quotas
 * (real API) and My Account shows my teams' usage. Quota usage is faked where a month of real
 * runtime would be needed.
 */

declare const ContentRouter: any;
declare const MyAccount: any;

const BASE = process.env.BASE_URL || 'http://localhost:8080';

async function admin(playwright: any) {
  return playwright.request.newContext({ baseURL: BASE, extraHTTPHeaders: { 'X-User-Id': devIds.admin, 'Content-Type': 'application/json' } });
}

/** A team owning a fresh environment; returns both ids. */
async function teamWithEnv(playwright: any, label: string) {
  const envId = await seedEnvironment(playwright, label);
  const api = await admin(playwright);
  const team = await (await api.post('/api/v1/teams', { data: { displayName: `${label} team ${Date.now()}` } })).json();
  await api.put(`/api/v1/environments/${envId}`, { data: { teamId: team.teamId } });
  await api.dispose();
  return { envId, teamId: team.teamId, teamName: team.displayName };
}

function preview(vms = 2, hours = 8, total = 1.54) {
  return { hours, hourlyTotal: 0.19, estimatedTotal: total, currency: 'USD', unknownPriceCount: 0, alreadyRunningCount: 0, approximate: false,
    vms: Array.from({ length: vms }, (_, i) => ({ vmId: `v${i}`, name: `vm-${i}`, instanceType: 'm5.large', nodeCount: 1,
      hourlyRate: 0.096, estimatedCost: 0.77, priceKnown: true, approximate: false })) };
}

function quota(over: any) {
  return { quotaId: 'q', metric: 'VM_HOURS', limit: 100, enforcement: 'WARN', warnPercent: 80, active: true, used: 70, percent: 70, ...over };
}

async function openStart(page: Page, envId: string) {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(id => { location.hash = `#/environments/${id}`; }, envId);
  await expect(page.locator('#btn-env-action')).toBeVisible({ timeout: 15_000 });
  await page.locator('#btn-env-action').click();
}

test.describe('Team quotas', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('near the limit the start dialog warns; past a BLOCK quota Start is disabled', async ({ page, playwright }) => {
    const { envId, teamId } = await teamWithEnv(playwright, 'Quota start');
    let usage = [quota({ used: 70 })];
    await page.route(/\/operations\/cost-preview/, route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(preview()) }));
    await page.route(new RegExp(`/api/v1/teams/${teamId}/quota-usage$`), route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify(usage) }));
    await openStart(page, envId);

    const line = page.locator('#quota-preview-line');
    await expect(line).toHaveText('Team quota: 70 h of 100 h used; this start adds ~16 h');
    await expect(line).toHaveAttribute('data-quota', 'warn'); // 86% projected, past the 80% warning level
    await expect(page.locator('#op-confirm-btn')).toBeEnabled();
    await page.locator('#operationConfirmModal .btn-close').click(); // works even while the dialog is still fading in
    await expect(page.locator('.modal.show')).toHaveCount(0, { timeout: 10_000 });

    usage = [quota({ used: 95, enforcement: 'BLOCK' })];
    await page.locator('#btn-env-action').click();
    await expect(line).toContainText('(over the limit: blocked)');
    await expect(line).toHaveAttribute('data-quota', 'block');
    await expect(page.locator('#op-confirm-btn')).toBeDisabled();
    await expect(page.locator('#op-confirm-btn')).toHaveAttribute('title', 'The team quota would be exceeded');
  });

  test('an admin sets the monthly limits on the team page (real API)', async ({ page, playwright }) => {
    const { teamId, teamName } = await teamWithEnv(playwright, 'Quota edit');
    await page.route(/\/api\/v1\/users\/me\/capabilities$/, async route => {
      const response = await route.fetch();
      const caps = await response.json();
      return route.fulfill({ response, body: JSON.stringify({ ...caps, features: { ...caps.features, quotas: true } }) });
    });
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    // U13-T08: the old #/teams?team=<id> link opens the team's page.
    await page.evaluate(id => { location.hash = '#/teams?team=' + id; }, teamId);
    await expect(page).toHaveURL(new RegExp(`#/teams/${teamId}$`), { timeout: 15_000 });
    await expect(page.locator('#team-detail-view h1')).toHaveText(teamName, { timeout: 15_000 });
    const hours = page.locator('#td-limits .td-limit[data-metric="VM_HOURS"]');
    await expect(hours).toContainText('No limit');

    // Acceptance: "Block new starts" at 3000 is saved as BLOCK with limit 3000.
    await hours.locator('[data-td-limit="limit"]').fill('3000');
    await hours.locator('[data-td-limit="enforcement"]').selectOption({ label: 'Block new starts' });
    const cost = page.locator('#td-limits .td-limit[data-metric="COST"]');
    await cost.locator('[data-td-limit="limit"]').fill('250');
    await cost.locator('[data-td-limit="enforcement"]').selectOption({ label: 'Ask an owner to approve each start' });
    await page.locator('#td-limits button[type="submit"]').click();
    // The bars come back with the team's overview only while the server's quota switch is on.
    await expect(page.locator('.notification-toast', { hasText: 'Limits saved.' })).toBeVisible({ timeout: 10_000 });

    const api = await admin(playwright);
    const quotas = await (await api.get(`/api/v1/teams/${teamId}/quotas`)).json();
    await api.dispose();
    const vmHours = quotas.find((q: any) => q.metric === 'VM_HOURS');
    expect(vmHours.enforcement).toBe('BLOCK');
    expect(Number(vmHours.limit)).toBe(3000);
    expect(quotas.find((q: any) => q.metric === 'COST').enforcement).toBe('REQUIRE_APPROVAL');
  });

  test('My Account shows my teams quota usage', async ({ page }) => {
    await page.route(/\/api\/v1\/teams\/mine\/quota-usage$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify([{ teamId: 't1', teamName: 'Platform', quotas: [quota({ used: 85, percent: 85 }),
        quota({ metric: 'COST', limit: 500, used: 120, percent: 24 })] }]) }));
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof MyAccount !== 'undefined');
    // Opening the first page closes any open panel; wait for it, or the panel can be shut right away.
    await expect(page.locator('#content-area h1').first()).toBeVisible({ timeout: 15_000 });
    await page.evaluate(() => MyAccount.open('cost'));
    const card = page.locator('.ma-quota-card[data-team="t1"]');
    await expect(card).toContainText('Platform', { timeout: 10_000 });
    await expect(card.locator('.quota-bar[data-metric="VM_HOURS"]')).toContainText('VM-hours: 85 h of 100 h');
    await expect(card.locator('.quota-bar[data-metric="COST"]')).toContainText('Spend: $120.00 of $500.00');
  });
});
