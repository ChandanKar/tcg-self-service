import { test as base, expect, type Page } from '@playwright/test';

/**
 * Credentials are read from env (test/.env, see .env.example).
 * Tests that need a session call requireUserCreds()/requireAdminCreds() and
 * call test.skip() themselves when nothing is configured, rather than
 * failing — this repo ships no seeded test account, so CI/dev machines
 * without a configured DB should still get a green (skipped) run instead of
 * a wall of false failures.
 */
export const creds = {
  user: {
    username: process.env.TEST_USERNAME || '',
    password: process.env.TEST_PASSWORD || '',
  },
  admin: {
    username: process.env.TEST_ADMIN_USERNAME || '',
    password: process.env.TEST_ADMIN_PASSWORD || '',
  },
  invalidPassword: process.env.TEST_INVALID_PASSWORD || 'not-a-real-password',
};

export function hasUserCreds(): boolean {
  return !!(creds.user.username && creds.user.password);
}

export function hasAdminCreds(): boolean {
  return !!(creds.admin.username && creds.admin.password);
}

/**
 * Logs in via the username/password form on /login (POST /api/auth/login).
 * This endpoint is active regardless of ENTRAID_ENABLED (see
 * docs/production-readiness-audit-2026-09-14.md, finding 1), so it's the
 * only login path Playwright can drive without a real Entra ID tenant.
 */
export async function loginAs(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#submitBtn').click();
  await page.waitForURL('**/home', { timeout: 10_000 });
}

export async function logout(page: Page): Promise<void> {
  // Best-effort: hits the logout endpoint the app itself uses (see AuthController /
  // the user menu's logout action) and returns to the login page.
  await page.goto('/logout');
  await expect(page).toHaveURL(/\/login/);
}

// Re-export a plain `test`/`expect` so specs only need one import.
export const test = base;
export { expect };
