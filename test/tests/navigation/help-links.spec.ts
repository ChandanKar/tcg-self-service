import { test, expect } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/** E29-T07/T08: every page that has a help topic shows a working '?' button (rendered, not escaped). */

declare const ContentRouter: any;

const PAGES: [string, string][] = [
  ['#/my-environments', 'Help: Environments'],
  ['#/activity-logs', 'Help: Activity logs'],
  ['#/audit-logs-all', 'Help: Audit logs'],
  ['#/cost-management', 'Help: Cost management'],
  ['#/request-access', 'Help: Request access'],
  ['#/vm-registry', 'Help: VM registry'],
  ['#/automation-rules', 'Help: Automation rules'],
  ['#/budgets', 'Help: Budgets'],
  ['#/chargeback', 'Help: Chargeback'],
  ['#/approvals', 'Help: Approvals'],
  ['#/settings', 'Help: Settings'],
  ['#/savings', 'Help: Savings'],
  ['#/teams', 'Help: Teams'],
];

test('each page header has its help button and it opens an existing topic', async ({ page }) => {
  test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  test.setTimeout(120_000);
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  for (const [hash, label] of PAGES) {
    await page.evaluate(h => { location.hash = h; }, hash);
    const button = page.locator(`.content-header a.help-link[aria-label="${label}"]`);
    await expect(button, hash).toHaveCount(1, { timeout: 15_000 });
    await expect(page.locator('.content-header'), hash).not.toContainText('help-link'); // not escaped markup
    const topic = await button.getAttribute('data-help-topic');
    await button.click();
    await expect(page.locator('#help-topic-title'), `${hash} -> ${topic}`).toBeVisible();
  }
});
