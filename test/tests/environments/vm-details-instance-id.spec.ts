import { test, expect, type APIRequestContext } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * U01-T05: a VM's details show its instance ID to administrators only ("Instance ID · Administrators
 * only"); anyone else sees the VM, its IP and state, and no instance ID anywhere on the page.
 */

declare const ContentRouter: any;

/** A made-up instance id, new for each environment (ids are unique); nothing in AWS is touched. */
const fakeInstanceId = () => 'i-0' + Array.from({ length: 16 }, () => Math.floor(Math.random() * 16).toString(16)).join('');

async function api(playwright: any, userId: string): Promise<APIRequestContext> {
  return playwright.request.newContext({
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    extraHTTPHeaders: { 'X-User-Id': userId, 'Content-Type': 'application/json' },
  });
}

async function environmentWithVm(playwright: any): Promise<{ envId: string; vmId: string; instance: string }> {
  const instance = fakeInstanceId();
  const admin = await api(playwright, devIds.admin);
  const stamp = Date.now();
  const env = await (await admin.post('/api/v1/environments', {
    data: { name: `instance-id-${stamp}`, displayName: `Instance ID ${stamp}`, cloudProvider: 'AWS' },
  })).json();
  const group = await (await admin.post(`/api/v1/environments/${env.environmentId}/groups`, {
    data: { name: 'web', displayName: 'Web', sequencePosition: 1 },
  })).json();
  const vm = await (await admin.post(`/api/v1/environments/${env.environmentId}/vms`, {
    data: { groupId: group.groupId, name: 'web-01', displayName: 'Web 01', provider: 'AWS', region: 'ap-south-1',
      providerVmId: instance, sequencePosition: 1 },
  })).json();
  const user = await (await admin.get(`/api/v1/users/${devIds.user}`)).json();
  await admin.post(`/api/v1/environments/${env.environmentId}/access`, {
    data: { userEmail: user.email, accessLevel: 'VIEWER', durationDays: 1 },
  });
  await admin.dispose();
  return { envId: env.environmentId, vmId: vm.vmId, instance };
}

async function openDetails(page: any, role: 'admin' | 'user', envId: string, vmId: string) {
  await signIn(page, role);
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate((id: string) => { location.hash = '#/environments/' + id; }, envId);
  const details = page.locator(`[data-action="vm-details"][data-vm-id="${vmId}"]`).first();
  await expect(details).toBeVisible({ timeout: 15_000 });
  await details.click();
  await expect(page.locator('.vm-provider-line')).toBeVisible({ timeout: 15_000 });
}

test.describe('Instance ID in VM details', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !devIds.admin || !canSignIn('user'), 'Needs TEST_DEV_USER_ID and TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('an administrator sees the instance ID, marked as administrators only', async ({ page, playwright }) => {
    const { envId, vmId, instance } = await environmentWithVm(playwright);
    await openDetails(page, 'admin', envId, vmId);

    await expect(page.locator('.vm-instance-id')).toContainText(instance);
    await expect(page.locator('.vm-instance-id')).toContainText('Administrators only');
  });

  test('a viewer sees the VM without any instance ID', async ({ page, playwright }) => {
    const { envId, vmId } = await environmentWithVm(playwright);
    await openDetails(page, 'user', envId, vmId);

    await expect(page.locator('.vm-instance-id')).toHaveCount(0);
    await expect(page.locator('body')).not.toContainText(/\bi-[0-9a-f]{8,17}\b/);
    await expect(page.locator('body')).not.toContainText('pending-provider-id');
  });
});
