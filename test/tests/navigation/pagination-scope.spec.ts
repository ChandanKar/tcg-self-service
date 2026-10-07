import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn } from '../../fixtures/auth';

/**
 * E02-T02: a pager only answers clicks on its own buttons, and a pager bound to #content-area
 * (Activity Logs, All Logs, Environments, Access Requests) stops listening when the page is
 * left. Before the fix, clicking page 2 on a later page also ran the earlier page's handler.
 */

// App modules are top-level `const`s in classic scripts: global, but not properties of window.
declare const ContentRouter: any;
declare const Pagination: any;

async function goTo(page: Page, route: string) {
  await page.evaluate((r) => { location.hash = '#/' + r; }, route);
  await page.waitForFunction((r) => location.hash === '#/' + r, route);
  await page.waitForTimeout(600); // let the route's own render finish before adding test pagers
}

/** Renders a 3-page pager into a fresh container inside #content-area and counts its page changes. */
async function addPager(page: Page, id: string, bindAncestor?: string) {
  await page.evaluate(({ id, bindAncestor }) => {
    const w = window as any;
    w.__pager = w.__pager || {};
    w.__pager[id] = [];
    const el = document.createElement('div');
    el.id = id;
    document.getElementById('content-area')!.appendChild(el);
    Pagination.renderNumbered('#' + id, {
      page: 1, totalItems: 30, pageSize: 10, itemLabel: 'rows',
      ...(bindAncestor ? { bindAncestor } : {}),
      onPageChange: (p: number) => w.__pager[id].push(p),
    });
  }, { id, bindAncestor });
}

const calls = (page: Page) => page.evaluate(() => (window as any).__pager);

test.describe('Pagination click scope', () => {
  test.beforeEach(async ({ page }) => {
    test.skip(!canSignIn('user'), 'No sign-in configured (TEST_DEV_USER_ID or TEST_USERNAME/TEST_PASSWORD in test/.env)');
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined' && typeof Pagination !== 'undefined');
  });

  test("a later page's pager never runs an earlier page's handler", async ({ page }) => {
    await goTo(page, 'activity-logs');
    await addPager(page, 'pg-old', '#content-area');   // like Activity Logs

    await goTo(page, 'request-access');
    await addPager(page, 'pg-new');                    // like User Management (binds to itself)
    await page.locator('#pg-new').getByRole('button', { name: 'Page 2', exact: true }).click();

    expect(await calls(page)).toEqual({ 'pg-old': [], 'pg-new': [2] });
  });

  test('two pagers bound to the same ancestor only answer their own buttons', async ({ page }) => {
    await goTo(page, 'activity-logs');
    await addPager(page, 'pg-a', '#content-area');
    await addPager(page, 'pg-b', '#content-area');

    await page.locator('#pg-b').getByRole('button', { name: 'Page 2', exact: true }).click();
    await page.locator('#pg-a').getByRole('button', { name: 'Page 3', exact: true }).click();

    expect(await calls(page)).toEqual({ 'pg-a': [3], 'pg-b': [2] });
  });

  test('re-rendering the same pager does not stack handlers', async ({ page }) => {
    await goTo(page, 'activity-logs');
    await addPager(page, 'pg-r', '#content-area');
    await page.evaluate(() => {
      const w = window as any;
      Pagination.renderNumbered('#pg-r', {
        page: 1, totalItems: 30, pageSize: 10, bindAncestor: '#content-area',
        onPageChange: (p: number) => w.__pager['pg-r'].push(p),
      });
    });
    await page.locator('#pg-r').getByRole('button', { name: 'Page 2', exact: true }).click();

    expect((await calls(page))['pg-r']).toEqual([2]);
  });

  test('pager buttons are labelled for screen readers', async ({ page }) => {
    await goTo(page, 'activity-logs');
    await addPager(page, 'pg-aria');

    const pager = page.locator('#pg-aria');
    await expect(pager.getByRole('button', { name: 'First page' })).toBeVisible();
    await expect(pager.getByRole('button', { name: 'Previous page' })).toBeVisible();
    await expect(pager.getByRole('button', { name: 'Next page' })).toBeVisible();
    await expect(pager.getByRole('button', { name: 'Last page' })).toBeVisible();
    await expect(pager.locator('button[aria-current="page"]')).toHaveText('1');
    await expect(pager.locator('button:not([type="button"])')).toHaveCount(0);
  });
});
