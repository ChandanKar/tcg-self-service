import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * U04-T03: the page header of the mockup. A breadcrumb ("Environments / <name>", the name marked
 * current), a 28/600 title, one summary line and the actions on the right of the same row; the
 * browser tab reads "<title> · Self-Service". Pages not migrated yet get the same title type and
 * lose the icon in front of the title.
 */

declare const ContentRouter: any;

const base = () => process.env.BASE_URL || 'http://localhost:8080';

async function go(page: Page, role: 'admin' | 'user', hash: string) {
  await page.setViewportSize({ width: 1440, height: 900 });
  await signIn(page, role);
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await expect(page.locator('#content-area h1').first()).toBeVisible({ timeout: 15_000 });
  await page.waitForLoadState('networkidle');
  await page.evaluate(h => { location.hash = h; }, hash);
}

test.describe('Page header', () => {
  test('the environment page: breadcrumb, 28 px title, summary, actions on the same row, tab title', async ({ page, playwright }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    const admin = await playwright.request.newContext({ baseURL: base(),
      extraHTTPHeaders: { 'X-User-Id': devIds.admin, 'Content-Type': 'application/json' } });
    const stamp = Date.now();
    const env = await (await admin.post('/api/v1/environments', {
      data: { name: `header-${stamp}`, displayName: `Header ${stamp}`, description: 'Checkout team staging', cloudProvider: 'AWS' } })).json();
    await admin.dispose();

    await go(page, 'admin', `#/environments/${env.environmentId}`);
    const header = page.locator('#env-detail-view .page-header');
    await expect(header.locator('h1')).toHaveText(`Header ${stamp}`, { timeout: 15_000 });

    const crumbs = header.getByRole('navigation', { name: 'Breadcrumb' });
    await expect(crumbs.getByRole('link', { name: 'Environments' })).toHaveAttribute('href', '#/my-environments');
    await expect(crumbs.locator('[aria-current="page"]')).toHaveText(`Header ${stamp}`);
    await expect(header.locator('h1')).toHaveCSS('font-size', '28px');
    await expect(header.locator('h1')).toHaveCSS('font-weight', '600');
    await expect(header.locator('.page-summary')).toContainText('Checkout team staging');

    const title = await header.locator('h1').boundingBox();
    const actions = await header.locator('.page-actions').boundingBox();
    expect(actions!.x).toBeGreaterThan(title!.x + title!.width);
    expect(Math.abs((actions!.y + actions!.height) - (title!.y + title!.height))).toBeLessThan(60);
    await expect(page).toHaveTitle(`Header ${stamp} · Self-Service`);
  });

  test('Help and the environment list use the header and name the tab', async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await go(page, 'user', '#/help');
    await expect(page.locator('#help-view .page-header h1')).toHaveText('Help', { timeout: 15_000 });
    await expect(page).toHaveTitle('Help · Self-Service');

    await page.evaluate(() => { location.hash = '#/my-environments'; });
    await expect(page.locator('#content-area .page-header h1')).toHaveText('Environments', { timeout: 15_000 });
    await expect(page.locator('#content-area .page-header a.help-link')).toHaveAttribute('aria-label', 'Help: Environments');
    await expect(page).toHaveTitle('Environments · Self-Service');
  });

  test('a page not migrated yet shows its title in the new type, without an icon; the tab says Self-Service', async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await go(page, 'admin', '#/automation-rules');
    const h1 = page.locator('#content-area .content-header h1').first();
    await expect(h1).toBeVisible({ timeout: 15_000 });
    await expect(h1).toHaveCSS('font-size', '28px');
    const icons = await h1.locator('> i').evaluateAll(els => els.map(el => getComputedStyle(el).display));
    expect(icons.every(d => d === 'none')).toBeTruthy();
    await expect(page.locator('#content-area .content-header').first()).toHaveCSS('border-left-width', '0px');
    await expect(page).toHaveTitle('Self-Service');
  });
});
