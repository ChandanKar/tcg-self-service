import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E28-T09: the AWS Accounts admin page. The account API is faked in the browser: the list with
 * verification states, add with client-side checks, verify, the Organizations import, escaping,
 * and the page refused to non-admins.
 */

declare const ContentRouter: any;

const DEFAULT = { awsAccountRef: '00000000-0000-0000-0000-000000000001', accountNumber: '123456789012', displayName: 'Default account',
  roleArn: null, externalIdMasked: null, regions: [], isDefault: true, isBillingAccount: true, isActive: true, source: 'DEFAULT',
  environmentCount: 4, lastVerifiedAt: new Date(Date.now() - 3600_000).toISOString(), lastError: null };
const MEMBER = { awsAccountRef: 'm-1', accountNumber: '210987654321', displayName: '<script>x</script>',
  roleArn: 'arn:aws:iam::210987654321:role/VmControlAccess', externalIdMasked: '****9876', regions: ['eu-west-1'],
  isDefault: false, isBillingAccount: false, isActive: true, source: 'MANUAL', environmentCount: 0,
  lastVerifiedAt: new Date().toISOString(), lastError: 'AccessDenied: not authorized to perform sts:AssumeRole' };

async function open(page: Page) {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/aws-accounts'; });
}

test.describe('AWS accounts page', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('lists accounts with their verification and escapes names', async ({ page }) => {
    await page.route(/\/api\/v1\/aws-accounts$/, r => r.fulfill({ json: [DEFAULT, MEMBER] }));
    await page.route(/\/aws-accounts\/organization-candidates$/, r => r.fulfill({ status: 400, json: { message: 'disabled' } }));
    await open(page);

    const rows = page.locator('#aws-account-table tbody tr');
    await expect(rows).toHaveCount(2, { timeout: 15_000 });
    await expect(rows.first()).toContainText('Default');
    await expect(rows.first()).toContainText('Verified');
    await expect(rows.nth(1)).toContainText('<script>x</script>');
    await expect(rows.nth(1).locator('.badge.text-bg-danger')).toHaveAttribute('title', /AssumeRole/);
    await expect(rows.first().locator('[data-action="aws-account-deactivate"]')).toHaveCount(0);
    await expect(page.locator('#aws-import-btn')).toBeHidden();
  });

  test('adding checks the ARN and regions before sending, then verify reports the result', async ({ page }) => {
    let created: any = null;
    await page.route(/\/api\/v1\/aws-accounts$/, r => {
      if (r.request().method() === 'POST') {
        created = r.request().postDataJSON();
        return r.fulfill({ status: 201, json: { ...MEMBER, displayName: created.displayName, awsAccountRef: 'm-2', lastError: null, lastVerifiedAt: null } });
      }
      return r.fulfill({ json: created ? [DEFAULT, { ...MEMBER, displayName: 'Payments', awsAccountRef: 'm-2', lastError: null, lastVerifiedAt: null }] : [DEFAULT] });
    });
    await page.route(/\/aws-accounts\/m-2\/verify$/, r => r.fulfill({ json: { ...MEMBER, displayName: 'Payments', lastError: null } }));
    await page.route(/\/aws-accounts\/organization-candidates$/, r => r.fulfill({ status: 400, json: { message: 'disabled' } }));
    await open(page);

    await page.locator('[data-action="aws-account-new"]').click();
    await page.locator('#acct-name').fill('Payments');
    await page.locator('#acct-arn').fill('arn:aws:iam::123:user/bob');
    await page.locator('#acct-regions').fill('eu-west-1, Mars');
    await page.locator('#acct-save').click();
    await expect(page.locator('#acct-error')).toContainText('role ARN');
    await expect(page.locator('#acct-error')).toContainText('"Mars"');
    expect(created).toBeNull();

    await page.locator('#acct-arn').fill('arn:aws:iam::210987654321:role/VmControlAccess');
    await page.locator('#acct-regions').fill('eu-west-1');
    await page.locator('#acct-ext').fill('ext-secret-9876');
    await page.locator('#acct-save').click();
    await expect(page.locator('#awsAccountModal')).toBeHidden();
    expect(created).toMatchObject({ displayName: 'Payments', regions: ['eu-west-1'], externalId: 'ext-secret-9876', isBillingAccount: false });

    await page.locator('#aws-account-table tbody tr[data-ref="m-2"] [data-action="aws-account-verify"]').click();
    await expect(page.locator('#notification-container').filter({ hasText: 'Payments verified' })).toBeVisible();
  });

  test('imports chosen organization accounts and reports the skipped ones', async ({ page }) => {
    let imported: any = null;
    await page.route(/\/api\/v1\/aws-accounts$/, r => r.fulfill({ json: [DEFAULT] }));
    await page.route(/\/aws-accounts\/organization-candidates$/, r => r.fulfill({ json: [
      { accountNumber: '111111111111', name: 'Shared', email: 's@x', proposedRoleArn: 'arn:aws:iam::111111111111:role/VmControlAccess', alreadyRegistered: false },
      { accountNumber: '210987654321', name: 'Payments', email: 'p@x', proposedRoleArn: 'arn:aws:iam::210987654321:role/VmControlAccess', alreadyRegistered: true }] }));
    await page.route(/\/aws-accounts\/import$/, r => {
      imported = r.request().postDataJSON();
      return r.fulfill({ json: { created: [{ ...MEMBER, awsAccountRef: 'm-3' }], skipped: [] } });
    });
    await open(page);

    await page.locator('#aws-import-btn').click();
    await expect(page.locator('#awsImportModal #imp-1')).toBeDisabled();
    await page.locator('#awsImportModal #imp-0').check();
    await page.locator('#awsImportModal .imp-arn[data-index="0"]').fill('arn:aws:iam::111111111111:role/Custom');
    await page.locator('#imp-save').click();
    await expect(page.locator('#imp-result')).toContainText('1 imported');
    expect(imported.accounts).toEqual([{ accountNumber: '111111111111', displayName: 'Shared',
      roleArn: 'arn:aws:iam::111111111111:role/Custom', externalId: null, regions: [] }]);
  });
});

test('non-admins have no AWS Accounts link and are refused', async ({ page }) => {
  test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  await signIn(page, 'user');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await expect(page.locator('#aws-accounts-link')).toBeHidden();
  await page.evaluate(() => { location.hash = '#/aws-accounts'; });
  await expect(page.locator('#content-area')).toContainText('Access denied');
});
