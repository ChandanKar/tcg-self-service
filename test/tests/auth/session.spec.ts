import { test, expect } from '../../fixtures/auth';
import { loginAs, logout, creds, hasUserCreds } from '../../fixtures/auth';

test.describe('Session & route protection', () => {
  test('an unauthenticated request to a protected API returns 401/403, not data', async ({ request }) => {
    const res = await request.get('/api/v1/environments');
    expect([401, 403]).toContain(res.status());
  });

  test('an unauthenticated visit to /home does not render the app shell', async ({ page }) => {
    const res = await page.goto('/home');
    // Depending on the active security profile this either redirects to
    // /login or returns 401/403 — either is an acceptable "not authenticated"
    // outcome. What must NOT happen is the SPA shell rendering.
    const onLogin = page.url().includes('/login');
    const statusBlocked = res ? [401, 403].includes(res.status()) : false;
    expect(onLogin || statusBlocked).toBeTruthy();
    if (!onLogin) {
      await expect(page.locator('#sidebar')).toHaveCount(0);
    }
  });

  test('logging out clears the session (protected route becomes unreachable again)', async ({ page }) => {
    test.skip(!hasUserCreds(), 'TEST_USERNAME/TEST_PASSWORD not set in test/.env');

    await loginAs(page, creds.user.username, creds.user.password);
    await expect(page.locator('#sidebar')).toBeVisible();

    await logout(page);

    await page.goto('/home');
    await expect(page).toHaveURL(/\/login/);
  });
});
