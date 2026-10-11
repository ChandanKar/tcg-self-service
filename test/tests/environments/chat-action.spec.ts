import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E24-T04: the confirm page behind one-click Teams buttons (#/chat-action/<token>). The API is
 * faked here (links are issued server-side when a message is sent); the server rules are covered
 * by ChatActionControllerIntegrationTest. Also: a deep link opened while signed out survives the
 * trip through /login.
 */

declare const ContentRouter: any;

const TOKEN = 'eyJqdGkiOiJ4In0.c2lnbmF0dXJl';

async function open(page: Page) {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(t => { location.hash = `#/chat-action/${t}`; }, TOKEN);
}

test.describe('Teams action confirm page', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('shows what will happen, runs it once on Confirm and shows the result', async ({ page }) => {
    let posts = 0;
    await page.route(/\/api\/v1\/chat-actions\/[^/]+$/, route => {
      if (route.request().method() === 'POST') {
        posts++;
        return route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({ type: 'APPROVE_ACCESS_REQUEST', environmentId: 'env-1', message: "Approved Alice's request on Payments QA." }) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        type: 'APPROVE_ACCESS_REQUEST', environmentId: 'env-1', environmentName: 'Payments QA',
        description: "Approve Alice's request for USER access on Payments QA for 7 days.", confirmLabel: 'Approve',
        expiresAt: new Date(Date.now() + 3_600_000).toISOString() }) });
    });
    await open(page);

    await expect(page.locator('#chat-action-description')).toHaveText("Approve Alice's request for USER access on Payments QA for 7 days.", { timeout: 15_000 });
    await expect(page.locator('#chat-action-confirm')).toHaveText('Approve');
    await page.locator('#chat-action-confirm').click();
    await expect(page.locator('#chat-action-done')).toContainText("Approved Alice's request on Payments QA.");
    expect(posts).toBe(1);
    await expect(page.locator('#chat-action-done + a')).toHaveAttribute('href', '#/environments/env-1');
  });

  test('an extend link offers a duration and sends the chosen one (E24-T05)', async ({ page }) => {
    let sent: any = 'none';
    await page.route(/\/api\/v1\/chat-actions\/[^/]+$/, route => {
      if (route.request().method() === 'POST') {
        sent = route.request().postDataJSON();
        return route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({ type: 'EXTEND_LEASE', environmentId: 'env-1', message: 'Dev QA now runs until 14:15 UTC.' }) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        type: 'EXTEND_LEASE', environmentId: 'env-1', environmentName: 'Dev QA',
        description: 'Keep Dev QA running longer. It now stops at 10:15 UTC; 2 hours more would be 12:15 UTC.',
        confirmLabel: 'Extend', expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
        choices: [60, 120, 240], defaultChoice: 120, choiceUnit: 'minutes' }) });
    });
    await open(page);
    const choice = page.locator('#chat-action-choice');
    await expect(choice).toHaveValue('120', { timeout: 15_000 });
    await expect(choice.locator('option')).toHaveText(['1 hour', '2 hours', '4 hours']);
    await choice.selectOption('240');
    await page.locator('#chat-action-confirm').click();
    await expect(page.locator('#chat-action-done')).toContainText('now runs until 14:15 UTC');
    expect(sent).toEqual({ choice: 240 });
  });

  test('a used link explains itself', async ({ page }) => {
    await page.route(/\/api\/v1\/chat-actions\/[^/]+$/, route => route.fulfill({ status: 409, contentType: 'application/json',
      body: JSON.stringify({ status: 409, message: 'This link was already used' }) }));
    await open(page);
    await expect(page.locator('#chat-action-error')).toContainText('Link already used', { timeout: 15_000 });
    await expect(page.locator('#chat-action-error')).toContainText('This link was already used');
  });

  test('a link opened while signed out is kept by the login page', async ({ page }) => {
    await page.goto(`/login#/chat-action/${TOKEN}`);
    const saved = await page.evaluate(() => sessionStorage.getItem('vmcontrol.intendedRoute'));
    expect(saved).toBe(`#/chat-action/${TOKEN}`);
  });
});
