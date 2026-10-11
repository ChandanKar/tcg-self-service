import { test, expect, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E13-T04: text on the key pages meets WCAG AA colour contrast (axe 'color-contrast').
 */

declare const ContentRouter: any;

async function contrastViolations(page: Page) {
  const result = await new AxeBuilder({ page }).withRules(['color-contrast']).analyze();
  return result.violations.flatMap(v => v.nodes.map(n => `${n.target.join(' ')} — ${n.any[0]?.message ?? v.help}`));
}

async function open(page: Page, hash: string, ready: string) {
  await page.evaluate(h => { location.hash = h; }, hash);
  await expect(page.locator(ready).first()).toBeVisible({ timeout: 15_000 });
  await page.waitForLoadState('networkidle');
  // Let entrance transitions settle so axe reads final colours.
  await page.waitForTimeout(600);
}

let seededEnvId = '';
test.beforeAll(async ({ playwright }) => {
  // Never depend on environments left by other runs (E13 a11y specs).
  seededEnvId = await seedEnvironment(playwright, 'Contrast E2E');
});

test.describe('Colour contrast', () => {
  test.beforeEach(async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.waitForLoadState('networkidle');
  });

  for (const [name, hash, ready] of [
    ['Dashboard', '#/dashboard', '#content-area .dashboard-shell, #content-area h1'],
    ['Activity Logs', '#/activity-logs', '#al-action-type-filter'],
    ['VM Registry', '#/vm-registry', '#content-area h1'],
    ['My Environments', '#/my-environments', '#env-list-search'],
  ] as const) {
    test(`${name} has no contrast violations`, async ({ page }) => {
      await open(page, hash, ready);
      expect(await contrastViolations(page)).toEqual([]);
    });
  }

  test('Environment Detail has no contrast violations', async ({ page }) => {
    await page.evaluate(id => { location.hash = '#/environments/' + id; }, seededEnvId);
    await expect(page.locator('#env-detail-view')).toBeVisible({ timeout: 15_000 });
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(600);
    expect(await contrastViolations(page)).toEqual([]);
  });
});
