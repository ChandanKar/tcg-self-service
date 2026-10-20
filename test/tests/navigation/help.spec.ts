import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E29-T07/T08: the Help page. Topic list and deep links, search, topics hidden by role, the header
 * '?' buttons, and every page help link pointing at a topic that exists. U09-T07 (Help.dc.html):
 * search in the header, topics in groups, the current one marked, the power-strip legend.
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
    await expect(page.locator('#help-no-results')).toHaveText('No topic matches "zzzz-no-such-thing".');
  });

  test('topics in groups, search in the header, the current topic marked and the legend (U09-T07)', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await go(page, 'user', '#/help/locks');
    await expect(page.locator('#help-topic-title')).toHaveText('Starting, stopping and locks', { timeout: 15_000 });
    await expect(page.locator('.content-header #help-search')).toBeVisible();
    await expect(page.getByRole('searchbox', { name: 'Search help' })).toBeVisible();
    const nav = page.getByRole('navigation', { name: 'Help topics' });
    await expect(nav.locator('.help-group-title')).toHaveText(['Using environments', 'Access', 'Cost and more']);
    const using = nav.getByRole('group', { name: 'Using environments' });
    await expect(using.locator('.help-topic-link').first()).toHaveText('Getting started and roles');
    await expect(nav.locator('[aria-current="page"]')).toHaveText('Starting, stopping and locks');
    await expect(page.locator('.help-legend')).toContainText("Green is running, gold is starting or stopping, hollow is stopped, striped means the state hasn't been read from AWS yet.");
    await expect(page.locator('.help-legend .pip')).toHaveCount(4);

    await nav.getByRole('link', { name: 'Requesting and managing access' }).click();
    await expect(page).toHaveURL(/#\/help\/access$/);
    await expect(page.locator('.help-legend')).toHaveCount(0);
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
    await expect(page.locator('.help-group-title')).toHaveText(['Using environments', 'Access', 'Cost and more', 'Manage', 'Administration']);

    await page.evaluate(() => { location.hash = '#/my-environments'; });
    const help = page.locator('.content-header .help-link').first();
    await expect(help).toHaveAttribute('aria-label', 'Help: Environments', { timeout: 15_000 });
    await help.click();
    // U02-T04: My Environments opens the Environments topic (it used to open Locks)
    await expect(page.locator('#help-topic-title')).toHaveText('Environments and start order');
  });

  test('every page help link resolves to an existing topic', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await go(page, 'user', '#/help');
    const missing = await page.evaluate(() => Object.entries(HelpContent.PAGE_TOPICS)
      .filter(([, id]) => !HelpContent.find(id)).map(([pageName]) => pageName));
    expect(missing).toEqual([]);
  });

  test('feature topics say when the feature may be switched off; core topics do not', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await go(page, 'user', '#/help/leases');
    await expect(page.locator('#help-topic-title')).toHaveText('Leases and extensions', { timeout: 15_000 });
    await expect(page.locator('.help-optional')).toContainText('may be switched off');
    await page.evaluate(() => { location.hash = '#/help/locks'; });
    await expect(page.locator('#help-topic-title')).toHaveText('Starting, stopping and locks');
    await expect(page.locator('.help-optional')).toHaveCount(0);
    await page.locator('#help-search').fill('snooze');
    await expect(page.locator('.help-topic-link', { hasText: 'Idle auto-stop and snooze' })).toBeVisible();
  });

  test('the top bar "?" offers help for this page, all topics and the shortcuts (U04-T01, U04-T04)', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await go(page, 'user', '#/my-environments');
    await expect(page.locator('#content-area .content-header h1').first()).toHaveText('Environments', { timeout: 15_000 });
    const help = page.locator('#topbar-help-btn');
    await expect(help).toHaveAccessibleName('Help');
    await expect(help).toHaveAttribute('aria-haspopup', 'menu');
    await help.click();
    await expect(help).toHaveAttribute('aria-expanded', 'true');
    const menu = page.getByRole('menu', { name: 'Help' });
    await expect(menu.getByRole('menuitem')).toHaveText(['Help for this page', 'All help topics', /Keyboard shortcuts/]);
    await expect(menu.getByRole('menuitem', { name: 'Help for this page' })).toHaveAttribute('href', '#/help/environments');
    await menu.getByRole('menuitem', { name: 'Help for this page' }).click();
    await expect(page).toHaveURL(/#\/help\/environments$/);
    await expect(page.locator('#help-topic-title')).toBeVisible();
    await expect(menu).toBeHidden();

    // The account menu no longer carries Help.
    await page.locator('.user-profile-trigger').click();
    await expect(page.locator('#help-menu-btn')).toHaveCount(0);
  });

  test('"?" without a page topic shows only All help topics and Keyboard shortcuts; shortcuts open the dialog', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await go(page, 'user', '#/dashboard');
    await expect(page.locator('#content-area .content-header h1').first()).toHaveText('Home', { timeout: 15_000 });
    await page.evaluate(() => { HelpContent.PAGE_TOPICS.dashboard = undefined; });
    await page.locator('#topbar-help-btn').click();
    const menu = page.getByRole('menu', { name: 'Help' });
    await expect(menu.getByRole('menuitem')).toHaveText(['All help topics', /Keyboard shortcuts/]);
    await menu.getByRole('menuitem', { name: /Keyboard shortcuts/ }).click();
    await expect(page.locator('#shortcutsHelpModal')).toBeVisible();
    // Escape reaches the dialog once it has focus (after its fade-in).
    await page.waitForFunction(() => document.getElementById('shortcutsHelpModal')?.contains(document.activeElement));
    await page.keyboard.press('Escape');
    await expect(page.locator('#shortcutsHelpModal')).toBeHidden();
    await expect(page.locator('#topbar-help-btn')).toBeFocused();
  });
});
