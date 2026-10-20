import { test, expect, type Page } from '@playwright/test';
import { devIds } from '../../fixtures/auth';

/**
 * U06-T06: Home per role on the real test data at 1440x900. Every non-admin gets Home, with up to
 * four cards matching their own summary, their access in words (Admin / Operator / View only), and
 * at most two "Needs you" tiles with the rest counted; a viewer is never asked to extend a lease.
 * No user gets a page scrollbar in either direction, however many grants end soon.
 */

declare const ContentRouter: any;

const OPERATOR = devIds.user;
const VIEWER = process.env.TEST_DEV_SECOND_USER_ID || 'user-002';
const ENV_ADMIN = process.env.TEST_DEV_ENV_ADMIN_ID || 'dev-user-001';

const ACCESS_WORDS: Record<string, string> = { ADMIN: 'Admin', USER: 'Operator', VIEWER: 'View only' };

/** Opens Home as the given dev user and returns the summary the page rendered. */
async function openHomeAs(page: Page, userId: string) {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.context().setExtraHTTPHeaders({ 'X-User-Id': userId });
  const summary = page.waitForResponse(r => /\/api\/v1\/dashboard\/summary$/.test(r.url()) && r.ok());
  await page.goto('/home');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  const body = await (await summary).json();
  await expect(page.locator('#content-area .page-header h1')).toHaveText('Home', { timeout: 15_000 });
  await expect(page.locator('.home-recent #home-recent-body')).not.toContainText('Loading', { timeout: 15_000 });
  return body;
}

/** No page scrollbar: neither the document nor the content column scrolls, in either direction. */
async function expectNoPageScrollbars(page: Page) {
  const sizes = await page.evaluate(() => {
    const doc = document.scrollingElement!;
    const main = document.getElementById('mainContent')!;
    return {
      doc: [doc.scrollHeight, doc.clientHeight, doc.scrollWidth, doc.clientWidth],
      main: [main.scrollHeight, main.clientHeight, main.scrollWidth, main.clientWidth],
    };
  });
  for (const [h, ch, w, cw] of [sizes.doc, sizes.main]) {
    expect(h, `vertical scroll ${JSON.stringify(sizes)}`).toBeLessThanOrEqual(ch);
    expect(w, `horizontal scroll ${JSON.stringify(sizes)}`).toBeLessThanOrEqual(cw);
  }
}

async function expectHomeMatches(page: Page, body: any) {
  expect(body.persona).not.toBe('ADMIN');
  const environments: any[] = body.environments || [];
  const cards = page.locator('.home-card');
  await expect(cards).toHaveCount(Math.min(4, environments.length));
  if (!environments.length) {
    await expect(page.locator('#home-empty-title')).toHaveText('You have no environments yet.');
  }
  const byId = new Map(environments.map(e => [e.environmentId, e]));
  for (const card of await cards.all()) {
    const id = decodeURIComponent((await card.getAttribute('href'))!.replace('#/environments/', ''));
    const env = byId.get(id);
    expect(env, `card ${id} comes from the summary`).toBeTruthy();
    await expect(card.locator('.home-card-access')).toHaveText(ACCESS_WORDS[env.myAccess] || 'Some groups');
  }
  // Two tiles at most; the rest are counted in "N more ending soon".
  const items = (body.needsYou || []).filter((n: any) => n.kind === 'LEASE_ENDING' || n.kind === 'ACCESS_ENDING');
  await expect(page.locator('.needs-you-tile')).toHaveCount(Math.min(2, items.length));
  if (items.length > 2) {
    await expect(page.locator('.needs-you-more')).toHaveText(`${items.length - 2} more ending soon`);
  } else {
    await expect(page.locator('.needs-you-more')).toHaveCount(0);
  }
  return { environments, needsYou: body.needsYou || [] };
}

test.describe('Home per role', () => {
  test.beforeEach(() => {
    test.skip(!OPERATOR, 'Needs TEST_DEV_USER_ID (dev mode)');
  });

  test('an operator: cards and tiles from their summary, no page scrollbar', async ({ page }) => {
    const body = await openHomeAs(page, OPERATOR);
    await expectHomeMatches(page, body);
    await expectNoPageScrollbars(page);
  });

  test('a viewer: View only where that is the grant, never an Extend lease tile', async ({ page }) => {
    const body = await openHomeAs(page, VIEWER);
    const { environments, needsYou } = await expectHomeMatches(page, body);
    const viewOnly = new Set(environments.filter(e => e.myAccess === 'VIEWER').map(e => e.environmentId));
    for (const item of needsYou.filter((n: any) => n.kind === 'LEASE_ENDING')) {
      expect(viewOnly.has(item.environmentId), `lease tile on view-only ${item.environmentName}`).toBeFalsy();
    }
    for (const id of viewOnly) {
      await expect(page.locator(`.needs-you-tile[href^="#/environments/${id}?extendLease"]`)).toHaveCount(0);
    }
    await expectNoPageScrollbars(page);
  });

  test('an environment admin: Home (not the fleet dashboard), Admin on every card, no page scrollbar', async ({ page }) => {
    const body = await openHomeAs(page, ENV_ADMIN);
    const { environments } = await expectHomeMatches(page, body);
    await expect(page.locator('.dashboard-kpi-grid')).toHaveCount(0);
    if (environments.every(e => e.myAccess === 'ADMIN')) {
      for (const access of await page.locator('.home-card-access').all()) {
        await expect(access).toHaveText('Admin');
      }
    }
    await expectNoPageScrollbars(page);
  });
});
