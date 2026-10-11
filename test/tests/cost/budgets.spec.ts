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
  await expect(page.locator('#budget-action')).toBeDisabled(); // actions are off on the smoke app
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

    const row = page.locator('#budgets-table tr', { has: page.locator(`a[href="#/environments/${environmentId}"]`) });
    await expect(row).toContainText('$500.00');
    await expect(row.locator('.budget-bar')).toHaveCount(1);
    await expect(page.locator('#budgets-view')).toContainText('Spend uses AWS billing where available');
  });

  test('an owner sets a budget from the environment page and sees the bar', async ({ page, playwright }) => {
    const environmentId = await seedEnvironment(playwright, 'Budget env');
    test.skip(!environmentId, 'Could not seed an environment');
    await open(page, `#/environments/${environmentId}`);

    await page.locator('[data-action="budget-set-env"]').click({ timeout: 15_000 });
    await expect(page.locator('#budget-env')).toBeDisabled();
    await fillBudgetForm(page, '1000');

    await expect(page.locator('#env-budget-summary')).toContainText('of $1,000.00');
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

  test('a frozen environment shows the banner with Lift freeze for owners', async ({ page, playwright }) => {
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

    const banner = page.locator('.budget-freeze-banner');
    await expect(banner).toContainText('Starts are frozen until', { timeout: 15_000 });
    await expect(banner.getByRole('button', { name: 'Lift freeze' })).toBeVisible();
    await expect(page.locator('.budget-env-card .budget-over')).toHaveCount(1);
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
});
