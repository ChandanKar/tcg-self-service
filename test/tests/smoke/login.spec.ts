import { test, expect } from '@playwright/test';
import { creds, hasUserCreds } from '../../fixtures/auth';

test.describe('Login page', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login');
  });

  test('renders the username/password form and the Entra ID option', async ({ page }) => {
    await expect(page).toHaveTitle(/Login/i);
    await expect(page.locator('#loginForm')).toBeVisible();
    await expect(page.locator('#username')).toBeVisible();
    await expect(page.locator('#password')).toBeVisible();
    await expect(page.locator('#submitBtn')).toBeVisible();

    // Microsoft Entra ID SSO button (see docs: dual-auth support).
    await expect(page.locator('a.btn-entraid')).toBeVisible();
    await expect(page.locator('a.btn-entraid')).toHaveAttribute('href', '/oauth2/authorization/azure');
  });

  test('rejects an empty submission client-side', async ({ page }) => {
    await page.locator('#submitBtn').click();
    // HTML5 `required` blocks submission; we should still be on /login.
    await expect(page).toHaveURL(/\/login/);
  });

  test('shows a generic error on invalid credentials (no user enumeration)', async ({ page }) => {
    await page.locator('#username').fill('definitely-not-a-real-user');
    await page.locator('#password').fill(creds.invalidPassword);
    await page.locator('#submitBtn').click();

    const errorAlert = page.locator('#errorAlert');
    await expect(errorAlert).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('#errorMessage')).toHaveText(/invalid credentials/i);
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
