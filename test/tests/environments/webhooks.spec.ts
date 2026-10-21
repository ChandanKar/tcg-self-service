import { test, expect, type Page, type Route } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { withFeatures } from '../../fixtures/capabilities';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E34-T07: the Webhooks panel on the environment page. The environment is real; the webhook API is
 * faked in the browser. Adding shows the signing secret once (gone after its dialog closes), Test
 * shows the receiver's answer, Deliveries lists attempts, and nothing renders while webhooks are off.
 * U14-T05: API access › Webhooks lists every webhook with its scope and the chosen one's deliveries.
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
  await withFeatures(page, { webhooks: true }); // U02-T03: switched on for this page
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(id => { location.hash = '#/environments/' + id; }, envId);
  await openSettings(page);
  return calls;
}

/** U11-T07: Spot, Teams and webhooks live on the environment's settings page; Settings opens it. */
async function openSettings(page: Page, section = 'notifications') {
  const settings = page.locator('#btn-env-settings');
  await expect(settings).toBeVisible({ timeout: 15_000 });
  await settings.click();
  await expect(page.locator('#es-panel')).toBeVisible({ timeout: 15_000 });
  await page.locator(`[data-es-section="${section}"]`).click();
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
    await expect(panel.locator('li[data-webhook="w-1"] .webhook-last')).toContainText('Delivered');

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
    await expect(page.locator('#webhook-deliveries')).toContainText('Gave up');
    await expect(page.locator('#webhook-deliveries')).toContainText('500 · 6 attempts');
  });

  test('nothing renders while webhooks are off', async ({ page }) => {
    await open(page, envId, false);
    await expect(page.locator('#env-webhook-panel')).toBeAttached({ timeout: 15_000 });
    await page.waitForTimeout(800);
    await expect(page.locator('.webhooks-card')).toHaveCount(0);
  });
});

/** U14-T05 (ApiTabs.dc.html): every webhook on API access › Webhooks. The webhook API is faked. */
test.describe('Webhooks tab', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  const at = (h: number, m: number) => { const d = new Date(); d.setHours(h, m, 0, 0); return d.toISOString(); };
  const HOOKS = [
    { ...HOOK, id: 'w-1', label: 'Release bot', environmentId: 'env-pay', eventTypes: ['operation.completed', 'operation.failed', 'lease.expiring'] },
    { ...HOOK, id: 'w-2', label: 'Audit sink', environmentId: null, eventTypes: ['access.granted'], active: false }];

  async function openTab(page: Page, enabled: boolean) {
    const calls: string[] = [];
    await page.route(/\/api\/v1\/webhooks(\?.*)?$/, route => {
      calls.push(route.request().method() + ' ' + route.request().url());
      if (route.request().method() === 'POST') return json(route, { secret: SECRET, webhook: { ...HOOK, id: 'w-3', label: 'new' } }, 201);
      return json(route, HOOKS);
    });
    await page.route(/\/api\/v1\/environments$/, route => route.request().method() === 'GET'
      ? json(route, [{ environmentId: 'env-pay', name: 'pay-dev', displayName: 'Payments Dev', isActive: true }]) : route.continue());
    await page.route(/\/api\/v1\/webhooks\/w-1\/deliveries/, route => json(route, { content: [
      { id: 'd-1', eventType: 'lease.expiring', status: 'DELIVERED', attempt: 1, responseCode: 200, createdAt: at(17, 45) },
      { id: 'd-2', eventType: 'operation.failed', status: 'FAILED', attempt: 3, responseCode: 503, createdAt: at(8, 41), nextAttemptAt: at(9, 11) }],
      page: { totalElements: 2 } }));
    await page.route(/\/api\/v1\/webhooks\/w-2\/deliveries/, route => json(route, { content: [], page: { totalElements: 0 } }));
    await page.route(/\/api\/v1\/webhooks\/w-1\/test$/, route => { calls.push('test'); return json(route, { delivered: true, status: 204 }); });
    await withFeatures(page, { apiTokens: true, webhooks: enabled });
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/api-access?tab=webhooks'; });
    await expect(page.getByRole('tab', { name: 'Webhooks' })).toHaveAttribute('aria-selected', 'true', { timeout: 15_000 });
    return calls;
  }

  test('every webhook with its scope; the chosen one has its events, deliveries and test', async ({ page }) => {
    const calls = await openTab(page, true);
    // Acceptance: two webhooks on different scopes are both listed with their scope.
    const items = page.locator('.wh-item');
    await expect(items).toHaveCount(2, { timeout: 15_000 });
    await expect(items.nth(0)).toContainText('Payments Dev only');
    await expect(items.nth(1)).toContainText('All environments');
    await expect(items.nth(1)).toContainText('Switched off');
    expect(calls.some(c => /\/webhooks$/.test(c.split(' ')[1]))).toBe(true); // all webhooks, not one environment's

    const card = page.locator('#wh-detail');
    await expect(card.locator('h2')).toHaveText('Webhook: Release bot');
    await expect(card.locator('.wh-chip')).toHaveCount(3);
    const rows = card.locator('#wh-delivery-table tr');
    await expect(rows).toHaveCount(2);
    await expect(rows.first()).toContainText('Delivered');
    await expect(rows.first()).toContainText('200 · attempt 1');
    // Acceptance: a failed delivery being retried reads 'Failed, retrying' with the next attempt time.
    await expect(rows.nth(1)).toContainText('Failed, retrying');
    await expect(rows.nth(1)).toContainText('503 · attempt 3, next at 09:11');
    await expect(card).toContainText('after 50 failed deliveries in a row the webhook switches itself off');

    await card.getByRole('button', { name: 'Send test' }).click();
    await expect(card.locator('.webhook-test-result')).toContainText('Ping delivered');

    await items.nth(1).click();
    await expect(page.locator('#wh-detail h2')).toHaveText('Webhook: Audit sink');
    await expect(page.locator('#wh-deliveries')).toContainText('No deliveries yet');
  });

  test('a new webhook can be for all environments or one', async ({ page }) => {
    await openTab(page, true);
    await page.locator('.wh-all').getByRole('button', { name: 'Add webhook' }).click();
    const modal = page.locator('#webhookModal');
    await expect(modal.locator('#webhook-env option')).toHaveText(['All environments', 'Payments Dev only']);
  });

  test('switched off: says so and asks for no webhook', async ({ page }) => {
    const calls = await openTab(page, false);
    // Acceptance: webhooks off: the tab says so and makes no webhook request.
    await expect(page.locator('#wh-off')).toContainText('Webhooks are switched off.');
    await page.waitForLoadState('networkidle');
    expect(calls).toEqual([]);
  });
});

