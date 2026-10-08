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

/**
 * Dev-mode identities: when the app runs with ENTRAID_ENABLED=false, DefaultSecurityConfig
 * trusts an X-User-Id header, so tests can act as a seeded user without a password account
 * (e.g. against the test schema, where src/test/resources/db/reset-test-data.sql seeds
 * 'user-001' and 'admin-001').
 */
export const devIds = {
  user: process.env.TEST_DEV_USER_ID || '',
  admin: process.env.TEST_DEV_ADMIN_ID || '',
};

/**
 * Signs in as a standard user or an admin and opens /home. Uses the dev header when
 * TEST_DEV_USER_ID / TEST_DEV_ADMIN_ID is set, otherwise the password form. Returns false
 * when neither is configured so the caller can test.skip().
 */
export async function signIn(page: Page, role: 'user' | 'admin' = 'user'): Promise<boolean> {
  const devId = devIds[role];
  if (devId) {
    await page.context().setExtraHTTPHeaders({ 'X-User-Id': devId });
    await page.goto('/home');
    return true;
  }
  const c = creds[role];
  if (!c.username || !c.password) return false;
  await loginAs(page, c.username, c.password);
  return true;
}

export function canSignIn(role: 'user' | 'admin' = 'user'): boolean {
  return !!devIds[role] || (role === 'admin' ? hasAdminCreds() : hasUserCreds());
}

export async function logout(page: Page): Promise<void> {
  // Dev mode only: GET /logout works because CSRF is off there (Entra mode needs the POST
  // form the user menu submits) and returns to the login page.
  await page.goto('/logout');
  await expect(page).toHaveURL(/\/login/);
}

// Re-export a plain `test`/`expect` so specs only need one import.
export const test = base;
export { expect };
