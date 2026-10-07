import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn } from '../../fixtures/auth';

/**
 * E02-T01: the router's page lifecycle (onLeave / token / isCurrent / reload) and
 * page-scoped polling in RealTime. Driven through the app's own globals with page.evaluate.
 */

// App modules are top-level `const`s in classic scripts: global, but not properties of window.
declare const ContentRouter: any;
declare const RealTime: any;

async function waitForRouter(page: Page) {
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined' && typeof ContentRouter.onLeave === 'function');
  await page.waitForTimeout(500); // let the initial route finish loading
}

async function goTo(page: Page, route: string) {
  await page.evaluate((r) => { location.hash = '#/' + r; }, route);
  await page.waitForFunction((r) => location.hash === '#/' + r, route);
  await page.waitForTimeout(300);
}

test.describe('Router page lifecycle', () => {
  test.beforeEach(async ({ page }) => {
    test.skip(!canSignIn('user'), 'No sign-in configured (TEST_DEV_USER_ID or TEST_USERNAME/TEST_PASSWORD in test/.env)');
    await signIn(page, 'user');
    await waitForRouter(page);
  });

  test('onLeave handler runs exactly once, before the next page loads', async ({ page }) => {
    await goTo(page, 'activity-logs');
    await page.evaluate(() => {
      const w = window as any;
      w.__leaveCalls = 0;
      ContentRouter.onLeave(() => { w.__leaveCalls++; });
    });

    await goTo(page, 'dashboard');
    await goTo(page, 'request-access');

    expect(await page.evaluate(() => (window as any).__leaveCalls)).toBe(1);
  });

  test('a token captured on one page is stale after navigating', async ({ page }) => {
    await goTo(page, 'activity-logs');
    const stale = await page.evaluate(() => {
      const w = window as any;
      w.__token = ContentRouter.token();
      return ContentRouter.isCurrent(w.__token);
    });
    expect(stale).toBe(true);

    await goTo(page, 'dashboard');
    expect(await page.evaluate(() => ContentRouter.isCurrent((window as any).__token))).toBe(false);
  });

  test('reload tears the current page down and makes old tokens stale', async ({ page }) => {
    await goTo(page, 'activity-logs');
    await page.evaluate(() => {
      const w = window as any;
      w.__reloadLeave = 0;
      w.__token = ContentRouter.token();
      ContentRouter.onLeave(() => w.__reloadLeave++);
      ContentRouter.reload();
    });
    await page.waitForTimeout(300);
    const r = await page.evaluate(() => ({
      leave: (window as any).__reloadLeave,
      current: ContentRouter.isCurrent((window as any).__token),
      hash: location.hash,
    }));
    expect(r).toEqual({ leave: 1, current: false, hash: '#/activity-logs' });
  });

  test('page-scoped polls stop on navigation; app-wide polls keep running', async ({ page }) => {
    await goTo(page, 'activity-logs');
    await page.evaluate(() => {
      const w = window as any;
      w.__scoped = 0;
      w.__global = 0;
      RealTime.startPolling('e2eScoped', () => w.__scoped++, 200, { pageScoped: true });
      RealTime.startPolling('e2eGlobal', () => w.__global++, 200);
    });
    await page.waitForTimeout(700);

    await goTo(page, 'dashboard');
    const atLeave = await page.evaluate(() => ({ scoped: (window as any).__scoped, global: (window as any).__global }));
    await page.waitForTimeout(900);
    const later = await page.evaluate(() => ({
      scoped: (window as any).__scoped,
      global: (window as any).__global,
      active: RealTime.activePolls(),
    }));
    await page.evaluate(() => RealTime.stopPolling('e2eGlobal'));

    expect(atLeave.scoped).toBeGreaterThan(0);
    expect(later.scoped).toBe(atLeave.scoped);
    expect(later.global).toBeGreaterThan(atLeave.global);
    expect(later.active).not.toContain('e2eScoped');
    expect(later.active).toContain('e2eGlobal');
  });

  test('an overdue poll runs once as soon as the tab becomes visible again', async ({ page }) => {
    await page.evaluate(() => {
      const w = window as any;
      w.__vis = 0;
      RealTime.startPolling('e2eVisibility', () => w.__vis++, 60_000);
    });
    expect(await page.evaluate(() => (window as any).__vis)).toBe(1); // immediate run

    // Pretend the tab was hidden for longer than the interval, then becomes visible.
    await page.evaluate(() => {
      const realNow = Date.now;
      Date.now = () => realNow() + 61_000;
      Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
      document.dispatchEvent(new Event('visibilitychange'));
      Object.defineProperty(document, 'hidden', { configurable: true, get: () => false });
      document.dispatchEvent(new Event('visibilitychange'));
      Date.now = realNow;
    });
    const runs = await page.evaluate(() => (window as any).__vis);
    await page.evaluate(() => RealTime.stopPolling('e2eVisibility'));
    expect(runs).toBe(2);
  });

  test('a throwing onLeave handler is logged and the next page still loads', async ({ page }) => {
    const errors: string[] = [];
    page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
    await goTo(page, 'activity-logs');
    await page.evaluate(() => ContentRouter.onLeave(() => { throw new Error('boom from e2e'); }));

    await page.locator('.sidebar-menu-link[data-content="dashboard"]').click();

    await expect(page).toHaveURL(/#\/dashboard$/);
    await expect(page.locator('#content-area .spinner-border')).toHaveCount(0, { timeout: 10_000 });
    expect(errors.some((e) => e.includes('Route leave handler failed'))).toBe(true);
  });
});
