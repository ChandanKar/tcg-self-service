import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E23-T08: the Chargeback page. The statement (faked) expands per team and finalizes behind a
 * type-to-confirm; the split-rule editor (real API) keeps Save disabled until fixed percentages
 * add up to 100; at phone width the statement is cards. Non-admins are refused.
 */

declare const ContentRouter: any;

function lastMonth(): string {
  const d = new Date();
  const m = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() - 1, 1));
  return `${m.getUTCFullYear()}-${String(m.getUTCMonth() + 1).padStart(2, '0')}`;
}

const MONTH = lastMonth();

function statement(status: 'DRAFT' | 'FINAL') {
  return {
    month: MONTH, status, generatedAt: new Date().toISOString(),
    dataSource: 'Estimates only (no AWS billing data for this month)',
    rows: [
      { teamId: 't-x', teamName: 'Team X', costCenter: 'CC-X', directCost: 300, sharedAllocated: 50, total: 350, actualCoveragePct: 0,
        environments: [{ environmentId: 'e-a', environmentName: 'Env A', amount: 300, shared: false },
          { environmentId: 'e-s', environmentName: 'Shared S', amount: 50, shared: true }] },
      { teamId: null, teamName: 'Unassigned', costCenter: null, directCost: 20, sharedAllocated: 0, total: 20, actualCoveragePct: 0,
        environments: [{ environmentId: 'e-u', environmentName: 'Loose', amount: 20, shared: false }] },
    ],
    totals: { teamId: null, teamName: 'Total', costCenter: null, directCost: 320, sharedAllocated: 50, total: 370, actualCoveragePct: 0, environments: [] },
  };
}

async function open(page: Page, hash = '#/chargeback', role: 'admin' | 'user' = 'admin') {
  await signIn(page, role);
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(h => { location.hash = h; }, hash);
}

async function fakeStatement(page: Page) {
  let finalized = false;
  await page.route(/\/api\/v1\/cost-management\/chargeback\?month=/, route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(statement(finalized ? 'FINAL' : 'DRAFT')) }));
  await page.route(/\/api\/v1\/cost-management\/chargeback\/[0-9-]+\/finalize$/, route => {
    finalized = true;
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(statement('FINAL')) });
  });
  return () => finalized;
}

async function createTeam(playwright: any, name: string): Promise<string> {
  const admin = await playwright.request.newContext({
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    extraHTTPHeaders: { 'X-User-Id': devIds.admin, 'Content-Type': 'application/json' },
  });
  const res = await admin.post('/api/v1/teams', { data: { displayName: name } });
  const id = (await res.json()).teamId;
  await admin.dispose();
  return id;
}

test.describe('Chargeback page', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('a draft statement expands per team and finalizes behind a typed month', async ({ page }) => {
    const finalized = await fakeStatement(page);
    await open(page);

    await expect(page.locator('#chargeback-status')).toHaveText('DRAFT', { timeout: 15_000 });
    await expect(page.locator('#chargeback-month')).toHaveValue(MONTH);
    await expect(page.locator('#chargeback-grand-total')).toHaveText('$370.00');
    await expect(page.locator('#chargeback-source')).toContainText('Estimates only');

    const toggle = page.locator('.chargeback-toggle', { hasText: 'Team X' });
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await toggle.click();
    await expect(page.locator('.chargeback-toggle', { hasText: 'Team X' })).toHaveAttribute('aria-expanded', 'true');
    await expect(page.locator('.chargeback-detail')).toContainText('Shared S');
    await expect(page.locator('.chargeback-detail')).toContainText('shared');

    await page.locator('#chargeback-finalize').click();
    await expect(page.locator('#btn-destructive-confirm')).toBeDisabled();
    await page.locator('#confirm-text-input').fill(MONTH);
    await page.locator('#btn-destructive-confirm').click();
    await expect.poll(finalized).toBe(true);
    await expect(page.locator('#chargeback-status')).toHaveText('FINAL');
    await expect(page.locator('#chargeback-finalize')).toHaveCount(0);
  });

  test('the current month cannot be finalized and downloads hit the export endpoint', async ({ page }) => {
    await fakeStatement(page);
    const exports: string[] = [];
    await page.route(/\/api\/v1\/cost-management\/chargeback\/export\?/, route => {
      exports.push(route.request().url());
      return route.fulfill({ status: 200, contentType: 'text/csv', body: '"Team"\r\n' });
    });
    const d = new Date();
    const current = `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
    await open(page, `#/chargeback?month=${current}`);
    await expect(page.locator('#chargeback-status')).toHaveText('DRAFT', { timeout: 15_000 });
    await expect(page.locator('#chargeback-finalize')).toHaveCount(0);

    const download = page.waitForEvent('download');
    await page.locator('[data-action="chargeback-download"][data-format="csv"]').click();
    expect((await download).suggestedFilename()).toBe(`chargeback-${current}.csv`);
    expect(exports[0]).toContain(`month=${current}`);
    expect(exports[0]).toContain('format=csv');
  });

  test('fixed percentages must add up to 100 before a rule can be saved', async ({ page, playwright }) => {
    const stamp = Date.now();
    const envId = await seedEnvironment(playwright, 'Shared CI');
    const x = await createTeam(playwright, `Split X ${stamp}`);
    const y = await createTeam(playwright, `Split Y ${stamp}`);
    expect(envId && x && y).toBeTruthy();

    await open(page, '#/chargeback?tab=rules');
    await expect(page.locator('#split-rules-table')).toBeVisible({ timeout: 15_000 });
    await page.locator('[data-action="chargeback-rule-new"]').click();
    const modal = page.locator('#splitRuleModal');
    await expect(modal).toBeVisible();
    await modal.locator('#csr-environment').selectOption(envId);
    await modal.locator('#csr-method').selectOption('FIXED_PERCENT');
    const teams = modal.locator('.csr-team');
    const percents = modal.locator('.csr-percent');
    await teams.nth(0).selectOption(x);
    await teams.nth(1).selectOption(y);
    await percents.nth(0).fill('60');
    await percents.nth(1).fill('35');

    await expect(modal.locator('#csr-sum')).toHaveText('Sum = 95.00% (must be 100%)');
    await expect(modal.locator('#csr-save')).toBeDisabled();
    await percents.nth(1).fill('40');
    await expect(modal.locator('#csr-sum')).toHaveText('Sum = 100.00%');
    await expect(modal.locator('#csr-save')).toBeEnabled();
    await modal.locator('#csr-save').click();

    const row = page.locator('#split-rules-table tbody tr', { hasText: `Split X ${stamp} 60%` });
    await expect(row).toHaveCount(1, { timeout: 10_000 });
    await expect(row).toContainText('Shared CI');
    await expect(row).toContainText(`Split Y ${stamp} 40%`);
    await expect(row).toContainText('Fixed percentages');
  });

  test('at phone width the statement is cards', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await fakeStatement(page);
    await open(page);
    await expect(page.locator('#chargeback-status')).toHaveText('DRAFT', { timeout: 15_000 });
    await expect(page.locator('#chargeback-table thead')).toBeHidden();
    const cell = page.locator('#chargeback-table tbody td[data-label="Total"]').first();
    await expect(cell).toBeVisible();
    const scroll = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(scroll).toBeLessThanOrEqual(0);
  });

  test('non-admins are refused', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await open(page, '#/chargeback', 'user');
    await expect(page.locator('#content-area')).toContainText('Access denied', { timeout: 15_000 });
  });
});
