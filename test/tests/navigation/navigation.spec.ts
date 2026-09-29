import { test, expect } from '@playwright/test';
import { loginAs, creds, hasUserCreds } from '../../fixtures/auth';

/**
 * Each entry is a sidebar link's `data-content` value and the `data-view`
 * the resulting content pane should carry once rendered (see home.html:
 * `$('.sidebar-menu-link[data-content]').click(...)`).
 * Admin-only items (access-management, automation-rules, cost-management,
 * system-health, settings) are covered separately in navigation.admin.spec.ts
 * since a non-admin USER account won't see them in the sidebar at all.
 */
const USER_VISIBLE_VIEWS = [
  'dashboard',
  'my-environments',
  'request-access',
  'activity-logs',
];

test.describe('Sidebar navigation (standard user)', () => {
  test.beforeEach(async ({ page }) => {
    test.skip(!hasUserCreds(), 'TEST_USERNAME/TEST_PASSWORD not set in test/.env');
    await loginAs(page, creds.user.username, creds.user.password);
  });

  for (const view of USER_VISIBLE_VIEWS) {
    test(`navigates to "${view}" and renders its content pane`, async ({ page }) => {
      const link = page.locator(`.sidebar-menu-link[data-content="${view}"]`);
      await expect(link).toBeVisible();
      await link.click();

      const pane = page.locator(`[data-view="${view}"]`);
      await expect(pane).toBeVisible({ timeout: 10_000 });
      await expect(link).toHaveClass(/active/);
    });
  }
});
