import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E33-T04: a stopped RDS database on the environment page says when AWS will start it again and
 * whether the platform will stop it again. The environment is real; its VM list and the RDS status
 * are faked in the browser.
 */

declare const ContentRouter: any;

const AUTO_START = new Date(Date.now() + 3 * 86400_000).toISOString();

function rdsVm(status: string, awsAutoStartAt: string | null) {
  return {
    vmId: 'vm-rds-e2e', groupId: 'g-rds', groupName: 'data', name: 'orders-db', displayName: 'Orders DB',
    provider: 'AWS_RDS', region: 'ap-south-1', providerVmId: 'db:orders', vmType: 'DEV', sequencePosition: 1,
    dependsOnVmIds: [], status, isActive: true, awsAutoStartAt,
  };
}

async function open(page: Page, envId: string, vm: any, keepStopped: boolean) {
  await page.route(new RegExp(`/api/v1/environments/${envId}/vms(\\?.*)?$`), route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify([{ group: { groupId: 'g-rds', name: 'data', displayName: 'Data', sequencePosition: 1,
      dependsOnGroupIds: [], vmCount: 1, runningVmCount: 0 }, vms: [vm] }]),
  }));
  await page.route(/\/api\/v1\/rds$/, route => route.fulfill({ status: 200, contentType: 'application/json',
    body: JSON.stringify({ enabled: true, keepStopped }) }));
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(id => { location.hash = '#/environments/' + id; }, envId);
}

test.describe('RDS automatic start note', () => {
  let envId = '';

  test.beforeAll(async ({ playwright }) => {
    envId = await seedEnvironment(playwright, 'Rds note');
  });

  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin') || !envId, 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('with keep-stopped on the note says the platform stops it again', async ({ page }) => {
    await open(page, envId, rdsVm('STOPPED', AUTO_START), true);

    const note = page.locator('.rds-auto-start');
    await expect(note).toHaveText(/AWS will restart it on .*; the platform will stop it again/, { timeout: 15_000 });
    await expect(page.locator('.vm-rds-badge')).toHaveText('DB instance');
  });

  test('with keep-stopped off it warns that the database will keep running', async ({ page }) => {
    await open(page, envId, rdsVm('STOPPED', AUTO_START), false);

    await expect(page.locator('.rds-auto-start')).toHaveText(/keep running until stopped/, { timeout: 15_000 });
  });

  test('a running database has no note', async ({ page }) => {
    await open(page, envId, rdsVm('RUNNING', null), true);

    await expect(page.locator('.vm-rds-badge')).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('.rds-auto-start')).toHaveCount(0);
  });
});
