import { test, expect, type Page, type Route } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E06-T05: the automation rules UI. The rules API is faked in the browser (nothing is created on
 * the server and no schedule can fire): one Save per click, the reason a rule was switched off,
 * the run reason, the locked/auto-disabled counts, a refused re-enable showing the server's
 * reason, and the browser's timezone as the default.
 */

declare const ContentRouter: any;

const ENV = { environmentId: 'env-auto-e2e', name: 'auto-e2e', displayName: 'Auto E2E', isActive: true };

function rule(patch: Record<string, unknown>) {
  return {
    ruleId: `rule-${Math.random().toString(36).slice(2)}`, name: 'Rule', description: null,
    environmentId: ENV.environmentId, environmentName: ENV.name, scopeType: 'ENVIRONMENT', scopeId: null,
    scopeName: null, triggerType: 'SCHEDULE', daysOfWeek: ['MON', 'TUE'], stopTime: '20:00', startTime: null,
    timezone: 'Asia/Kolkata', accessGrantMode: null, skipIfAlreadyInTargetState: true, enabled: true,
    createdByUserId: 'admin-001', createdByDisplayName: 'Admin', createdAt: new Date().toISOString(),
    lastRunAt: null, lastRunStatus: null, lastRunDetail: null, lastRunReason: null, disabledReason: null,
    ...patch,
  };
}

class FakeRules {
  rules: any[] = [];
  posts = 0;
  patchError: string | null = null;

  async install(page: Page) {
    await page.route(/\/api\/v1\/automation-rules(\/.*)?(\?.*)?$/, (route) => this.handle(route));
    await page.route(/\/api\/v1\/environments(\?.*)?$/, (route) =>
      route.request().method() === 'GET' ? this.json(route, [ENV]) : route.continue());
    await page.route(/\/api\/v1\/environments\/env-auto-e2e\/(groups|vms)(\?.*)?$/, (route) => this.json(route, []));
  }

  private json(route: Route, body: unknown, status = 200) {
    return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
  }

  private async handle(route: Route) {
    const req = route.request();
    const parts = new URL(req.url()).pathname.split('/'); // ['', 'api','v1','automation-rules', id?, 'enabled'?]
    if (req.method() === 'GET') return this.json(route, this.rules);
    if (req.method() === 'POST' && parts[4] === 'preview') return this.json(route, []); // next-runs preview (E19-T04)
    if (req.method() === 'POST') {
      this.posts++;
      await new Promise(r => setTimeout(r, 400)); // a slow server: the second click lands mid-request
      const created = rule({ ...req.postDataJSON() });
      this.rules.unshift(created);
      return this.json(route, created, 201);
    }
    if (req.method() === 'PATCH' && parts[5] === 'enabled') {
      if (this.patchError) return this.json(route, { error: 'Validation', message: this.patchError }, 400);
      const r = this.rules.find(x => x.ruleId === parts[4]);
      Object.assign(r, { enabled: req.postDataJSON().enabled, disabledReason: null });
      return this.json(route, r);
    }
    return route.continue();
  }
}

async function open(page: Page, fake: FakeRules) {
  await fake.install(page);
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/automation-rules'; });
  await expect(page.locator('#automation-rules-view')).toBeVisible({ timeout: 10_000 });
}

test.describe('Automation rules UI', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('a double click on Create sends one request', async ({ page }) => {
    const fake = new FakeRules();
    await open(page, fake);
    await page.locator('#ar-new-rule-btn').click();
    await page.locator('#ar-name').fill('Nightly stop');

    await page.locator('#ar-save-btn').dblclick();

    await expect(page.locator('#automationRuleModal')).toBeHidden({ timeout: 10_000 });
    await expect(page.locator('#ar-table-wrapper .ar-rule', { hasText: 'Nightly stop' })).toHaveCount(1);
    expect(fake.posts).toBe(1);
  });

  test('a new rule defaults to the browser timezone', async ({ browser }) => {
    const context = await browser.newContext({ timezoneId: 'Europe/London' });
    const page = await context.newPage();
    await open(page, new FakeRules());
    await page.locator('#ar-new-rule-btn').click();

    await expect(page.locator('#ar-timezone')).toHaveValue('Europe/London');
    await context.close();
  });

  test('shows why a rule was switched off, the run reason and the counts', async ({ page }) => {
    const fake = new FakeRules();
    fake.rules = [
      rule({ name: 'Off by system', enabled: false, disabledReason: 'Environment <b>deactivated</b>',
        lastRunAt: new Date().toISOString(), lastRunStatus: 'SKIPPED', lastRunReason: 'ENVIRONMENT_INACTIVE' }),
      rule({ name: 'Locked out', lastRunAt: new Date().toISOString(), lastRunStatus: 'SKIPPED', lastRunReason: 'LOCKED' }),
      rule({ name: 'Nothing to do', lastRunAt: new Date().toISOString(), lastRunStatus: 'SKIPPED', lastRunReason: 'NOTHING_TO_DO' }),
      rule({ name: 'Off by hand', enabled: false }),
    ];
    await open(page, fake);

    const off = page.locator('#ar-table-wrapper .ar-rule', { hasText: 'Off by system' });
    await expect(off.locator('.ar-disabled-reason')).toHaveText('Turned off: Environment <b>deactivated</b>'); // escaped
    await expect(off.locator('.ar-chip-off')).toHaveText('Off');
    await expect(off.locator('.ar-rule-summary')).toContainText(/skipped \w{3}: environment deactivated/);
    await expect(off.locator('.week-strip.week-off')).toHaveCount(1);
    await expect(page.locator('#ar-table-wrapper .ar-rule', { hasText: 'Off by hand' }).locator('.ar-disabled-reason')).toHaveCount(0);
    // U12-T02 counts: rules on, environments, and only the LOCKED skip as a lock (not "nothing to do").
    await expect(page.locator('#ar-counts')).toContainText('2 rules on');
    await expect(page.locator('#ar-counts')).toContainText('cover 1 environment');
    await expect(page.locator('#ar-counts')).toContainText('1 run skipped last week: environment was locked');
    await expect(page.locator('#automation-rules-view')).toContainText('A rule never breaks a lock: it retries, then skips and tells you.'); // U12-T02
  });

  test('a refused re-enable shows the server reason and stays off', async ({ page }) => {
    const fake = new FakeRules();
    fake.rules = [rule({ name: 'Blocked', enabled: false, disabledReason: 'Environment deactivated' })];
    fake.patchError = 'This rule cannot be enabled: Environment deactivated';
    await open(page, fake);

    const toggle = page.locator('#ar-table-wrapper .ar-rule', { hasText: 'Blocked' }).locator('.ar-toggle-enabled');
    await toggle.click(); // not check(): the page is expected to turn it back off

    await expect(page.getByText('This rule cannot be enabled: Environment deactivated')).toBeVisible();
    await expect(toggle).not.toBeChecked();
  });
});

/**
 * U12-T02 (AutomationRules.dc.html): tabs, counts, and each schedule on a Mon–Sun week strip; 10
 * schedules a page without a page scrollbar at 1440x900.
 */
test.describe('Automation rules page', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('Office hours draws five bars from 08:00 to 20:00 and none at the weekend', async ({ browser }) => {
    const context = await browser.newContext({ timezoneId: 'Asia/Kolkata' });
    const page = await context.newPage();
    const fake = new FakeRules();
    fake.rules = [rule({ name: 'Office hours', scheduleSummary: 'Mon–Fri 08:00–20:00', timezone: 'Asia/Kolkata',
      windows: [{ daysOfWeek: ['MON', 'TUE', 'WED', 'THU', 'FRI'], startTime: '08:00', stopTime: '20:00' }],
      nextRunAt: new Date(Date.now() + 3600_000).toISOString(), nextRunAction: 'STOP' })];
    await open(page, fake);

    const card = page.locator('.ar-rule', { hasText: 'Office hours' });
    const rows = card.locator('.week-row');
    await expect(rows).toHaveCount(7);
    for (let i = 0; i < 5; i++) {
      const bar = rows.nth(i).locator('.week-bar');
      await expect(bar).toHaveCount(1);
      await expect(bar).toHaveAttribute('style', /left:33.33%;width:50%/);
    }
    await expect(rows.nth(5).locator('.week-bar')).toHaveCount(0);
    await expect(rows.nth(6).locator('.week-bar')).toHaveCount(0);
    await expect(card.locator('.week-strip')).toHaveAttribute('aria-label', /^Mon–Fri 08:00–20:00 · Asia\/Kolkata/);
    await expect(card.locator('.ar-next-run')).toHaveText(/^Stop (today|tomorrow) \d\d:\d\d$/);
    await expect(card.getByRole('switch', { name: 'Turn Office hours on' })).toBeChecked();
    await context.close();
  });

  test('environment admins see all four tabs; the tab is kept in the address', async ({ page, playwright }) => {
    const envAdminId = process.env.TEST_DEV_ENV_ADMIN_ID || 'dev-user-001';
    const admin = await playwright.request.newContext({ baseURL: process.env.BASE_URL || 'http://localhost:8080',
      extraHTTPHeaders: { 'X-User-Id': devIds.admin, 'Content-Type': 'application/json' } });
    await admin.patch(`/api/v1/users/${envAdminId}/roles`, { data: { admin: false, envAdmin: true } });
    await admin.dispose();
    await new FakeRules().install(page);
    await page.context().setExtraHTTPHeaders({ 'X-User-Id': envAdminId });
    await page.goto('/home');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/automation-rules'; });
    const tabs = page.getByRole('tab');
    await expect(tabs).toHaveText(['Schedules', 'When access is granted', 'Holiday calendars', 'Recent runs'], { timeout: 10_000 });
    await page.getByRole('tab', { name: 'Recent runs' }).click();
    await expect(page.getByRole('tab', { name: 'Recent runs' })).toHaveAttribute('aria-selected', 'true');
    await expect(page).toHaveURL(/#\/automation-rules\?tab=runs$/);
    await page.getByRole('tab', { name: 'Holiday calendars' }).click();
    await expect(page.locator('#ar-content')).toContainText('Holiday calendars are kept by administrators');
  });

  test('12 schedules: 10 on the page with a pager, no page scrollbar at 1440x900', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    const fake = new FakeRules();
    fake.rules = Array.from({ length: 12 }, (_, i) => rule({ name: `Schedule ${i + 1}`, scheduleSummary: 'Mon–Fri 08:00–20:00',
      windows: [{ daysOfWeek: ['MON', 'TUE', 'WED', 'THU', 'FRI'], startTime: '08:00', stopTime: '20:00' }],
      createdAt: new Date(Date.now() - i * 60_000).toISOString() }));
    await open(page, fake);

    await expect(page.locator('#ar-table-wrapper .ar-rule')).toHaveCount(10);
    await expect(page.locator('#ar-pagination .pager-range')).toHaveText('1–10 of 12');
    const overflow = await page.evaluate(() => document.documentElement.scrollHeight - window.innerHeight);
    expect(overflow).toBeLessThanOrEqual(0);
    await page.locator('#ar-pagination [data-action="ar-page"]', { hasText: 'Next' }).click();
    await expect(page.locator('#ar-table-wrapper .ar-rule')).toHaveCount(2);
  });
});
