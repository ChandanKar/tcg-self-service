import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E13-T05/T06: at 390px nothing scrolls sideways, row actions stay on screen, and the user
 * menu fits the viewport.
 */

declare const ContentRouter: any;

const WIDTH = 390;
test.use({ viewport: { width: WIDTH, height: 844 } });

async function go(page: Page, hash: string, ready: string) {
  await page.evaluate(h => { location.hash = h; }, hash);
  await expect(page.locator(ready).first()).toBeVisible({ timeout: 15_000 });
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(400);
}

async function scrollWidth(page: Page) {
  return page.evaluate(() => document.documentElement.scrollWidth);
}

/** Elements wider than the viewport, for a readable failure message. */
async function overflowing(page: Page) {
  return page.evaluate(w => Array.from(document.querySelectorAll('body *'))
    .filter(el => {
      const r = el.getBoundingClientRect();
      return r.width > 0 && r.right > w + 1 && getComputedStyle(el).position !== 'fixed'
        && !el.closest('.slideout-panel, .modal, .sidebar, .user-dropdown, .notification-dropdown');
    })
    .slice(0, 8)
    .map(el => `${el.tagName.toLowerCase()}#${el.id}.${String(el.className).split(' ').join('.')} â†’ ${Math.round(el.getBoundingClientRect().right)}`), WIDTH);
}

async function expectNoSideScroll(page: Page) {
  const offenders = await overflowing(page);
  expect(await scrollWidth(page), offenders.join('\n')).toBeLessThanOrEqual(WIDTH);
}

async function expectInViewport(page: Page, selector: string) {
  const boxes = await page.locator(selector).evaluateAll(els => els
    .filter(el => (el as HTMLElement).offsetParent !== null)
    .map(el => { const r = el.getBoundingClientRect(); return { left: r.left, right: r.right }; }));
  for (const b of boxes) {
    expect(b.left).toBeGreaterThanOrEqual(0);
    expect(b.right).toBeLessThanOrEqual(WIDTH);
  }
  return boxes.length;
}

let seededEnvId = '';
test.beforeAll(async ({ playwright }) => {
  // Never depend on environments left by other runs (E13 a11y specs).
  seededEnvId = await seedEnvironment(playwright, 'Phone E2E', { grantUser: true });
});

test.describe('Phone layout (user)', () => {
  test.beforeEach(async ({ page }) => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.waitForLoadState('networkidle');
  });

  test('Dashboard and Environments fit the screen', async ({ page }) => {
    await go(page, '#/dashboard', '#content-area h1');
    await expectNoSideScroll(page);
    await go(page, '#/my-environments', '#env-list-search');
    await expectNoSideScroll(page);
    await expectInViewport(page, '#content-area [data-action="view"]');
  });

  test('the avatar opens My account, and it fits the screen (U16-T01)', async ({ page }) => {
    await page.locator('.user-profile-trigger').click();
    const panel = page.locator('.my-account').first();
    await expect(panel).toBeVisible({ timeout: 10_000 });
    const box = await panel.boundingBox();
    expect(box).not.toBeNull();
    expect(box!.x).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width).toBeLessThanOrEqual(WIDTH);
    await expectNoSideScroll(page);
  });
});

test.describe('Phone layout (admin)', () => {
  test.beforeEach(async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.waitForLoadState('networkidle');
  });

  test('Environment Detail keeps every Start/Stop button on screen', async ({ page }) => {
    // The test schema has no VMs, so the environment's groups and VMs are faked.
    const vm = (id: string, status: string) => ({
      vmId: id, name: `application-server-with-a-long-name-${id}`, provider: 'AWS', region: 'ap-south-1',
      status, sequencePosition: 1, cloudInstanceId: `i-${id}`,
    });
    await page.route(/\/api\/v1\/environments\/[^/]+\/vms(\?.*)?$/, route => route.fulfill({ json: [
      { group: { groupId: 'g1', name: 'web', displayName: 'Web tier', sequencePosition: 1 },
        vms: [vm('a1', 'RUNNING'), vm('a2', 'STOPPED')] },
      { group: { groupId: 'g2', name: 'db', displayName: 'Database tier', sequencePosition: 2 },
        vms: [vm('b1', 'STOPPED')] },
    ] }));

    await page.evaluate(id => { location.hash = '#/environments/' + id; }, seededEnvId);
    await expect(page.locator('[data-action="start-vm"], [data-action="stop-vm"]').first()).toBeAttached({ timeout: 15_000 });
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(600);
    await expectNoSideScroll(page);
    expect(await expectInViewport(page, '[data-action="start-vm"], [data-action="stop-vm"]')).toBeGreaterThan(0);
  });
});

test.describe('Phone layout (admin tables)', () => {
  test.beforeEach(async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.waitForLoadState('networkidle');
  });

  // E13-T06: every data table is a card list on a phone, with its row actions on screen.
  for (const [hash, ready] of [
    ['#/access-management', '#content-area table'],
    ['#/request-access', '#ra-env-list'],
    ['#/pending-requests', '#content-area h1'],
    ['#/user-management', '#content-area table'],
    ['#/activity-logs', '#al-action-filter'],
    ['#/audit-logs-all', '#content-area table'],
    ['#/vm-registry', '#content-area table'],
  ] as const) {
    test(`${hash} fits the screen`, async ({ page }) => {
      await go(page, hash, ready);
      await expectNoSideScroll(page);
      await expectInViewport(page, '#content-area table tbody button, #content-area table tbody a.btn');
    });
  }
});


/** U16-T02 (PhoneHome.dc.html): Home on a phone, from a faked summary. */
test.describe('Phone Home', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  });

  test('the summary line, the gold lease row and one card per environment with a large strip and one line', async ({ page }) => {
    const soon = new Date(Date.now() + 2 * 3600000);
    const hhmm = `${String(soon.getHours()).padStart(2, '0')}:${String(soon.getMinutes()).padStart(2, '0')}`;
    const card = (id: string, displayName: string, over: Record<string, unknown>) => ({ environmentId: id, name: id, displayName,
      totalVms: 0, runningVms: 0, transitionalVms: 0, startingVms: 0, failedVms: 0, groupStates: [], myAccess: 'USER', ...over });
    await page.route(/\/api\/v1\/dashboard\/summary$/, async route => {
      const response = await route.fetch();
      const body = await response.json();
      body.environments = [
        card('e1', 'Payments Dev', { totalVms: 7, runningVms: 5, leaseEndsAt: soon.toISOString(),
          groupStates: [{ name: 'Database', running: 3, total: 3 }, { name: 'Application', running: 2, total: 2 }, { name: 'Web', running: 0, total: 2 }] }),
        card('e2', 'Analytics QA', { totalVms: 3, runningVms: 0, groupStates: [{ name: 'App', running: 0, total: 3 }] }),
        card('e3', 'CRM UAT', { totalVms: 2, runningVms: 2, groupStates: [{ name: 'App', running: 2, total: 2 }] }),
      ];
      body.needsYou = [{ kind: 'LEASE_ENDING', environmentId: 'e1', environmentName: 'Payments Dev', at: soon.toISOString() }];
      body.summary = { ...(body.summary || {}), monthToDateCost: 412.8 };
      await route.fulfill({ response, json: body });
    });
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await go(page, '#/dashboard', '.phone-home');

    // Acceptance: the summary line and three cards, each with a large strip and one status line.
    await expect(page.locator('.phone-home .page-summary')).toHaveText('7/12 VMs running · $412.80 this month');
    const cards = page.locator('.phone-home-card');
    await expect(cards).toHaveCount(3);
    for (let i = 0; i < 3; i++) {
      await expect(cards.nth(i).locator('.power-strip')).toHaveCount(1);
      await expect(cards.nth(i).locator('.phone-home-line')).toHaveCount(1);
    }
    await expect(cards.filter({ hasText: 'Payments Dev' })).toHaveAttribute('href', '#/environments/e1');
    // Acceptance: a lease ending within 4 hours is a gold row with Extend above the cards.
    const alert = page.locator('.phone-home-alert');
    await expect(alert).toContainText(`Payments Dev stops at ${hhmm}`);
    await expect(alert.locator('b')).toHaveText('Extend');
    await expect(alert).toHaveAttribute('href', '#/environments/e1?extendLease=1');
    await expect(page.locator('.home-lower')).toHaveCount(0);
    await expectNoSideScroll(page);

    // Wider than a phone, Home is the desktop layout again.
    await page.setViewportSize({ width: 1280, height: 800 });
    await expect(page.locator('.home-grid')).toBeVisible();
    await expect(page.locator('.phone-home')).toHaveCount(0);
  });
});
