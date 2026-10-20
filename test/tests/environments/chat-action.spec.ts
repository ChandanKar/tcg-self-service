import { test, expect, type Page, type Route } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E24-T04: the confirm page behind one-click Teams buttons (#/chat-action/<token>). The API is
 * faked here (links are issued server-side when a message is sent); the server rules are covered
 * by ChatActionControllerIntegrationTest. Also: a deep link opened while signed out survives the
 * trip through /login. U09-T08 (ChatAction.dc.html): a bare page with one card (source, question,
 * radio chips, signed-in line, "Open <environment> instead") and outcome states by code.
 */

declare const ContentRouter: any;

const TOKEN = 'eyJqdGkiOiJ4In0.c2lnbmF0dXJl';

async function open(page: Page) {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(t => { location.hash = `#/chat-action/${t}`; }, TOKEN);
}

const json = (route: Route, body: unknown, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

function inHours(h: number) {
  return new Date(Date.now() + h * 3_600_000).toISOString();
}

test.describe('Teams action confirm page', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('asks the question on a bare page, runs it once and says Done', async ({ page }) => {
    let posts = 0;
    await page.route(/\/api\/v1\/chat-actions\/[^/]+$/, route => {
      if (route.request().method() === 'POST') {
        posts++;
        return json(route, { type: 'APPROVE_ACCESS_REQUEST', environmentId: 'env-1', message: "Approved Alice's request on Payments QA." });
      }
      return json(route, {
        type: 'APPROVE_ACCESS_REQUEST', environmentId: 'env-1', environmentName: 'Payments QA',
        description: "Approve Alice's request for USER access on Payments QA for 7 days.", confirmLabel: 'Approve',
        expiresAt: inHours(1), title: 'Approve this access request?', source: 'From the Payments QA Teams channel' });
    });
    await open(page);

    await expect(page.getByRole('heading', { level: 1, name: 'Approve this access request?' })).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('.ca-source')).toHaveText('From the Payments QA Teams channel');
    await expect(page.locator('#chat-action-description')).toHaveText("Approve Alice's request for USER access on Payments QA for 7 days.");
    await expect(page.locator('.ca-signed-in')).toHaveText(/^Signed in as .+\. The link works once and ends at \d\d:\d\d (today|tomorrow)\.$/);
    await expect(page.getByRole('link', { name: 'Open Payments QA instead' })).toHaveAttribute('href', '#/environments/env-1');
    // The bare page: no side menu, bottom bar or top-bar tools.
    await expect(page.locator('#sidebar')).toBeHidden();
    await expect(page.locator('#notification-bell-btn')).toBeHidden();

    await page.locator('#chat-action-confirm').click();
    const done = page.locator('#chat-action-done');
    await expect(done).toHaveAttribute('role', 'status');
    await expect(done.locator('h1')).toHaveText('Done');
    await expect(done).toContainText("Approved Alice's request on Payments QA.");
    expect(posts).toBe(1);
    await expect(done.getByRole('link', { name: 'Open Payments QA' })).toHaveAttribute('href', '#/environments/env-1');

    // Leaving the page brings the side menu back.
    await done.getByRole('link', { name: 'Open Payments QA' }).click();
    await expect(page.locator('body')).not.toHaveClass(/bare-page/);
  });

  test('an extend link offers 1, 2 or 4 hours as chips and sends the chosen one (E24-T05)', async ({ page }) => {
    let sent: any = 'none';
    const end = inHours(3);
    await page.route(/\/api\/v1\/chat-actions\/[^/]+$/, route => {
      if (route.request().method() === 'POST') {
        sent = route.request().postDataJSON();
        return json(route, { type: 'EXTEND_LEASE', environmentId: 'env-1', message: 'Dev QA now runs until 14:15 UTC.', endsAt: inHours(7) });
      }
      return json(route, {
        type: 'EXTEND_LEASE', environmentId: 'env-1', environmentName: 'Dev QA',
        description: 'Keep Dev QA running longer. It now stops at 10:15 UTC; 2 hours more would be 12:15 UTC.',
        confirmLabel: 'Extend', expiresAt: inHours(1), choices: [60, 120, 240], defaultChoice: 120, choiceUnit: 'minutes',
        title: 'Extend the lease on Dev QA?', source: 'From the Dev QA Teams channel', currentEnd: end });
    });
    await open(page);
    const group = page.getByRole('group', { name: 'Add' });
    await expect(group.getByRole('radio')).toHaveCount(3, { timeout: 15_000 });
    await expect(group.locator('.ca-chip')).toHaveText(['1 hour', '2 hours', '4 hours']);
    await expect(group.getByRole('radio', { name: '2 hours' })).toBeChecked();
    await expect(page.locator('#chat-action-description')).toHaveText(/^It stops at \d\d:\d\d (today|tomorrow); 2 hours more would be \d\d:\d\d( tomorrow)?\.$/);

    await group.getByText('4 hours').click();
    await expect(page.locator('#chat-action-description')).toContainText('4 hours more would be');
    await page.locator('#chat-action-confirm').click();
    await expect(page.locator('#chat-action-done')).toContainText(/Dev QA now stops at \d\d:\d\d (today|tomorrow)\./);
    expect(sent).toEqual({ choice: 240 });
  });

  test('a used link, an expired link and one the user may not use say so', async ({ page }) => {
    const answers = [
      { status: 409, body: { message: 'This link was already used', code: 'LINK_USED', environmentId: 'env-1', environmentName: 'Payments Dev' } },
      { status: 400, body: { message: 'This link has expired', code: 'LINK_EXPIRED', environmentId: 'env-1', environmentName: 'Payments Dev' } },
      { status: 403, body: { message: 'You can view Payments Dev but not change its lease. Ask its admin, Sanjay Rao.',
        code: 'NOT_ALLOWED', environmentId: 'env-1', environmentName: 'Payments Dev', adminName: 'Sanjay Rao' } },
    ];
    await page.route(/\/api\/v1\/chat-actions\/[^/]+$/, route => {
      const next = answers.shift()!;
      return json(route, next.body, next.status);
    });
    await open(page);
    const error = page.locator('#chat-action-error');
    await expect(error.locator('h1')).toHaveText('This link was already used', { timeout: 15_000 });
    await expect(error).toHaveAttribute('role', 'alert');
    await expect(error).toHaveAttribute('data-tone', 'gold');
    await expect(error.getByRole('link', { name: 'Open Payments Dev' })).toHaveAttribute('href', '#/environments/env-1');

    await page.evaluate(() => ContentRouter.reload());
    await expect(error.locator('h1')).toHaveText('This link has expired');
    await expect(error).toContainText('Links work for 24 hours.');

    await page.evaluate(() => ContentRouter.reload());
    await expect(error.locator('h1')).toHaveText("You can't do this");
    await expect(error).toContainText('Ask its admin, Sanjay Rao.');
    await expect(error).toHaveAttribute('data-tone', 'red');
  });

  test('fits a 390 px phone without sideways scrolling', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.route(/\/api\/v1\/chat-actions\/[^/]+$/, route => json(route, {
      type: 'SNOOZE_IDLE_STOP', environmentId: 'env-1', environmentName: 'Payments Dev', description: 'Pause idle auto-stop.',
      confirmLabel: 'Snooze', expiresAt: inHours(20), choices: [1, 4, 8], defaultChoice: 4, choiceUnit: 'hours',
      title: 'Snooze idle auto-stop on Payments Dev?', source: 'From the Payments Dev Teams channel' }));
    await open(page);
    await expect(page.getByRole('group', { name: 'Snooze for' }).getByRole('radio', { name: '4 hours' })).toBeChecked({ timeout: 15_000 });
    await expect(page.locator('#mobile-nav')).toBeHidden();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  });

  test('a link opened while signed out is kept by the login page', async ({ page }) => {
    await page.goto(`/login#/chat-action/${TOKEN}`);
    const saved = await page.evaluate(() => sessionStorage.getItem('vmcontrol.intendedRoute'));
    expect(saved).toBe(`#/chat-action/${TOKEN}`);
  });
});
