import { test, expect, type Page, type Route } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E33-T02: registering RDS databases from the VM registry. The registry and RDS APIs are faked in
 * the browser: the EC2 / RDS toggle appears only while RDS is on, databases are listed with their
 * kind and whether RDS can stop them, registered ones are not offered, and selecting one fills the
 * register form with provider AWS_RDS and its db:/cluster: id.
 */

declare const ContentRouter: any;
declare const VmRegistry: any;

const ENV_ID = 'env-rds-e2e';
const DATABASES = [
  { providerVmId: 'cluster:aurora-qa', identifier: 'aurora-qa', kind: 'CLUSTER', engine: 'aurora-postgresql', engineVersion: '16.4',
    instanceClass: null, status: 'available', multiAz: false, stoppable: true, notStoppableReason: null, registered: false },
  { providerVmId: 'db:orders', identifier: 'orders', kind: 'INSTANCE', engine: 'postgres', engineVersion: '16.3',
    instanceClass: 'db.t3.medium', status: 'stopped', multiAz: false, stoppable: true, notStoppableReason: null, registered: true },
  { providerVmId: 'db:orders-ro', identifier: 'orders-ro<b>', kind: 'INSTANCE', engine: 'postgres', engineVersion: '16.3',
    instanceClass: 'db.t3.medium', status: 'available', multiAz: false, stoppable: false,
    notStoppableReason: 'DB instance orders-ro is a read replica, which RDS cannot stop', registered: false },
];

const json = (route: Route, body: unknown, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function openRegister(page: Page, rdsEnabled: boolean) {
  const posts: any[] = [];
  await page.route(/\/api\/v1\/environments(\?.*)?$/, route => route.request().method() === 'GET'
    ? json(route, [{ environmentId: ENV_ID, name: 'rds-e2e', displayName: 'RDS E2E', isActive: true, serviceType: 'EC2',
        groupCount: 1, vmCount: 0, createdAt: new Date().toISOString() }])
    : route.continue());
  await page.route(new RegExp(`/api/v1/environments/${ENV_ID}/vms(/.*)?(\\?.*)?$`), route => {
    const req = route.request();
    if (req.method() === 'POST') {
      posts.push(req.postDataJSON());
      return json(route, { vmId: 'vm-new', ...req.postDataJSON() }, 201);
    }
    if (req.url().includes('/review/counts')) return json(route, { drift: 0, pending: 0, inactive: 0 });
    if (req.url().includes('/review')) return json(route, { content: [], page: { size: 25, number: 0, totalElements: 0, totalPages: 1 } });
    return json(route, [{ group: { groupId: 'g-data', name: 'data', displayName: 'Data', sequencePosition: 1, dependsOnGroupIds: [],
      vmCount: 0, runningVmCount: 0 }, vms: [] }]);
  });
  await page.route(/\/api\/v1\/rds$/, route => json(route, { enabled: rdsEnabled }));
  await page.route(/\/api\/v1\/rds\/databases\?/, route => json(route, DATABASES));
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/vm-registry'; });
  await page.waitForFunction(() => typeof VmRegistry !== 'undefined' && (window as any).VmRegistryState?.environments?.length > 0);
  await page.evaluate(id => VmRegistry.manageGroups(id), ENV_ID);
  await expect(page.locator('#manageGroupsModal')).toBeVisible();
  await page.evaluate(() => VmRegistry.openVmForm('g-data'));
  await expect(page.locator('#registerVmModal')).toBeVisible();
  return posts;
}

test.describe('Registering RDS databases', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('an RDS database is picked from the list and registered as AWS_RDS', async ({ page }) => {
    const posts = await openRegister(page, true);
    const toggle = page.locator('#importSourceToggle');
    await expect(toggle).toBeVisible();
    await page.locator('label[for="importSourceRds"]').click();
    await expect(page.locator('#btnFetchEc2')).toHaveText(/Fetch Databases/);
    await page.locator('#ec2Region').selectOption({ index: 1 });
    await page.locator('#btnFetchEc2').click();

    const table = page.locator('#rdsDatabaseTable');
    await expect(table.locator('tbody tr')).toHaveCount(3);
    const inUse = table.locator('tr[data-provider-vm-id="db:orders"]');
    await expect(inUse).toContainText('In Use');
    await expect(inUse).not.toHaveAttribute('data-action', /.*/);
    const replica = table.locator('tr[data-provider-vm-id="db:orders-ro"]');
    await expect(replica.locator('.rds-not-stoppable')).toContainText('read replica');
    await expect(replica).toContainText('orders-ro<b>');
    await expect(replica.locator('b')).toHaveCount(0);

    await table.locator('tr[data-provider-vm-id="cluster:aurora-qa"]').click();
    await expect(page.locator('#vmProvider')).toHaveValue('AWS_RDS');
    await expect(page.locator('#vmProviderVmId')).toHaveValue('cluster:aurora-qa');
    await expect(page.locator('#vmName')).toHaveValue('aurora-qa');

    await page.locator('#btnSubmitVm').click();
    await expect.poll(() => posts.length).toBe(1);
    expect(posts[0]).toMatchObject({ provider: 'AWS_RDS', providerVmId: 'cluster:aurora-qa', name: 'aurora-qa', groupId: 'g-data' });
  });

  test('with RDS off only EC2 can be browsed', async ({ page }) => {
    await openRegister(page, false);
    await page.waitForTimeout(500);
    await expect(page.locator('#importSourceToggle')).toBeHidden();
    await expect(page.locator('#btnFetchEc2')).toHaveText(/Fetch Instances/);
  });
});
