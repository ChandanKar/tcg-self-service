import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E29-T04: the Settings page. The settings API is faked: groups and read-only rows, a switch
 * that saves at once and can be undone, a number checked against its range, a confirmation for
 * switches that act on AWS, and reset to default.
 */

declare const ContentRouter: any;

function setting(over: any) {
  return { group: 'Notifications', description: 'desc', type: 'BOOLEAN', tier: 1, editable: true, value: 'false',
    defaultValue: 'false', source: 'DEFAULT', min: null, max: null, updatedAt: null, updatedByName: null, ...over };
}

const LIST = [
  setting({ key: 'notification.email.lock-broken.enabled', label: 'Lock broken' }),
  setting({ key: 'vm.metrics.idle.cpu-threshold-percent', label: 'Idle CPU %', group: 'Idle detection', type: 'DECIMAL',
    value: '5', defaultValue: '5', min: 0, max: 100 }),
  setting({ key: 'cost.tagging.enabled', label: 'Cost-allocation tagging', group: 'Cost management' }),
  setting({ key: 'entraid.enabled', label: 'Microsoft Entra ID sign-in', group: 'Restart required', type: 'STRING', tier: 2,
    editable: false, value: 'false' }),
];

async function open(page: Page) {
  const puts: any[] = [];
  await page.route(/\/api\/v1\/settings$/, r => r.fulfill({ json: LIST }));
  await page.route(/\/api\/v1\/settings\/[^/]+$/, r => {
    const key = decodeURIComponent(r.request().url().split('/').pop()!);
    const base = LIST.find(s => s.key === key)!;
    if (r.request().method() === 'DELETE') return r.fulfill({ json: { ...base } });
    const body = r.request().postDataJSON();
    puts.push({ key, value: body.value });
    return r.fulfill({ json: { ...base, value: body.value, source: 'OVERRIDE', updatedByName: 'Test Admin',
      updatedAt: new Date().toISOString() } });
  });
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/settings'; });
  await expect(page.locator('.settings-group')).toHaveCount(4, { timeout: 15_000 });
  return puts;
}

const row = (page: Page, key: string) => page.locator(`[data-row="${key}"]`);

test.describe('Settings page', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('a switch saves at once, shows who changed it and can be undone', async ({ page }) => {
    const puts = await open(page);
    await row(page, 'notification.email.lock-broken.enabled').locator('input[type=checkbox]').check();
    const r = row(page, 'notification.email.lock-broken.enabled');
    await expect(r).toContainText('Changed');
    await expect(r).toContainText('Test Admin');
    await expect(r.locator('.settings-status')).toContainText('Saved');
    expect(puts).toEqual([{ key: 'notification.email.lock-broken.enabled', value: 'true' }]);

    await r.locator('[data-action="settings-undo"]').click();
    await expect.poll(() => puts.length).toBe(2);
    expect(puts[1]).toEqual({ key: 'notification.email.lock-broken.enabled', value: 'false' });
  });

  test('a number outside its range is not sent', async ({ page }) => {
    const puts = await open(page);
    const r = row(page, 'vm.metrics.idle.cpu-threshold-percent');
    await r.locator('input[type=number]').fill('150');
    await r.locator('[data-action="settings-save"]').click();
    await expect(r.locator('.settings-status')).toContainText('from 0 to 100');
    expect(puts).toEqual([]);

    await r.locator('input[type=number]').fill('8');
    await r.locator('[data-action="settings-save"]').click();
    await expect(r).toContainText('Changed');
    expect(puts).toEqual([{ key: 'vm.metrics.idle.cpu-threshold-percent', value: '8' }]);
  });

  test('switches that act on AWS ask first, and restart-only settings are read-only', async ({ page }) => {
    const puts = await open(page);
    await row(page, 'cost.tagging.enabled').locator('input[type=checkbox]').click();
    await expect(page.locator('.modal.show')).toContainText('writes cost-allocation tags');
    await page.locator('.modal.show .btn-secondary, .modal.show [data-bs-dismiss="modal"]').first().click();
    await expect(page.locator('.modal.show')).toHaveCount(0);
    expect(puts).toEqual([]);
    await expect(row(page, 'cost.tagging.enabled').locator('input[type=checkbox]')).not.toBeChecked();

    const ro = row(page, 'entraid.enabled');
    await expect(ro).toContainText('Restart required');
    await expect(ro).toContainText('edit .env and restart');
    await expect(ro.locator('input')).toHaveCount(0);
  });
});

test('non-admins see access denied and the API is not called', async ({ page }) => {
  test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  let called = false;
  await page.route(/\/api\/v1\/settings/, r => { called = true; return r.fulfill({ json: [] }); });
  await signIn(page, 'user');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await expect(page.locator('#settings-link')).toBeHidden();
  await page.evaluate(() => { location.hash = '#/settings'; });
  await expect(page.locator('#content-area')).toContainText('Access denied');
  expect(called).toBe(false);
});
