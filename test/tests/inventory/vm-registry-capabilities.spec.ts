import { test, expect, type APIRequestContext } from '@playwright/test';
import { devIds } from '../../fixtures/auth';

/**
 * U02-T02: the VM registry shows change controls only where the server says the person administers the
 * environment. Run against an app with security.env-admin.scope=assigned (the local test app): an
 * environment admin without an admin grant on an environment sees it as "View only".
 */

declare const ContentRouter: any;
declare const VmRegistry: any;

const ENV_ADMIN_ID = process.env.TEST_DEV_ENV_ADMIN_ID || 'dev-user-001';

async function adminApi(playwright: any): Promise<APIRequestContext> {
  return playwright.request.newContext({
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    extraHTTPHeaders: { 'X-User-Id': devIds.admin, 'Content-Type': 'application/json' },
  });
}

test.describe('VM registry follows what the server allows', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin, 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('an environment admin can change only the environments they administer', async ({ page, playwright }) => {
    const admin = await adminApi(playwright);
    await admin.patch(`/api/v1/users/${ENV_ADMIN_ID}/roles`, { data: { admin: false, envAdmin: true } });
    const stamp = Date.now();
    const mine = await (await admin.post('/api/v1/environments', {
      data: { name: `caps-mine-${stamp}`, displayName: `Caps mine ${stamp}`, cloudProvider: 'AWS' } })).json();
    const other = await (await admin.post('/api/v1/environments', {
      data: { name: `caps-other-${stamp}`, displayName: `Caps other ${stamp}`, cloudProvider: 'AWS' } })).json();
    const envAdmin = await (await admin.get(`/api/v1/users/${ENV_ADMIN_ID}`)).json();
    await admin.post(`/api/v1/environments/${mine.environmentId}/access`, {
      data: { userEmail: envAdmin.email, accessLevel: 'ADMIN', durationDays: 1 } });
    const caps = await (await (await playwright.request.newContext({
      baseURL: process.env.BASE_URL || 'http://localhost:8080',
      extraHTTPHeaders: { 'X-User-Id': ENV_ADMIN_ID } })).get(`/api/v1/environments/${other.environmentId}`)).json();
    await admin.dispose();
    test.skip(caps.viewerCanAdminister === true, 'This app runs security.env-admin.scope=global: every environment admin administers everything');

    await page.context().setExtraHTTPHeaders({ 'X-User-Id': ENV_ADMIN_ID });
    await page.goto('/home');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/vm-registry'; });
    await page.waitForFunction(() => typeof VmRegistry !== 'undefined' && (window as any).VmRegistryState?.environments?.length > 0);

    const mineRow = page.locator(`tr[data-env-id="${mine.environmentId}"]`);
    const otherRow = page.locator(`tr[data-env-id="${other.environmentId}"]`);
    await expect(mineRow.locator('[data-action="vr-edit-environment"]')).toBeVisible();
    await expect(otherRow.locator('[data-action="vr-edit-environment"]')).toHaveCount(0);
    await expect(otherRow).toContainText('View only');
  });
});
