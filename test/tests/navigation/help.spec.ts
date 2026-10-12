import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E29-T07: the Help page. Topic list and deep links, search, topics hidden by role, the header
 * '?' buttons, and every page help link pointing at a topic that exists.
 */

declare const ContentRouter: any;
declare const HelpContent: any;

async function go(page: Page, who: 'admin' | 'user', hash: string) {
  await signIn(page, who);
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined' && typeof HelpContent !== 'undefined');
  await page.evaluate(h => { location.hash = h; }, hash);
}

test.describe('Help', () => {
  test('a deep link opens its topic and search narrows the list', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await go(page, 'user', '#/help/locks');
    await expect(page.locator('#help-topic-title')).toHaveText('Starting, stopping and locks', { timeout: 15_000 });
    await expect(page.locator('.help-topic-link.active')).toHaveText('Starting, stopping and locks');

    await page.locator('#help-search').fill('lock');
    const links = page.locator('.help-topic-link');
    await expect(links).not.toHaveCount(0);
    for (const text of await links.allTextContents()) {
      expect(text.toLowerCase()).not.toContain('getting started');
    }
    await page.locator('#help-search').fill('zzzz-no-such-thing');
    await expect(page.locator('#help-no-results')).toBeVisible();
  });

  test('a user does not see admin topics; an admin does', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await go(page, 'user', '#/help');
    await expect(page.locator('.help-topic-link').first()).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('.help-topic-link', { hasText: 'Automation rules administration' })).toHaveCount(0);
    await page.evaluate(() => { location.hash = '#/help/automation-rules'; });
    await expect(page.locator('#help-topic')).toContainText('Topic not found');
  });

  test('admins see every topic and the Environments page help button opens its topic', async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await go(page, 'admin', '#/help');
    await expect(page.locator('.help-topic-link', { hasText: 'Automation rules administration' })).toHaveCount(1, { timeout: 15_000 });

    await page.evaluate(() => { location.hash = '#/my-environments'; });
    const help = page.locator('.content-header .help-link').first();
    await expect(help).toHaveAttribute('aria-label', 'Help: Environments', { timeout: 15_000 });
    await help.click();
    await expect(page.locator('#help-topic-title')).toHaveText('Starting, stopping and locks');
  });

  test('every page help link resolves to an existing topic', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await go(page, 'user', '#/help');
    const missing = await page.evaluate(() => Object.entries(HelpContent.PAGE_TOPICS)
      .filter(([, id]) => !HelpContent.find(id)).map(([pageName]) => pageName));
    expect(missing).toEqual([]);
  });

  test('the user menu has a Help entry', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await go(page, 'user', '#/dashboard');
    await expect(page.locator('#help-menu-btn')).toHaveAttribute('href', '#/help');
  });
});
