import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E32-T07: New environment from a template. The catalogue, the request form built from the
 * template's parameter schema with client checks, My requests with its actions, and the typed-name
 * confirmation before a destroy. Every provisioning API call is intercepted.
 */

declare const ContentRouter: any;

const TEMPLATE = {
  templateId: 't-1', name: 'Dev stack', description: 'A small <b>dev</b> stack', engine: 'SERVICE_CATALOG', engineRef: null,
  parameterSchema: [
    { key: 'size', label: 'Size', type: 'enum', options: ['small', 'large'], default: 'small' },
    { key: 'owner', label: 'Owner', type: 'string', required: true, pattern: '[a-z.]+' },
  ],
  region: 'us-east-1', defaultTtlHours: 48, maxTtlHours: 168, requiresApproval: true, approverRole: 'ADMIN',
  allowedRoles: ['USER'], estimatedMonthlyCost: 120.5, active: true,
};

const READY = {
  requestId: 'r-9', templateId: 't-1', templateName: 'Dev stack', requestedByUserId: 'u', requestedByName: 'Me',
  environmentName: 'feature-x', parameters: {}, ttlHours: 48, status: 'READY', reviewedByName: 'Admin',
  environmentId: 'env-9', destroyAt: new Date(Date.now() + 30 * 3600_000).toISOString(), createdAt: new Date().toISOString(),
  canApprove: false, canCancel: false,
};

async function open(page: Page, mine: any[] = [READY]) {
  const posts: any[] = [];
  await page.route(/\/api\/v1\/templates(\?.*)?$/, route => route.fulfill({ status: 200, contentType: 'application/json',
    body: JSON.stringify([TEMPLATE]) }));
  await page.route(/\/api\/v1\/provisioning-requests\/mine/, route => route.fulfill({ status: 200, contentType: 'application/json',
    body: JSON.stringify({ content: mine, page: { size: 100, number: 0, totalElements: mine.length, totalPages: 1 } }) }));
  await page.route(/\/api\/v1\/provisioning-requests$/, route => {
    posts.push(route.request().postDataJSON());
    return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ ...READY, status: 'PENDING_APPROVAL' }) });
  });
  await page.route(/\/api\/v1\/provisioning-requests\/r-9\/destroy$/, route => {
    posts.push({ destroy: route.request().postDataJSON() });
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...READY, status: 'DESTROYING' }) });
  });
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  return posts;
}

test.describe('New environment from a template', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('the catalogue and a request built from the parameter schema', async ({ page }) => {
    const posts = await open(page);
    await expect(page.locator('#templates-link')).toBeVisible({ timeout: 15_000 });
    await page.evaluate(() => { location.hash = '#/templates'; });

    const card = page.locator('.template-card[data-template="t-1"]');
    await expect(card).toContainText('Needs approval', { timeout: 15_000 });
    await expect(card).toContainText('A small <b>dev</b> stack');
    await expect(card.locator('b')).toHaveCount(0);
    await card.getByRole('button', { name: 'Request' }).click();

    const modal = page.locator('#templateRequestModal');
    await expect(modal.locator('select[data-param="size"] option')).toHaveText(['small', 'large']);
    await modal.locator('#tpl-name').fill('feature-x');
    await modal.locator('#tpl-submit').click();
    await expect(modal.locator('[data-error="owner"]')).toHaveText('Required.');
    expect(posts).toHaveLength(0);

    await modal.locator('[data-param="owner"]').fill('team.a');
    await modal.locator('[data-param="size"]').selectOption('large');
    await modal.locator('#tpl-submit').click();
    await expect.poll(() => posts.length).toBe(1);
    expect(posts[0]).toMatchObject({ templateId: 't-1', environmentName: 'feature-x', ttlHours: 48,
      parameters: { size: 'large', owner: 'team.a' } });
    await expect(page.locator('#my-requests-table')).toBeVisible();
  });

  test('a ready environment is destroyed only after its name is typed exactly', async ({ page }) => {
    const posts = await open(page);
    await page.evaluate(() => { location.hash = '#/templates'; });
    await page.locator('[data-action="templates-tab"][data-tab="mine"]').click();

    const row = page.locator('tr[data-request="r-9"]');
    await expect(row.locator('.request-status')).toHaveText('Ready', { timeout: 15_000 });
    await expect(row.getByRole('link', { name: 'Open' })).toHaveAttribute('href', '#/environments/env-9');
    await row.getByRole('button', { name: 'Destroy' }).click();
    const modal = page.locator('#templateDestroyModal');
    const confirm = modal.locator('#tpl-destroy-confirm');
    await modal.locator('#tpl-destroy-name').fill('feature');
    await expect(confirm).toBeDisabled();
    await modal.locator('#tpl-destroy-name').fill('feature-x');
    await expect(confirm).toBeEnabled();
    await confirm.click();
    await expect.poll(() => posts.length).toBe(1);
    expect(posts[0]).toEqual({ destroy: { confirm: true } });
  });

  test('with provisioning off there is no sidebar item', async ({ page }) => {
    await page.route(/\/api\/v1\/templates(\?.*)?$/, route => route.fulfill({ status: 404, contentType: 'application/json',
      body: JSON.stringify({ message: 'Provisioning is not enabled' }) }));
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.waitForTimeout(500);
    await expect(page.locator('#templates-link')).toBeHidden();
  });
});
