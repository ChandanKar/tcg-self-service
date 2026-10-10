import { test, expect } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E13-T01: on a phone the bottom bar links to real routes, More / the hamburger open the
 * sidebar drawer, and a link, the overlay or Escape closes it again.
 */

declare const ContentRouter: any;

test.use({ viewport: { width: 390, height: 844 } });

test.describe('Phone navigation', () => {
  test.beforeEach(async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.waitForLoadState('networkidle');
  });

  test('bottom-bar items open their pages and mark themselves active', async ({ page }) => {
    for (const [label, hash] of [['Environments', '#/my-environments'], ['Activity', '#/activity-logs'],
      ['Dashboard', '#/dashboard']]) {
      const item = page.locator('#mobile-nav .mobile-nav-item', { hasText: label });
      await item.click();
      await expect(page).toHaveURL(new RegExp(hash.replace('/', '\\/') + '$'));
      await expect(item).toHaveClass(/active/);
      await expect(item).toHaveAttribute('aria-current', 'page');
    }
  });

  test('More opens the drawer; a link inside it navigates and closes it', async ({ page }) => {
    const more = page.locator('#mobile-more-btn');
    await more.click();
    await expect(page.locator('#sidebar')).toHaveClass(/mobile-open/);
    await expect(page.locator('#mobile-overlay')).toHaveClass(/show/);
    await expect(more).toHaveAttribute('aria-expanded', 'true');

    await page.locator('#sidebar .sidebar-menu-link[data-content="request-access"]').click();
    await expect(page).toHaveURL(/#\/request-access$/);
    await expect(page.locator('#sidebar')).not.toHaveClass(/mobile-open/);
    await expect(more).toHaveClass(/active/);
    await expect(more).toBeFocused();
  });

  test('the hamburger opens the drawer; Escape and the overlay close it', async ({ page }) => {
    const burger = page.locator('#toggleSidebar');
    await burger.click();
    await expect(page.locator('#sidebar')).toHaveClass(/mobile-open/);
    await expect(burger).toHaveAttribute('aria-expanded', 'true');
    await page.keyboard.press('Escape');
    await expect(page.locator('#sidebar')).not.toHaveClass(/mobile-open/);
    await expect(burger).toBeFocused();

    await burger.click();
    await page.locator('#mobile-overlay').click({ position: { x: 370, y: 400 } });
    await expect(page.locator('#sidebar')).not.toHaveClass(/mobile-open/);
    await expect(page.locator('#sidebar')).not.toHaveClass(/collapsed/);
  });
});
