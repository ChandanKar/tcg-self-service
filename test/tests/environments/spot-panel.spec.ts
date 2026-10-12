import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E31-T08: the Spot panel on the environment page. The summary and actions are intercepted (no
 * AWS call, Spot need not be on in the smoke app): an admin allows Spot after the acknowledgement
 * and sees convert/exclude actions; a viewer sees the state without actions; with Spot off
 * (404) there is no panel.
 */

declare const ContentRouter: any;

function summary(overrides: any = {}) {
  return {
    environmentId: 'x', policy: 'NONE', production: false, canAdminister: true,
    conversionEnabled: true, spotVmCount: 1, standbyCount: 1, fallbackActive: true, interruptions30d: 2, spotSavingsMonth: 120.5,
    vms: [
      { vmId: 'ng-twin', name: 'workers-spot', kind: 'EKS_NODE_GROUP', purchaseOption: 'SPOT', excluded: false, standby: false,
        partnerName: 'workers', fallbackActive: true, status: 'RUNNING', convertible: false, revertible: true },
      { vmId: 'ng-src', name: 'workers', kind: 'EKS_NODE_GROUP', purchaseOption: 'ON_DEMAND', excluded: false, standby: true,
        partnerName: 'workers-spot', fallbackActive: false, status: 'STOPPED', convertible: false, revertible: true },
      { vmId: 'ng-api', name: '<i>api</i>', kind: 'EKS_NODE_GROUP', purchaseOption: 'ON_DEMAND', excluded: false, standby: false,
        partnerName: null, fallbackActive: false, status: 'RUNNING', convertible: true, revertible: false },
    ],
    ...overrides,
  };
}

async function openEnv(page: Page, envId: string, body: any, status = 200) {
  const puts: any[] = [];
  await page.route(/\/api\/v1\/environments\/[^/]+\/spot-summary$/, route =>
    route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) }));
  await page.route(/\/api\/v1\/environments\/[^/]+\/spot-policy$/, route => {
    puts.push(route.request().postDataJSON());
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ spotPolicy: 'ALLOWED' }) });
  });
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(id => { location.hash = `#/environments/${id}`; }, envId);
  return puts;
}

test.describe('Spot panel', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('an admin sees the Spot state and allows Spot after the acknowledgement', async ({ page, playwright }) => {
    const envId = await seedEnvironment(playwright, 'Spot env');
    const puts = await openEnv(page, envId, summary());

    const panel = page.locator('.spot-card');
    await expect(panel).toBeVisible({ timeout: 15_000 });
    await expect(panel.locator('.spot-policy')).toHaveText('Not allowed');
    await expect(panel.locator('.spot-stats')).toContainText('2 interruptions in 30 days');
    await expect(panel.locator('.spot-stats')).toContainText('on-demand fallback running');
    await expect(panel.locator('[data-spot-vm="ng-twin"] .spot-fallback-badge')).toHaveText('On-demand fallback');
    await expect(panel.locator('[data-spot-vm="ng-src"]')).toContainText('Standby');
    await expect(panel.locator('[data-spot-vm="ng-api"]')).toContainText('<i>api</i>');
    await expect(panel.locator('[data-spot-vm="ng-api"] i')).toHaveCount(0);
    await expect(panel.locator('[data-spot-vm="ng-api"] [data-action="spot-convert"]')).toBeVisible();
    await expect(panel.locator('[data-spot-vm="ng-twin"] [data-action="spot-revert"]')).toBeVisible();

    await panel.locator('[data-action="spot-policy"]').click();
    await page.locator('.modal.show .btn-warning').click();
    await expect.poll(() => puts.length).toBe(1);
    expect(puts[0]).toEqual({ policy: 'ALLOWED', acknowledgeInterruptible: true });
  });

  test('a viewer sees the state without any action', async ({ page, playwright }) => {
    const envId = await seedEnvironment(playwright, 'Spot view');
    await openEnv(page, envId, summary({ canAdminister: false,
      vms: summary().vms.map((v: any) => ({ ...v, convertible: false, revertible: false })) }));

    const panel = page.locator('.spot-card');
    await expect(panel).toBeVisible({ timeout: 15_000 });
    await expect(panel.locator('button')).toHaveCount(0);
  });

  test('with Spot off there is no panel', async ({ page, playwright }) => {
    const envId = await seedEnvironment(playwright, 'Spot off');
    await openEnv(page, envId, { message: 'Spot is not enabled' }, 404);

    await expect(page.locator('#env-detail-view')).toBeVisible({ timeout: 15_000 });
    await page.waitForTimeout(500);
    await expect(page.locator('.spot-card')).toHaveCount(0);
  });
});
