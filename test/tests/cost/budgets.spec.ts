import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E20-T05: the Budgets page and the budget panel on the environment page. Budgets are created
 * through the UI against the smoke app; a pending stop action and a frozen environment are faked
 * in the browser so nothing is really stopped.
 */

declare const ContentRouter: any;

async function open(page: Page, hash: string, role: 'admin' | 'user' = 'admin') {
  await signIn(page, role);
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(h => { location.hash = h; }, hash);
}

async function fillBudgetForm(page: Page, amount: string) {
  await expect(page.locator('#budgetModal')).toBeVisible();
  await page.locator('#budget-amount').fill(amount);
  // Actions are off on the smoke app: only Alert only can be chosen.
  await expect(page.locator('input[name="budget-action"][value="FREEZE_STARTS"]')).toBeDisabled();
  await expect(page.locator('input[name="budget-action"][value="NONE"]')).toBeChecked();
  await page.locator('#budget-save-btn').click();
  await expect(page.locator('#budgetModal')).toBeHidden({ timeout: 10_000 });
}

test.describe('Budgets', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('an admin creates an environment budget on the Budgets page', async ({ page, playwright }) => {
    const environmentId = await seedEnvironment(playwright, 'Budget page');
    test.skip(!environmentId, 'Could not seed an environment');
    await open(page, '#/budgets');
    await expect(page.locator('#budget-new-btn')).toBeVisible({ timeout: 15_000 });

    await page.locator('#budget-new-btn').click();
    await page.locator('#budget-env').selectOption(environmentId);
    await fillBudgetForm(page, '500');

    // U12-T07: a row with its bar, "$0 of $500" and the action in words.
    const row = page.locator('#budgets-list .bd-row', { has: page.locator(`a[href="#/environments/${environmentId}"]`) });
    await expect(row).toContainText('of $500');
    await expect(row.locator('.budget-bar')).toHaveCount(1);
    await expect(row.locator('.bd-action')).toHaveText('Alerts only');
    await expect(page.locator('#budget-totals')).toContainText(/October|January|February|March|April|May|June|July|August|September|November|December/);
    await expect(page.locator('#budgets-view')).toContainText('Spend uses AWS billing where available');
  });

  test('an owner sets a budget from the environment page and sees the bar', async ({ page, playwright }) => {
    const environmentId = await seedEnvironment(playwright, 'Budget env');
    test.skip(!environmentId, 'Could not seed an environment');
    await open(page, `#/environments/${environmentId}`);

    await page.locator('[data-action="budget-set-env"]').click({ timeout: 15_000 });
    await expect(page.locator('#budget-env')).toBeDisabled();
    await fillBudgetForm(page, '1000');

    await expect(page.locator('#env-budget-summary')).toContainText('of the $1,000.00 budget');
    await expect(page.locator('.budget-env-card .budget-bar')).toHaveCount(1);
  });

  test('approving a pending stop needs the environment name typed', async ({ page }) => {
    let approved = '';
    await page.route(/\/api\/v1\/budgets\/actions\?status=PENDING/, route => route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify([{
        requestId: 'req-1', budgetId: 'b-1', environmentId: 'env-x', environmentName: 'Payments QA', month: '2026-09',
        actionType: 'STOP_ENVIRONMENT', status: 'PENDING', monthlyAmount: 500, requestedAt: new Date().toISOString(),
      }]),
    }));
    await page.route(/\/api\/v1\/budgets\/actions\/req-1\/approve/, route => {
      approved = route.request().method();
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{"requestId":"req-1","status":"APPROVED"}' });
    });
    await open(page, '#/budgets');

    const row = page.locator('.budget-action-row', { hasText: 'Payments QA' });
    await expect(row).toContainText('stop environment');
    await row.getByRole('button', { name: 'Approve' }).click();
    await expect(page.locator('#destructiveConfirmModal')).toBeVisible();
    await expect(page.locator('#btn-destructive-confirm')).toBeDisabled();
    await page.locator('#confirm-text-input').fill('Payments QA');
    await page.locator('#btn-destructive-confirm').click();
    await expect.poll(() => approved).toBe('POST');
  });

  test('a frozen environment shows the red Budget alert with Lift freeze for owners', async ({ page, playwright }) => {
    const environmentId = await seedEnvironment(playwright, 'Frozen env');
    test.skip(!environmentId, 'Could not seed an environment');
    const until = new Date(Date.now() + 5 * 86_400_000).toISOString();
    await page.route(new RegExp(`/api/v1/environments/${environmentId}/budget$`), route => route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify({
        budgetId: 'b-frozen', scopeType: 'ENVIRONMENT', scopeId: environmentId, scopeName: 'Frozen env',
        monthlyAmount: 100, thresholds: [50, 75, 90, 100], forecastAlert: true, actionType: 'FREEZE_STARTS', enabled: true,
        month: '2026-09', spendToDate: 120, percentUsed: 120, forecastMonthEnd: 300, forecastPercent: 300,
        startFrozenUntil: until,
      }),
    }));
    await open(page, `#/environments/${environmentId}`);

    // U07-T07: the first alert in the page's stack, role=alert.
    const banner = page.locator('#env-alerts [data-kind="budget"]');
    await expect(banner).toContainText('Starts are blocked.', { timeout: 15_000 });
    await expect(banner).toContainText('passed its September budget of $100.00, so new starts are frozen until');
    await expect(banner).toHaveAttribute('role', 'alert');
    await expect(banner.getByRole('button', { name: 'Lift freeze' })).toBeVisible();
    await expect(page.locator('.budget-env-card .budget-over')).toHaveCount(1);
  });

  test('while frozen, an operator cannot start: Start is disabled with the reason', async ({ page, playwright }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    const environmentId = await seedEnvironment(playwright, 'Frozen operator env', { grantUser: true });
    test.skip(!environmentId, 'Could not seed an environment');
    await page.route(new RegExp(`/api/v1/environments/${environmentId}/budget$`), route => route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify({
        budgetId: 'b-frozen-2', scopeType: 'ENVIRONMENT', scopeId: environmentId, scopeName: 'Frozen operator env',
        monthlyAmount: 300, thresholds: [100], forecastAlert: false, actionType: 'FREEZE_STARTS', enabled: true,
        month: '2026-10', spendToDate: 310, percentUsed: 103, forecastMonthEnd: null, forecastPercent: null,
        startFrozenUntil: new Date(Date.now() + 86_400_000).toISOString(),
      }),
    }));
    await open(page, `#/environments/${environmentId}`, 'user');

    await expect(page.locator('#env-alerts [data-kind="budget"]')).toContainText('An owner can lift it. Stops still work.', { timeout: 15_000 });
    const start = page.locator('#btn-env-action');
    await expect(start).toBeDisabled();
    await expect(start).toHaveAttribute('title', 'Starts are blocked by the budget');
  });

  test('a member without a budget sees no budget panel', async ({ page, playwright }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    const environmentId = await seedEnvironment(playwright, 'Member env', { grantUser: true });
    test.skip(!environmentId, 'Could not seed an environment');
    await open(page, `#/environments/${environmentId}`, 'user');
    await expect(page.locator('#env-cost-card')).toBeVisible({ timeout: 15_000 });
    await page.waitForLoadState('networkidle');
    await expect(page.locator('#env-budget-panel')).toBeEmpty();
  });

  /** U12-T07 (Budgets.dc.html): month totals and a row per budget; the list is faked. */
  test('rows: chips, forecast tick capped at the end, team rows and the totals', async ({ page }) => {
    const b = (over: any) => ({ budgetId: `b-${Math.random()}`, scopeType: 'ENVIRONMENT', scopeId: 'env-x', scopeName: 'X', monthlyAmount: 100,
      thresholds: [50, 75, 90, 100], forecastAlert: true, actionType: 'NONE', enabled: true, month: '2026-10', spendToDate: 0,
      percentUsed: 0, forecastMonthEnd: 0, forecastPercent: 0, startFrozenUntil: null, environmentCount: 1, ...over });
    await page.route(/\/api\/v1\/budgets$/, route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([
      b({ scopeId: 'env-pay', scopeName: 'Payments Dev', monthlyAmount: 400, spendToDate: 186, percentUsed: 46.5, forecastMonthEnd: 310, forecastPercent: 77.5 }),
      b({ scopeId: 'env-ana', scopeName: 'Analytics QA', monthlyAmount: 150, spendToDate: 122, percentUsed: 81.3, forecastMonthEnd: 205,
        forecastPercent: 136.7, actionType: 'FREEZE_STARTS' }),
      b({ scopeType: 'TEAM', scopeId: 'CRM', scopeName: 'CRM', monthlyAmount: 300, spendToDate: 288, percentUsed: 96, forecastMonthEnd: 470,
        forecastPercent: 156.7, environmentCount: 3 }),
    ]) }));
    await open(page, '#/budgets');

    const totals = page.locator('#budget-totals');
    await expect(totals).toContainText('Spent so far $308', { timeout: 15_000 });
    await expect(totals).toContainText('Forecast for the month $515');
    await expect(totals).toContainText(/\w+ · day \d+ of \d+/);

    const pay = page.locator('.bd-row', { hasText: 'Payments Dev' });
    await expect(pay.locator('.bd-chip')).toHaveText('On track');
    await expect(pay.locator('.bd-line')).toContainText('$186 of $400 · forecast $310');
    await expect(pay.locator('.budget-bar')).toHaveAttribute('aria-label', '47% spent, forecast 78%');

    // Acceptance: 122 of 150, forecast 205: Forecast over, the tick at the right end.
    const ana = page.locator('.bd-row', { hasText: 'Analytics QA' });
    await expect(ana.locator('.bd-chip')).toHaveText('Forecast over');
    await expect(ana.locator('.budget-tick')).toHaveAttribute('style', 'left:100%');
    await expect(ana.locator('.bd-action')).toContainText('When reached, an owner approves freezing starts');

    const crm = page.locator('.bd-row', { hasText: 'CRM team' });
    await expect(crm.locator('.bd-kind')).toHaveText('· team · 3 environments');
    await expect(crm.locator('.bd-chip')).toHaveText('96% reached');
    await expect(crm.locator('.bd-action')).toHaveText('Alerts only (team budgets cannot freeze or stop)');
  });

  test('the dialog: up to 4 alerts as chips, and a team budget cannot freeze or stop', async ({ page }) => {
    let sent: any = null;
    await page.route(/\/api\/v1\/budgets\/config$/, route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      actionsEnabled: true, environments: [{ environmentId: 'env-ana', name: 'Analytics QA' }], teams: ['CRM'] }) }));
    await page.route(/\/api\/v1\/budgets$/, route => {
      if (route.request().method() === 'POST') {
        sent = route.request().postDataJSON();
        return route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ message: 'This environment already has a budget' }) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    });
    await open(page, '#/budgets');
    await page.locator('#budget-new-btn').click();
    const dialog = page.locator('#budgetModal');
    await expect(dialog).toContainText('A monthly limit. It starts again on the 1st.');
    await expect(dialog).toContainText("You can't move a budget to something else later.");

    // Acceptance: four thresholds, so Add is off and says 4 of 4.
    await expect(dialog.locator('.bd-tchip')).toHaveText(['50%', '75%', '90%', '100%'].map(t => new RegExp(`^${t}`)));
    await expect(page.locator('#budget-threshold-count')).toHaveText('4 of 4');
    await expect(page.locator('#budget-threshold-add')).toBeDisabled();
    await dialog.getByRole('button', { name: 'Remove 75%' }).click();
    await expect(page.locator('#budget-threshold-count')).toHaveText('3 of 4');
    await page.locator('#budget-threshold-input').fill('90');
    await page.locator('#budget-threshold-add').click();
    await expect(page.locator('#budget-error')).toHaveText('90% is already there.');
    await page.locator('#budget-threshold-input').fill('120');
    await page.locator('#budget-threshold-input').press('Enter');
    await expect(page.locator('#budget-threshold-count')).toHaveText('4 of 4');

    const freeze = page.locator('input[name="budget-action"][value="FREEZE_STARTS"]');
    const stop = page.locator('input[name="budget-action"][value="STOP_ENVIRONMENT"]');
    await expect(freeze).toBeEnabled();
    await dialog.getByText('Freeze new starts').click();
    await expect(freeze).toBeChecked();
    // Acceptance: a team budget offers neither, and says why.
    await page.locator('#budget-scope-type').selectOption('TEAM');
    await expect(freeze).toBeDisabled();
    await expect(stop).toBeDisabled();
    await expect(page.locator('#budget-action-cards')).toContainText('Not offered for team budgets.');
    await expect(page.locator('input[name="budget-action"][value="NONE"]')).toBeChecked();
    await page.locator('#budget-scope-type').selectOption('ENVIRONMENT');

    await page.locator('#budget-amount').fill('150');
    await page.locator('#budget-save-btn').click();
    await expect(page.locator('#budget-error')).toHaveText('This environment already has a budget');
    expect(sent).toMatchObject({ scopeType: 'ENVIRONMENT', scopeId: 'env-ana', monthlyAmount: 150, thresholds: [50, 90, 100, 120],
      forecastAlert: true, actionType: 'NONE', enabled: true });
  });
});
