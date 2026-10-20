import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { fakeEnvironmentPage, groupStates, pageBody } from '../../fixtures/mock-api';

/**
 * U06-T06: the Environments list at 1440x900 (Environments15.dc.html). Fifteen environments, one
 * of them with 54 VMs, fit without a page scrollbar in either direction; the big one shows a bar
 * instead of 54 lights; no pager. A sixteenth brings the pager "1–15 of 16".
 */

declare const ContentRouter: any;

function rows(count: number) {
  return Array.from({ length: count }, (_, i) => {
    const big = i === 0;
    const groups = big
      ? [groupStates('Database', 1, { running: 8 }), groupStates('Application', 2, { running: 14, stopped: 4 }),
         groupStates('Workers', 3, { running: 12, stopped: 4 }), groupStates('Web', 4, { running: 11, problem: 1 })]
      : [groupStates('App', 1, { running: i % 3, stopped: 2 }), groupStates('Web', 2, { stopped: 1 })];
    const vmCount = groups.reduce((n, g) => n + g.total, 0);
    return {
      environmentId: `env-fit-${i}`, name: big ? 'payments-mirror' : `env-fit-${String(i).padStart(2, '0')}`,
      displayName: big ? 'Payments Prod Mirror' : `Fit Environment ${String(i).padStart(2, '0')}`,
      serviceType: i % 5 === 0 ? 'EKS' : 'EC2', metadata: '{"defaultCloudProvider":"AWS"}', regions: ['ap-south-1'],
      vmCount, runningVmCount: groups.reduce((n, g) => n + g.running, 0), locked: i === 3,
      lockedByUserId: 'someone', lockedByDisplayName: 'Rahul Verma', myAccess: i % 4 === 0 ? 'VIEWER' : 'USER',
      monthToDateCost: big ? 2410 : 40 + i, groupStates: groups,
    };
  });
}

async function openList(page: Page, total: number) {
  const all = rows(total);
  await page.setViewportSize({ width: 1440, height: 900 });
  await fakeEnvironmentPage(page, url => {
    const n = Number(url.searchParams.get('page') || 0);
    return { body: pageBody(all.slice(n * 15, n * 15 + 15), total, n),
      vms: all.reduce((s, r) => s + r.vmCount, 0), running: all.reduce((s, r) => s + r.runningVmCount, 0) };
  });
  await signIn(page, 'user');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/my-environments'; });
  await expect(page.locator('#env-list-body tr')).toHaveCount(Math.min(15, total), { timeout: 15_000 });
}

test.describe('Environments list fit', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  });

  test('15 environments with a 54-VM one: no page scrollbars, a bar for the big one, no pager', async ({ page }) => {
    await openList(page, 15);

    const sizes = await page.evaluate(() => {
      const doc = document.scrollingElement!;
      const main = document.getElementById('mainContent')!;
      return [[doc.scrollHeight, doc.clientHeight, doc.scrollWidth, doc.clientWidth],
        [main.scrollHeight, main.clientHeight, main.scrollWidth, main.clientWidth]];
    });
    for (const [h, ch, w, cw] of sizes) {
      expect(h).toBeLessThanOrEqual(ch);
      expect(w).toBeLessThanOrEqual(cw);
    }

    const big = page.locator('#env-list-body tr', { hasText: 'Payments Prod Mirror' }).locator('td.col-running');
    await expect(big.getByRole('img', { name: '45 of 54 VMs running' })).toBeVisible();
    await expect(big.locator('.pip')).toHaveCount(0);       // a bar, not 54 lights
    await expect(page.locator('#env-list-body tr').nth(1).locator('.pip')).toHaveCount(4); // 1 running + 2 stopped | 1 stopped
    await expect(page.locator('#env-list-pagination')).toHaveText('15 environments');
    await expect(page.locator('#env-list-pagination button')).toHaveCount(0);
  });

  test('16 environments: the pager shows 1–15 of 16', async ({ page }) => {
    await openList(page, 16);
    await expect(page.locator('#env-list-pagination .pager-range')).toHaveText('1–15 of 16');
    await expect(page.locator('#env-list-pagination').getByRole('button', { name: 'Next' })).toBeEnabled();
  });
});
