import { test, expect, type Page, type Route } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * U01-T02 / U11-T04 (RegisterVms.dc.html): VMs are added from the Add VMs page. Instances are listed
 * by name with an opaque reference, never by id; several are ticked and added to a group in one
 * batch; instances already in an environment cannot be picked. The API is faked in the browser.
 */

declare const ContentRouter: any;

const ENV_ID = 'env-ec2-ref';
const CANDIDATES = [
  { ref: 'ref-web-03', name: 'web-03', instanceType: 't3.medium', zone: 'ap-south-1a', privateIp: '10.20.3.43', state: 'stopped',
    status: 'AVAILABLE', note: null },
  { ref: 'ref-web-04', name: 'web-04', instanceType: 't3.medium', zone: 'ap-south-1b', privateIp: '10.20.3.44', state: 'stopped',
    status: 'AVAILABLE', note: null },
  { ref: 'ref-worker', name: 'pay-worker-01', instanceType: 'c6i.large', zone: 'ap-south-1a', privateIp: '10.20.4.11', state: 'running',
    status: 'DISCOVERED_UNGROUPED', note: 'Found by discovery, not in a group' },
  { ref: 'ref-etl', name: 'analytics-etl', instanceType: 'r6i.large', zone: 'ap-south-1a', privateIp: '10.30.1.20', state: 'running',
    status: 'IN_ENVIRONMENT', inEnvironmentName: 'Analytics QA', note: 'Already in Analytics QA' },
  { ref: 'ref-untagged', name: null, instanceType: 't3.small', zone: 'ap-south-1c', privateIp: '10.20.9.31', state: 'stopped',
    status: 'AVAILABLE', note: null },
  { ref: 'ref-old', name: 'old-box', instanceType: 't2.micro', zone: 'ap-south-1a', privateIp: '10.20.0.9', state: 'terminated',
    status: 'STALE', note: 'In Payments Dev, but AWS reports it terminated' },
];

const json = (route: Route, body: unknown, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

/** Opens the Add VMs page with the environment, its groups and the candidates faked; returns the batches sent. */
async function openAddVms(page: Page, hash = `#/vm-registry/${ENV_ID}/add`) {
  const batches: any[] = [];
  const candidateQueries: string[] = [];
  await page.route(new RegExp(`/api/v1/environments/${ENV_ID}$`), route => json(route, { environmentId: ENV_ID, name: 'payments-dev',
    displayName: 'Payments Dev', isActive: true, serviceType: 'EC2', regions: ['ap-south-1'], viewerCanAdminister: true }));
  await page.route(new RegExp(`/api/v1/environments/${ENV_ID}/groups$`), route => json(route, [
    { groupId: 'g-db', name: 'db', displayName: 'Database', sequencePosition: 1 },
    { groupId: 'g-app', name: 'app', displayName: 'Application', sequencePosition: 2 },
    { groupId: 'g-web', name: 'web', displayName: 'Web', sequencePosition: 3 }]));
  await page.route(/\/api\/v1\/rds$/, route => json(route, { enabled: false }));
  await page.route(new RegExp(`/api/v1/environments/${ENV_ID}/vms/candidates\\?`), route => {
    candidateQueries.push(new URL(route.request().url()).search);
    return json(route, CANDIDATES);
  });
  await page.route(new RegExp(`/api/v1/environments/${ENV_ID}/vms/batch$`), route => {
    batches.push(route.request().postDataJSON());
    return json(route, [], 201);
  });
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(h => { location.hash = h; }, hash);
  await expect(page.locator('#content-area h1')).toHaveText('Add VMs to Payments Dev', { timeout: 15_000 });
  await expect(page.locator('#rv-table tbody tr')).toHaveCount(5);
  return { batches, candidateQueries };
}

const row = (page: Page, name: string) => page.locator('#rv-table tbody tr', { hasText: name });

test.describe('Adding VMs from AWS', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('instances are listed by name, never by id; taken ones cannot be picked', async ({ page }) => {
    const { candidateQueries } = await openAddVms(page);

    expect(candidateQueries[0]).toContain('region=ap-south-1');
    expect(candidateQueries[0]).toContain('source=EC2');
    await expect(page.locator('#rv-table')).not.toContainText(/\bi-[0-9a-f]{8,17}\b/);
    await expect(row(page, 'analytics-etl').locator('.rv-pick')).toBeDisabled();
    await expect(row(page, 'analytics-etl').locator('.rv-note')).toHaveText('Already in Analytics QA');
    await expect(row(page, 'pay-worker-01').locator('.rv-note')).toHaveText('Found by discovery, not in a group');
    await expect(row(page, 'pay-worker-01').locator('.rv-pick')).toBeEnabled();
    await expect(row(page, '(no name tag)')).toHaveCount(1);
    await expect(row(page, 'old-box')).toHaveCount(0);

    await page.locator('#rv-show-ended').check();
    await expect(row(page, 'old-box').locator('.rv-pick')).toBeDisabled();

    await page.locator('#rv-search').fill('10.20.3');
    await expect(page.locator('#rv-table tbody tr')).toHaveCount(2);
  });

  test('two picked instances go to a group in one batch', async ({ page }) => {
    const { batches } = await openAddVms(page);

    await expect(page.locator('#rv-group option')).toHaveText(['Web · starts 3rd', 'Application · starts 2nd', 'Database · starts 1st', 'New group…']);
    await expect(page.locator('#rv-add')).toHaveText('Pick VMs to add');
    await expect(page.locator('#rv-add')).toBeDisabled();
    await row(page, 'web-03').locator('.rv-pick').check();
    await expect(page.locator('#rv-add')).toHaveText('Add 1 VM');
    await row(page, 'web-04').locator('.rv-pick').check();
    await expect(page.locator('#rv-add')).toHaveText('Add 2 VMs');
    await expect(page.locator('#rv-hint')).toHaveText("2 selected · they start after the group's current VMs");

    await page.locator('#rv-add').click();
    await expect.poll(() => batches.length).toBe(1);
    expect(batches[0]).toEqual({ groupId: 'g-web', items: [{ ref: 'ref-web-03', name: 'web-03' }, { ref: 'ref-web-04', name: 'web-04' }] });
    await expect(page.getByText('Added 2 VMs to Web.')).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`#/vm-registry\\?env=${ENV_ID}$`));
  });

  test('a new group needs a name; Add VM on a group opens the page with it chosen', async ({ page }) => {
    const { batches } = await openAddVms(page, `#/vm-registry/${ENV_ID}/add?group=g-app`);
    await expect(page.locator('#rv-group')).toHaveValue('g-app');

    await row(page, '(no name tag)').locator('.rv-pick').check();
    await page.locator('#rv-group').selectOption({ label: 'New group…' });
    await expect(page.locator('#rv-new-group')).toBeFocused();
    await expect(page.locator('#rv-add')).toBeDisabled();
    await page.locator('#rv-new-group').fill('Reports');
    await page.locator('#rv-add').click();

    await expect.poll(() => batches.length).toBe(1);
    expect(batches[0]).toEqual({ newGroupName: 'Reports', items: [{ ref: 'ref-untagged', name: 'vm-10-20-9-31' }] });
  });

  test('a refused batch says why and stays on the page', async ({ page }) => {
    await openAddVms(page);
    await page.route(new RegExp(`/api/v1/environments/${ENV_ID}/vms/batch$`), route =>
      json(route, { message: 'web-03 is already in CRM UAT. Nothing was added.' }, 400));
    await row(page, 'web-03').locator('.rv-pick').check();
    await page.locator('#rv-add').click();

    await expect(page.locator('#rv-error')).toHaveText('web-03 is already in CRM UAT. Nothing was added.');
    await expect(page.locator('#rv-add')).toBeEnabled();
    await expect(page).toHaveURL(/\/add$/);
  });

  test('a region AWS cannot list says what to check', async ({ page }) => {
    await openAddVms(page);
    await page.route(new RegExp(`/api/v1/environments/${ENV_ID}/vms/candidates\\?`), route =>
      json(route, { message: 'Unable to load credentials' }, 500));
    await page.locator('#rv-region').selectOption('us-east-1');

    await expect(page.locator('#rv-table-wrap')).toContainText(
      "Couldn't list instances in us-east-1. Check the AWS account in Settings > AWS accounts, then try again.");
  });
});

test.describe('Found in AWS (U19-T03)', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('what no environment holds, by account and region, filtered, ignored, never by instance id', async ({ page }) => {
    const ignored: any[] = [];
    await page.route(/\/api\/v1\/discovery\/found/, route => json(route, {
      items: [
        { kind: 'EC2', ref: 'ref-eu', name: 'web-01', accountRef: 'd', accountName: 'Default account', region: 'eu-west-1',
          zone: 'eu-west-1a', size: 't3.medium', state: 'running' },
        { kind: 'EC2', ref: 'ref-me', name: null, accountRef: 'b', accountName: 'Payments', region: 'me-central-1',
          zone: 'me-central-1b', size: 'm5.large', state: 'stopped', taggedEnvironment: 'CRM UAT', taggedEnvironmentId: 'env-crm' },
        { kind: 'EKS', ref: null, name: 'data-platform', accountRef: 'd', accountName: 'Default account', region: 'eu-west-1' },
      ],
      scannedAt: new Date().toISOString(), unreachable: ['us-west-1'], ignored: 0 }));
    await page.route(/\/api\/v1\/discovery\/ignored$/, route => {
      if (route.request().method() === 'POST') {
        ignored.push(route.request().postDataJSON());
        return route.fulfill({ status: 204 });
      }
      return json(route, []);
    });
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/vm-registry'; });

    await page.getByRole('button', { name: 'Found in AWS' }).click();
    await expect(page.locator('#fia .fia-summary')).toHaveText('2 instances and 1 cluster need a decision.', { timeout: 15_000 });
    await expect(page.locator('#fia .fia-unreachable')).toContainText("Couldn't read us-west-1");
    await expect(page.locator('#fia tbody tr')).toHaveCount(3);
    await expect(page.locator('#fia')).not.toContainText('i-0');
    await expect(page.locator('#fia tbody tr').nth(1)).toContainText('(no name tag)');
    await expect(page.locator('#fia tbody tr').nth(1)).toContainText('Tagged for CRM UAT');

    await page.locator('#fia-region').selectOption('eu-west-1');
    await expect(page.locator('#fia tbody tr')).toHaveCount(2);
    await page.locator('#fia-kind').selectOption('EKS');
    await expect(page.locator('#fia tbody tr')).toHaveCount(1);
    await page.locator('#fia [data-fia="ignore"]').click();
    await expect.poll(() => ignored).toEqual([{ kind: 'EKS', accountRef: 'd', region: 'eu-west-1', clusterName: 'data-platform' }]);
    await expect(page.locator('#fia [data-fia="ignored"]')).toHaveText('Ignored (1)');
    await expect(page.locator('#fia .ui-state-filtered, #fia .ui-state-empty')).toBeVisible();
  });
});
