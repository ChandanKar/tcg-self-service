import { test, expect } from '@playwright/test';
import { devIds } from '../../fixtures/auth';

/**
 * U02-T05 / U10-T07: on Access requests the Approve and Deny buttons never overlap, at desktop and
 * phone widths, and a long reason wraps inside its card (the card never runs off the screen).
 */

declare const ContentRouter: any;

const LONG_REASON = 'Regression run for release 4.2 across payments and refunds, including the partner settlement '
  + 'flows and the nightly reconciliation jobs that need the full environment for two weeks.';

test.describe('Access requests layout', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !devIds.user, 'Needs TEST_DEV_ADMIN_ID and TEST_DEV_USER_ID (dev mode)');
  });

  for (const viewport of [{ width: 1440, height: 900 }, { width: 360, height: 800 }]) {
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

      const card = page.locator('.ar-card', { hasText: `Layout ${stamp}` });
      await expect(card).toBeVisible({ timeout: 15_000 });
      await card.scrollIntoViewIfNeeded();
      const approve = await card.locator('[data-action="approve"]').boundingBox();
      const deny = await card.locator('[data-action="deny"]').boundingBox();
      const box = await card.boundingBox();
      expect(approve && deny && box).toBeTruthy();
      const apart = approve!.x + approve!.width <= deny!.x || deny!.x + deny!.width <= approve!.x
        || approve!.y + approve!.height <= deny!.y || deny!.y + deny!.height <= approve!.y;
      expect(apart, 'Approve and Deny overlap').toBeTruthy();
      for (const b of [approve!, deny!]) {
        expect(b.x).toBeGreaterThanOrEqual(box!.x - 1);
        expect(b.x + b.width).toBeLessThanOrEqual(box!.x + box!.width + 1);
      }
      expect(box!.x + box!.width).toBeLessThanOrEqual(viewport.width + 1);
      await expect(card.locator('.ar-reason')).toHaveText(`"${LONG_REASON}"`);
    });
  }
});
