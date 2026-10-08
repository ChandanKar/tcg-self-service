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
    await expect(page.locator('#ar-table-wrapper tr', { hasText: 'Nightly stop' })).toHaveCount(1);
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

    const off = page.locator('#ar-table-wrapper tr', { hasText: 'Off by system' });
    await expect(off.locator('.ar-disabled-reason')).toHaveText(/Disabled: Environment <b>deactivated<\/b>/); // escaped
    await expect(off.locator('.ar-run-reason')).toHaveText('Environment deactivated');
    await expect(page.locator('#ar-table-wrapper tr', { hasText: 'Off by hand' }).locator('.ar-disabled-reason')).toHaveCount(0);
    // Only the LOCKED skip counts as locked (a "nothing to do" skip did too before).
    await expect(page.locator('.metric-card', { hasText: 'LAST RUN SKIPPED (LOCKED)' }).locator('.metric-value')).toHaveText('1');
    await expect(page.locator('#ar-stat-auto-disabled .metric-value')).toHaveText('1');
    await expect(page.locator('#automation-rules-view')).toContainText('the rule retries until its catch-up window ends');
  });

  test('a refused re-enable shows the server reason and stays off', async ({ page }) => {
    const fake = new FakeRules();
    fake.rules = [rule({ name: 'Blocked', enabled: false, disabledReason: 'Environment deactivated' })];
    fake.patchError = 'This rule cannot be enabled: Environment deactivated';
    await open(page, fake);

    const toggle = page.locator('#ar-table-wrapper tr', { hasText: 'Blocked' }).locator('.ar-toggle-enabled');
    await toggle.click(); // not check(): the page is expected to turn it back off

    await expect(page.getByText('This rule cannot be enabled: Environment deactivated')).toBeVisible();
    await expect(toggle).not.toBeChecked();
  });
});
