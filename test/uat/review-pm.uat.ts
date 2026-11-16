import { type Page } from '@playwright/test';
import * as fs from 'fs';
import { test, signInAdmin, signInAs, PEOPLE, readState, visit, describeVisit } from './guard';

/**
 * Product-manager review (read-only): walks every page as admin and as the viewer, on the owner's
 * laptop size (1366 x 657) and a few on a phone (390 x 844). Screenshots plus a text digest per page.
 * Opens dialogs only to look at them and always closes them with Cancel / Escape.
 */
const iseId = () => readState<any>('preflight')!.ise.environmentId as string;

const ADMIN_ROUTES = [
  '#/dashboard', '#/my-environments', 'ISE', '#/request-access', '#/activity-logs',
  '#/pending-requests', '#/access-management', '#/vm-registry', '#/automation-rules', '#/approval-policies',
  '#/budgets', '#/audit-logs-all', '#/cost-management', '#/savings', '#/waste', '#/chargeback',
  '#/user-management', '#/teams', '#/aws-accounts', '#/api-access', '#/settings', '#/system-health', '#/help',
];
const VIEWER_ROUTES = [
  '#/dashboard', '#/my-environments', 'ISE', '#/request-access', '#/activity-logs', '#/help',
  // typed into the address bar, to see how a refused page reads
  '#/access-management', '#/cost-management', '#/settings',
];

type Digest = Record<string, any>;

function slug(s: string) { return s.replace(/^#\//, '').replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, '') || 'root'; }

async function digest(page: Page): Promise<any> {
  return page.evaluate(() => {
    const c = document.querySelector('#content-area') as HTMLElement | null;
    const text = (c?.innerText || '').replace(/\n{2,}/g, '\n').slice(0, 2500);
    const h1 = Array.from(document.querySelectorAll('#content-area h1')).map(h => (h as HTMLElement).innerText.trim());
    const tabs = Array.from(document.querySelectorAll('#content-area [role="tab"]')).filter(t => (t as HTMLElement).offsetParent).map(t => (t as HTMLElement).innerText.trim());
    const buttons = Array.from(document.querySelectorAll('#content-area button')).filter(b => (b as HTMLElement).offsetParent).map(b => ((b as HTMLElement).innerText || b.getAttribute('aria-label') || '').trim()).filter(Boolean).slice(0, 40);
    const menu = Array.from(document.querySelectorAll('.sidebar .sidebar-menu-link')).filter(a => (a as HTMLElement).offsetParent).map(a => (a as HTMLElement).innerText.trim()).filter(Boolean);
    const scroll = { docH: document.documentElement.scrollHeight, winH: window.innerHeight, docW: document.documentElement.scrollWidth, winW: window.innerWidth };
    return { h1, tabs, buttons, menu, scroll, text };
  });
}

async function closeAnything(page: Page) {
  for (let i = 0; i < 2; i++) {
    const cancel = page.locator('.modal.show button, [role="dialog"]:visible button').filter({ hasText: /^(cancel|close|not now)$/i }).first();
    if (await cancel.count().catch(() => 0)) await cancel.click().catch(() => {});
    else await page.keyboard.press('Escape').catch(() => {});
    await page.waitForTimeout(400);
  }
}

/** Waits (up to 90 s on the slow link) until the page shows no spinner or 'Loading' text. */
async function settle(page: Page): Promise<number> {
  const t0 = Date.now();
  while (Date.now() - t0 < 90_000) {
    const busy = await page.evaluate(() => {
      const c = document.querySelector('#content-area') as HTMLElement | null;
      if (!c) return true;
      const vis = (e: Element) => !!(e as HTMLElement).offsetParent;
      if (Array.from(c.querySelectorAll('[aria-busy="true"], .spinner, .spinner-border, .loading-spinner')).some(vis)) return true;
      return /(^|\n)\s*Loading[^\n]*(\.\.\.|…)/.test(c.innerText || '');
    }).catch(() => false);
    if (!busy) break;
    await page.waitForTimeout(1500);
  }
  await page.waitForTimeout(800);
  return Math.round((Date.now() - t0) / 1000);
}

async function walk(page: Page, who: string, routes: string[], out: Digest) {
  for (const r of routes) {
    const route = r === 'ISE' ? '#/environments/' + iseId() : r;
    const name = `${who}-${r === 'ISE' ? 'ise' : slug(r)}`;
    try {
      const v = await visit(page, route, name);
      const waited = await settle(page);
      await page.screenshot({ path: test.info().outputPath(name + '.png'), fullPage: true });
      out[name] = { route, waited, problems: describeVisit(v), ...(await digest(page)) };
      // Click through the page's tabs (read-only views).
      const tabs = page.locator('#content-area [role="tab"]:visible');
      const n = Math.min(await tabs.count(), 8);
      for (let i = 1; i < n; i++) {
        const t = tabs.nth(i);
        const label = ((await t.innerText().catch(() => '')) || `tab${i}`).trim();
        await t.click().catch(() => {});
        await page.waitForLoadState('networkidle', { timeout: 30_000 }).catch(() => {});
        await page.waitForTimeout(1200);
        await settle(page);
        const shot = `${name}-tab-${slug(label).slice(0, 30)}`;
        await page.screenshot({ path: test.info().outputPath(shot + '.png'), fullPage: true });
        out[shot] = { route, tab: label, ...(await digest(page)) };
      }
    } catch (e: any) {
      out[name] = { route, error: String(e?.message || e).slice(0, 300) };
    }
  }
}

async function chrome(page: Page, who: string, out: Digest) {
  // Bell dropdown (look only; no item is clicked)
  try {
    await page.locator('#notification-bell-btn').first().click();
    await page.waitForTimeout(2000);
    await page.screenshot({ path: test.info().outputPath(`${who}-bell.png`) });
    out[`${who}-bell`] = { text: (await page.locator('#notification-dropdown').innerText().catch(() => '')).slice(0, 1500) };
    await page.keyboard.press('Escape');
    await page.locator('#content-area').click({ position: { x: 5, y: 5 } }).catch(() => {});
    await page.waitForTimeout(500);
  } catch (e: any) { out[`${who}-bell`] = { error: String(e?.message || e).slice(0, 200) }; }
  // User menu, then My account
  try {
    await page.locator('.user-profile-trigger').first().click();
    await page.waitForTimeout(800);
    await page.screenshot({ path: test.info().outputPath(`${who}-user-menu.png`) });
    out[`${who}-user-menu`] = { text: (await page.locator('.user-dropdown').innerText().catch(() => '')).slice(0, 600) };
    const ma = page.locator('.user-dropdown [role="menuitem"]').filter({ hasText: /my account/i }).first();
    if (await ma.count()) {
      await ma.click();
      await page.waitForTimeout(2500);
      await page.screenshot({ path: test.info().outputPath(`${who}-my-account.png`), fullPage: true });
      out[`${who}-my-account`] = { text: (await page.locator('#dynamicSlideoutPanel').innerText().catch(() => '')).slice(0, 2500) };
      const tabs = page.locator('#dynamicSlideoutPanel [role="tab"]:visible');
      const n = Math.min(await tabs.count(), 6);
      for (let i = 1; i < n; i++) {
        const label = (await tabs.nth(i).innerText().catch(() => `tab${i}`)).trim();
        await tabs.nth(i).click().catch(() => {});
        await page.waitForTimeout(1500);
        await page.screenshot({ path: test.info().outputPath(`${who}-my-account-${slug(label)}.png`), fullPage: true });
        out[`${who}-my-account-${slug(label)}`] = { text: (await page.locator('#dynamicSlideoutPanel').innerText().catch(() => '')).slice(0, 2000) };
      }
      await closeAnything(page);
    }
  } catch (e: any) { out[`${who}-user-menu`] = { error: String(e?.message || e).slice(0, 200) }; }
  // Top-bar Help (?) on the environment page
  try {
    await visit(page, '#/environments/' + iseId(), `${who}-ise-before-help`);
    await page.locator('#topbar-help-btn').first().click();
    await page.waitForTimeout(2000);
    await page.screenshot({ path: test.info().outputPath(`${who}-topbar-help.png`) });
    out[`${who}-topbar-help`] = { hash: await page.evaluate(() => location.hash), text: (await page.locator('#dynamicSlideoutPanel, #help-view, #content-area').first().innerText().catch(() => '')).slice(0, 1500) };
    await closeAnything(page);
  } catch (e: any) { out[`${who}-topbar-help`] = { error: String(e?.message || e).slice(0, 200) }; }
}

function save(name: string, d: Digest) {
  fs.writeFileSync(test.info().outputPath(name + '.json'), JSON.stringify(d, null, 2));
}


test('PM review: admin on a laptop', async ({ page }) => {
  test.setTimeout(30 * 60_000);
  await page.setViewportSize({ width: 1366, height: 657 });
  await signInAdmin(page);
  const out: Digest = {};
  await walk(page, 'admin', ADMIN_ROUTES, out);
  // Start dialog on ise: open, look, cancel
  try {
    await visit(page, '#/environments/' + iseId(), 'admin-ise-again');
    const btn = page.locator('#btn-env-action').first();
    if (await btn.count()) {
      out['admin-start-dialog-button'] = (await btn.innerText()).trim();
      await btn.click();
      await page.waitForTimeout(3000);
      await page.screenshot({ path: test.info().outputPath('admin-start-dialog.png') });
      out['admin-start-dialog'] = { text: (await page.locator('.modal.show, [role="dialog"]:visible').first().innerText().catch(() => '')).slice(0, 2000) };
      await closeAnything(page);
      await page.screenshot({ path: test.info().outputPath('admin-start-dialog-closed.png') });
    }
  } catch (e: any) { out['admin-start-dialog'] = { error: String(e?.message || e).slice(0, 200) }; }
  await chrome(page, 'admin', out);
  save('admin-digest', out);
});

test('PM review: viewer on a laptop', async ({ page }) => {
  test.setTimeout(30 * 60_000);
  await page.setViewportSize({ width: 1366, height: 657 });
  await signInAs(page, PEOPLE.viewer.user, PEOPLE.viewer.pass);
  const out: Digest = {};
  await walk(page, 'viewer', VIEWER_ROUTES, out);
  await chrome(page, 'viewer', out);
  save('viewer-digest', out);
});

test('PM review: phone shots', async ({ page }) => {
  test.setTimeout(30 * 60_000);
  await page.setViewportSize({ width: 390, height: 844 });
  await signInAdmin(page);
  const out: Digest = {};
  for (const r of ['#/dashboard', '#/my-environments', 'ISE', '#/cost-management', '#/request-access']) {
    const route = r === 'ISE' ? '#/environments/' + iseId() : r;
    const name = `phone-${r === 'ISE' ? 'ise' : slug(r)}`;
    try {
      const v = await visit(page, route, name);
      await settle(page);
      await page.screenshot({ path: test.info().outputPath(name + '.png'), fullPage: true });
      out[name] = { route, problems: describeVisit(v), ...(await digest(page)) };
    } catch (e: any) { out[name] = { route, error: String(e?.message || e).slice(0, 200) }; }
  }
  save('phone-digest', out);
});
