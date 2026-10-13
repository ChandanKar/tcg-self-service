import { test, expect, type Page, type Route } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E34-T07: the Webhooks panel on the environment page. The environment is real; the webhook API is
 * faked in the browser. Adding shows the signing secret once (gone after its dialog closes), Test
 * shows the receiver's answer, Deliveries lists attempts, and nothing renders while webhooks are off.
 */

declare const ContentRouter: any;

const SECRET = 'whsec_' + 'S'.repeat(43);
const HOOK = { id: 'w-1', label: 'Deploy bot <i>x</i>', urlHost: 'https://hooks.example.com', eventTypes: ['environment.stopped'],
  environmentId: '', active: true, disabledReason: null, createdAt: new Date().toISOString(),
  lastDelivery: { status: 'DELIVERED', at: new Date().toISOString(), responseCode: 204 } };

const json = (route: Route, body: unknown, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function open(page: Page, envId: string, enabled: boolean) {
  const calls: any[] = [];
  let hooks = [{ ...HOOK, environmentId: envId }];
  await page.route(/\/api\/v1\/webhooks(\?.*)?$/, route => {
    if (!enabled) return json(route, { message: 'Webhooks are not enabled' }, 404);
    if (route.request().method() === 'POST') {
      calls.push({ create: route.request().postDataJSON() });
      hooks = [...hooks, { ...HOOK, id: 'w-2', label: 'ci', environmentId: envId, lastDelivery: null }];
      return json(route, { secret: SECRET, webhook: hooks[1] }, 201);
    }
    return json(route, hooks);
  });
  await page.route(/\/api\/v1\/webhooks\/w-1\/test$/, route => { calls.push({ test: true }); return json(route, { delivered: false, status: 500, error: '' }); });
  await page.route(/\/api\/v1\/webhooks\/w-1\/deliveries/, route => json(route, { content: [
    { id: 'd-1', eventId: 'e-1', eventType: 'environment.stopped', status: 'GAVE_UP', attempt: 6, responseCode: 500, error: 'HTTP 500',
      createdAt: new Date().toISOString() }], page: { totalElements: 1 } }));
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(id => { location.hash = '#/environments/' + id; }, envId);
  return calls;
}

test.describe('Webhooks panel', () => {
  let envId = '';

  test.beforeAll(async ({ playwright }) => {
    envId = await seedEnvironment(playwright, 'Webhook env');
  });

  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin') || !envId, 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('adding a webhook shows its secret once', async ({ page }) => {
    const calls = await open(page, envId, true);
    const panel = page.locator('.webhooks-card');
    await expect(panel.locator('li[data-webhook="w-1"]')).toContainText('Deploy bot <i>x</i>', { timeout: 15_000 });
    await expect(panel.locator('li[data-webhook="w-1"] .fw-semibold i')).toHaveCount(0);
    await expect(panel.locator('li[data-webhook="w-1"] .webhook-last')).toContainText('delivered');

    await panel.getByRole('button', { name: 'Add webhook' }).click();
    const modal = page.locator('#webhookModal');
    await modal.locator('#webhook-label').fill('ci');
    await modal.locator('#webhook-url').fill('https://hooks.example.com/in');
    await modal.locator('#webhook-save').click();
    await expect.poll(() => calls.length).toBe(1);
    expect(calls[0].create).toMatchObject({ label: 'ci', url: 'https://hooks.example.com/in', environmentId: envId,
      eventTypes: ['operation.failed', 'environment.stopped'] });

    const reveal = page.locator('#webhookSecretModal');
    await expect(reveal.locator('#webhook-secret-value')).toHaveValue(SECRET);
    await reveal.getByRole('button', { name: 'Done' }).click();
    await expect(page.locator('#webhookSecretModal')).toHaveCount(0);
    expect(await page.evaluate(s => document.documentElement.outerHTML.includes(s), SECRET)).toBe(false);
    await expect(panel.locator('li[data-webhook="w-2"]')).toBeVisible();
  });

  test('Test shows the receiver answer and Deliveries lists attempts', async ({ page }) => {
    await open(page, envId, true);
    const row = page.locator('li[data-webhook="w-1"]');
    await row.getByRole('button', { name: 'Test' }).click({ timeout: 15_000 });
    await expect(row.locator('.webhook-test-result')).toContainText('HTTP 500');

    await row.getByRole('button', { name: 'Deliveries' }).click();
    await expect(page.locator('#webhook-deliveries tbody tr')).toHaveCount(1);
    await expect(page.locator('#webhook-deliveries')).toContainText('gave up');
  });

  test('nothing renders while webhooks are off', async ({ page }) => {
    await open(page, envId, false);
    await expect(page.locator('#env-webhook-panel')).toBeAttached({ timeout: 15_000 });
    await page.waitForTimeout(800);
    await expect(page.locator('.webhooks-card')).toHaveCount(0);
  });
});
