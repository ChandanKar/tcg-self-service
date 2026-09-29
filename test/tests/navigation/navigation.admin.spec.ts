import { test, expect } from '@playwright/test';
import { loginAs, creds, hasAdminCreds } from '../../fixtures/auth';

const ADMIN_ONLY_VIEWS = [
  'access-management',
  'vm-registry',
  'automation-rules',
  'audit-logs-all',
  'cost-management',
  'system-health',
];

test.describe('Sidebar navigation (admin)', () => {
  test.beforeEach(async ({ page }) => {
    test.skip(!hasAdminCreds(), 'TEST_ADMIN_USERNAME/TEST_ADMIN_PASSWORD not set in test/.env');
    await loginAs(page, creds.admin.username, creds.admin.password);
  });

  test('admin menu is visible for a global admin account', async ({ page }) => {
    await expect(page.locator('#admin-menu')).toBeVisible();
  });

  for (const view of ADMIN_ONLY_VIEWS) {
    test(`navigates to admin view "${view}"`, async ({ page }) => {
      const link = page.locator(`.sidebar-menu-link[data-content="${view}"]`);
      await expect(link).toBeVisible();
      await link.click();
      await expect(page.locator(`[data-view="${view}"]`)).toBeVisible({ timeout: 10_000 });
    });
  }
});
