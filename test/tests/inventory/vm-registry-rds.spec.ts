import { test, expect, type Page, type Route } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E33-T02 / U11-T04: RDS databases are added from the Add VMs page's Databases (RDS) tab, which
 * shows only while RDS is on. Databases are listed by identifier with why RDS cannot stop some,
 * registered ones are not offered, and the batch carries their references. The API is faked in
 * the browser.
 */

declare const ContentRouter: any;

const ENV_ID = 'env-rds-e2e';
const DATABASES = [
  { ref: 'ref-aurora', name: 'aurora-qa', instanceType: null, zone: null, privateIp: null, state: 'available',
    status: 'AVAILABLE', note: null },
  { ref: 'ref-orders', name: 'orders', instanceType: 'db.t3.medium', zone: null, privateIp: null, state: 'stopped',
    status: 'IN_ENVIRONMENT', inEnvironmentName: 'RDS E2E', note: 'Already in RDS E2E' },
  { ref: 'ref-ro', name: 'orders-ro<b>', instanceType: 'db.t3.medium', zone: null, privateIp: null, state: 'available',
    status: 'AVAILABLE', note: 'DB instance orders-ro is a read replica, which RDS cannot stop' },
];

const json = (route: Route, body: unknown, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function openAddVms(page: Page, rdsEnabled: boolean) {
  const batches: any[] = [];
  const sources: string[] = [];
  await page.route(new RegExp(`/api/v1/environments/${ENV_ID}$`), route => json(route, { environmentId: ENV_ID, name: 'rds-e2e',
    displayName: 'RDS E2E', isActive: true, serviceType: 'EC2', regions: [], viewerCanAdminister: true }));
  await page.route(new RegExp(`/api/v1/environments/${ENV_ID}/groups$`), route => json(route, [
    { groupId: 'g-data', name: 'data', displayName: 'Data', sequencePosition: 1 }]));
  await page.route(/\/api\/v1\/rds$/, route => json(route, { enabled: rdsEnabled }));
  await page.route(new RegExp(`/api/v1/environments/${ENV_ID}/vms/candidates\\?`), route => {
    const source = new URL(route.request().url()).searchParams.get('source') || '';
    sources.push(source);
    return json(route, source === 'RDS' ? DATABASES : []);
  });
  await page.route(new RegExp(`/api/v1/environments/${ENV_ID}/vms/batch$`), route => {
    batches.push(route.request().postDataJSON());
    return json(route, [], 201);
  });
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(id => { location.hash = `#/vm-registry/${id}/add`; }, ENV_ID);
  await expect(page.locator('#content-area h1')).toHaveText('Add VMs to RDS E2E', { timeout: 15_000 });
  await expect(page.locator('#rv-table-wrap')).toContainText('No instances in this region.');
  return { batches, sources };
}

test.describe('Adding RDS databases', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('a database is picked on the Databases tab and added by its reference', async ({ page }) => {
    const { batches, sources } = await openAddVms(page, true);
    await page.locator('[data-rv-kind="RDS"]').click();
    await expect(page.locator('[data-rv-kind="RDS"]')).toHaveAttribute('aria-pressed', 'true');

    const rows = page.locator('#rv-table tbody tr');
    await expect(rows).toHaveCount(3);
    expect(sources).toEqual(['EC2', 'RDS']);
    await expect(rows.filter({ hasText: 'orders-ro' }).locator('.rv-note')).toContainText('read replica');
    await expect(rows.filter({ hasText: 'orders-ro' }).locator('.rv-name')).toHaveText('orders-ro<b>');
    await expect(page.locator('#rv-table b')).toHaveCount(0);
    await expect(rows.filter({ hasText: 'Already in RDS E2E' }).locator('.rv-pick')).toBeDisabled();

    await rows.filter({ hasText: 'aurora-qa' }).locator('.rv-pick').check();
    await page.locator('#rv-add').click();
    await expect.poll(() => batches.length).toBe(1);
    expect(batches[0]).toEqual({ groupId: 'g-data', items: [{ ref: 'ref-aurora', name: 'aurora-qa' }] });
  });

  test('with RDS off only servers can be listed', async ({ page }) => {
    await openAddVms(page, false);
    await expect(page.locator('.rv-kind')).toBeHidden();
  });
});
