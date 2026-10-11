import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E28-T10: the AWS account in environment and VM forms. The account options are faked: with two
 * accounts the forms offer a choice and send it, with one account nothing changes; an account
 * with its own regions narrows the EC2 region list.
 */

declare const Modals: any;
declare const AwsAccountOptions: any;
declare const VmRegistry: any;
declare const ContentRouter: any;

const TWO = [
  { ref: '00000000-0000-0000-0000-000000000001', name: 'Default account', regions: [], isDefault: true },
  { ref: 'acct-b', name: 'Payments', regions: ['eu-west-1', 'us-east-1'], isDefault: false },
];

async function ready(page: Page, options: any[]) {
  await page.route(/\/api\/v1\/aws-accounts\/options$/, r => r.fulfill({ json: options }));
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof Modals !== 'undefined' && typeof ContentRouter !== 'undefined');
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => AwsAccountOptions.invalidate());
}

test.describe('AWS account in forms', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('with two accounts the new environment form offers the account and sends it', async ({ page }) => {
    let sent: any = null;
    await page.route(/\/api\/v1\/environments$/, r => {
      if (r.request().method() !== 'POST') return r.continue();
      sent = r.request().postDataJSON();
      return r.fulfill({ status: 201, json: { environmentId: 'e-new', name: sent.name, displayName: sent.displayName } });
    });
    await ready(page, TWO);
    await page.evaluate(() => Modals.showCreateEnvironment());

    await expect(page.locator('#cem-account-row')).toBeVisible();
    await expect(page.locator('#cem-account option')).toHaveText(['Default account', 'Payments']);
    await page.locator('#cem-account').selectOption('acct-b');
    await page.locator('#cem-name').fill('pay-dev');
    await page.locator('#cem-submitBtn').click();
    await expect.poll(() => sent).not.toBeNull();
    expect(sent.awsAccountRef).toBe('acct-b');
  });

  test('with one account there is no account choice and none is sent', async ({ page }) => {
    let sent: any = null;
    await page.route(/\/api\/v1\/environments$/, r => {
      if (r.request().method() !== 'POST') return r.continue();
      sent = r.request().postDataJSON();
      return r.fulfill({ status: 201, json: { environmentId: 'e-new', name: sent.name, displayName: sent.displayName } });
    });
    await ready(page, TWO.slice(0, 1));
    await page.evaluate(() => Modals.showCreateEnvironment());
    await page.locator('#cem-name').fill('solo-dev');
    await expect(page.locator('#cem-account-row')).toBeHidden();
    await page.locator('#cem-submitBtn').click();
    await expect.poll(() => sent).not.toBeNull();
    expect(sent.awsAccountRef).toBeUndefined();
  });

  test('the edit form preselects the environment account', async ({ page }) => {
    await ready(page, TWO);
    await page.evaluate(() => Modals.showEditEnvironment({ environmentId: 'e-1', name: 'pay', displayName: 'Pay',
      serviceType: 'EC2', metadata: '{}', awsAccountRef: 'acct-b' }));
    await expect(page.locator('#editEnvAccountRow')).toBeVisible();
    await expect(page.locator('#editEnvAccount')).toHaveValue('acct-b');
  });

  test('an account with its own regions narrows the EC2 region list', async ({ page }) => {
    await ready(page, TWO);
    const regions = await page.evaluate(async () => {
      await AwsAccountOptions.load();
      VmRegistry.fillEc2Regions('acct-b');
      const narrowed = [...document.querySelectorAll('#ec2Region option')].map(o => (o as HTMLOptionElement).value);
      VmRegistry.fillEc2Regions(null);
      return { narrowed, all: document.querySelectorAll('#ec2Region option').length };
    });
    expect(regions.narrowed).toEqual(['eu-west-1', 'us-east-1']);
    expect(regions.all).toBeGreaterThan(2);
  });
});
