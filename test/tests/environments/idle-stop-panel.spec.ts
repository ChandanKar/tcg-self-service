import { test, expect, type Page, type Route } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E16-T05: the idle auto-stop panel on the environment page. The idle-stop API is faked in the
 * browser and the panel is mounted with IdleStop.renderPanel, so no environment, rule or cloud
 * call is needed: dry-run savings, snooze and end snooze, a viewer sees no buttons, the flag off
 * renders nothing, and the settings modal saves once per click.
 */

declare const IdleStop: any;

const ENV_ID = 'env-idle-e2e';

class FakeIdleStop {
  status: Record<string, any> = {
    featureEnabled: true, production: false, canOperate: true, canAdminister: false,
    rules: [{ ruleId: 'rule-1', environmentId: ENV_ID, scopeType: 'ENVIRONMENT', groupId: null, groupName: null,
      idleMinutes: 60, cpuMaxPercent: 5, networkMbPerDay: 5, mode: 'DRY_RUN', enabled: true,
      dryRunStartedAt: new Date(Date.now() - 3 * 86400000).toISOString() }],
    snoozedUntil: null, snoozedByUserId: null, snoozedByDisplayName: null,
    latestEvent: { outcome: 'WOULD_STOP', reason: 'Dry run: would stop 2 VM(s)', evaluatedAt: new Date().toISOString(),
      idleSince: new Date(Date.now() - 2 * 3600000).toISOString() },
    days: 14, wouldStopEpisodes: 3, wouldHaveSaved: 9, stoppedCount: 0, savedEstimate: 0, stoppedSavings: [],
  };
  snoozes: number[] = [];
  puts = 0;

  async install(page: Page) {
    await page.route(new RegExp(`/api/v1/environments/${ENV_ID}/idle-stop/.*`), route => this.handle(route));
  }

  private json(route: Route, body: unknown, status = 200) {
    return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
  }

  private async handle(route: Route) {
    const req = route.request();
    const path = new URL(req.url()).pathname;
    if (req.method() === 'GET' && path.endsWith('/status')) return this.json(route, this.status);
    if (req.method() === 'POST' && path.endsWith('/snooze')) {
      const hours = req.postDataJSON().hours;
      this.snoozes.push(hours);
      this.status.snoozedUntil = new Date(Date.now() + hours * 3600000).toISOString();
      this.status.snoozedByDisplayName = 'Test User 1';
      return this.json(route, { environmentId: ENV_ID, snoozedUntil: this.status.snoozedUntil });
    }
    if (req.method() === 'DELETE' && path.endsWith('/snooze')) {
      this.status.snoozedUntil = null;
      return route.fulfill({ status: 204 });
    }
    if (req.method() === 'PUT' && path.includes('/rules/')) {
      this.puts++;
      await new Promise(r => setTimeout(r, 300));
      Object.assign(this.status.rules[0], req.postDataJSON());
      return this.json(route, this.status.rules[0]);
    }
    return route.continue();
  }
}

async function mount(page: Page, fake: FakeIdleStop, role: 'user' | 'admin' = 'user') {
  await fake.install(page);
  await signIn(page, role);
  await page.waitForFunction(() => typeof IdleStop !== 'undefined');
  await page.evaluate(envId => {
    // Outside #content-area, so the router rendering the landing page cannot replace it.
    document.body.insertAdjacentHTML('beforeend', '<div id="env-idle-panel"></div>');
    IdleStop.renderPanel({ environmentId: envId, groups: [] });
  }, ENV_ID);
}

test.describe('Idle auto-stop panel', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  });

  test('a dry run shows what it would have saved in the last 14 days', async ({ page }) => {
    await mount(page, new FakeIdleStop());

    await expect(page.locator('#idle-stop-card')).toContainText('Dry run');
    await expect(page.locator('#idle-would-have-saved')).toContainText('$9.00');
    await expect(page.locator('#idle-would-have-saved')).toContainText('last 14 days');
  });

  test('snooze 4 h shows the snooze and End snooze, which ends it', async ({ page }) => {
    const fake = new FakeIdleStop();
    await mount(page, fake);

    await page.locator('[data-action="idle-snooze"][data-hours="4"]').click();

    await expect(page.locator('#idle-snoozed')).toContainText('Snoozed until');
    await expect(page.locator('[data-action="idle-snooze"]')).toHaveCount(0);
    expect(fake.snoozes).toEqual([4]);
    await page.locator('[data-action="idle-end-snooze"]').click();
    await expect(page.locator('[data-action="idle-snooze"]')).toHaveCount(3);
  });

  test('a viewer sees the panel without snooze or settings buttons', async ({ page }) => {
    const fake = new FakeIdleStop();
    fake.status.canOperate = false;
    await mount(page, fake);

    await expect(page.locator('#idle-stop-card')).toBeVisible();
    await expect(page.locator('#idle-stop-card button')).toHaveCount(0);
  });

  test('nothing renders while the feature is off', async ({ page }) => {
    const fake = new FakeIdleStop();
    fake.status.featureEnabled = false;
    await mount(page, fake);

    await page.waitForTimeout(500);
    await expect(page.locator('#idle-stop-card')).toHaveCount(0);
  });

  test('settings: enforce is unavailable during the dry run, and Save sends one request', async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    const fake = new FakeIdleStop();
    fake.status.canAdminister = true;
    await mount(page, fake, 'user'); // a non-admin env admin view: the 14-day gate applies

    await page.locator('[data-action="idle-rule-settings"]').click();
    await expect(page.locator('#idleStopRuleModal')).toBeVisible();
    await expect(page.locator('#idleMode option[value="ENFORCE"]')).toBeDisabled();
    await page.locator('#idleMinutes').fill('90');
    await page.locator('#idleSaveRule').dblclick();

    await expect(page.locator('#idleStopRuleModal')).toBeHidden();
    expect(fake.puts).toBe(1);
    expect(fake.status.rules[0].idleMinutes).toBe(90);
  });
});
