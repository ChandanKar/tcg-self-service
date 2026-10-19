import { test, expect } from '@playwright/test';
import { devIds } from '../../fixtures/auth';

/**
 * U02-T05: on Access Requests the Approve and Deny buttons sit side by side and never overlap, and a long
 * reason is cut with an ellipsis (the whole text is in the title).
 */

declare const ContentRouter: any;

const LONG_REASON = 'Regression run for release 4.2 across payments and refunds, including the partner settlement '
  + 'flows and the nightly reconciliation jobs that need the full environment for two weeks.';

test.describe('Access requests layout', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !devIds.user, 'Needs TEST_DEV_ADMIN_ID and TEST_DEV_USER_ID (dev mode)');
  });

  for (const viewport of [{ width: 1440, height: 900 }, { width: 1280, height: 800 }]) {
    test(`Approve and Deny do not overlap at ${viewport.width}x${viewport.height}`, async ({ page, playwright }) => {
      const base = process.env.BASE_URL || 'http://localhost:8080';
      const admin = await playwright.request.newContext({ baseURL: base,
        extraHTTPHeaders: { 'X-User-Id': devIds.admin, 'Content-Type': 'application/json' } });
      const user = await playwright.request.newContext({ baseURL: base,
        extraHTTPHeaders: { 'X-User-Id': devIds.user, 'Content-Type': 'application/json' } });
      const stamp = Date.now();
      const env = await (await admin.post('/api/v1/environments', {
        data: { name: `layout-${stamp}`, displayName: `Layout ${stamp}`, cloudProvider: 'AWS' } })).json();
      const asked = await user.post(`/api/v1/environments/${env.environmentId}/access-requests`, {
        data: { accessLevel: 'USER', businessJustification: LONG_REASON, durationDays: 14 } });
      expect(asked.ok(), await asked.text()).toBeTruthy();
      await admin.dispose();
      await user.dispose();

      await page.setViewportSize(viewport);
      await page.context().setExtraHTTPHeaders({ 'X-User-Id': devIds.admin });
      await page.goto('/home');
      await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
      await page.evaluate(() => { location.hash = '#/pending-requests'; });

      const row = page.locator('#pending-requests-table tbody tr', { hasText: `Layout ${stamp}` });
      await expect(row).toBeVisible({ timeout: 15_000 });
      const approve = await row.locator('[data-action="approve"]').boundingBox();
      const deny = await row.locator('[data-action="deny"]').boundingBox();
      const cell = await row.locator('td.ra-actions-cell').boundingBox();
      expect(approve && deny && cell).toBeTruthy();
      const apart = approve!.x + approve!.width <= deny!.x || deny!.x + deny!.width <= approve!.x;
      expect(apart, 'Approve and Deny overlap').toBeTruthy();
      for (const box of [approve!, deny!]) {
        expect(box.x).toBeGreaterThanOrEqual(cell!.x - 1);
        expect(box.x + box.width).toBeLessThanOrEqual(cell!.x + cell!.width + 1);
      }
      const reason = row.locator('td[data-label="Reason"]');
      await expect(reason).toHaveAttribute('title', LONG_REASON);
    });
  }
});
