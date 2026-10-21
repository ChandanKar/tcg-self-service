import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E29-T04, U14-T07 (Settings.dc.html): the Settings page. The settings API is faked: a section menu
 * with counts, rows with their state and named switches, changes held in a bar until Save changes
 * (one batch, all or none, errors under their rows), Discard, a confirmation for switches that act on
 * AWS, reset to default, and read-only settings that are set in .env.
 */

declare const ContentRouter: any;

function setting(over: any) {
  return { group: 'Notifications', description: 'desc', type: 'BOOLEAN', tier: 1, editable: true, value: 'false',
    defaultValue: 'false', source: 'DEFAULT', min: null, max: null, updatedAt: null, updatedByName: null, ...over };
}

const LIST = [
  setting({ key: 'notification.email.enabled', label: 'Email notifications', description: 'The master switch for every email.' }),
  setting({ key: 'notification.email.lock-broken.enabled', label: 'Lock broken' }),
  setting({ key: 'notification.email.access-granted.enabled', label: 'Access granted', source: 'OVERRIDE', value: 'true',
    updatedByName: 'Test Admin', updatedAt: new Date().toISOString() }),
  setting({ key: 'auth.password-login.enabled', label: 'Username and password sign-in', group: 'Access and security', value: 'true', defaultValue: 'true' }),
  setting({ key: 'entraid.enabled', label: 'Microsoft Entra ID sign-in', group: 'Access and security', type: 'STRING', tier: 2,
    editable: false, value: 'false' }),
  setting({ key: 'vm.metrics.idle.cpu-threshold-percent', label: 'Idle CPU %', group: 'Automation', type: 'DECIMAL',
    value: '5', defaultValue: '5', min: 0, max: 100 }),
  setting({ key: 'cost.tagging.enabled', label: 'Cost-allocation tagging', group: 'Cost' }),
];

async function open(page: Page, hash = '#/settings', batchFails = false) {
  const calls: any[] = [];
  await page.route(/\/api\/v1\/settings$/, r => {
    if (r.request().method() === 'PUT') {
      const body = r.request().postDataJSON();
      calls.push({ batch: body.changes });
      if (batchFails) {
        return r.fulfill({ status: 400, json: { message: 'Nothing was saved: vm.metrics.idle.cpu-threshold-percent must be between 0 and 100' } });
      }
      return r.fulfill({ json: body.changes.map((c: any) => ({ ...LIST.find(x => x.key === c.key)!, value: c.value, source: 'OVERRIDE',
        updatedByName: 'Test Admin', updatedAt: new Date().toISOString() })) });
    }
    return r.fulfill({ json: LIST });
  });
  await page.route(/\/api\/v1\/settings\/[^/]+$/, r => {
    const key = decodeURIComponent(r.request().url().split('/').pop()!);
    calls.push({ [r.request().method()]: key });
    return r.fulfill({ json: { ...LIST.find(s => s.key === key)!, source: 'DEFAULT', value: 'false' } });
  });
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(h => { location.hash = h; }, hash);
  await expect(page.locator('#st-nav .st-nav-item')).toHaveCount(8, { timeout: 15_000 });
  return calls;
}

const row = (page: Page, key: string) => page.locator(`[data-row="${key}"]`);

test.describe('Settings page', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('sections with counts; rows with their state; switches named by their setting', async ({ page }) => {
    await open(page);
    const nav = page.locator('#st-nav');
    await expect(nav.locator('[aria-current="page"]')).toContainText('Notifications');
    await expect(nav.locator('[data-st-section="notif"] .st-count')).toHaveText('3');
    await expect(nav.locator('[data-st-section="access"] .st-count')).toHaveText('2');
    await expect(page.locator('#st-title')).toHaveText('Notifications');
    await expect(page.getByRole('switch', { name: 'Email notifications' })).not.toBeChecked();
    await expect(row(page, 'notification.email.enabled').locator('.st-state')).toHaveText('Off by default');
    await expect(row(page, 'notification.email.enabled')).toContainText('notification.email.enabled');
    await expect(row(page, 'notification.email.access-granted.enabled').locator('.st-state')).toHaveText('Set by Test Admin');
    await expect(page.locator('#st-bar')).toBeHidden();

    // Acceptance: a tier 2 setting says 'Set in .env' and has no control.
    await nav.getByRole('button', { name: /Access and security/ }).click();
    await expect(page).toHaveURL(/#\/settings\?section=access$/);
    const ro = row(page, 'entraid.enabled');
    await expect(ro.locator('.st-state')).toHaveText('Set in .env');
    await expect(ro.locator('input')).toHaveCount(0);
    await expect(page.getByRole('switch', { name: 'Username and password sign-in' })).toBeChecked();
  });

  test('changes wait in the bar; Discard puts them back; Save changes sends them together', async ({ page }) => {
    const calls = await open(page);
    // Acceptance: two email switches turned on: nothing saved until Save changes; the bar says 2.
    await page.getByRole('switch', { name: 'Email notifications' }).check();
    await page.getByRole('switch', { name: 'Lock broken' }).check();
    await expect(page.locator('#st-bar-text')).toHaveText('2 changes not saved');
    await expect(row(page, 'notification.email.enabled').locator('.st-state')).toHaveText('Changed');
    expect(calls.filter(c => c.batch || c.PUT)).toEqual([]);

    // Acceptance: Discard returns both to their stored values.
    await page.locator('#st-discard').click();
    await expect(page.getByRole('switch', { name: 'Email notifications' })).not.toBeChecked();
    await expect(page.getByRole('switch', { name: 'Lock broken' })).not.toBeChecked();
    await expect(page.locator('#st-bar')).toBeHidden();

    await page.getByRole('switch', { name: 'Email notifications' }).check();
    await page.getByRole('switch', { name: 'Lock broken' }).check();
    await page.getByRole('switch', { name: 'Lock broken' }).uncheck(); // back to its stored value: not a change
    await expect(page.locator('#st-bar-text')).toHaveText('1 change not saved');
    await page.locator('#st-nav [data-st-section="auto"]').click();
    await page.getByLabel('Idle CPU %').fill('8');
    await expect(page.locator('#st-bar-text')).toHaveText('2 changes not saved');
    await expect(page.locator('#st-nav [data-st-section="notif"] .st-dot')).toHaveCount(1);
    await page.locator('#st-save').click();
    await expect.poll(() => calls.find(c => c.batch)?.batch).toEqual([
      { key: 'notification.email.enabled', value: 'true' }, { key: 'vm.metrics.idle.cpu-threshold-percent', value: '8' }]);
    await expect(page.locator('#st-bar')).toBeHidden();
    await expect(row(page, 'vm.metrics.idle.cpu-threshold-percent').locator('.st-state')).toHaveText('Set by Test Admin');
  });

  test('a number outside its range is not sent; a refused batch shows the problem under its row', async ({ page }) => {
    const calls = await open(page, '#/settings?section=auto', true);
    await page.getByLabel('Idle CPU %').fill('150');
    await page.locator('#st-save').click();
    await expect(row(page, 'vm.metrics.idle.cpu-threshold-percent').locator('.st-error')).toHaveText('Enter a number from 0 to 100.');
    expect(calls.filter(c => c.batch)).toEqual([]);

    await page.getByLabel('Idle CPU %').fill('50');
    await page.locator('#st-save').click();
    await expect(row(page, 'vm.metrics.idle.cpu-threshold-percent').locator('.st-error')).toContainText('must be between 0 and 100');
    await expect(page.locator('#st-bar-text')).toHaveText('1 change not saved');
  });

  test('switches that act on AWS are confirmed before the save; Reset returns a setting to its default', async ({ page }) => {
    const calls = await open(page, '#/settings?section=cost');
    await page.getByRole('switch', { name: 'Cost-allocation tagging' }).check();
    await page.locator('#st-save').click();
    await expect(page.locator('.modal.show')).toContainText('writes cost-allocation tags');
    await page.locator('.modal.show .btn-secondary, .modal.show [data-bs-dismiss="modal"]').first().click();
    await expect(page.locator('.modal.show')).toHaveCount(0);
    expect(calls.filter(c => c.batch)).toEqual([]);
    await expect(page.locator('#st-bar-text')).toHaveText('1 change not saved');

    await page.locator('#st-nav [data-st-section="notif"]').click();
    await row(page, 'notification.email.access-granted.enabled').getByRole('button', { name: 'Reset Access granted to its default' }).click();
    await expect.poll(() => calls.some(c => c.DELETE === 'notification.email.access-granted.enabled')).toBe(true);
    await expect(row(page, 'notification.email.access-granted.enabled').locator('.st-state')).toHaveText('Off by default');
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
  await expect(page.locator('#content-area')).toContainText('is for administrators');
  expect(called).toBe(false);
});
