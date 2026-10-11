import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E25-T02: the start dialog and the lease-extension dialog show what they will cost. The preview
 * API is faked (prices come from the server's reference data); a failing preview leaves the dialog
 * working without a cost line.
 */

declare const ContentRouter: any;
declare const Leases: any;

function preview(over: any = {}) {
  return {
    hours: 8, hourlyTotal: 0.19, estimatedTotal: 1.54, currency: 'USD', unknownPriceCount: 0, alreadyRunningCount: 0,
    approximate: false,
    vms: [{ vmId: 'a', name: 'web-1', instanceType: 'm5.large', nodeCount: 1, hourlyRate: 0.096, estimatedCost: 0.77, priceKnown: true, approximate: false },
      { vmId: 'b', name: 'web-2', instanceType: 'm5.large', nodeCount: 1, hourlyRate: 0.096, estimatedCost: 0.77, priceKnown: true, approximate: false }],
    ...over,
  };
}

async function openEnv(page: Page, envId: string) {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(id => { location.hash = `#/environments/${id}`; }, envId);
  await expect(page.locator('#btn-env-action')).toBeVisible({ timeout: 15_000 });
}

test.describe('Cost preview in dialogs', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('the start dialog shows the cost next to the time estimate', async ({ page, playwright }) => {
    const envId = await seedEnvironment(playwright, 'Priced env');
    const asked: string[] = [];
    await page.route(/\/operations\/cost-preview/, route => {
      asked.push(route.request().url());
      return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify(preview({ unknownPriceCount: 1, approximate: true })) });
    });
    await openEnv(page, envId);
    await page.locator('#btn-env-action').click();

    const line = page.locator('#confirmModal #cost-preview-line');
    await expect(line).toHaveText('About $1.54 for 8 h ($0.19/h) · price unknown for 1 VM, approximate');
    await expect(line).toBeVisible();
    expect(asked[0]).not.toContain('extension=true');
    await expect(page.locator('#confirmBtn')).toBeEnabled();
  });

  test('a failing preview leaves the dialog without a cost line', async ({ page, playwright }) => {
    const envId = await seedEnvironment(playwright, 'Unpriced env');
    await page.route(/\/operations\/cost-preview/, route => route.fulfill({ status: 500, body: '' }));
    await openEnv(page, envId);
    await page.locator('#btn-env-action').click();
    await expect(page.locator('#confirmModal')).toBeVisible();
    await expect(page.locator('#estimate-placeholder, #confirmModal .alert')).toHaveCount(1, { timeout: 10_000 });
    await expect(page.locator('#cost-preview-line')).toBeHidden();
    await expect(page.locator('#confirmBtn')).toBeEnabled();
  });

  test('the extension dialog shows the extra cost for the chosen length', async ({ page }) => {
    const asked: string[] = [];
    await page.route(/\/operations\/cost-preview/, route => {
      const url = new URL(route.request().url());
      asked.push(url.search);
      const hours = Number(url.searchParams.get('hours'));
      return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify(preview({ hours, estimatedTotal: 0.19 * hours, hourlyTotal: 0.19 })) });
    });
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof Leases !== 'undefined');
    await page.evaluate(() => {
      const now = Date.now();
      Leases.showExtendDialog('env-x', { startedAt: new Date(now - 3_600_000).toISOString(), endsAt: new Date(now + 600_000).toISOString() },
        { effectiveMaxMinutes: 10_000 }, () => {});
    });
    const cost = page.locator('#lease-extend-cost');
    await page.locator('#lease-extend-minutes').selectOption('120');
    await expect(cost).toHaveText('Extra ~ $0.38 for 2 h');
    expect(asked.at(-1)).toContain('extension=true');
    expect(asked.at(-1)).toContain('hours=2');
  });
});
