import { test, expect, type Page, type APIRequestContext } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';
import { ensureVolume } from '../../fixtures/volume';
import { seriousViolations } from '../../fixtures/axe';

/**
 * U04-T01: the app shell of TopBar.dc.html and SideNav.dc.html. A 56 px top bar and a 240 px side
 * menu stay in place while the main area scrolls; the menu's sections never collapse, its rows are
 * 30 px and at 1440x900 an administrator sees every link without a scrollbar. Manage is for
 * environment admins and admins, Cost and Administration for admins only.
 */

declare const ContentRouter: any;

const base = () => process.env.BASE_URL || 'http://localhost:8080';

async function api(playwright: any, userId: string): Promise<APIRequestContext> {
  return playwright.request.newContext({ baseURL: base(), extraHTTPHeaders: { 'X-User-Id': userId, 'Content-Type': 'application/json' } });
}

async function open(page: Page, role: 'admin' | 'user', hash = '#/dashboard') {
  await page.setViewportSize({ width: 1440, height: 900 });
  await signIn(page, role);
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(h => { location.hash = h; }, hash);
  await expect(page.locator('#content-area h1').first()).toBeVisible({ timeout: 15_000 });
}

test.describe('App shell', () => {
  test('an administrator sees all four sections and every link, with no menu scrollbar', async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await open(page, 'admin');

    for (const [title, text] of [['#operations-title', 'Workspace'], ['#manage-title', 'Manage'],
      ['#cost-title', 'Cost'], ['#admin-title', 'Administration']]) {
      await expect(page.locator(title)).toHaveText(text);
      await expect(page.locator(title)).toBeVisible();
    }
    await expect(page.locator('#sidebar [aria-expanded]')).toHaveCount(0);
    // Sections never collapse; the menu's only buttons are the phone sheet's Close and Sign out (U16-T01).
    await expect(page.locator('#sidebar button:visible')).toHaveCount(0);

    const labels = await page.locator('#sidebar .sidebar-menu-link:visible > span:not(.badge)').allTextContents();
    expect(labels).toEqual(expect.arrayContaining(['Home', 'Environments', 'Request access', 'My activity',
      'Access requests', 'Access management', 'VM registry', 'Automation rules', 'Approval policies', 'Budgets',
      'Audit log', 'Cost management', 'Savings', 'Waste', 'Chargeback', 'Users', 'Teams', 'AWS accounts',
      'API access', 'Settings', 'System health']));
    expect(labels).not.toContain('Cost setup');

    const menu = await page.locator('#sidebar').evaluate(el => ({ scroll: el.scrollHeight, client: el.clientHeight, width: el.getBoundingClientRect().width }));
    expect(menu.scroll).toBeLessThanOrEqual(menu.client);
    expect(menu.width).toBe(240);

    const row = await page.locator('.sidebar-menu-link[data-content="vm-registry"]').boundingBox();
    expect(row!.height).toBe(30);
    const bar = await page.locator('.top-navbar').boundingBox();
    expect(bar!.height).toBe(56);
  });

  test('the top bar holds Help, notifications and the account with initials, name and role', async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await open(page, 'admin');
    await expect(page.locator('#topbar-help-btn')).toHaveAttribute('aria-haspopup', 'menu');
    await expect(page.locator('#notification-bell-btn')).toBeVisible();
    await expect(page.locator('.top-navbar .user-role')).toHaveText('Administrator');
    await expect(page.locator('.top-navbar .user-avatar')).toHaveCSS('width', '34px');
    await expect(page.locator('#toggleSidebar')).toBeHidden();
    await expect(page.locator('.top-navbar .logo')).toHaveText('Self-Service');
  });

  test('the top bar and menu stay put while a long page scrolls; the document never scrolls', async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await open(page, 'admin', '#/help');
    await page.evaluate(() => {
      const filler = document.createElement('div');
      filler.id = 'shell-filler';
      filler.style.height = '3000px';
      document.getElementById('content-area')!.appendChild(filler);
    });
    const main = page.locator('#mainContent');
    await main.evaluate(el => { el.scrollTop = 1500; });
    expect(await main.evaluate(el => el.scrollTop)).toBeGreaterThan(1000);

    const bar = await page.locator('.top-navbar').boundingBox();
    const menu = await page.locator('#sidebar').boundingBox();
    expect(bar!.y).toBe(0);
    expect(menu!.y).toBe(56);
    const doc = await page.evaluate(() => {
      const el = document.scrollingElement!;
      return { v: el.scrollHeight - el.clientHeight, h: el.scrollWidth - el.clientWidth, top: el.scrollTop };
    });
    expect(doc).toEqual({ v: 0, h: 0, top: 0 });
  });

  test('an environment admin sees Workspace and Manage; a user sees Workspace only', async ({ page, playwright }) => {
    test.skip(!devIds.admin || !devIds.user || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID and TEST_DEV_USER_ID (dev mode)');
    const admin = await api(playwright, devIds.admin);
    const promoted = await admin.patch('/api/v1/users/dev-user-001/roles', { data: { admin: false, envAdmin: true } });
    expect(promoted.ok(), await promoted.text()).toBeTruthy();
    await admin.dispose();

    await page.setViewportSize({ width: 1440, height: 900 });
    await page.context().setExtraHTTPHeaders({ 'X-User-Id': 'dev-user-001' });
    await page.goto('/home#/dashboard');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await expect(page.locator('#manage-menu')).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('#operations-menu')).toBeVisible();
    await expect(page.locator('#cost-section')).toBeHidden();
    await expect(page.locator('#admin-section')).toBeHidden();
    await expect(page.locator('.top-navbar .user-role')).toHaveText('Environment admin');

    const other = await page.context().browser()!.newPage();
    await other.setViewportSize({ width: 1440, height: 900 });
    await other.context().setExtraHTTPHeaders({ 'X-User-Id': devIds.user });
    await other.goto(base() + '/home#/dashboard');
    await other.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await expect(other.locator('#operations-menu')).toBeVisible({ timeout: 15_000 });
    await expect(other.locator('#manage-section')).toBeHidden();
    await expect(other.locator('#cost-section')).toBeHidden();
    await expect(other.locator('#admin-section')).toBeHidden();
    await expect(other.locator('.top-navbar .user-role')).toHaveText('User');
    await other.close();
  });

  test('Cost management links to Cost setup, which left the menu', async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await open(page, 'admin', '#/cost-management');
    await expect(page.locator('.cost-tabs-row a[href="#/cost-setup"]')).toBeVisible({ timeout: 15_000 }); // U15-T01: after the tabs
  });
});

/**
 * U17-T07 and U18-T01: no page scrollbar, across or down, on any route for any persona, at 1440 x 900
 * (the mockup) and at two laptop windows: 1536 x 730 and 1366 x 657 (a 1920 x 1080 or 1366 x 768
 * screen at 125% with the browser's bars). Staging-like volume is seeded first (fixtures/volume.ts)
 * so lists are checked full. The page itself and the main area (#mainContent) are both checked.
 */
const SIZES: [number, number][] = [[1440, 900], [1536, 730], [1366, 657]];

/**
 * Pages measured to scroll on laptop windows before U18 (2026-10-22 sweep), each with the U18 task
 * that moves it onto the page frame. Checked strictly at 1440 x 900; at the smaller sizes they are
 * skipped until their task lands. Empty since U18-T05: every page fits.
 */
const LAPTOP_KNOWN: Record<string, string> = {};

test.describe('Fits the window without a page scrollbar', () => {
  test.describe.configure({ timeout: 300_000 });

  test.beforeAll(async ({ playwright }) => {
    test.setTimeout(300_000);
    if (devIds.admin) await ensureVolume(playwright, devIds.admin);
  });

  for (const [width, height] of SIZES) {
    for (const persona of ['admin-001', 'dev-user-001', 'user-001', 'user-002']) {
      test(`${width} x ${height}, ${persona}: every route`, async ({ page }) => {
        test.skip(!devIds.admin || !canSignIn('admin'), 'Needs the dev X-User-Id header (TEST_DEV_ADMIN_ID)');
        const mockup = width === 1440;
        await page.setViewportSize({ width, height });
        await page.context().setExtraHTTPHeaders({ 'X-User-Id': persona });
        await page.goto('/home');
        await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
        await expect(page.locator('#content-area h1, #content-area .no-access').first()).toBeVisible({ timeout: 15_000 });
        const hashes: string[] = await page.evaluate(() => Array.from(new Set(ContentRouter._routeKeys()
          .map((k: string) => { try { return ContentRouter.hashFor(k); } catch (e) { return null; } })
          .filter(Boolean))));
        const scrolls: string[] = [];
        for (const hash of hashes) {
          if (!mockup && LAPTOP_KNOWN[hash]) continue;
          await page.evaluate(() => { location.hash = '#/help'; });
          await expect(page.locator('#help-search')).toBeVisible();
          await page.evaluate(h => { location.hash = h; }, hash);
          await page.waitForLoadState('networkidle');
          await page.waitForTimeout(500);
          const over = await page.evaluate(() => {
            const out: string[] = [];
            const d = document.scrollingElement!;
            const main = document.getElementById('mainContent');
            if (d.scrollHeight > window.innerHeight + 1) out.push(`page is ${d.scrollHeight}px tall`);
            if (d.scrollWidth > window.innerWidth + 1) out.push(`page is ${d.scrollWidth}px wide`);
            if (main && main.scrollHeight > main.clientHeight + 1) out.push(`main area is ${main.scrollHeight}px of ${main.clientHeight}px`);
            return out;
          });
          over.forEach(o => scrolls.push(`${hash}: ${o}`));
        }
        expect(scrolls, scrolls.join('\n')).toEqual([]);
      });
    }
  }
});

/**
 * U18-T01: the page frame and the list panel themselves, on a page built from them with 200 rows.
 * The page never scrolls, the list scrolls inside with its column headings in view and the pager
 * fixed, edge shadows show hidden rows, the rows that fit really fit, and at 200% zoom (a 720 x 450
 * window) the frame lets go and the page scrolls instead.
 */
declare const ListPanel: any;
declare const PageHeader: any;

async function demoPage(page: Page, rows: number) {
  await page.evaluate((n: number) => {
    const body = '<table class="table"><thead><tr><th scope="col">Name</th><th scope="col">State</th></tr></thead><tbody>'
      + Array.from({ length: n }, (_, i) => `<tr><td>Row ${i + 1}</td><td><button type="button" class="btn btn-sm btn-outline-secondary">Open ${i + 1}</button></td></tr>`).join('')
      + '</tbody></table>';
    document.getElementById('content-area')!.innerHTML = `<div class="page page-frame" id="lp-demo">
        ${PageHeader.markup({ title: 'Panel demo', summaryHtml: 'A page built on the frame.' })}
        <div class="page-fill">${ListPanel.markup({ id: 'lp', title: 'Rows', toolbarHtml: '<input type="search" aria-label="Search rows">',
          bodyHtml: body, footHtml: `<div class="pager"><span class="pager-range">1–${n} of ${n}</span></div>` })}</div></div>`;
    (window as any).__fits = [];
    (window as any).__lp = ListPanel.attach(document.getElementById('lp'), { rowSelector: 'tbody tr', onFit: (c: number) => (window as any).__fits.push(c) });
  }, rows);
}

const scrollState = (page: Page) => page.evaluate(() => {
  const main = document.getElementById('mainContent')!;
  const body = document.getElementById('lp-body')!;
  return { mainScrolls: main.scrollHeight > main.clientHeight + 1, bodyScrolls: body.scrollHeight > body.clientHeight + 1 };
});

test.describe('Page frame and list panel', () => {
  test.beforeEach(async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    await page.setViewportSize({ width: 1366, height: 657 });
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined' && typeof ListPanel !== 'undefined');
    await page.evaluate(() => { location.hash = '#/help'; });
    await expect(page.locator('#help-search')).toBeVisible({ timeout: 15_000 });
  });

  test('1366 x 657: the page stays put, the list scrolls inside with its headings and pager in view', async ({ page }) => {
    await demoPage(page, 200);
    expect(await scrollState(page)).toEqual({ mainScrolls: false, bodyScrolls: true });
    const panel = page.locator('#lp');
    await expect(panel).toHaveClass(/has-more-below/);
    await expect(panel).not.toHaveClass(/has-more-above/);
    await expect(page.locator('#lp-foot')).toBeInViewport();

    await page.locator('#lp-body').evaluate((b: HTMLElement) => { b.scrollTop = 400; });
    await expect(panel).toHaveClass(/has-more-above/);
    const [bodyTop, headTop] = await page.evaluate(() => [document.getElementById('lp-body')!.getBoundingClientRect().top,
      document.querySelector('#lp-body thead th')!.getBoundingClientRect().top]);
    expect(Math.abs(headTop - bodyTop)).toBeLessThanOrEqual(1); // the column headings stay at the top

    // A named region reachable by keyboard; nothing serious for axe.
    await expect(page.locator('#lp-body')).toHaveAttribute('tabindex', '0');
    await expect(page.getByRole('region', { name: 'Rows, scrolls' })).toBeVisible();
    expect(await seriousViolations(page, '#lp-demo')).toEqual([]);
  });

  test('the rows that fit really fit, and a resize reports a new count once it settles', async ({ page }) => {
    await demoPage(page, 200);
    const fits = await page.evaluate(() => (window as any).__lp.fitCount());
    expect(fits).toBeGreaterThanOrEqual(5);
    await demoPage(page, fits);
    expect(await scrollState(page)).toEqual({ mainScrolls: false, bodyScrolls: false });
    await expect(page.locator('#lp')).not.toHaveClass(/has-more-below/);

    await page.setViewportSize({ width: 1440, height: 900 });
    await expect.poll(() => page.evaluate(() => (window as any).__fits.length)).toBeGreaterThan(0);
    const bigger = await page.evaluate(() => (window as any).__fits.at(-1));
    expect(bigger).toBeGreaterThan(fits);
  });

  test('200% zoom (720 x 450): the frame lets go and the page scrolls; every row shows', async ({ page }) => {
    await page.setViewportSize({ width: 720, height: 450 });
    await demoPage(page, 40);
    expect(await scrollState(page)).toEqual({ mainScrolls: true, bodyScrolls: false });
    expect(await page.evaluate(() => (window as any).__lp.fitCount())).toBeNull();
  });
});

/** U18-T02: Home, Environments, Request access and My activity on the page frame. */
test.describe('Everyday pages on the page frame', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  async function openAs(page: Page, width: number, height: number, hash: string, ready: string, role: 'admin' | 'user' = 'admin') {
    await page.setViewportSize({ width, height });
    await signIn(page, role);
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(h => { location.hash = h; }, hash);
    await expect(page.locator(ready).first()).toBeVisible({ timeout: 15_000 });
    await page.waitForLoadState('networkidle');
  }

  test('Environments: rows a page follow the window, up to 15, with the pager always in view', async ({ page, playwright }) => {
    await ensureVolume(playwright, devIds.admin);
    await openAs(page, 1366, 657, '#/my-environments', '#env-list-body tr');
    const rows = page.locator('#env-list-body tr');
    await expect.poll(async () => rows.count()).toBeLessThan(15);
    const small = await rows.count();
    await expect(page.locator('#env-list-pagination .pager-range')).toBeInViewport();
    await expect(page.locator('#env-list-pagination .pager-range')).toHaveText(new RegExp(`^1–${small} of [0-9]+$`));

    await page.setViewportSize({ width: 1440, height: 900 });
    await expect.poll(async () => rows.count(), { timeout: 10_000 }).toBeGreaterThan(small);
    expect(await rows.count()).toBeLessThanOrEqual(15);
  });

  test('Request access: the environments and the form side by side at the same height; search filters the list', async ({ page, playwright }) => {
    await ensureVolume(playwright, devIds.admin);
    // A user: the seeded environments are the ones to ask for (an administrator can use every one).
    await openAs(page, 1366, 657, '#/request-access', '#ra-env-list .ra-env', 'user');
    const list = await page.locator('#ra-list-panel').boundingBox();
    const form = await page.locator('#ra-form-card').boundingBox();
    expect(Math.abs(list!.height - form!.height)).toBeLessThanOrEqual(1);
    expect(Math.abs(list!.y - form!.y)).toBeLessThanOrEqual(1);
    await expect(page.locator('#ra-env-pager')).toBeInViewport();
    await expect(page.locator('#ra-send')).toBeInViewport();
    await page.locator('#ra-search').fill('fit-volume-0');
    await expect(page.locator('#ra-env-list .ra-env').first()).toBeVisible();
    await expect(page.locator('#ra-env-list .ra-env').first()).toContainText(/\d/);
  });

  test('Environment page at 1366 x 657: Start order and every panel keep their height; the column scrolls instead', async ({ page, playwright }) => {
    const admin = await api(playwright, devIds.admin!);
    const stamp = Date.now();
    const env = await (await admin.post('/api/v1/environments', { data: { name: `start-order-${stamp}`, displayName: `Start order ${stamp}`,
      cloudProvider: 'AWS', region: 'ap-south-1' } })).json();
    const group = await (await admin.post(`/api/v1/environments/${env.environmentId}/groups`, { data: { name: 'default-group',
      displayName: 'Default Group', sequencePosition: 1 } })).json();
    for (let i = 1; i <= 9; i++) {
      await admin.post(`/api/v1/environments/${env.environmentId}/vms`, { data: { groupId: group.groupId, name: `so-${stamp}-${i}`,
        displayName: `so-vm-${i}`, provider: 'AWS', region: 'ap-south-1', providerVmId: `i-0${String(stamp).slice(-8)}${String(i).padStart(8, '0')}`,
        sequencePosition: i } });
    }
    await admin.dispose();
    await openAs(page, 1366, 657, `#/environments/${env.environmentId}`, '.env-start-order');
    await expect(page.locator('.env-start-order')).toContainText('Default Group');
    // U20 follow-up: no panel is squeezed below its content (Start order was cut off at the bottom).
    const cut = await page.evaluate(() => [...document.querySelectorAll('.env-main > *, .env-aside > *')]
      .filter(el => (el as HTMLElement).offsetParent !== null && el.scrollHeight > el.clientHeight + 1)
      .map(el => el.className));
    expect(cut).toEqual([]);
    const strip = await page.locator('.env-start-order .env-stage').boundingBox();
    const panel = await page.locator('.env-start-order').boundingBox();
    expect(strip!.y + strip!.height).toBeLessThanOrEqual(panel!.y + panel!.height);
  });

  test('Home and My activity hold to a 1366 x 657 window', async ({ page }) => {
    await openAs(page, 1366, 657, '#/dashboard', '#dashboard-view');
    const fits = () => page.evaluate(() => { const m = document.getElementById('mainContent')!; return m.scrollHeight <= m.clientHeight + 1; });
    expect(await fits()).toBe(true);
    await page.evaluate(() => { location.hash = '#/activity-logs'; });
    await expect(page.locator('#al-results')).toBeVisible({ timeout: 15_000 });
    await page.waitForLoadState('networkidle');
    expect(await fits()).toBe(true);
  });

  test('Forms keep their buttons in view at 1366 x 657 and only their content scrolls (U18-T05)', async ({ page }) => {
    const fits = () => page.evaluate(() => { const m = document.getElementById('mainContent')!; return m.scrollHeight <= m.clientHeight + 1; });
    const forms: [string, string, string][] = [
      ['#/automation-rules/new', '#ar-form', '#ar-save-btn'],
      ['#/access-management/grant', '#ga-env-list', '#ga-grant'],
      ['#/user-management/add', '#adp-people', '#adp-submit'],
    ];
    await openAs(page, 1366, 657, '#/help', '#help-search');
    for (const [hash, ready, button] of forms) {
      await page.evaluate(h => { location.hash = h; }, hash);
      await expect(page.locator(ready)).toBeVisible({ timeout: 15_000 });
      await page.waitForLoadState('networkidle');
      expect(await fits(), `${hash} fits the window`).toBe(true);
      await expect(page.locator(button), `${hash}: ${button} in view`).toBeInViewport();
    }
  });

  test('Settings: rows a page fit the window and the changes bar never moves them', async ({ page }) => {
    await openAs(page, 1366, 657, '#/settings', '#st-panel .st-row');
    const fits = () => page.evaluate(() => { const m = document.getElementById('mainContent')!; return m.scrollHeight <= m.clientHeight + 1; });
    expect(await fits()).toBe(true);
    const rows = page.locator('#st-panel .st-row');
    const count = await rows.count();
    expect(count).toBeGreaterThan(0);
    expect(count).toBeLessThanOrEqual(6);
    const control = page.locator('#st-panel select[data-st-key], #st-panel input[type=checkbox][data-st-key]').first();
    test.skip(!(await control.count()), 'No editable setting in this section');
    if (await control.evaluate(el => el.tagName === 'SELECT')) {
      await control.evaluate((el: HTMLSelectElement) => {
        el.selectedIndex = (el.selectedIndex + 1) % el.options.length;
        el.dispatchEvent(new Event('change', { bubbles: true }));
      });
    } else {
      await control.click();
    }
    await expect(page.locator('#st-bar')).toBeVisible();
    await expect(page.locator('#st-save')).toBeInViewport();
    await page.waitForTimeout(400);
    expect(await rows.count()).toBe(count);
    expect(await fits()).toBe(true);
    await page.locator('#st-discard').click();
    await expect(page.locator('#st-bar')).toBeHidden();
  });
});
