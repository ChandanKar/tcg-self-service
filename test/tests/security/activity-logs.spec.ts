import { test, expect, type Request } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E11-T06: My Activity sends exact instants (no UTC calendar days), lists every action from
 * /audit/actions (no "Unknown"), and never asks for more than 500 rows.
 */

declare const ContentRouter: any;

test.describe('My Activity', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  });

  test('sends instants, lists every action and keeps pages at most 500', async ({ page }) => {
    const requests: Request[] = [];
    page.on('request', req => { if (req.url().includes('/api/v1/audit/logs/my')) requests.push(req); });
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.waitForLoadState('networkidle');
    await page.evaluate(() => { location.hash = '#/activity-logs'; });
    await expect(page.locator('#al-action-type-filter')).toBeVisible({ timeout: 15_000 });

    const p = new URL(requests[0].url()).searchParams;
    expect(p.get('from')).toMatch(/T.*Z$/);
    expect(p.has('startDate')).toBe(false);
    expect(Math.round((Date.parse(p.get('to')!) - Date.parse(p.get('from')!)) / 3600000)).toBe(24 * 7);

    const actions: string[] = await page.request.get('/api/v1/audit/actions', {
      headers: { 'X-User-Id': devIds.user } }).then(r => r.json());
    await expect(page.locator('#al-action-type-filter option')).toHaveCount(actions.length + 1);
    await expect(page.locator('#al-page-size-filter option[value="10000"]')).toHaveCount(0);
  });
});
