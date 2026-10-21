import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { withFeatures } from '../../fixtures/capabilities';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E31-T08 / U11-T08 (SpotSettings.dc.html): the Spot page, Settings › Spot of an EKS environment.
 * The summary and actions are intercepted (no AWS call, Spot need not be on in the smoke app), and
 * the seeded environment is shown as EKS: the policy with its acknowledgement, the node groups with
 * size, where they run, standby, savings and actions, the interruptions and what Spot saved; a
 * production environment cannot allow Spot; a viewer gets no actions; Spot off and non-EKS say so.
 */

declare const ContentRouter: any;

function summary(overrides: any = {}) {
  return {
    environmentId: 'x', policy: 'NONE', production: false, canAdminister: true,
    conversionEnabled: true, spotVmCount: 1, standbyCount: 1, fallbackActive: true, interruptions30d: 2, spotSavingsMonth: 120.5,
    fallbackGraceMinutes: 10,
    recentInterruptions: [
      { vmName: 'workers-spot', at: new Date(Date.now() - 2 * 3600_000).toISOString(), standbyMinutes: null },
      { vmName: 'workers-spot', at: new Date(Date.now() - 3 * 86_400_000).toISOString(), standbyMinutes: 22 },
    ],
    vms: [
      { vmId: 'ng-twin', name: 'workers-spot', kind: 'EKS_NODE_GROUP', purchaseOption: 'SPOT', excluded: false, standby: false,
        partnerName: 'workers', fallbackActive: true, status: 'RUNNING', convertible: false, revertible: true,
        instanceType: 'c6i.xlarge', nodeCount: 2, monthlySavings: 61, savingsEstimated: false },
      { vmId: 'ng-src', name: 'workers', kind: 'EKS_NODE_GROUP', purchaseOption: 'ON_DEMAND', excluded: false, standby: true,
        partnerName: 'workers-spot', fallbackActive: false, status: 'STOPPED', convertible: false, revertible: true,
        instanceType: 'c6i.xlarge', nodeCount: 2, monthlySavings: null, savingsEstimated: false },
      { vmId: 'ng-api', name: '<i>api</i>', kind: 'EKS_NODE_GROUP', purchaseOption: 'ON_DEMAND', excluded: false, standby: false,
        partnerName: null, fallbackActive: false, status: 'RUNNING', convertible: true, revertible: false,
        instanceType: 'c6i.2xlarge', nodeCount: 3, monthlySavings: 96, savingsEstimated: true },
    ],
    ...overrides,
  };
}

/** Opens Settings › Spot with the summary faked and the environment shown as EKS (or not). */
async function openSpot(page: Page, envId: string, body: any, status = 200, eks = true) {
  const puts: any[] = [];
  const posts: string[] = [];
  await page.route(new RegExp(`/api/v1/environments/${envId}$`), async route => {
    const res = await route.fetch();
    return route.fulfill({ response: res, json: { ...(await res.json()), serviceType: eks ? 'EKS' : 'EC2' } });
  });
  await page.route(/\/api\/v1\/environments\/[^/]+\/spot-summary$/, route =>
    route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) }));
  await page.route(/\/api\/v1\/environments\/[^/]+\/spot-policy$/, route => {
    puts.push(route.request().postDataJSON());
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ spotPolicy: 'ALLOWED' }) });
  });
  await page.route(/\/api\/v1\/environments\/[^/]+\/vms\/[^/]+\/convert-to-spot$/, route => {
    posts.push(route.request().url());
    return route.fulfill({ status: 202, contentType: 'application/json', body: '{}' });
  });
  await withFeatures(page, { spot: true }); // U02-T03: switched on for this page
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(id => { location.hash = `#/environments/${id}/settings/spot`; }, envId);
  await expect(page.locator('#es-panel h2').first()).toHaveText('Spot', { timeout: 15_000 });
  return { puts, posts };
}

const row = (page: Page, id: string) => page.locator(`tr[data-spot-vm="${id}"]`);

test.describe('Spot page', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('node groups with size, where they run, standby, savings; allowing Spot needs the acknowledgement', async ({ page, playwright }) => {
    const envId = await seedEnvironment(playwright, 'Spot env');
    const { puts } = await openSpot(page, envId, summary());

    await expect(page.locator('.spot-table tbody tr')).toHaveCount(2); // the standby shows as its Spot copy's standby
    await expect(row(page, 'ng-twin').locator('.spot-name span')).toHaveText('2 × c6i.xlarge');
    await expect(row(page, 'ng-twin').locator('.spot-chip')).toHaveText('Spot');
    await expect(row(page, 'ng-twin').locator('.spot-standby')).toHaveText('Running: Spot is short');
    await expect(row(page, 'ng-twin').locator('[data-spot-act="revert"]')).toHaveText('Back to on-demand');
    await expect(row(page, 'ng-api').locator('.spot-name')).toContainText('<i>api</i>');
    await expect(row(page, 'ng-api').locator('i')).toHaveCount(0);
    await expect(row(page, 'ng-api').locator('.spot-saves')).toHaveText(/^about \D?96/);
    await expect(row(page, 'ng-api').locator('[data-spot-act]')).toHaveText(['Move to Spot', 'Keep off Spot']);
    await expect(page.locator('#spot-interruptions .spot-int')).toHaveCount(2);
    await expect(page.locator('#spot-interruptions .spot-int').first()).toContainText('standby still running');
    await expect(page.locator('#spot-interruptions .spot-int').last()).toContainText('standby ran 22 min');
    await expect(page.locator('#spot-interruptions .spot-saved')).toContainText('on Spot this month.');
    await expect(page.locator('.spot-aside')).toContainText('short of nodes for 10 minutes');

    await expect(page.locator('#spot-save')).toBeDisabled();
    await page.locator('input[name="spot-policy-choice"][value="ALLOWED"]').check();
    await expect(page.locator('#spot-save')).toBeDisabled(); // not without the acknowledgement
    await page.locator('#spot-ack').check();
    await page.locator('#spot-save').click();
    await expect.poll(() => puts.length).toBe(1);
    expect(puts[0]).toEqual({ policy: 'ALLOWED', acknowledgeInterruptible: true });
  });

  test('Move to Spot asks first, then the row shows it is moving', async ({ page, playwright }) => {
    const envId = await seedEnvironment(playwright, 'Spot move');
    const { posts } = await openSpot(page, envId, summary({ policy: 'ALLOWED' }));

    await row(page, 'ng-api').locator('[data-spot-act="convert"]').click();
    await page.locator('.modal.show .btn-primary', { hasText: 'Move to Spot' }).click();
    await expect.poll(() => posts.length).toBe(1);
    await expect(row(page, 'ng-api').locator('.spot-busy')).toHaveText('Moving to Spot…');
  });

  test('a production environment cannot allow Spot; a viewer gets no actions', async ({ page, playwright }) => {
    const envId = await seedEnvironment(playwright, 'Spot prod');
    await openSpot(page, envId, summary({ production: true, canAdminister: false,
      vms: summary().vms.map((v: any) => ({ ...v, convertible: false, revertible: false })) }));

    const allowed = page.locator('input[name="spot-policy-choice"][value="ALLOWED"]');
    await expect(allowed).toBeDisabled();
    await expect(page.locator('.spot-policy-choice')).toContainText('Not offered for production environments');
    await expect(page.locator('.spot-table button')).toHaveCount(0);
    await expect(page.locator('#spot-save')).toHaveCount(0);
  });

  test('with Spot off the page says so', async ({ page, playwright }) => {
    const envId = await seedEnvironment(playwright, 'Spot off');
    await openSpot(page, envId, { message: 'Spot is not enabled' }, 404);

    await expect(page.locator('#spot-off')).toHaveText('Spot is switched off for the platform. An administrator can switch it on in Settings.');
    await expect(page.locator('.spot-table')).toHaveCount(0);
  });

  test('a non-EKS environment has no Spot section', async ({ page, playwright }) => {
    const envId = await seedEnvironment(playwright, 'Spot ec2');
    await openSpot(page, envId, summary(), 200, false);

    await expect(page.locator('#spot-not-eks')).toHaveText('Spot is for EKS environments only.');
    await expect(page.locator('[data-es-section="spot"]')).toHaveCount(0);
  });
});
