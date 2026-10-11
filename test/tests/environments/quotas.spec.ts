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
    await expect(page.locator('#confirmBtn')).toBeEnabled();
    await page.locator('#confirmModal .btn-close').click(); // works even while the dialog is still fading in
    await expect(page.locator('.modal.show')).toHaveCount(0, { timeout: 10_000 });

    usage = [quota({ used: 95, enforcement: 'BLOCK' })];
    await page.locator('#btn-env-action').click();
    await expect(line).toContainText('(over the limit: blocked)');
    await expect(line).toHaveAttribute('data-quota', 'block');
    await expect(page.locator('#confirmBtn')).toBeDisabled();
    await expect(page.locator('#confirmBtn')).toHaveAttribute('title', 'The team quota would be exceeded');
  });

  test('an admin sets a cost quota on the Teams page and sees the usage bar in dollars', async ({ page, playwright }) => {
    const { teamName } = await teamWithEnv(playwright, 'Quota edit');
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/teams'; });
    await page.locator('[data-action="team-select"]', { hasText: teamName }).click();
    await expect(page.locator('#team-quotas')).toContainText('No quotas', { timeout: 10_000 });

    await page.locator('#quota-on-COST').check();
    await page.locator('#quota-limit-COST').fill('250');
    await page.locator('#quota-enf-COST').selectOption('REQUIRE_APPROVAL');
    await page.locator('[data-action="team-save-quotas"]').click();

    const bar = page.locator('#team-quotas .quota-bar[data-metric="COST"]');
    await expect(bar).toContainText('Spend: $0.00 of $250.00', { timeout: 10_000 });
    await expect(page.locator('#quota-enf-COST')).toHaveValue('REQUIRE_APPROVAL');
  });

  test('My Account shows my teams quota usage', async ({ page }) => {
    await page.route(/\/api\/v1\/teams\/mine\/quota-usage$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify([{ teamId: 't1', teamName: 'Platform', quotas: [quota({ used: 85, percent: 85 }),
        quota({ metric: 'COST', limit: 500, used: 120, percent: 24 })] }]) }));
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof MyAccount !== 'undefined');
    await page.evaluate(() => MyAccount.open('cost'));
    const card = page.locator('.ma-quota-card[data-team="t1"]');
    await expect(card).toContainText('Platform', { timeout: 10_000 });
    await expect(card.locator('.quota-bar[data-metric="VM_HOURS"]')).toContainText('VM-hours: 85 h of 100 h');
    await expect(card.locator('.quota-bar[data-metric="COST"]')).toContainText('Spend: $120.00 of $500.00');
  });
});
