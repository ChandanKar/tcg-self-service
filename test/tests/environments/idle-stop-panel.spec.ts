import { test, expect, type Page, type Route } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E16-T05: idle auto-stop on the environment page, since U07-T06 the "Idle auto-stop" fact of
 * "About this environment" with "Pause or review". The idle-stop API is faked in the browser and
 * the fact is mounted with IdleStop.renderPanel, so no environment, rule or cloud call is needed:
 * dry-run savings, snooze and end snooze, a viewer sees no action, the flag off renders nothing,
 * and the settings modal saves once per click.
 * U08-T09: "Pause or review" opens one dialog: the recent decisions newest first, pause for 2, 4 or
 * 8 hours ("Paused until HH:MM" and Resume now), and the rule form with a named switch.
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
    recentEvents: [
      { outcome: 'WOULD_STOP', reason: 'All 7 VMs idle for 60 min', evaluatedAt: new Date(Date.now() - 3600000).toISOString() },
      { outcome: 'WOULD_STOP', reason: 'All 7 VMs idle for 60 min', evaluatedAt: new Date(Date.now() - 5 * 3600000).toISOString() },
      { outcome: 'SKIPPED', reason: 'Locked by Priya Nair', evaluatedAt: new Date(Date.now() - 26 * 3600000).toISOString() },
      { outcome: 'SNOOZED', reason: 'Paused by Karthik S until 23:00', evaluatedAt: new Date(Date.now() - 50 * 3600000).toISOString() },
      { outcome: 'SKIPPED', reason: 'An operation is running', evaluatedAt: new Date(Date.now() - 74 * 3600000).toISOString() }],
    minDryRunDays: 14, canEnforceEarly: false,
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
  await page.waitForLoadState('networkidle');
  await page.evaluate(envId => {
    // Outside #content-area (the router cannot replace it), as a fixed overlay above the shell.
    document.body.insertAdjacentHTML('beforeend',
      '<div id="env-fact-idle" style="position:fixed;top:0;left:0;right:0;z-index:20000;background:#fff"></div>');
    IdleStop.renderPanel({ environmentId: envId, groups: [] });
  }, ENV_ID);
}

test.describe('Idle auto-stop panel', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  });

  test('a dry run shows what it would have saved in the last 14 days', async ({ page }) => {
    await mount(page, new FakeIdleStop());

    await expect(page.locator('#idle-stop-card .env-fact-value')).toHaveText('Dry run · after 60 min idle');
    await expect(page.locator('#idle-would-have-saved')).toContainText('$9.00');
    await expect(page.locator('#idle-would-have-saved')).toContainText('last 14 days');
  });

  test('the dialog: decisions newest first, pause for 2 hours, Paused until and Resume now', async ({ page }) => {
    const fake = new FakeIdleStop();
    await mount(page, fake);

    await page.getByRole('button', { name: 'Pause or review' }).click();
    const dialog = page.locator('#idleStopDialog');
    await expect(dialog.locator('.idle-head')).toContainText(/Dry run · day \d+ of 14/);
    const decisions = dialog.locator('.idle-decision');
    await expect(decisions).toHaveCount(5);
    await expect(decisions.locator('.idle-tag')).toHaveText(['Would stop', 'Would stop', 'Skipped: locked', 'Skipped: paused', 'Skipped']);
    await expect(decisions.nth(2)).toContainText('Locked by Priya Nair');
    await expect(dialog.getByRole('button', { name: /hours$/ })).toHaveText(['2 hours', '4 hours', '8 hours']);

    await dialog.getByRole('button', { name: '2 hours' }).click();
    await expect.poll(() => fake.snoozes).toEqual([2]);
    await expect(dialog.locator('#idle-dialog-paused')).toHaveText(/^Paused until \d\d:\d\d/);
    await expect(page.locator('#idle-snoozed')).toContainText(/Paused until \d\d:\d\d by Test User 1/);
    await expect(dialog.locator('[data-action="idle-snooze"]')).toHaveCount(0);
    await dialog.getByRole('button', { name: 'Resume now' }).click();
    await expect(dialog.locator('[data-action="idle-snooze"]')).toHaveCount(3);
    await expect(dialog.getByRole('checkbox')).toHaveCount(0);  // an operator: no rule form
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

  test('settings: the rule form in the dialog; stopping is unavailable during the dry run; Save sends one request', async ({ page }) => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
    const fake = new FakeIdleStop();
    fake.status.canAdminister = true;
    await mount(page, fake, 'user'); // a non-admin env admin view: the 14-day gate applies

    await page.getByRole('button', { name: 'Pause or review' }).click();
    const dialog = page.locator('#idleStopDialog');
    await expect(dialog.getByRole('checkbox', { name: 'On', exact: true })).toBeChecked();
    await expect(dialog.getByLabel('Applies to')).toBeDisabled();
    await expect(dialog.getByRole('radio', { name: /Stop idle VMs/ })).toBeDisabled();
    await expect(dialog.locator('#idle-enforce-note')).toHaveText(/^Available after 14 days of dry run, on \d+ \w{3}$/);
    await dialog.getByLabel('Idle for (min)').fill('90');
    await page.locator('#idleSaveRule').dblclick();

    await expect(dialog).toBeHidden();
    expect(fake.puts).toBe(1);
    expect(fake.status.rules[0].idleMinutes).toBe(90);
  });
});
