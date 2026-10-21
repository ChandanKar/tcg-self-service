import { test, expect, type Page, type Route } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * U01-T02: an EC2 instance is picked from the AWS list by name and registered by its reference. The list
 * is faked the way the API answers someone who is not an administrator: no instance ids, a reference per
 * instance and its registration. Nothing on screen shows an id and the register call sends the reference.
 */

declare const ContentRouter: any;
declare const VmRegistry: any;

const ENV_ID = 'env-ec2-ref-e2e';
const CHOICES = [
  { ref: 'ref-web-03', name: 'web-03', instanceType: 't3.medium', state: 'running', privateIpAddress: '10.0.1.15',
    availabilityZone: 'ap-south-1a', registered: false, stale: false, discoveryPending: false },
  { ref: 'ref-db-01', name: 'db-01', instanceType: 'r6i.large', state: 'stopped', privateIpAddress: '10.0.2.20',
    availabilityZone: 'ap-south-1b', registered: true, stale: false, registeredVmId: 'vm-db',
    registeredEnvironmentName: 'Payments Dev', registeredGroupName: 'Database', discoveryPending: false },
];

const json = (route: Route, body: unknown, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function openRegister(page: Page) {
  const posts: any[] = [];
  await page.route(/\/api\/v1\/environments(\?.*)?$/, route => route.request().method() === 'GET'
    ? json(route, [{ environmentId: ENV_ID, name: 'ec2-ref-e2e', displayName: 'EC2 ref E2E', isActive: true, serviceType: 'EC2',
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
    return json(route, [{ group: { groupId: 'g-web', name: 'web', displayName: 'Web', sequencePosition: 1, dependsOnGroupIds: [],
      vmCount: 0, runningVmCount: 0 }, vms: [] }]);
  });
  await page.route(/\/api\/v1\/rds$/, route => json(route, { enabled: false }));
  await page.route(/\/api\/ec2\/instances\?/, route => json(route, CHOICES));
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/vm-registry'; });
  await page.waitForFunction(() => typeof VmRegistry !== 'undefined' && (window as any).VmRegistryState?.environments?.length > 0);
  await page.evaluate(id => VmRegistry.manageGroups(id), ENV_ID);
  await expect(page.locator('#vr-tree')).toBeVisible(); // U11-T03: the groups are on the page
  await page.evaluate(() => VmRegistry.openVmForm('g-web'));
  await expect(page.locator('#registerVmModal')).toBeVisible();
  return posts;
}

test.describe('Registering EC2 instances by reference', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('instances are listed by name, never by id, and registered by their reference', async ({ page }) => {
    const posts = await openRegister(page);
    await page.locator('#ec2Region').selectOption({ index: 1 });
    await page.locator('#btnFetchEc2').click();

    const list = page.locator('#ec2InstancesList');
    await expect(list.locator('tbody tr')).toHaveCount(2);
    await expect(list).not.toContainText(/\bi-[0-9a-f]{8,17}\b/);
    const inUse = list.locator('tr[data-ref="ref-db-01"]');
    await expect(inUse).toContainText('In Use');
    await expect(inUse).toContainText('In Payments Dev');
    await expect(inUse).not.toHaveAttribute('data-action', /.*/);

    await list.locator('tr[data-ref="ref-web-03"]').click();
    await expect(page.locator('#vmName')).toHaveValue('web-03');
    await expect(page.locator('#vmProviderVmId')).toBeHidden();

    await page.locator('#btnSubmitVm').click();
    await expect.poll(() => posts.length).toBe(1);
    expect(posts[0]).toMatchObject({ provider: 'AWS', instanceRef: 'ref-web-03', name: 'web-03', groupId: 'g-web' });
    expect(posts[0].providerVmId || '').toBe('');
  });
});
