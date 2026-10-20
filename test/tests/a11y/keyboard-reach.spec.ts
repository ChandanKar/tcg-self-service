import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E13-T02 / U04-T01: keyboard users reach every menu link, My Account and Logout; the menu's
 * sections are always open and the link of the open page is marked current.
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

  test('Tab reaches Users without opening anything (sections are always open, U04-T01)', async ({ page }) => {
    await page.evaluate(() => { location.hash = '#/dashboard'; });
    await expect(page.locator('#admin-menu')).toBeVisible();
    await expect(page.locator('#sidebar [aria-expanded]')).toHaveCount(0);

    await page.locator('body').focus();
    expect(await tabUntil(page, el => el.getAttribute('data-content') === 'user-management', 40)).toBe(true);
  });

  test('the user menu opens from the keyboard and Escape returns focus', async ({ page }) => {
    await page.locator('body').focus();
    expect(await tabUntil(page, el => el.classList.contains('user-profile-trigger'))).toBe(true);
    const trigger = page.locator('.user-profile-trigger');

    await page.keyboard.press('Enter');
    await expect(trigger).toHaveAttribute('aria-expanded', 'true');
    await expect(page.locator('#my-account-btn')).toBeFocused();
    // Help moved to the top bar (U04-T01); Ask is hidden while it is off.
    await page.keyboard.press('ArrowDown');
    await expect(page.locator('#logout-btn')).toBeFocused();
    await page.keyboard.press('ArrowDown');
    await expect(page.locator('#my-account-btn')).toBeFocused();

    await page.keyboard.press('Escape');
    await expect(trigger).toHaveAttribute('aria-expanded', 'false');
    await expect(trigger).toBeFocused();
    await expect(page.locator('#user-dropdown')).toBeHidden();
  });

  test('the help menu opens from the keyboard; arrows move; Escape returns focus to "?" (U04-T04)', async ({ page }) => {
    await page.evaluate(() => { location.hash = '#/my-environments'; });
    await expect(page.locator('#content-area .content-header h1').first()).toHaveText('My Environments', { timeout: 15_000 });
    await page.locator('body').focus();
    expect(await tabUntil(page, el => el.id === 'topbar-help-btn')).toBe(true);
    const help = page.locator('#topbar-help-btn');

    await page.keyboard.press('Enter');
    await expect(help).toHaveAttribute('aria-expanded', 'true');
    const items = page.locator('#help-menu [role="menuitem"]');
    await expect(items.nth(0)).toBeFocused();
    await page.keyboard.press('ArrowDown');
    await expect(items.nth(1)).toBeFocused();
    await page.keyboard.press('ArrowDown');
    await expect(items.nth(2)).toBeFocused();
    await page.keyboard.press('ArrowDown');
    await expect(items.nth(0)).toBeFocused();
    await page.keyboard.press('ArrowUp');
    await expect(items.nth(2)).toBeFocused();

    await page.keyboard.press('Escape');
    await expect(page.locator('#help-menu')).toBeHidden();
    await expect(help).toHaveAttribute('aria-expanded', 'false');
    await expect(help).toBeFocused();
  });

  test('an admin page marks its link active and current', async ({ page }) => {
    await page.evaluate(() => { location.hash = '#/user-management'; });
    await expect(page.locator('#admin-menu')).toBeVisible();
    const link = page.locator('.sidebar-menu-link[data-content="user-management"]');
    await expect(link).toHaveClass(/active/);
    await expect(link).toHaveAttribute('aria-current', 'page');

    await page.reload();
    await expect(page.locator('#admin-menu')).toBeVisible({ timeout: 10_000 });
  });

  test('every section stays open, also after a reload that had stored closed sections', async ({ page }) => {
    await page.evaluate(() => localStorage.setItem('vmcontrol.sidebar.sections', JSON.stringify({ admin: false, cost: false })));
    await page.reload();
    await expect(page.locator('#operations-menu')).toBeVisible({ timeout: 10_000 });
    for (const menu of ['#manage-menu', '#cost-menu', '#admin-menu']) await expect(page.locator(menu)).toBeVisible();
    expect(await page.evaluate(() => localStorage.getItem('vmcontrol.sidebar.sections'))).toBeNull();
    await page.locator('#admin-title').click();
    await expect(page.locator('#admin-menu')).toBeVisible();
  });
});
