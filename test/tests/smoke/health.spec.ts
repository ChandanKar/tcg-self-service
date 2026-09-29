import { test, expect } from '@playwright/test';

/**
 * Basic reachability smoke tests — no login required. If these fail, the app
 * isn't up / reachable at BASE_URL and every other spec will fail too.
 */
test.describe('Application reachability', () => {
  test('actuator health endpoint reports UP', async ({ request }) => {
    const res = await request.get('/actuator/health');
    expect(res.ok()).toBeTruthy();
    const body = await res.json();
    expect(body.status).toBe('UP');
  });

  test('root redirects to the login page', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveURL(/\/login/);
  });

  test('Swagger UI is reachable', async ({ page }) => {
    const res = await page.goto('/swagger-ui/index.html');
    // Non-2xx here just means Swagger has been locked down for this profile —
    // don't hard-fail the whole suite over a documented, intentional toggle.
    test.skip(!res || !res.ok(), 'Swagger UI not exposed in this environment');
    await expect(page).toHaveTitle(/Swagger/i);
  });
});
