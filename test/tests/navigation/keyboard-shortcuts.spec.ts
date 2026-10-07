import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn } from '../../fixtures/auth';

/**
 * E02-T04: keyboard shortcuts go through the router, ignore modified keys (Ctrl+R stays the
 * browser's) and never fire while typing or under a modal.
 */

declare const ContentRouter: any;

async function ready(page: Page) {
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined' && typeof ContentRouter.onLeave === 'function');
  await page.waitForTimeout(500);
  await page.locator('body').click({ position: { x: 5, y: 5 } }).catch(() => undefined);
}

async function goTo(page: Page, route: string) {
  await page.evaluate((r) => { location.hash = '#/' + r; }, route);
  await page.waitForFunction((r) => location.hash === '#/' + r, route);
  await page.waitForTimeout(500);
}

test.describe('Keyboard shortcuts', () => {
  test.beforeEach(async ({ page }) => {
    test.skip(!canSignIn('user'), 'No sign-in configured (TEST_DEV_USER_ID or TEST_USERNAME/TEST_PASSWORD in test/.env)');
    await signIn(page, 'user');
    await ready(page);
  });

  test('g then e opens My Environments; g then a opens Activity Logs; g then d the Dashboard', async ({ page }) => {
    await page.keyboard.press('g');
    await page.keyboard.press('e');
    await expect(page).toHaveURL(/#\/my-environments$/);

    await page.keyboard.press('g');
    await page.keyboard.press('a');
    await expect(page).toHaveURL(/#\/activity-logs$/);

    await page.keyboard.press('g');
    await page.keyboard.press('d');
    await expect(page).toHaveURL(/#\/dashboard$/);
  });

  test('r reloads the current route through the router (teardown runs)', async ({ page }) => {
    await goTo(page, 'activity-logs');
    await page.evaluate(() => {
      const w = window as any;
      w.__left = 0;
      w.__token = ContentRouter.token();
      ContentRouter.onLeave(() => w.__left++);
    });

    await page.keyboard.press('r');
    await page.waitForTimeout(300);

    const r = await page.evaluate(() => ({ left: (window as any).__left, stale: !ContentRouter.isCurrent((window as any).__token) }));
    expect(r).toEqual({ left: 1, stale: true });
    await expect(page).toHaveURL(/#\/activity-logs$/);
  });

  test('Ctrl+R is not intercepted by the app (plain r is)', async ({ page }) => {
    // A window listener runs after the app's document listener and sees whether it called
    // preventDefault on the event.
    const probe = (ctrlKey: boolean) => page.evaluate((ctrl) => {
      let prevented: boolean | null = null;
      window.addEventListener('keydown', (e) => { prevented = e.defaultPrevented; }, { once: true });
      document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'r', ctrlKey: ctrl, bubbles: true, cancelable: true }));
      return prevented;
    }, ctrlKey);

    expect(await probe(true)).toBe(false);
    expect(await probe(false)).toBe(true);
  });

  test('typing in a field never navigates', async ({ page }) => {
    await goTo(page, 'dashboard');
    await page.evaluate(() => {
      const input = document.createElement('input');
      input.id = 'kb-test-input';
      document.getElementById('content-area')!.prepend(input);
    });
    await page.locator('#kb-test-input').click();
    await page.keyboard.type('ge');
    await page.waitForTimeout(300);
    await expect(page).toHaveURL(/#\/dashboard$/);
    await expect(page.locator('#kb-test-input')).toHaveValue('ge');
  });

  test('? opens the help, and shortcuts do nothing while it is open', async ({ page }) => {
    await goTo(page, 'dashboard');
    await page.keyboard.press('Shift+?');
    const modal = page.locator('#shortcutsHelpModal');
    await expect(modal).toBeVisible();
    await expect(modal).toContainText('Go to My Environments');
    await expect(modal.locator('kbd').first()).toHaveText('g');

    await page.keyboard.press('g');
    await page.keyboard.press('e');
    await page.waitForTimeout(300);
    await expect(page).toHaveURL(/#\/dashboard$/);

    await page.keyboard.press('Escape');
    await expect(modal).toBeHidden();
  });
});
