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
  bodies: any[] = []; // POST and PUT bodies
  saveError: string | null = null;
  patchError: string | null = null;

  async install(page: Page) {
    await page.route(/\/api\/v1\/automation-rules(\/.*)?(\?.*)?$/, (route) => this.handle(route));
    await page.route(/\/api\/v1\/environments(\?.*)?$/, (route) =>
      route.request().method() === 'GET' ? this.json(route, [ENV]) : route.continue());
    await page.route(/\/api\/v1\/environments\/env-auto-e2e\/groups(\?.*)?$/, (route) =>
      this.json(route, [{ groupId: 'g-web', name: 'web', displayName: 'Web' }]));
    await page.route(/\/api\/v1\/environments\/env-auto-e2e\/vms(\?.*)?$/, (route) => this.json(route, []));
    await page.route(/\/api\/v1\/holiday-calendars(\?.*)?$/, (route) => this.json(route, [{ calendarId: 'cal-in', name: 'India public',
      description: null, dateCount: 3, nextHoliday: '2099-10-20', nextHolidayLabel: 'Office holiday', usedByRuleCount: 9 }]));
    await page.route(/\/api\/v1\/holiday-calendars\/cal-in\/dates(\?.*)?$/, (route) => this.json(route, { content: [
      { holidayId: 'h1', date: '2020-10-02', label: 'Gandhi Jayanti' },
      { holidayId: 'h2', date: '2099-10-20', label: 'Office holiday' },
      { holidayId: 'h3', date: '2099-12-25', label: 'Christmas' }] }));
  }

  private json(route: Route, body: unknown, status = 200) {
    return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
  }

  private async handle(route: Route) {
    const req = route.request();
    const parts = new URL(req.url()).pathname.split('/'); // ['', 'api','v1','automation-rules', id?, 'enabled'?]
    if (req.method() === 'GET' && parts[5] === 'overrides') return this.json(route, []);
    if (req.method() === 'GET' && parts[4]) {
      const found = this.rules.find(x => x.ruleId === parts[4]);
      return found ? this.json(route, found) : this.json(route, { message: 'Rule not found' }, 404);
    }
    if (req.method() === 'GET') return this.json(route, this.rules);
    if (req.method() === 'POST' && parts[4] === 'preview') return this.json(route, []); // next-runs preview (E19-T04)
    if ((req.method() === 'POST' || req.method() === 'PUT') && this.saveError) {
      return this.json(route, { error: 'Validation', message: this.saveError }, 400);
    }
    if (req.method() === 'PUT') {
      this.bodies.push(req.postDataJSON());
      const r = this.rules.find(x => x.ruleId === parts[4]);
      Object.assign(r, req.postDataJSON());
      return this.json(route, r);
    }
    if (req.method() === 'POST') {
      this.posts++;
      this.bodies.push(req.postDataJSON());
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

  test('a double click on Save schedule sends one request', async ({ page }) => {
    const fake = new FakeRules();
    await open(page, fake);
    await page.locator('#ar-new-rule-btn').click();
    await page.locator('#ar-name').fill('Nightly stop');

    await page.locator('#ar-save-btn').dblclick();

    await expect(page).toHaveURL(/#\/automation-rules$/, { timeout: 10_000 });
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
    await expect(card.getByRole('checkbox', { name: 'Turn Office hours on' })).toBeChecked();
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
    // U12-T04: environment admins read the calendars; nothing in them can be changed.
    await page.getByRole('tab', { name: 'Holiday calendars' }).click();
    await expect(page.locator('#hc-calendars .hc-item')).toHaveText([/India public\s*3 dates · next 20 Oct/]);
    await expect(page.locator('#hc-used')).toHaveText('Used by 9 schedules');
    await expect(page.locator('#hc-dates-table tbody tr')).toHaveCount(3);
    await expect(page.locator('#hc-dates-table tbody tr').first()).toHaveClass(/hc-past/);
    await expect(page.locator('#hc-dates-table tbody tr').nth(1)).toContainText('20-10-2099');
    await expect(page.locator('#hc-detail')).toContainText('skipped runs show "holiday" in Recent runs');
    for (const name of ['Import CSV', 'Add date', 'Remove', '+ New calendar', 'Rename', 'Delete calendar']) {
      await expect(page.locator('#ar-content').getByRole('button', { name, exact: true })).toHaveCount(0);
    }
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

/**
 * U12-T03 (RuleEditor.dc.html): New schedule and Edit open the schedule editor page. The rules API
 * is faked as above.
 */
test.describe('Schedule editor', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('a new schedule: days, times, week, holidays, saved as one window', async ({ browser }) => {
    const context = await browser.newContext({ timezoneId: 'Asia/Kolkata', viewport: { width: 1440, height: 900 } });
    const page = await context.newPage();
    const fake = new FakeRules();
    await open(page, fake);
    await page.locator('#ar-new-rule-btn').click();
    await expect(page).toHaveURL(/#\/automation-rules\/new$/);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('New schedule');
    await expect(page.locator('#ar-timezone')).toHaveValue('Asia/Kolkata');

    const pills = page.locator('#re-days .ar-day-pill');
    await expect(pills).toHaveText(['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']);
    await expect(page.locator('#re-days .ar-day-pill[aria-pressed="true"]')).toHaveCount(5);
    await expect(page.locator('#re-hours')).toContainText('Runs 60 hours a week instead of 168');
    // Saturday too: 72 hours, and a sixth bar.
    await page.locator('#re-days .ar-day-pill[data-day="SAT"]').click();
    await expect(page.locator('#re-hours')).toContainText('Runs 72 hours a week instead of 168');
    await expect(page.locator('#re-week-strip .week-row').nth(5).locator('.week-bar')).toHaveCount(1);

    await expect(page.getByLabel('On holidays').locator('option')).toHaveText([
      "Don't start on India public holidays", 'Run as usual', "Don't start or stop on India public holidays"]);
    await page.getByLabel('On holidays').selectOption({ label: "Don't start or stop on India public holidays" });

    // A missing name is said next to the field; nothing is sent.
    await page.locator('#ar-save-btn').click();
    await expect(page.locator('#re-err-name')).toHaveText('Give the schedule a name.');
    expect(fake.posts).toBe(0);

    await page.locator('#ar-name').fill('Office hours');
    await page.locator('#ar-save-btn').click();
    await expect(page).toHaveURL(/#\/automation-rules$/, { timeout: 10_000 });
    expect(fake.bodies[0]).toMatchObject({ name: 'Office hours', triggerType: 'SCHEDULE', scopeType: 'ENVIRONMENT',
      timezone: 'Asia/Kolkata', holidayCalendarId: 'cal-in', holidayBehavior: 'SKIP_ALL',
      windows: [{ daysOfWeek: ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'], startTime: '08:00', stopTime: '20:00' }] });
    await context.close();
  });

  test('editing keeps different Saturday hours; the server reason shows by the times; Cancel goes back', async ({ page }) => {
    const fake = new FakeRules();
    const existing = rule({ name: 'Two shifts', holidayCalendarId: 'cal-in', holidayCalendarName: 'India public', holidayBehavior: 'SKIP_START',
      windows: [{ daysOfWeek: ['MON', 'TUE', 'WED', 'THU', 'FRI'], startTime: '08:00', stopTime: '20:00' },
        { daysOfWeek: ['SAT'], startTime: '10:00', stopTime: '14:00' }] });
    fake.rules = [existing];
    await open(page, fake);
    await page.locator('.ar-rule', { hasText: 'Two shifts' }).locator('.ar-edit-btn').click();
    await expect(page).toHaveURL(new RegExp(`#/automation-rules/${existing.ruleId}$`));
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Edit Two shifts');
    await expect(page.locator('#ar-windows .ar-window')).toHaveCount(2);
    await expect(page.locator('#re-days')).toHaveCount(0);
    await expect(page.getByLabel('On holidays')).toHaveValue('SKIP_START:cal-in');
    await expect(page.locator('#re-hours')).toContainText('Runs 64 hours a week instead of 168');

    fake.saveError = 'Windows overlap on SAT';
    await page.locator('#ar-save-btn').click();
    await expect(page.locator('#re-err-times')).toHaveText('Windows overlap on SAT');
    fake.saveError = null;
    await page.locator('#ar-save-btn').click();
    await expect(page).toHaveURL(/#\/automation-rules$/, { timeout: 10_000 });
    expect(fake.bodies[0].windows).toEqual(existing.windows);
    expect(fake.bodies[0]).toMatchObject({ holidayCalendarId: 'cal-in', holidayBehavior: 'SKIP_START' });

    await page.locator('.ar-rule', { hasText: 'Two shifts' }).locator('.ar-edit-btn').click();
    await page.getByRole('link', { name: 'Cancel' }).click();
    await expect(page).toHaveURL(/#\/automation-rules\?tab=schedules$/);
    await expect(page.getByRole('tab', { name: 'Schedules' })).toHaveAttribute('aria-selected', 'true');
  });

  test('access rules: a sentence each, and the form beside them', async ({ page }) => {
    const fake = new FakeRules();
    fake.rules = [rule({ name: 'On lock', triggerType: 'ACCESS_GRANT', accessGrantMode: 'LOCK_ACQUIRE' })];
    await open(page, fake);
    await page.getByRole('tab', { name: 'When access is granted' }).click();
    const card = page.locator('.ar-rule', { hasText: 'On lock' });
    await expect(card.locator('.ar-rule-summary')).toHaveText('Starts Auto E2E when someone takes the lock, in their name');

    await card.locator('.ar-edit-btn').click();
    const panel = page.locator('#ag-panel');
    await expect(panel.getByRole('heading', { name: 'Edit On lock' })).toBeVisible();
    await expect(panel.getByLabel('Someone takes the lock')).toBeChecked();
    await expect(card).toHaveClass(/ar-rule-editing/);
    await panel.getByRole('button', { name: 'Cancel' }).click();
    await expect(panel).toHaveCount(0);

    // U12-T04 acceptance: 'Someone takes the lock' sends LOCK_ACQUIRE.
    await page.locator('#ar-new-access-btn').click();
    await expect(panel.getByRole('heading', { name: 'New rule' })).toBeVisible();
    await expect(page.locator('#ag-name')).toBeFocused();
    await page.locator('#ag-name').fill('Start for whoever locks it');
    await expect(page.locator('#ag-which')).toHaveValue('env-auto-e2e');
    await expect(panel).toContainText('These rules only start. The lease or a schedule stops it.');
    await panel.getByRole('button', { name: 'Create rule' }).click();
    await expect(panel).toHaveCount(0, { timeout: 10_000 });
    expect(fake.bodies[0]).toMatchObject({ name: 'Start for whoever locks it', triggerType: 'ACCESS_GRANT', accessGrantMode: 'LOCK_ACQUIRE',
      environmentId: 'env-auto-e2e', scopeType: 'ENVIRONMENT', scopeId: null, skipIfAlreadyInTargetState: true, enabled: true });
    await expect(page.locator('.ar-rule', { hasText: 'Start for whoever locks it' })).toHaveCount(1);

    // A group, started on approval in the owner's name.
    await page.locator('#ar-new-access-btn').click();
    await page.locator('#ag-name').fill('Web on approval');
    await page.locator('#ag-applies').selectOption('GROUP');
    await expect(page.locator('#ag-which optgroup')).toHaveAttribute('label', 'Auto E2E');
    await page.locator('#ag-which').selectOption({ label: 'Web' });
    await panel.getByLabel('An access request is approved').check();
    await panel.getByLabel("Do nothing if it's already running").uncheck();
    await panel.getByRole('button', { name: 'Create rule' }).click();
    await expect(panel).toHaveCount(0, { timeout: 10_000 });
    expect(fake.bodies[1]).toMatchObject({ accessGrantMode: 'ACCESS_APPROVED', scopeType: 'GROUP', scopeId: 'g-web',
      environmentId: 'env-auto-e2e', skipIfAlreadyInTargetState: false });
  });

  test('an access rule without a name is not sent; the server reason shows in the form', async ({ page }) => {
    const fake = new FakeRules();
    await open(page, fake);
    await page.getByRole('tab', { name: 'When access is granted' }).click();
    await page.locator('#ar-new-access-btn').click();
    await page.locator('#ag-panel').getByRole('button', { name: 'Create rule' }).click();
    await expect(page.locator('#ag-error')).toHaveText('Give the rule a name.');
    expect(fake.posts).toBe(0);
    fake.saveError = 'Selected group does not belong to environment auto-e2e';
    await page.locator('#ag-name').fill('Bad');
    await page.locator('#ag-panel').getByRole('button', { name: 'Create rule' }).click();
    await expect(page.locator('#ag-error')).toHaveText('Selected group does not belong to environment auto-e2e');
    await expect(page.locator('#ag-panel')).toBeVisible();
  });
});

/**
 * U12-T05: Recent runs lists every run of the last 30 days from the audit log (faked here), 10 a
 * page, with the result and what happened.
 */
test.describe('Recent runs', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  function runRow(i: number, schedule: any) {
    const kinds = [
      { action: 'AUTOMATION_RULE_SKIPPED', details: 'Skipped: Environment locked by Asha Rao', success: true },
      { action: 'AUTOMATION_RULE_TRIGGERED', details: 'Triggered STOP (schedule)', success: true },
      { action: 'AUTOMATION_RULE_SKIPPED', details: 'Skipped: holiday (Diwali)', success: true },
      { action: 'AUTOMATION_RULE_FAILED', details: 'AWS said no', errorMessage: 'AWS said no', success: false },
    ];
    return { logId: `log-${i}`, targetType: 'automation_rule', targetId: schedule.ruleId, targetName: schedule.name,
      environmentId: ENV.environmentId, environmentName: ENV.name, userDisplayName: 'Admin',
      createdAt: new Date(Date.now() - i * 3600_000).toISOString(), ...kinds[i % kinds.length] };
  }

  test('25 runs: 10 a page, the lock holder named, filters and the rule link', async ({ page }) => {
    const fake = new FakeRules();
    const schedule = rule({ name: 'Office hours' });
    fake.rules = [schedule];
    const all = Array.from({ length: 25 }, (_, i) => runRow(i, schedule));
    const asked: URL[] = [];
    await page.route(/\/api\/v1\/audit\/logs\?/, route => {
      const url = new URL(route.request().url());
      asked.push(url);
      const actions = url.searchParams.getAll('action');
      const rows = all.filter(r => actions.includes(r.action));
      const n = Number(url.searchParams.get('page')), size = Number(url.searchParams.get('size'));
      return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ content: rows.slice(n * size, n * size + size), totalElements: rows.length }) });
    });
    await open(page, fake);
    await page.getByRole('tab', { name: 'Recent runs' }).click();

    const rows = page.locator('#ar-runs-table tbody tr');
    await expect(rows).toHaveCount(10);
    await expect(page.locator('#ar-runs-pager .pager-range')).toHaveText('1–10 of 25');
    expect(asked[0].searchParams.get('category')).toBe('AUTOMATION');
    expect(asked[0].searchParams.getAll('action')).toEqual(['AUTOMATION_RULE_TRIGGERED', 'AUTOMATION_RULE_SKIPPED', 'AUTOMATION_RULE_FAILED']);
    expect(Date.now() - new Date(asked[0].searchParams.get('from')!).getTime()).toBeGreaterThan(29.9 * 86400_000);
    // U12-T05 acceptance: a skip because the environment was locked names the holder.
    await expect(rows.nth(0).locator('.ar-result')).toHaveText('Skipped');
    await expect(rows.nth(0)).toContainText('environment was locked by Asha Rao');
    await expect(rows.nth(1).locator('.ar-result')).toHaveText('Stopped');
    await expect(rows.nth(1)).toContainText('on schedule');
    await expect(rows.nth(2)).toContainText('holiday (Diwali)');
    await expect(rows.nth(3).locator('.ar-result')).toHaveText('Failed');

    await page.locator('#ar-runs-pager [data-action="ar-runs-page"]', { hasText: 'Next' }).click();
    await expect(page.locator('#ar-runs-pager .pager-range')).toHaveText('11–20 of 25');

    await page.locator('#ar-runs-result').selectOption('SKIPPED');
    await expect(page.locator('#ar-runs-pager .pager-range')).toHaveText('1–10 of 13');
    expect(asked[asked.length - 1].searchParams.getAll('action')).toEqual(['AUTOMATION_RULE_SKIPPED']);
    await page.locator('#ar-runs-env').selectOption(ENV.environmentId);
    await expect.poll(() => asked[asked.length - 1].searchParams.get('environmentId')).toBe(ENV.environmentId);

    await rows.first().getByRole('link', { name: 'Office hours' }).click();
    await expect(page).toHaveURL(new RegExp(`#/automation-rules/${schedule.ruleId}$`));
  });

  test('no runs: says so', async ({ page }) => {
    await page.route(/\/api\/v1\/audit\/logs\?/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ content: [], totalElements: 0 }) }));
    await open(page, new FakeRules());
    await page.getByRole('tab', { name: 'Recent runs' }).click();
    await expect(page.locator('#ar-runs-list')).toHaveText('No runs in the last 30 days.');
    await expect(page.locator('#ar-runs-table')).toHaveCount(0);
  });
});
