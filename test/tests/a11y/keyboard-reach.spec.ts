import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E13-T02: keyboard users reach the Admin section, its links, My Account and Logout; section
 * headers announce their state; the section holding the active page opens by itself.
 */

declare const ContentRouter: any;

/** Tab up to `max` times; return true once `match` holds for the focused element. */
async function tabUntil(page: Page, match: (el: Element) => boolean, max = 60): Promise<boolean> {
  for (let i = 0; i < max; i++) {
    await page.keyboard.press('Tab');
    if (await page.evaluate(m => {
      const fn = new Function('el', `return (${m})(el)`) as (el: Element) => boolean;
      return !!document.activeElement && fn(document.activeElement);
    }, match.toString())) return true;
  }
  return false;
}

test.describe('Keyboard reach', () => {
  test.beforeEach(async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.waitForLoadState('networkidle');
  });

  test('Tab reaches the Admin header; Enter opens it and its links are reachable', async ({ page }) => {
    await page.evaluate(() => { location.hash = '#/dashboard'; });
    const admin = page.locator('#admin-title');
    await expect(admin).toHaveAttribute('aria-expanded', 'false');

    await page.locator('body').focus();
    expect(await tabUntil(page, el => el.id === 'admin-title')).toBe(true);
    await page.keyboard.press('Enter');
    await expect(admin).toHaveAttribute('aria-expanded', 'true');
    await expect(page.locator('#admin-menu')).toBeVisible();

    expect(await tabUntil(page, el => el.getAttribute('data-content') === 'user-management', 20)).toBe(true);
  });

  test('the user menu opens from the keyboard and Escape returns focus', async ({ page }) => {
    await page.locator('body').focus();
    expect(await tabUntil(page, el => el.classList.contains('user-profile-trigger'))).toBe(true);
    const trigger = page.locator('.user-profile-trigger');

    await page.keyboard.press('Enter');
    await expect(trigger).toHaveAttribute('aria-expanded', 'true');
    await expect(page.locator('#my-account-btn')).toBeFocused();
    await page.keyboard.press('ArrowDown');
    await expect(page.locator('#logout-btn')).toBeFocused();
    await page.keyboard.press('ArrowDown');
    await expect(page.locator('#my-account-btn')).toBeFocused();

    await page.keyboard.press('Escape');
    await expect(trigger).toHaveAttribute('aria-expanded', 'false');
    await expect(trigger).toBeFocused();
    await expect(page.locator('#user-dropdown')).toBeHidden();
  });

  test('an admin page opens the Admin section and marks its link active', async ({ page }) => {
    await page.evaluate(() => { location.hash = '#/vm-registry'; });
    await expect(page.locator('#admin-menu')).toBeVisible();
    await expect(page.locator('#admin-title')).toHaveAttribute('aria-expanded', 'true');
    await expect(page.locator('.sidebar-menu-link[data-content="vm-registry"]')).toHaveClass(/active/);

    await page.reload();
    await expect(page.locator('#admin-menu')).toBeVisible({ timeout: 10_000 });
  });
});
