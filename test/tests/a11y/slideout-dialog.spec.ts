import { test, expect, type APIRequestContext } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seriousViolations } from '../../fixtures/axe';

/**
 * E13-T03: slide-out panels are modal dialogs — focus moves in and stays in, Escape while typing
 * keeps the draft, Escape elsewhere closes and returns focus, closed panels are out of the Tab order.
 */

declare const ContentRouter: any;
declare const MyAccount: any;

let accessId = '';

async function api(playwright: any, userId: string): Promise<APIRequestContext> {
  return playwright.request.newContext({
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    extraHTTPHeaders: { 'X-User-Id': userId, 'Content-Type': 'application/json' },
  });
}

test.describe('Slide-out dialogs', () => {
  test.beforeAll(async ({ playwright }) => {
    if (!devIds.admin || !devIds.user) return;
    const admin = await api(playwright, devIds.admin);
    const created = await admin.post('/api/v1/environments', {
      data: { name: `dialog-e2e-${Date.now()}`, displayName: `Dialog E2E ${Date.now()}`, cloudProvider: 'AWS' },
    });
    expect(created.ok()).toBeTruthy();
    const envId = (await created.json()).environmentId;
    const user = await (await admin.get(`/api/v1/users/${devIds.user}`)).json();
    const granted = await admin.post(`/api/v1/environments/${envId}/access`, {
      data: { userEmail: user.email, accessLevel: 'USER', durationDays: 3 },
    });
    expect(granted.ok()).toBeTruthy();
    accessId = (await granted.json()).accessId;
    await admin.dispose();
  });

  test.beforeEach(async ({ page }) => {
    test.skip(!devIds.admin || !devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID and TEST_DEV_ADMIN_ID');
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.waitForLoadState('networkidle');
  });

  test('My Account is a dialog that holds focus and returns it on Escape', async ({ page }) => {
    await page.locator('.user-profile-trigger').click();
    await page.locator('#my-account-btn').click();

    const panel = page.locator('#dynamicSlideoutPanel');
    await expect(panel).toHaveAttribute('role', 'dialog');
    await expect(panel).toHaveAttribute('aria-modal', 'true');
    // U09-T05: the dialog is named after the person, with "email · role" under the name.
    const me = await (await page.request.get('/api/v1/users/me/profile', { headers: { 'X-User-Id': devIds.user } })).json();
    await expect(page.locator('#dynamicSlideoutTitle')).toHaveText(me.displayName);
    const role = me.admin ? 'Administrator' : me.envAdmin ? 'Environment admin' : 'User';
    await expect(panel.locator('.ma-head-sub')).toHaveText(`${me.email} · ${role}`);
    await expect(panel.locator('.ma-avatar')).toHaveAttribute('aria-hidden', 'true');
    await expect(panel.getByRole('tab')).toHaveText(['Access', 'Cost', 'Notifications', 'Profile']);
    await expect(panel.getByRole('tab', { name: 'Access' })).toHaveAttribute('aria-selected', 'true');
    await expect.poll(() => page.evaluate(() =>
      !!document.getElementById('dynamicSlideoutPanel')?.contains(document.activeElement))).toBe(true);

    for (let i = 0; i < 30; i++) {
      await page.keyboard.press('Tab');
      expect(await page.evaluate(() =>
        !!document.getElementById('dynamicSlideoutPanel')?.contains(document.activeElement))).toBe(true);
    }

    await page.locator('#dynamicSlideoutPanel .close-btn').focus();
    await page.keyboard.press('Escape');
    await expect(panel).not.toHaveClass(/show/);
    await expect(page.locator('.user-profile-trigger')).toBeFocused();
  });

  test('Escape while typing an extension reason keeps the panel and the draft', async ({ page }) => {
    await page.evaluate(() => MyAccount.open('access'));
    const extend = page.locator(`[data-ma-action="extend"][data-access-id="${accessId}"]`);
    await expect(extend).toBeVisible({ timeout: 10_000 });
    // The panel slides in and its lists load after it opens; click once it has settled.
    await expect(page.locator('#dynamicSlideoutPanel')).toHaveClass(/show/);
    await page.waitForLoadState('networkidle');
    await extend.click();

    const reason = page.locator('#ma-extend-reason');
    await reason.fill('Need one more week of testing');
    await reason.press('Escape');
    await expect(page.locator('#dynamicSlideoutPanel')).toHaveClass(/show/);
    await expect(reason).toHaveValue('Need one more week of testing');
  });

  test('old tab names still work: Overview opens Access, Activity goes to My activity', async ({ page }) => {
    await page.evaluate(() => MyAccount.open('overview'));
    await expect(page.locator('#ma-tab-access')).toHaveAttribute('aria-selected', 'true');
    await expect(page.locator('#ma-tab-overview, #ma-tab-activity')).toHaveCount(0);
    await page.evaluate(() => MyAccount.open('activity'));
    await expect(page).toHaveURL(/#\/activity-logs$/);
    await expect(page.locator('#dynamicSlideoutPanel')).not.toHaveClass(/show/);
  });

  test('Profile: signed in with, role, team, shortcuts and Sign out', async ({ page }) => {
    await page.route(/\/api\/v1\/users\/me\/profile$/, async route => {
      const real = await (await route.fetch()).json();
      await route.fulfill({ json: { ...real, admin: false, envAdmin: false, authMethod: 'ENTRA_ID', teamNames: ['Payments'] } });
    });
    await page.evaluate(() => MyAccount.open('profile'));
    const profile = page.locator('#dynamicSlideoutPanel .ma-profile');
    await expect(profile.locator('.ma-profile-row')).toHaveText([
      /^Signed in withMicrosoft \(Entra ID\)$/, /^RoleUser$/, /^TeamPayments$/, /^Previous sign-in/, /^Keyboard shortcuts\?\s*to see them$/]);
    await expect(profile.getByRole('button', { name: 'Sign out' })).toBeVisible();
  });

  test('closed panels are not in the Tab order', async ({ page }) => {
    await page.evaluate(() => MyAccount.open());
    await page.locator('#dynamicSlideoutPanel .close-btn').click();
    await expect(page.locator('#dynamicSlideoutPanel')).not.toHaveClass(/show/);

    await page.locator('body').focus();
    for (let i = 0; i < 40; i++) {
      await page.keyboard.press('Tab');
      expect(await page.evaluate(() => !!document.activeElement?.closest('.slideout-panel'))).toBe(false);
    }
  });
});

/**
 * U18-T07: every dialog and side panel fits the window. The title bar and the button strip stay in
 * view, only the content between them scrolls (named, keyboard reachable, with a shadow on the edge
 * while more is hidden), and the page behind never scrolls. On a phone a dialog is a full-screen sheet.
 */
declare const Modals: any;
declare const Slideout: any;

const LONG = Array.from({ length: 60 }, (_, i) => `<p>Line ${i + 1} of a long explanation.</p>`).join('');

async function openApp(page: any, width: number, height: number) {
  await page.setViewportSize({ width, height });
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined' && typeof Modals !== 'undefined');
  await page.evaluate(() => { location.hash = '#/help'; });
  await expect(page.locator('#help-search')).toBeVisible({ timeout: 15_000 });
}

test.describe('Dialogs and side panels fit the window', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('1366 x 657: a long dialog keeps its title and buttons in view; only the content scrolls', async ({ page }) => {
    await openApp(page, 1366, 657);
    await page.evaluate((body: string) => Modals.show({ id: 'longDialog', title: 'A long dialog', body,
      buttons: [{ text: 'Cancel', class: 'btn-outline-secondary', dismiss: true }, { text: 'Save', class: 'btn-primary', id: 'longSave' }] }), LONG);
    const dialog = page.locator('#longDialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.locator('.modal-header')).toBeInViewport({ ratio: 1 });
    await expect(dialog.locator('.modal-footer')).toBeInViewport({ ratio: 1 });
    const state = await page.evaluate(() => {
      const modal = document.getElementById('longDialog')!;
      const body = modal.querySelector('.modal-body') as HTMLElement;
      return { modalScrolls: modal.scrollHeight > modal.clientHeight + 1, bodyScrolls: body.scrollHeight > body.clientHeight + 1,
        pageScrolls: document.scrollingElement!.scrollHeight > window.innerHeight + 1 };
    });
    expect(state).toEqual({ modalScrolls: false, bodyScrolls: true, pageScrolls: false });
    await expect(dialog.getByRole('region', { name: 'A long dialog, scrolls' })).toHaveAttribute('tabindex', '0');
    await expect(dialog.locator('.modal-content')).toHaveClass(/has-more-below/);
    await dialog.locator('.modal-body').evaluate((b: HTMLElement) => { b.scrollTop = b.scrollHeight; });
    await expect(dialog.locator('.modal-content')).toHaveClass(/has-more-above/);
    await expect(dialog.locator('.modal-content')).not.toHaveClass(/has-more-below/);
    expect(await seriousViolations(page, '#longDialog')).toEqual([]);
  });

  test('a short dialog does not pretend to scroll', async ({ page }) => {
    await openApp(page, 1366, 657);
    await page.evaluate(() => Modals.show({ id: 'shortDialog', title: 'Short', body: '<p>One line.</p>' }));
    await expect(page.locator('#shortDialog')).toBeVisible();
    await expect(page.locator('#shortDialog .modal-body')).not.toHaveAttribute('role', 'region');
    await expect(page.locator('#shortDialog .modal-content')).not.toHaveClass(/has-more-(above|below)/);
  });

  test('390 x 844: a dialog is a full-screen sheet with its title and buttons in view', async ({ page }) => {
    await openApp(page, 390, 844);
    await page.evaluate((body: string) => Modals.show({ id: 'phoneDialog', title: 'On a phone', body }), LONG);
    const dialog = page.locator('#phoneDialog');
    await expect(dialog).toBeVisible();
    const box = await dialog.locator('.modal-dialog').boundingBox();
    expect(Math.round(box!.width)).toBeGreaterThanOrEqual(389);
    await expect(dialog.locator('.modal-header')).toBeInViewport({ ratio: 1 });
    await expect(dialog.locator('.modal-footer')).toBeInViewport({ ratio: 1 });
  });

  test('1366 x 657: a side panel keeps its header and action strip in view; only the content scrolls', async ({ page }) => {
    await openApp(page, 1366, 657);
    await page.evaluate((body: string) => Slideout.open('A long panel', body, { footer: '<button type="button" class="btn btn-primary">Save</button>' }), LONG);
    const panel = page.locator('#dynamicSlideoutPanel');
    await expect(panel).toBeVisible();
    await expect(panel.locator('.slideout-panel-header')).toBeInViewport({ ratio: 1 });
    await expect(panel.locator('.slideout-panel-footer')).toBeInViewport({ ratio: 1 });
    const state = await page.evaluate(() => {
      const p = document.getElementById('dynamicSlideoutPanel')!;
      const c = p.querySelector('.slideout-panel-content') as HTMLElement;
      return { panelScrolls: p.scrollHeight > p.clientHeight + 1, contentScrolls: c.scrollHeight > c.clientHeight + 1 };
    });
    expect(state).toEqual({ panelScrolls: false, contentScrolls: true });
    await expect(panel.getByRole('region', { name: 'Panel, scrolls' })).toHaveAttribute('tabindex', '0');
    await expect(panel).toHaveClass(/has-more-below/);
  });
});

