import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E32-T08: the template editor (with its parameter-schema builder) and the approvals queue.
 * Every template and provisioning API call is intercepted.
 */

declare const ContentRouter: any;

const TEMPLATE = {
  templateId: 't-1', name: 'Dev stack', description: 'Small', engine: 'SERVICE_CATALOG',
  engineRef: { productId: 'prod-abc', provisioningArtifactId: 'pa-1' },
  parameterSchema: [{ key: 'size', label: 'Size', type: 'enum', options: ['small', 'large'], default: 'small' }],
  region: 'us-east-1', awsAccountRef: null, defaultTtlHours: 48, maxTtlHours: 168, requiresApproval: true,
  approverRole: 'ADMIN', allowedRoles: ['USER'], estimatedMonthlyCost: 120.5, active: true,
};

const PENDING = {
  requestId: 'r-1', templateId: 't-1', templateName: 'Dev stack', requestedByUserId: 'u-2', requestedByName: 'Pat <i>x</i>',
  environmentName: 'feature-y', parameters: { size: 'large' }, justification: 'Release test', ttlHours: 24,
  status: 'PENDING_APPROVAL', createdAt: new Date().toISOString(), canApprove: true, canCancel: false,
};

async function open(page: Page) {
  const calls: any[] = [];
  const json = (body: any, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
  await page.route(/\/api\/v1\/templates(\?.*)?$/, route => {
    if (route.request().method() === 'POST') {
      calls.push({ create: route.request().postDataJSON() });
      return route.fulfill(json({ ...TEMPLATE, templateId: 't-2' }, 201));
    }
    return route.fulfill(json([TEMPLATE]));
  });
  await page.route(/\/api\/v1\/templates\/all$/, route => route.fulfill(json([TEMPLATE, { ...TEMPLATE, templateId: 't-0', name: 'Old', active: false }])));
  await page.route(/\/api\/v1\/templates\/t-1$/, route => {
    calls.push({ [route.request().method()]: route.request().postDataJSON?.() ?? null });
    return route.fulfill(json({ ...TEMPLATE, active: route.request().method() !== 'DELETE' }));
  });
  await page.route(/\/api\/v1\/provisioning-requests\/mine/, route => route.fulfill(json({ content: [], page: { totalElements: 0 } })));
  await page.route(/\/api\/v1\/provisioning-requests\/pending/, route => route.fulfill(json({ content: [PENDING], page: { totalElements: 1 } })));
  await page.route(/\/api\/v1\/provisioning-requests\/r-1\/(approve|reject)$/, route => {
    calls.push({ [route.request().url().endsWith('approve') ? 'approve' : 'reject']: route.request().postDataJSON() });
    return route.fulfill(json({ ...PENDING, status: 'APPROVED' }));
  });
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/templates'; });
  return calls;
}

test.describe('Template administration and approvals', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('an admin builds a template with an enum parameter', async ({ page }) => {
    const calls = await open(page);
    await page.locator('[data-action="templates-tab"][data-tab="manage"]').click();
    const table = page.locator('#templates-admin-table');
    await expect(table.locator('tr[data-template="t-0"]')).toContainText('Retired', { timeout: 15_000 });
    await expect(table.locator('tr[data-template="t-0"] [data-action="tpl-retire"]')).toHaveCount(0);

    await page.getByRole('button', { name: 'New template' }).click();
    const modal = page.locator('#templateEditorModal');
    await modal.locator('#tpl-e-name').fill('QA stack');
    await modal.locator('#tpl-ref-productId').fill('prod-qa');
    await modal.locator('#tpl-ref-provisioningArtifactId').fill('pa-9');
    await modal.getByRole('button', { name: 'Add parameter' }).click();
    const row = modal.locator('tr.tpl-param').first();
    await row.locator('[data-f="key"]').fill('size');
    await row.locator('[data-f="type"]').selectOption('enum');
    await row.locator('[data-f="options"]').fill('small, large');
    await row.locator('[data-f="required"]').check();
    await modal.locator('#tpl-e-save').click();

    await expect.poll(() => calls.length).toBe(1);
    expect(calls[0].create).toMatchObject({
      name: 'QA stack', engine: 'SERVICE_CATALOG', engineRef: { productId: 'prod-qa', provisioningArtifactId: 'pa-9' },
      parameterSchema: [{ key: 'size', type: 'enum', required: true, options: ['small', 'large'] }],
      requiresApproval: true, allowedRoles: ['ENV_ADMIN', 'USER'],
    });
  });

  test('switching the engine shows the Terraform fields, and retire asks first', async ({ page }) => {
    const calls = await open(page);
    await page.locator('[data-action="templates-tab"][data-tab="manage"]').click();
    await page.locator('tr[data-template="t-1"] [data-action="tpl-edit"]').click({ timeout: 15_000 });
    const modal = page.locator('#templateEditorModal');
    await expect(modal.locator('#tpl-ref-productId')).toHaveValue('prod-abc');
    await modal.locator('#tpl-e-engine').selectOption('TERRAFORM');
    await expect(modal.locator('#tpl-ref-workspacePrefix')).toBeVisible();
    await expect(modal.locator('#tpl-ref-productId')).toHaveCount(0);
    await modal.getByRole('button', { name: 'Cancel' }).click();
    await expect(modal).toBeHidden();

    await page.locator('tr[data-template="t-1"] [data-action="tpl-retire"]').click();
    await page.locator('#confirmBtn').click();
    await expect.poll(() => calls.length).toBe(1);
    expect(calls[0]).toHaveProperty('DELETE');
  });

  test('an approver must give a reason to reject; approve asks first', async ({ page }) => {
    const calls = await open(page);
    await page.locator('[data-action="templates-tab"][data-tab="approvals"]').click();
    const card = page.locator('.provisioning-approval[data-request="r-1"]');
    await expect(card.locator('.request-params')).toHaveText('Parameters: size = large', { timeout: 15_000 });
    await expect(card).toContainText('Why: Release test');
    await expect(card).toContainText('Pat <i>x</i>');

    await card.getByRole('button', { name: 'Reject' }).click();
    const submit = page.locator('#promptSubmitBtn');
    await expect(submit).toHaveText('Reject');
    await expect(page.locator('#promptInput')).toBeFocused(); // the prompt binds its button once shown
    await submit.click();
    await expect(page.locator('#promptInput')).toHaveClass(/is-invalid/);
    expect(calls).toHaveLength(0);
    await page.locator('#promptInput').fill('Use the shared stack');
    await submit.click();
    await expect.poll(() => calls.length).toBe(1);
    expect(calls[0]).toEqual({ reject: { notes: 'Use the shared stack' } });

    await expect(page.locator('.modal.show')).toHaveCount(0);
    await card.getByRole('button', { name: 'Approve' }).click();
    await page.locator('#confirmBtn').click();
    await expect.poll(() => calls.length).toBe(2);
    expect(calls[1]).toEqual({ approve: {} });
  });
});
