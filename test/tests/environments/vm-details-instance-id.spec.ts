import { test, expect, type APIRequestContext } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * U01-T05: a VM's details show its instance ID to administrators only ("Instance ID · Administrators
 * only"); anyone else sees the VM, its IP and state, and no instance ID anywhere on the page.
 * U08-T05: the details are a side panel named after the VM, with the environment and group above the
 * name, quick stats, and Overview / Use / Storage / Idle / History tabs that the arrow keys move
 * between; Use switches its window without leaving the tab; Close returns to the Details button.
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
  await expect(page.locator('#dynamicSlideoutPanel[data-variant="vm-details"] .vm-panel-fields')).toBeVisible({ timeout: 15_000 });
  return details;
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

  test('U08-T05: the panel is named after the VM, with its place, stats and Overview fields', async ({ page, playwright }) => {
    const { envId, vmId } = await environmentWithVm(playwright);
    await openDetails(page, 'admin', envId, vmId);
    const panel = page.locator('#dynamicSlideoutPanel[data-variant="vm-details"]');
    await expect(panel).toHaveAttribute('role', 'dialog');
    await expect(page.getByRole('dialog', { name: 'Web 01', exact: true })).toBeVisible();
    await expect(panel.locator('.vm-head-kicker')).toHaveText(/^Instance ID \d+ · Web group$/);
    await expect(panel.locator('.vm-state-chip')).toBeVisible();
    await expect(panel.locator('.vm-panel-stat-label')).toHaveText(['Size', 'CPU now', 'IP address', 'Disk used']);
    await expect(panel.locator('.vm-panel-stat').nth(3)).toContainText('Not collected');
    await expect(panel.locator('.vm-panel-field dt')).toHaveText(['Size', 'vCPU / memory', 'Region / zone', 'Private IP',
      'Public IP', 'Purchase', 'Purpose', 'Launched', 'State read from AWS', 'Cost this month']);
    await expect(panel.locator('.vm-panel-field').filter({ hasText: 'Region / zone' }).locator('dd')).toHaveText('ap-south-1');
    await expect(panel).not.toContainText('pending-provider-id');
  });

  test('U08-T05: the tabs are a tablist; Use switches to 7 days and stays on Use; Close returns to Details', async ({ page, playwright }) => {
    const { envId, vmId } = await environmentWithVm(playwright);
    const details = await openDetails(page, 'user', envId, vmId);
    const panel = page.locator('#dynamicSlideoutPanel[data-variant="vm-details"]');
    const tabs = panel.getByRole('tablist', { name: 'VM details' }).getByRole('tab');
    await expect(tabs).toHaveText(['Overview', 'Use', 'Storage', 'Idle', 'History']);

    await tabs.nth(0).focus();
    await page.keyboard.press('ArrowRight');
    await expect(tabs.nth(1)).toHaveAttribute('aria-selected', 'true');
    await expect(tabs.nth(1)).toBeFocused();
    const use = panel.getByRole('tabpanel', { name: 'Use' });
    await expect(use).toBeVisible();
    await expect(use.locator('[data-metric="Memory"]')).toContainText('Needs the CloudWatch agent on the VM', { timeout: 10_000 });

    const asked = page.waitForRequest(r => r.url().includes(`/vms/${vmId}/metrics`) && r.url().includes('window=7d') && r.url().includes('period=3600'));
    await use.getByRole('button', { name: '7 days' }).click();
    await asked;
    await expect(panel.getByRole('tab', { name: 'Use' })).toHaveAttribute('aria-selected', 'true');
    await expect(panel.getByRole('button', { name: '7 days' })).toHaveAttribute('aria-pressed', 'true');
    await expect(use.locator('[data-metric="CPU"]')).toBeVisible();

    await panel.locator('.close-btn').click();
    await expect(panel).not.toHaveClass(/show/);
    await expect(details).toBeFocused();
  });

  test('U08-T06: cost this month and the rightsizing note; administrators resize, others are told to ask', async ({ page, playwright }) => {
    const { envId, vmId } = await environmentWithVm(playwright);
    const advice = { monthToDateCost: 38.2, hourlyRate: 0.096, priceApproximate: false, purchaseOption: 'ON_DEMAND',
      rightsizing: { currentType: 'm6i.large', recommendedType: 'm6i.medium', direction: 'SCALE_DOWN', monthlySavings: 31,
        cpuPeak: 18, memoryPeak: 41, source: 'cpu-threshold-rule', optimizerAgrees: true } };
    await page.route(new RegExp(`/vms/${vmId}/advice$`), route => route.fulfill({ json: advice }));
    await openDetails(page, 'user', envId, vmId);
    const panel = page.locator('#dynamicSlideoutPanel[data-variant="vm-details"]');
    await expect(panel.locator('.vm-panel-field').filter({ hasText: 'Cost this month' }).locator('dd')).toHaveText('$38.20');
    const note = panel.locator('.vm-advice');
    await expect(note).toContainText('Could be one size smaller: m6i.medium');
    await expect(note).toContainText('CPU peaked at 18% and memory at 41%. Saves about $31 a month. AWS Compute Optimizer agrees.');
    await expect(note).toContainText('Ask an administrator to resize');
    await expect(note.getByRole('link')).toHaveCount(0);

    await page.locator('#dynamicSlideoutPanel .close-btn').click();
    await openDetails(page, 'admin', envId, vmId);
    await expect(panel.locator('.vm-advice').getByRole('link', { name: 'Resize safely…' })).toHaveAttribute('href', '#/cost-management');
  });
});
