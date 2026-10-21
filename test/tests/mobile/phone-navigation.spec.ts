import { test, expect } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E13-T01, U16-T01 (PhoneHome.dc.html, PhoneMenu.dc.html): on a phone the top bar holds only the logo,
 * the bell and the avatar; the bottom bar Home / Environments / Activity / More links to real routes;
 * More opens the side menu as a full-screen sheet with who is signed in, the role's sections with
 * count badges, Help and Sign out; a link, Close or Escape closes it and focus returns to More.
 */

declare const ContentRouter: any;

test.use({ viewport: { width: 390, height: 844 } });

async function open(page: any, role: 'user' | 'admin') {
  await signIn(page, role);
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.waitForLoadState('networkidle');
}

test.describe('Phone navigation', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  });

  test('the top bar shows only the logo, the bell and the avatar; the bottom bar reads Home, Environments, Activity, More', async ({ page }) => {
    await open(page, 'user');
    // Acceptance: at 390 x 844 only logo, bell and avatar in the top bar.
    await expect(page.locator('.top-navbar .tcg-logo')).toBeVisible();
    await expect(page.locator('#notification-bell-btn')).toBeVisible();
    await expect(page.locator('.top-navbar .user-avatar')).toBeVisible();
    for (const hidden of ['#toggleSidebar', '#sync-indicator', '#topbar-help-btn', '.top-navbar .user-info', '.top-navbar .logo']) {
      await expect(page.locator(hidden)).toBeHidden();
    }
    await expect(page.getByRole('navigation', { name: 'Main', exact: true }).locator('.mobile-nav-item'))
      .toHaveText(['Home', 'Environments', 'Activity', 'More']);
  });

  test('bottom-bar items open their pages and mark themselves active', async ({ page }) => {
    await open(page, 'user');
    for (const [label, hash] of [['Environments', '#/my-environments'], ['Activity', '#/activity-logs'], ['Home', '#/dashboard']]) {
      const item = page.locator('#mobile-nav .mobile-nav-item', { hasText: label });
      await item.click();
      await expect(page).toHaveURL(new RegExp(hash.replace('/', '\\/') + '$'));
      await expect(item).toHaveClass(/active/);
      await expect(item).toHaveAttribute('aria-current', 'page');
    }
  });

  test('More opens a full-screen menu with who is signed in, the sections, Help and Sign out; a link closes it', async ({ page }) => {
    await open(page, 'user');
    const more = page.locator('#mobile-more-btn');
    await more.click();
    const sheet = page.locator('#sidebar');
    await expect(sheet).toHaveClass(/mobile-open/);
    await expect(more).toHaveAttribute('aria-expanded', 'true');
    // Acceptance: full screen, the name and role, the role's sections, Help and Sign out.
    await expect.poll(async () => (await sheet.boundingBox())?.width).toBe(390);
    await expect(page.locator('#phone-menu-name')).not.toBeEmpty();
    await expect(page.locator('#phone-menu-role')).toHaveText('User');
    await expect(page.locator('#operations-title')).toBeVisible();
    await expect(page.locator('#manage-section')).toBeHidden();
    await expect(page.locator('#phone-menu-help')).toBeVisible();
    await expect(page.locator('#phone-menu-signout')).toBeVisible();

    await page.locator('#sidebar .sidebar-menu-link[data-content="request-access"]').click();
    await expect(page).toHaveURL(/#\/request-access$/);
    await expect(sheet).not.toHaveClass(/mobile-open/);
    await expect(more).toHaveClass(/active/);
    await expect(more).toBeFocused();
  });

  test('Close and Escape close the menu and give focus back to More; Tab stays inside it', async ({ page }) => {
    await open(page, 'user');
    const more = page.locator('#mobile-more-btn');
    await more.click();
    await page.getByRole('button', { name: 'Close menu' }).click();
    await expect(page.locator('#sidebar')).not.toHaveClass(/mobile-open/);
    await expect(more).toBeFocused();

    await more.click();
    await page.locator('#phone-menu-signout').focus();
    await page.keyboard.press('Tab');
    await expect(page.locator('#phone-menu-close')).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(page.locator('#sidebar')).not.toHaveClass(/mobile-open/);
    await expect(more).toBeFocused();
  });

  test('the avatar opens My account directly', async ({ page }) => {
    await open(page, 'user');
    await page.locator('.top-navbar .user-profile-trigger').click();
    await expect(page.locator('#user-dropdown')).toBeHidden();
    await expect(page.locator('.my-account').first()).toBeVisible({ timeout: 10_000 });
  });

  test('the menu shows the Access requests and Approvals counts as badges', async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await page.route(/\/api\/v1\/access-requests\/pending$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify([{ requestId: 'r1' }, { requestId: 'r2' }, { requestId: 'r3' }]) }));
    await page.route(/\/api\/v1\/approval-requests\/status$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ visible: true, waiting: 2 }) }));
    await open(page, 'admin');
    await page.locator('#mobile-more-btn').click();
    // Acceptance: an environment admin with 3 pending access requests sees a 3 badge.
    await expect(page.locator('#sidebar .sidebar-menu-link[data-content="pending-requests"] .pending-count')).toHaveText('3');
    await expect(page.locator('#sidebar .sidebar-menu-link[data-content="approvals"] .approvals-count')).toHaveText('2');
    await expect(page.locator('#phone-menu-role')).toHaveText('Administrator');
  });
});
