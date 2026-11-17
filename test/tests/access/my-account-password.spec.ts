import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * U29: My account > Profile offers "Change password" to people who sign in with a password. The
 * profile's canChangePassword flag and POST /users/me/password are faked in the browser: the form
 * checks the three fields first, a wrong current password shows in the form (the session stays),
 * and a change closes the form with a note that other sessions are signed out. People who sign in
 * with Microsoft see no such row.
 */

declare const MyAccount: any;

async function openProfile(page: Page, canChangePassword: boolean) {
  await page.route(/\/api\/v1\/users\/me\/profile$/, async route => {
    const real = await route.fetch();
    route.fulfill({ response: real, json: { ...(await real.json()), canChangePassword } });
  });
  await signIn(page, 'user');
  await page.waitForFunction(() => typeof MyAccount !== 'undefined');
  await expect(page.locator('#content-area h1').first()).toBeVisible({ timeout: 15_000 });
  await page.evaluate(() => MyAccount.open('profile'));
  await expect(page.locator('#ma-tab-profile')).toHaveAttribute('aria-selected', 'true');
}

test.describe('My account: change password', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  });

  test('checks the fields, shows a wrong current password in the form, then changes it', async ({ page }) => {
    const sent: any[] = [];
    await page.route(/\/api\/v1\/users\/me\/password$/, route => {
      const body = route.request().postDataJSON();
      sent.push(body);
      return body.currentPassword === 'right-current-pw'
        ? route.fulfill({ status: 204 })
        : route.fulfill({ status: 400, contentType: 'application/json',
          body: JSON.stringify({ error: 'Validation', message: 'The current password is not right.' }) });
    });
    await openProfile(page, true);

    await page.getByRole('button', { name: 'Change password' }).click();
    const form = page.locator('#ma-password-form');
    await expect(form.getByRole('heading', { name: 'Change password' })).toBeVisible();
    await expect(page.getByLabel('Current password')).toBeFocused();

    await page.getByLabel('Current password').fill('right-current-pw');
    await page.getByLabel('New password', { exact: true }).fill('a-brand-new-password');
    await page.getByLabel('New password again').fill('a-brand-new-passwerd');
    await form.getByRole('button', { name: 'Change password' }).click();
    await expect(page.locator('#ma-pw-error')).toHaveText("The two new passwords don't match.");
    expect(sent).toEqual([]);

    await page.getByLabel('Current password').fill('wrong-current-pw');
    await page.getByLabel('New password again').fill('a-brand-new-password');
    await form.getByRole('button', { name: 'Change password' }).click();
    await expect(page.locator('#ma-pw-error')).toHaveText('The current password is not right.');
    await expect(page.getByLabel('Current password')).toHaveValue('');
    await expect(page.getByText("You've been signed out")).toHaveCount(0);

    await page.getByLabel('Current password').fill('right-current-pw');
    await form.getByRole('button', { name: 'Change password' }).click();
    await expect(page.getByText('Password changed. Your other sessions are signed out.')).toBeVisible();
    await expect(form).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Change password' })).toBeFocused();
    expect(sent[sent.length - 1]).toEqual({ currentPassword: 'right-current-pw', newPassword: 'a-brand-new-password' });
  });

  test('Cancel closes the form and nothing is sent', async ({ page }) => {
    let calls = 0;
    await page.route(/\/api\/v1\/users\/me\/password$/, route => { calls++; return route.fulfill({ status: 204 }); });
    await openProfile(page, true);
    await page.getByRole('button', { name: 'Change password' }).click();
    await page.getByLabel('Current password').fill('typed-but-cancelled');
    await page.locator('#ma-password-form').getByRole('button', { name: 'Cancel' }).click();
    await expect(page.locator('#ma-password-form')).toHaveCount(0);
    expect(calls).toBe(0);
  });

  test('people who sign in with Microsoft see no Change password', async ({ page }) => {
    await openProfile(page, false);
    await expect(page.locator('.ma-profile')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Change password' })).toHaveCount(0);
  });
});
