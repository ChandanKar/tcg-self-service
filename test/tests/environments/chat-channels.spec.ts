import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E24-T02: the Teams channels panel on the environment detail page. An admin adds a channel (the
 * real API stores the webhook encrypted and shows only a masked host), sends a test (faked
 * delivery: no real Teams call) and removes it. A user without admin rights sees no panel.
 */

declare const ContentRouter: any;

async function openEnv(page: Page, envId: string, role: 'admin' | 'user' = 'admin') {
  await signIn(page, role);
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(id => { location.hash = `#/environments/${id}`; }, envId);
}

const HOOK = 'https://prod-12.westeurope.logic.azure.com/workflows/abc/triggers/manual?sig=E2E-SECRET';

test.describe('Teams channels panel', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('an admin adds a channel, sends a test and removes it', async ({ page, playwright }) => {
    const envId = await seedEnvironment(playwright, 'Chat env');
    expect(envId).toBeTruthy();
    // Never call Teams from e2e: the test endpoint is faked.
    await page.route(/\/api\/v1\/chat-channels\/[^/]+\/test$/, route => route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify({ delivered: true, error: null }) }));
    await openEnv(page, envId);

    const panel = page.locator('.chat-channels-card');
    await expect(panel).toContainText('No channels yet', { timeout: 15_000 });
    await panel.locator('[data-action="chat-add"]').click();
    const modal = page.locator('#chatChannelModal');
    await expect(modal).toBeVisible();
    await expect(modal.locator('.chat-event:checked')).toHaveCount(6);
    await modal.locator('#chat-label').fill('Platform team');
    await modal.locator('#chat-url').fill(HOOK);
    await modal.locator('#chat-save-btn').click();

    const row = panel.locator('.chat-channel', { hasText: 'Platform team' });
    await expect(row).toContainText('…westeurope.logic.azure.com', { timeout: 10_000 });
    await expect(panel).not.toContainText('E2E-SECRET');

    await row.locator('[data-action="chat-test"]').click();
    await expect(panel.locator('.chat-channel', { hasText: 'Platform team' })).toContainText('Delivered');

    await panel.locator('.chat-channel', { hasText: 'Platform team' }).locator('[data-action="chat-remove"]').click();
    await page.locator('.modal.show .btn-danger').click();
    await expect(panel).toContainText('No channels yet', { timeout: 10_000 });
  });

  test('a bad webhook URL is refused with the server message', async ({ page, playwright }) => {
    const envId = await seedEnvironment(playwright, 'Chat strict');
    await openEnv(page, envId);
    const panel = page.locator('.chat-channels-card');
    await expect(panel).toBeVisible({ timeout: 15_000 });
    await panel.locator('[data-action="chat-add"]').click();
    await page.locator('#chat-label').fill('Nope');
    await page.locator('#chat-url').fill('https://evil.example.com/hook');
    await page.locator('#chat-save-btn').click();
    await expect(page.locator('body')).toContainText('not allowed');
    await expect(page.locator('#chatChannelModal')).toBeVisible();
  });

  test('a user who does not administer the environment sees no panel', async ({ page, playwright }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    const envId = await seedEnvironment(playwright, 'Chat hidden', { grantUser: true });
    await openEnv(page, envId, 'user');
    await expect(page.locator('#env-chat-panel')).toBeAttached({ timeout: 15_000 });
    await page.waitForTimeout(1000);
    await expect(page.locator('.chat-channels-card')).toHaveCount(0);
  });
});
