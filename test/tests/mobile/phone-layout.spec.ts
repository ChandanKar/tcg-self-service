import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { seedEnvironment } from '../../fixtures/seed';

/**
 * E13-T05/T06: at 390px nothing scrolls sideways, row actions stay on screen, and the user
 * menu fits the viewport.
 */

declare const ContentRouter: any;
declare const AssistantPanel: any;

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

/** The side panel slides in (0.3 s); measure it once it has stopped. */
async function settled(page: Page) {
  await expect.poll(async () => Math.round((await page.locator('#dynamicSlideoutPanel').boundingBox())?.x ?? -1), { timeout: 5_000 }).toBe(0);
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
    await settled(page);
    const box = await panel.boundingBox();
    expect(box).not.toBeNull();
    expect(box!.x).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width).toBeLessThanOrEqual(WIDTH);
    await expectNoSideScroll(page);
  });

  // U16-T04: the everyday screens on a phone.
  for (const [hash, ready] of [
    ['#/request-access', '#ra-env-list'],
    ['#/approvals', '#content-area h1'],
    ['#/activity-logs', '#al-results'],
    ['#/help', '#content-area h1'],
    ['#/templates', '#content-area h1'],
    ['#/chat-action/not-a-real-token', '.chat-action-page'],
  ]) {
    test(`${hash} fits the screen (U16-T04)`, async ({ page }) => {
      await go(page, hash, ready);
      await expectNoSideScroll(page);
    });
  }

  test('My account covers the screen and Close returns focus to the avatar (U16-T04)', async ({ page }) => {
    const avatar = page.locator('.user-profile-trigger');
    await avatar.click();
    const panel = page.locator('#dynamicSlideoutPanel');
    await expect(page.locator('.my-account').first()).toBeVisible({ timeout: 10_000 });
    await settled(page);
    const box = await panel.boundingBox();
    expect(box).toEqual({ x: 0, y: 0, width: WIDTH, height: 844 });
    await panel.locator('.slideout-panel-header .close-btn').click();
    await expect(avatar).toBeFocused();
  });

  test('Request access: tapping an environment shows the form on its own, Back returns to the list (U16-T04)', async ({ page }) => {
    await go(page, '#/request-access', '#ra-env-list');
    const list = page.locator('.ra-list-card');
    const form = page.locator('#ra-form-card');
    await expect(list).toBeVisible();
    await expect(form).toBeHidden();
    const env = page.locator('#ra-env-list [data-ra-env]').first();
    test.skip(await env.count() === 0, 'No environment to ask for');
    await env.click();
    await expect(form).toBeVisible();
    await expect(list).toBeHidden();
    await expect(page.locator('#ra-form-title')).toBeFocused();
    await page.getByRole('button', { name: 'Back to environments' }).click();
    await expect(list).toBeVisible();
    await expect(form).toBeHidden();
    await expect(env).toBeFocused();
    await expectNoSideScroll(page);
  });

  test('My activity: the filters open as a sheet from one Filters button (U16-T04)', async ({ page }) => {
    await go(page, '#/activity-logs', '#al-results');
    const sheet = page.locator('#al-filter-sheet');
    await expect(page.locator('#al-environment-filter')).toBeHidden();
    const button = page.locator('#al-filters-btn');
    await expect(button).toHaveText('Filters');
    await button.click();
    await expect(button).toHaveAttribute('aria-expanded', 'true');
    await expect(sheet).toBeVisible();
    await page.locator('#al-action-filter').selectOption({ index: 1 });
    await page.locator('#al-filters-done').click();
    await expect(sheet).toBeHidden();
    await expect(button).toHaveText('Filters · 1');
    await expect(button).toBeFocused();
  });

  test('the notifications and Ask are full-screen sheets with a Close in the header (U16-T04)', async ({ page }) => {
    await page.locator('#notification-bell-btn').click();
    const list = page.locator('#notification-dropdown');
    await expect(list).toBeVisible();
    expect(await list.boundingBox()).toEqual({ x: 0, y: 0, width: WIDTH, height: 844 });
    await page.getByRole('button', { name: 'Close notifications' }).click();
    await expect(list).toBeHidden();
    await expect(page.locator('#notification-bell-btn')).toBeFocused();

    await page.evaluate(() => AssistantPanel.open());
    const ask = page.locator('#assistant-question');
    await expect(ask).toBeVisible();
    await settled(page);
    expect(await page.locator('#dynamicSlideoutPanel').boundingBox()).toEqual({ x: 0, y: 0, width: WIDTH, height: 844 });
    const input = await ask.boundingBox();
    expect(input!.height).toBeGreaterThanOrEqual(44);
    expect(input!.y + input!.height).toBeLessThanOrEqual(844);
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

  // The test schema has no VMs, so the environment's groups and VMs are faked.
  const vm = (id: string, status: string) => ({
    vmId: id, name: `application-server-with-a-long-name-${id}`, provider: 'AWS', region: 'ap-south-1',
    status, sequencePosition: 1, cloudInstanceId: `i-${id}`,
  });
  async function fakeGroups(page: Page) {
    await page.route(/\/api\/v1\/environments\/[^/]+\/vms(\?.*)?$/, route => route.fulfill({ json: [
      { group: { groupId: 'g1', name: 'web', displayName: 'Web tier', sequencePosition: 1 },
        vms: [vm('a1', 'RUNNING'), vm('a2', 'STOPPED')] },
      { group: { groupId: 'g2', name: 'db', displayName: 'Database tier', sequencePosition: 2 },
        vms: [vm('b1', 'STOPPED')] },
    ] }));
  }

  test('the environment on a phone: state, the two big actions, details and groups that open (U16-T03)', async ({ page }) => {
    await fakeGroups(page);
    await page.evaluate(id => { location.hash = '#/environments/' + id; }, seededEnvId);
    await expect(page.locator('.phone-env-state')).toBeVisible({ timeout: 15_000 });
    await page.waitForLoadState('networkidle');

    await expect(page.locator('.phone-page-head .phone-back')).toHaveAttribute('aria-label', 'Back to environments');
    await expect(page.locator('.phone-page-head #btn-operation-history')).toHaveText('History');
    await expect(page.locator('.phone-env-count')).toHaveText('1 of 3 VMs running');
    await expect(page.locator('.phone-stage-name')).toHaveText(['Web tier', 'Database tier']);
    // Acceptance: an operator with 2 stopped VMs sees both actions without scrolling, and they open the confirm.
    const start = page.getByRole('button', { name: 'Start the 2 stopped' });
    const stop = page.getByRole('button', { name: 'Stop all' });
    for (const b of [start, stop]) {
      const box = await b.boundingBox();
      expect(box).not.toBeNull();
      expect(box!.y + box!.height).toBeLessThanOrEqual(844 - 64);
    }
    await start.click();
    await expect(page.locator('#operationConfirmModal')).toBeVisible();
    await page.locator('#operationConfirmModal [data-bs-dismiss="modal"]').first().click();
    await expect(page.locator('.modal.show')).toHaveCount(0);

    // Acceptance: tapping a group opens it and lists its VMs.
    const web = page.locator('.phone-group-row', { hasText: 'Web tier' });
    await expect(web).toHaveAttribute('aria-expanded', 'false');
    await web.click();
    await expect(web).toHaveAttribute('aria-expanded', 'true');
    const rows = page.locator('#phone-group-g1 .phone-vm');
    await expect(rows).toHaveCount(2);
    await expect(rows.first()).toContainText('application-server-with-a-long-name-a1');
    await expect(rows.first().getByRole('button', { name: /^Stop / })).toBeVisible();
    await expect(page.locator('#phone-group-g2')).toBeHidden();
    // U23-T11: each group row has its own start or stop, a 44 px target that opens the confirm.
    const groupStart = page.getByRole('button', { name: 'Start group Web tier' });
    expect((await groupStart.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    await groupStart.click();
    await expect(page.locator('#operationConfirmModal')).toBeVisible();
    await page.locator('#operationConfirmModal [data-bs-dismiss="modal"]').first().click();
    await expect(page.locator('.modal.show')).toHaveCount(0);
    await expectNoSideScroll(page);
    expect(await expectInViewport(page, '[data-action="start-vm"], [data-action="stop-vm"]')).toBeGreaterThan(0);
  });

  test('U22-T11: on a phone the cost reads, the schedule shows, and Insights taps with the groups open', async ({ page }) => {
    await fakeGroups(page);
    const days = Array.from({ length: 14 }, (_, i) => ({ date: `2026-10-${String(i + 10).padStart(2, '0')}`, estimated: 1 + (i % 4), actual: null }));
    await page.route(/\/api\/v1\/environments\/[^/]+\/cost$/, route => route.fulfill({ json: {
      environmentId: seededEnvId, scope: 'FULL', monthToDateEstimated: 32.56, monthToDateActual: null, changePercent: null,
      forecastMonthEnd: 65.21, forecastBasis: 'run rate', daily: days, topVms: [],
      scheduleStatus: { ruleCount: 1, nextStop: new Date(Date.now() + 22 * 3600_000).toISOString(), nextStart: null } } }));
    await page.evaluate(id => { location.hash = '#/environments/' + id; }, seededEnvId);
    await expect(page.locator('.phone-env-state')).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('#env-cost-mtd')).toContainText('$32.56', { timeout: 15_000 });

    // The figure and the 14-day bars do not overlap (the bars were drawn over the figure).
    const mtd = (await page.locator('#env-cost-mtd').boundingBox())!;
    const bars = (await page.locator('#env-cost-trend').boundingBox())!;
    expect(bars.y, 'the bars start below the figure').toBeGreaterThanOrEqual(mtd.y + mtd.height - 1);
    // The schedule row shows: when it stops next.
    await expect(page.locator('.phone-env-details #env-fact-schedule')).toBeVisible();
    await expect(page.locator('#env-cost-schedule')).not.toHaveText('');

    // With a group open, Insights at the bottom can still be tapped above the bottom bar.
    await page.locator('.phone-group-row', { hasText: 'Web tier' }).click();
    const insights = page.locator('.phone-env-more #btn-env-insights');
    await insights.scrollIntoViewIfNeeded();
    await insights.click({ timeout: 5_000 });
  });

  test('a viewer on a phone gets no start, stop, lock or extend control (U16-T03)', async ({ page }) => {
    await fakeGroups(page);
    await page.route(/\/api\/v1\/environments\/[^/?]+$/, async route => {
      if (route.request().method() !== 'GET') return route.continue();
      const response = await route.fetch();
      const body = await response.json();
      body.viewerCanOperate = false;
      body.viewerCanAdminister = false;
      await route.fulfill({ response, json: body });
    });
    // The lock fact follows the per-viewer flags on the lock status (E07-T05).
    await page.route(/\/api\/v1\/environments\/[^/]+\/lock$/, route => route.fulfill({ json: {
      isLocked: false, canAcquire: false, canRelease: false, canForceRelease: false } }));
    await page.evaluate(id => { location.hash = '#/environments/' + id; }, seededEnvId);
    await expect(page.locator('.phone-env-state')).toBeVisible({ timeout: 15_000 });
    await page.waitForLoadState('networkidle');

    // Acceptance: a viewer sees no start/stop/lock/extend control.
    await page.locator('.phone-group-row', { hasText: 'Web tier' }).click();
    await expect(page.locator('#phone-group-g1 .phone-vm')).toHaveCount(2);
    await expect(page.locator('#btn-env-action, #btn-env-action-stop')).toHaveCount(0);
    await expect(page.locator('[data-action="start-vm"], [data-action="stop-vm"]')).toHaveCount(0);
    await expect(page.locator('[data-lock-action="acquire"]')).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Extend/ })).toHaveCount(0);
    await expect(page.locator('.phone-viewonly')).toContainText('You can see this environment but not start or stop it.');
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
    ['#/activity-logs', '#al-results'],
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
    // Past midnight the time carries the weekday ("Thu 00:57"), so the run time of day doesn't matter.
    const day = soon.getDate() === new Date().getDate() ? '' : `${soon.toLocaleDateString('en-GB', { weekday: 'short' })} `;
    await expect(alert).toContainText(`Payments Dev stops at ${day}${hhmm}`);
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

/**
 * U16-T05: every page each persona can open, at 390 x 844: nothing scrolls sideways (content may
 * scroll inside its own box), wide tables are labelled cards, and buttons, tabs and fields are at
 * least 44 px tall (a field inside a <label> is tapped through the label).
 */
test.describe('Phone sweep (every route, every persona)', () => {
  test.describe.configure({ timeout: 300_000 });

  for (const persona of ['admin-001', 'dev-user-001', 'user-001', 'user-002']) {
    test(`${persona}: every route fits a phone`, async ({ page }) => {
      test.skip(!devIds.admin, 'Needs the dev X-User-Id header (TEST_DEV_ADMIN_ID)');
      await page.context().setExtraHTTPHeaders({ 'X-User-Id': persona });
      await page.goto('/home');
      await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
      await expect(page.locator('#content-area h1').first()).toBeVisible({ timeout: 15_000 });
      const hashes: string[] = await page.evaluate(() => Array.from(new Set(ContentRouter._routeKeys()
        .map((k: string) => { try { return ContentRouter.hashFor(k); } catch (e) { return null; } })
        .filter(Boolean))));
      hashes.push('#/environments/' + seededEnvId);

      const problems: string[] = [];
      for (const hash of hashes) {
        await page.evaluate(h => { location.hash = h; }, hash);
        await page.waitForLoadState('networkidle');
        await page.waitForTimeout(500);
        const found = await page.evaluate(w => {
          const out: string[] = [];
          if (document.documentElement.scrollWidth > w) out.push(`page is ${document.documentElement.scrollWidth}px wide`);
          const scrollsInside = (el: Element) => {
            for (let a = el.parentElement; a && a.id !== 'content-area'; a = a.parentElement) {
              const o = getComputedStyle(a).overflowX;
              if (o === 'auto' || o === 'scroll' || o === 'hidden') return true;
            }
            return false;
          };
          const name = (el: Element) => `${el.tagName.toLowerCase()}${el.id ? '#' + el.id : ''}.${String(el.className).trim().split(/\s+/).slice(0, 2).join('.')}`;
          Array.from(document.querySelectorAll('#content-area *')).forEach(el => {
            const r = el.getBoundingClientRect();
            if (r.width > 0 && r.right > w + 1 && !scrollsInside(el)
              && !Array.from(el.children).some(c => c.getBoundingClientRect().right > w + 1)) out.push(`${name(el)} ends at ${Math.round(r.right)}px`);
          });
          Array.from(document.querySelectorAll('#content-area table:not([data-cards="off"])')).forEach(t => {
            const table = t as HTMLTableElement;
            if (table.getBoundingClientRect().width === 0 || !table.tBodies.length) return;
            if (table.scrollWidth > (table.parentElement as HTMLElement).clientWidth + 1) out.push(`${name(table)} scrolls sideways instead of showing cards`);
          });
          document.querySelectorAll('#content-area button:not(.btn-link), #content-area .btn:not(.btn-link), #content-area [role=tab], '
            + '#content-area select, #content-area input:not([type=hidden]):not([type=checkbox]):not([type=radio])').forEach(el => {
            const r = el.getBoundingClientRect();
            if (r.width === 0 || r.height === 0 || getComputedStyle(el).visibility === 'hidden') return;
            const target = el.tagName === 'INPUT' && el.parentElement!.tagName === 'LABEL' ? el.parentElement! : el;
            const h = target.getBoundingClientRect().height;
            if (h < 43.5) out.push(`${name(el)} is ${Math.round(h)}px tall`);
          });
          return out.slice(0, 5);
        }, WIDTH);
        found.forEach(f => problems.push(`${hash}: ${f}`));
      }
      expect(problems, problems.join('\n')).toEqual([]);
    });
  }
});
