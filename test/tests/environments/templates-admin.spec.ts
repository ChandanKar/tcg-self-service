import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E32-T08, U14-T08 (TemplatesAdmin.dc.html): the templates list with the chosen one's editor (with its
 * parameter-schema builder) and the requests waiting alongside, and the Requests tab. Every template and
 * provisioning API call is intercepted.
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
    const body = route.request().postDataJSON?.() ?? null;
    calls.push({ [route.request().method()]: body });
    return route.fulfill(json({ ...TEMPLATE, ...(body || {}), templateId: 't-1' }));
  });
  await page.route(/\/api\/v1\/aws-accounts\/options$/, route => route.fulfill(json([
    { ref: 'acc-1', name: 'tcg-nonprod', regions: ['ap-south-1'], isDefault: true }])));
  await page.route(/\/api\/v1\/provisioning-requests\/mine/, route => route.fulfill(json({ content: [], page: { totalElements: 0 } })));
  await page.route(/\/api\/v1\/provisioning-requests\/pending/, route => route.fulfill(json({ content: [PENDING], page: { totalElements: 1 } })));
  await page.route(/\/api\/v1\/provisioning-requests\/r-1\/(approve|reject)$/, route => {
    calls.push({ [route.request().url().endsWith('approve') ? 'approve' : 'reject']: route.request().postDataJSON() });
    return route.fulfill(json({ ...PENDING, status: 'APPROVED' }));
  });
  // U02-T01: the router opens New environments only while the capabilities say provisioning is on.
  await page.route(/\/api\/v1\/users\/me\/capabilities$/, async route => {
    const real = await route.fetch();
    const body = await real.json();
    body.features = { ...(body.features || {}), templates: true };
    await route.fulfill({ response: real, json: body });
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

  test('an admin lands on Templates: the list, the chosen one in the editor and the requests waiting alongside', async ({ page }) => {
    await open(page);
    await expect(page.locator('#templates-view h1')).toHaveText('New environments', { timeout: 15_000 });
    const tabs = page.locator('[data-action="templates-tab"]');
    await expect(tabs).toHaveText(['Templates', 'Requests · 1 waiting', 'Ask for one', 'My requests']);
    const list = page.locator('#templates-admin-list');
    await expect(list.locator('[data-template="t-0"]')).toContainText('Not offered');
    await expect(list.locator('[data-template="t-1"]')).toContainText('Offered');
    await expect(list.locator('[data-template="t-1"]')).toHaveAttribute('aria-current', 'true');
    await expect(page.locator('#ta-editor-title')).toHaveText('Dev stack');
    await expect(page.locator('#tpl-e-active')).toBeChecked();
    // Acceptance: pending requests appear alongside.
    const waiting = page.locator('.ta-aside .ta-request[data-request="r-1"]');
    await expect(waiting).toContainText('feature-y');
    await expect(waiting).toContainText('Pat <i>x</i> · for 24 hours · about $4');
    await expect(waiting).toContainText('"Release test"');
  });

  test('changing the longest allowed hours saves and stays on the template', async ({ page }) => {
    const calls = await open(page);
    await expect(page.locator('#ta-editor-title')).toHaveText('Dev stack', { timeout: 15_000 });
    await page.locator('#tpl-e-max').fill('3000');
    await page.locator('#tpl-e-save').click();
    await expect(page.locator('#tpl-e-error')).toHaveText('The longest allowed is 2,160 hours.');
    expect(calls).toHaveLength(0);

    await page.locator('#tpl-e-max').fill('336');
    await page.locator('#tpl-e-layout').fill('1|2');
    await page.locator('#tpl-e-save').click();
    // Acceptance: Save template keeps the list and the editor, with the new value sent.
    await expect.poll(() => calls.length).toBe(1);
    expect(calls[0].PUT).toMatchObject({ name: 'Dev stack', maxTtlHours: 336, vmLayout: '1|2', active: true,
      parameterSchema: [{ key: 'size', type: 'enum', options: ['small', 'large'] }] });
    await expect(page.locator('#templates-admin-list [data-template="t-1"]')).toBeVisible();
    await expect(page.locator('#ta-editor-title')).toHaveText('Dev stack');
  });

  test('a new template with an enum parameter', async ({ page }) => {
    const calls = await open(page);
    await page.getByRole('button', { name: '+ New template' }).click({ timeout: 15_000 });
    await expect(page.locator('#ta-editor-title')).toHaveText('New template');
    await page.locator('#tpl-e-name').fill('QA stack');
    await page.locator('#tpl-ref-productId').fill('prod-qa');
    await page.locator('#tpl-ref-provisioningArtifactId').fill('pa-9');
    await page.locator('.ta-params summary').click();
    await page.getByRole('button', { name: 'Add parameter' }).click();
    const row = page.locator('tr.tpl-param').first();
    await row.locator('[data-f="key"]').fill('size');
    await row.locator('[data-f="type"]').selectOption('enum');
    await row.locator('[data-f="options"]').fill('small, large');
    await row.locator('[data-f="required"]').check();
    await page.locator('#tpl-e-save').click();

    await expect.poll(() => calls.length).toBe(1);
    expect(calls[0].create).toMatchObject({
      name: 'QA stack', engine: 'SERVICE_CATALOG', engineRef: { productId: 'prod-qa', provisioningArtifactId: 'pa-9' },
      parameterSchema: [{ key: 'size', type: 'enum', required: true, options: ['small', 'large'] }],
      requiresApproval: true, allowedRoles: ['USER', 'ENV_ADMIN'], active: true,
    });
  });

  test('switching the engine shows the Terraform fields; turning Offered off asks first', async ({ page }) => {
    const calls = await open(page);
    await expect(page.locator('#tpl-ref-productId')).toHaveValue('prod-abc', { timeout: 15_000 });
    await page.locator('#tpl-e-engine').selectOption('TERRAFORM');
    await expect(page.locator('#tpl-ref-workspacePrefix')).toBeVisible();
    await expect(page.locator('#tpl-ref-productId')).toHaveCount(0);
    await page.locator('#tpl-e-engine').selectOption('SERVICE_CATALOG');
    await page.locator('#tpl-ref-productId').fill('prod-abc');
    await page.locator('#tpl-ref-provisioningArtifactId').fill('pa-1');

    await page.locator('#tpl-e-active').uncheck();
    await page.locator('#tpl-e-save').click();
    await expect(page.locator('.modal.show')).toContainText("Stop offering 'Dev stack'?");
    await page.locator('#confirmBtn').click();
    await expect.poll(() => calls.length).toBe(1);
    expect(calls[0].PUT).toMatchObject({ active: false });
  });

  test('an approver must give a reason to decline; approve asks first', async ({ page }) => {
    const calls = await open(page);
    await page.locator('[data-action="templates-tab"][data-tab="approvals"]').click({ timeout: 15_000 });
    await expect(page).toHaveURL(/#\/templates\?tab=approvals$/);
    const card = page.locator('.ta-queue .provisioning-approval[data-request="r-1"]');
    await expect(card.locator('.request-params')).toHaveText('Parameters: size = large', { timeout: 15_000 });
    await expect(card).toContainText('"Release test"');
    await expect(card).toContainText('Pat <i>x</i>');

    await card.getByRole('button', { name: 'Decline' }).click();
    const submit = page.locator('#promptSubmitBtn');
    await expect(submit).toHaveText('Decline');
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

test('a user gets the catalogue and their requests, not the Templates editor or Requests', async ({ page }) => {
  test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  await page.route(/\/api\/v1\/templates(\?.*)?$/, route => route.fulfill({ json: [TEMPLATE] }));
  await page.route(/\/api\/v1\/provisioning-requests\/mine/, route => route.fulfill({ json: { content: [], page: { totalElements: 0 } } }));
  await page.route(/\/api\/v1\/users\/me\/capabilities$/, async route => {
    const real = await route.fetch();
    const body = await real.json();
    body.features = { ...(body.features || {}), templates: true };
    await route.fulfill({ response: real, json: body });
  });
  await signIn(page, 'user');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/templates?tab=manage'; });
  await expect(page.locator('[data-action="templates-tab"]')).toHaveText(['Templates', 'My requests'], { timeout: 15_000 });
  await expect(page.locator('#templates-admin-list')).toHaveCount(0);
  await expect(page.locator('.template-card[data-template="t-1"]')).toBeVisible();
});
