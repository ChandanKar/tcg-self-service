import { test, expect } from '@playwright/test';
import { creds, hasUserCreds } from '../../fixtures/auth';

test.describe('Login page', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login');
  });

  test('Sign in with Microsoft comes first, then the username form after "or with a username"', async ({ page }) => {
    await expect(page).toHaveTitle(/Sign in/i);
    // U09-T01: two sections, the platform and signing in.
    await expect(page.getByRole('region', { name: 'About the platform' })).toBeVisible();
    await expect(page.getByRole('img', { name: /A rack of environments/ })).toBeVisible();
    const signIn = page.getByRole('region', { name: 'Sign in' });
    await expect(signIn.getByRole('heading', { name: 'Sign in' })).toBeVisible();

    const microsoft = page.getByRole('link', { name: 'Sign in with Microsoft' });
    await expect(microsoft).toHaveAttribute('href', '/oauth2/authorization/azure');
    await page.locator('body').focus();
    await page.keyboard.press('Tab');
    await expect(microsoft).toBeFocused();                       // the first thing to tab to
    await expect(page.locator('#loginDivider')).toHaveText('or with a username');
    await expect(page.getByLabel('Username')).toBeVisible();
    await expect(page.getByLabel('Password')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible();
    await expect(page.getByText('Trouble signing in? Ask your platform administrator to check your account.')).toBeVisible();
  });

  test('rejects an empty submission client-side', async ({ page }) => {
    await page.locator('#submitBtn').click();
    await expect(page.locator('#errorMessage')).toHaveText('Enter your username and password.');
    await expect(page).toHaveURL(/\/login/);
  });

  test('with password sign-in off only Sign in with Microsoft shows', async ({ page }) => {
    await page.route('**/api/auth/options', route => route.fulfill({ json: { passwordLoginEnabled: false } }));
    await page.goto('/login');
    await expect(page.getByRole('link', { name: 'Sign in with Microsoft' })).toBeVisible();
    await expect(page.locator('#loginForm')).toBeHidden();
    await expect(page.locator('#loginDivider')).toBeHidden();
  });

  test('an ended session and a sign-out say so until the user acts', async ({ page }) => {
    await page.goto('/login?expired');
    await expect(page.getByRole('alert')).toHaveText('Your session ended. Sign in again to carry on.');
    await page.goto('/login?logout');
    const note = page.getByRole('status').filter({ hasText: 'You are signed out.' });
    await expect(note).toBeVisible();
    await page.waitForTimeout(5_500);                            // no 5-second timer any more
    await expect(note).toBeVisible();
    await page.getByLabel('Username').fill('a');
    await expect(note).toBeHidden();
  });

  test('fits a 1440x900 window without scrolling and puts signing in first at 390 px', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/login');
    expect(await page.evaluate(() => document.documentElement.scrollHeight <= window.innerHeight
      && document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.setViewportSize({ width: 390, height: 844 });
    const signInTop = await page.getByRole('region', { name: 'Sign in' }).evaluate(el => el.getBoundingClientRect().top);
    const aboutTop = await page.getByRole('region', { name: 'About the platform' }).evaluate(el => el.getBoundingClientRect().top);
    expect(signInTop).toBeLessThan(aboutTop);
  });

  test('shows a generic error on invalid credentials (no user enumeration)', async ({ page }) => {
    await page.locator('#username').fill('definitely-not-a-real-user');
    await page.locator('#password').fill(creds.invalidPassword);
    await page.locator('#submitBtn').click();

    const errorAlert = page.locator('#errorAlert');
    await expect(errorAlert).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('#errorMessage')).toHaveText(/invalid username or password/i);
    // The message stays until the user edits a field (no 5-second timer, WCAG 2.2.1).
    await page.waitForTimeout(6_000);
    await expect(errorAlert).toBeVisible();
    await page.locator('#password').fill('x');
    await expect(errorAlert).toBeHidden();
    // Regression guard: the message must not reveal whether the username exists
    // (see AuthController — deliberately generic message).
    await expect(page.locator('#errorMessage')).not.toHaveText(/user not found\.?$/i);
  });

  test('logs in with valid credentials and reaches the app shell', async ({ page }) => {
    test.skip(!hasUserCreds(), 'TEST_USERNAME/TEST_PASSWORD not set in test/.env — see .env.example');

    await page.locator('#username').fill(creds.user.username);
    await page.locator('#password').fill(creds.user.password);
    await page.locator('#submitBtn').click();

    await page.waitForURL('**/home', { timeout: 10_000 });
    await expect(page.locator('#sidebar')).toBeVisible();
    await expect(page.locator('#dashboard-view')).toBeVisible();
  });
});
