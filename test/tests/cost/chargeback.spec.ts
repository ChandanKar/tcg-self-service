import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';
import { withFeatures } from '../../fixtures/capabilities';

/**
 * E23-T08, U15-T09 (Chargeback.dc.html, ChargebackRules.dc.html): the Chargeback page. The statement
 * (faked) shows the missing-team banner, expands per team and is finalised and sent behind a
 * type-to-confirm; the inline rule editor (real API) keeps Save rule disabled until fixed percentages
 * add up to 100; Missing tags lists environments with no team and tags the VMs; at phone width the
 * statement is cards. Non-admins are refused.
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
    month: MONTH, status, generatedAt: new Date().toISOString(), dataFrom: `${MONTH}-01`, dataThrough: `${MONTH}-28`,
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
  await page.route(/\/api\/v1\/cost-management\/chargeback\/[0-9-]+\/finalize\?send=true$/, route => {
    finalized = true;
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...statement('FINAL'), emailed: true }) });
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

  test('U22-T07: with chargeback switched off a draft cannot be finalised and the page says why', async ({ page }) => {
    await withFeatures(page, { chargeback: false });
    await fakeStatement(page);
    await open(page);
    await expect(page.locator('#chargeback-status')).toHaveText('DRAFT', { timeout: 15_000 });
    await expect(page.locator('#chargeback-finalize')).toHaveCount(0);
    await expect(page.locator('#chargeback-foot')).toContainText('Chargeback is switched off, so statements are not finalised or sent to finance.');
  });

  test('a draft statement: the missing-team banner, the columns, expands per team, finalised and sent behind a typed month', async ({ page }) => {
    await withFeatures(page, { chargeback: true });
    const finalized = await fakeStatement(page);
    await open(page);

    await expect(page.locator('#chargeback-status')).toHaveText('DRAFT', { timeout: 15_000 });
    await expect(page.locator('#chargeback-month')).toHaveValue(MONTH);
    await expect(page.locator('#chargeback-grand-total')).toHaveText('$370');
    await expect(page.locator('#chargeback-table thead th')).toHaveText(['Team', 'Cost centre', 'Own environments', 'Share of shared',
      'Total', 'From AWS billing']);
    await expect(page.locator('#chargeback-banner')).toContainText('1 environment has no team, so $20 sits in Unassigned.');
    await expect(page.locator('#chargeback-banner').getByRole('link', { name: 'Assign teams' })).toHaveAttribute('href', '#/teams');
    await expect(page.locator('#chargeback-foot')).toContainText("Draft. It is finalised early in");
    // U23-T02: the statement names its month and the days it covers (staging's $4,764 was read as November's).
    await expect(page.locator('#chargeback-period')).toContainText(', 1 to 28 ');
    await expect(page.locator('#chargeback-period')).toContainText('from the daily cost snapshots.');
    // The tab counts the real environments without a team (only the statement is faked here).
    await expect(page.getByRole('tab', { name: /^Missing tags · \d+$/ })).toBeVisible();

    const toggle = page.locator('.chargeback-toggle', { hasText: 'Team X' });
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await toggle.click();
    await expect(page.locator('.chargeback-toggle', { hasText: 'Team X' })).toHaveAttribute('aria-expanded', 'true');
    await expect(page.locator('.chargeback-detail')).toContainText('Shared S');
    await expect(page.locator('.chargeback-detail')).toContainText('shared');

    // Acceptance: Finalise and send, confirmed by typing the month; the answer says finance was emailed.
    await page.getByRole('button', { name: 'Finalise and send to finance' }).click();
    await expect(page.locator('#btn-destructive-confirm')).toBeDisabled();
    await page.locator('#confirm-text-input').fill(MONTH);
    await page.locator('#btn-destructive-confirm').click();
    await expect.poll(finalized).toBe(true);
    await expect(page.locator('#chargeback-status')).toHaveText('FINAL');
    await expect(page.locator('#chargeback-foot')).toContainText("Final. The numbers don't change.");
    await expect(page.locator('#chargeback-finalize')).toHaveCount(0);
    await expect(page.locator('.toast, .notification-toast').first()).toContainText('finalised and sent to finance');
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
    await expect(page.locator('#chargeback-month option:checked')).toContainText('(so far)');
    await expect(page.locator('#chargeback-foot')).toHaveText('So far this month; the numbers change until the month ends.');

    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Download, Excel' }).click();
    expect((await download).suggestedFilename()).toBe(`chargeback-${current}.xlsx`);
    expect(exports[0]).toContain(`month=${current}`);
    expect(exports[0]).toContain('format=xlsx');
  });

  test('fixed percentages must add up to 100 before a rule can be saved', async ({ page, playwright }) => {
    const stamp = Date.now();
    const envId = await seedEnvironment(playwright, 'Shared CI');
    const x = await createTeam(playwright, `Split X ${stamp}`);
    const y = await createTeam(playwright, `Split Y ${stamp}`);
    expect(envId && x && y).toBeTruthy();

    await open(page, '#/chargeback?tab=rules');
    await expect(page.locator('.csr-list')).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('.csr-list-title')).toHaveText('One rule per environment');
    await page.getByRole('button', { name: '+ New rule' }).click();
    const editor = page.locator('#csr-editor');
    await editor.locator('#csr-environment').selectOption(envId);
    await editor.getByRole('radio', { name: 'Fixed percentages' }).check();
    const teams = editor.locator('.csr-team');
    const percents = editor.locator('.csr-percent');
    await teams.nth(0).selectOption(x);
    await teams.nth(1).selectOption(y);
    await percents.nth(0).fill('60');
    await percents.nth(1).fill('35');

    // Acceptance: shares adding up to 95% keep Save rule disabled and say so.
    await expect(editor.locator('#csr-sum')).toHaveText('95%, needs 100%');
    await expect(editor.getByRole('button', { name: 'Save rule' })).toBeDisabled();
    await percents.nth(1).fill('40');
    await expect(editor.locator('#csr-sum')).toHaveText('100% ✓');
    await expect(editor.getByRole('button', { name: 'Save rule' })).toBeEnabled();
    await editor.getByRole('button', { name: 'Save rule' }).click();

    const rule = page.locator('.csr-rule', { hasText: 'Shared CI' });
    await expect(rule.first()).toContainText('Fixed percentages · 2 teams', { timeout: 10_000 });
    await expect(page.locator('#csr-editor .csr-percent').nth(0)).toHaveValue('60');
    await expect(page.getByRole('button', { name: 'Delete rule' })).toBeVisible();
  });

  test('Missing tags lists environments with no team; Tag the N now writes the tags and refreshes the count', async ({ page }) => {
    await fakeStatement(page);
    let reconciled = 0;
    await page.route(/\/api\/v1\/cost-management\/tags\/status$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ tagged: reconciled ? 53 : 47, untagged: reconciled ? 0 : 6 }) }));
    await page.route(/\/api\/v1\/cost-management\/tags\/reconcile$/, route => {
      reconciled++;
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ tagged: 6, failed: 0, total: 6 }) });
    });
    await withFeatures(page, { costTagging: true });
    await open(page, '#/chargeback?tab=tags');

    await expect(page.getByRole('tab', { name: 'Missing tags · 7' })).toHaveAttribute('aria-selected', 'true', { timeout: 15_000 });
    await expect(page.locator('#chargeback-untagged-envs')).toContainText('Loose');
    await expect(page.locator('#chargeback-tags-card')).toContainText('6 missing');
    // Acceptance: with 6 VMs untagged and tagging on, Tag the 6 now runs the reconcile and the count refreshes.
    await page.getByRole('button', { name: 'Tag the 6 now' }).click();
    await expect.poll(() => reconciled).toBe(1);
    await expect(page.locator('#chargeback-tags-card')).toContainText('0 missing');
    await expect(page.getByRole('tab', { name: 'Missing tags · 1' })).toBeVisible();
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
    await expect(page.locator('#content-area')).toContainText('is for administrators', { timeout: 15_000 });
  });
});
