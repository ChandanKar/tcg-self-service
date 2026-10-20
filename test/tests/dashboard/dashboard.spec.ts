import { test, expect } from '@playwright/test';
import { loginAs, creds, hasUserCreds } from '../../fixtures/auth';

test.describe('Dashboard', () => {
  test.beforeEach(async ({ page }) => {
    test.skip(!hasUserCreds(), 'TEST_USERNAME/TEST_PASSWORD not set in test/.env');
    await loginAs(page, creds.user.username, creds.user.password);
  });

  test('a user lands on Home with their environments or the empty state (U06-T02)', async ({ page }) => {
    await expect(page.locator('#dashboard-view.home-page')).toBeVisible();
    await expect(page.locator('#dashboard-view .content-header h1')).toHaveText('Home');
    await expect(page.locator('#home-envs-title, #home-empty-title')).toBeVisible();
  });

  test('global search box is present and accepts input', async ({ page }) => {
    const search = page.locator('#global-search');
    await expect(search).toBeVisible();
    await search.fill('prod');
    await expect(search).toHaveValue('prod');
  });

  test('sidebar reflects the signed-in user (no anonymous/guest state)', async ({ page }) => {
    // The workspace/admin menus are hidden until the user context resolves;
    // by the time the dashboard view is visible, at least the operations menu
    // (present for every authenticated role) must be shown.
    await expect(page.locator('#operations-menu')).toBeVisible();
  });
});
