import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E23-T03: the Teams page (create a team, add a member by hand) and the team picker that replaced
 * the free-text owner team in the environment modals. Runs against the smoke app.
 */

declare const ContentRouter: any;

async function openTeams(page: Page) {
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/teams'; });
  await expect(page.locator('#teams-view')).toBeVisible({ timeout: 15_000 });
}

test.describe('Teams', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('an admin creates a team and adds a member', async ({ page }) => {
    const name = `Platform ${Date.now()}`;
    await openTeams(page);
    await page.locator('[data-action="team-new"]').click();
    await page.locator('#team-display').fill(name);
    await page.locator('#team-cost-center').fill('CC-42');
    await page.locator('#team-save-btn').click();

    // U13-T08: a new team opens its own page.
    await expect(page.locator('#team-detail-view h1')).toHaveText(name, { timeout: 10_000 });
    await expect(page.locator('#team-detail-view .content-header')).toContainText('cost centre CC-42 · 0 environments · 0 members');

    await page.locator('#td-add-people').click();
    await page.locator('#td-pick input[type="checkbox"]').first().check();
    await expect(page.locator('#td-add-confirm')).toHaveText('Add 1 person');
    await page.locator('#td-add-confirm').click();
    await expect(page.locator('#td-member-table .td-tag-manual')).toHaveText('Added by hand', { timeout: 10_000 });
    await expect(page.locator('#td-member-table [data-td-remove]')).toHaveCount(1);
    await expect(page.locator('#team-detail-view .content-header')).toContainText('1 member');

    await page.locator('#team-detail-view .page-breadcrumb a', { hasText: 'Teams' }).click();
    await expect(page.locator('#teams-cards .tm-card').first()).toBeVisible(); // back on the cards (U13-T07)
  });

  test('the environment modal offers teams instead of a free-text owner', async ({ page }) => {
    await openTeams(page);
    const name = `Picker ${Date.now()}`;
    await page.locator('[data-action="team-new"]').click();
    await page.locator('#team-display').fill(name);
    await page.locator('#team-save-btn').click();
    await expect(page.locator('#team-detail-view h1')).toHaveText(name, { timeout: 10_000 });

    await page.evaluate('Modals.showCreateEnvironment(function() {})');
    const picker = page.locator('#cem-team');
    await expect(picker.locator('option', { hasText: name })).toHaveCount(1);
    await expect(page.locator('#cem-ownerTeam')).toHaveCount(0);

    let sent: any = null;
    await page.route(/\/api\/v1\/environments$/, route => {
      if (route.request().method() !== 'POST') return route.continue();
      sent = route.request().postDataJSON();
      return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ environmentId: 'e-1', name: 'x' }) });
    });
    await page.locator('#cem-name').fill(`picker-env-${Date.now()}`);
    await picker.selectOption({ label: name });
    await page.locator('#cem-submitBtn').click();
    await expect.poll(() => sent && sent.teamId).toBeTruthy();
    expect(JSON.parse(sent.metadata).ownerTeam).toBeUndefined();
  });

  test('required allocation fields: the picker is marked required and gaps are listed (E23-T04)', async ({ page }) => {
    await page.route(/\/api\/v1\/environments\/allocation-rules$/, route => route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ requireTeam: true, requiredMetadataKeys: ['costCenter'] }),
    }));
    await page.route(/\/api\/v1\/environments\/allocation-gaps$/, route => route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify([{ environmentId: 'env-x', name: 'Legacy <b>env</b>', missing: ['team', 'costCenter'] }]),
    }));
    await openTeams(page);
    const gaps = page.locator('#teams-gaps');
    await expect(gaps).toContainText('1 environment missing allocation fields');
    await expect(gaps).toContainText('team, costCenter');
    await expect(gaps.locator('a[href="#/environments/env-x"]')).toHaveText('Legacy <b>env</b>');

    await page.evaluate('Modals.showCreateEnvironment(function() {})');
    await expect(page.locator('#cem-team')).toHaveAttribute('aria-required', 'true');
    await expect(page.locator('label[for="cem-team"] .text-danger')).toHaveCount(1);
  });
});

/**
 * U13-T07 (Teams.dc.html): one card per team with cost centre, members line, environments with
 * strips and the VM-hours bar. The overview and the environment list are faked.
 */
test.describe('Team cards', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  const quota = (used: number, limit: number) => [{ quotaId: 'q', metric: 'VM_HOURS', limit, enforcement: 'WARN', warnPercent: 80, active: true,
    used, percent: Math.round(used / limit * 100) }];
  const team = (id: string, name: string, cc: string, members: number, envs: string[], quotas: any[], entra?: string) => ({
    teamId: id, name: name.toLowerCase(), displayName: name, costCenter: cc, active: true, memberCount: members,
    entraGroupId: entra ? 'g-' + id : null, entraGroupName: entra || null, entraMemberCount: entra ? members : 0, lastSyncedAt: null,
    environments: envs.map(e => ({ environmentId: 'env-' + e.toLowerCase().replace(/ /g, '-'), name: e.toLowerCase(), displayName: e, production: false })),
    quotas });
  const TEAMS = [
    team('t1', 'Payments', 'CC-4105', 6, ['Payments Dev'], quota(1840, 4000), 'Payments Engineering'),
    team('t2', 'Data', 'CC-4410', 9, ['Data Lake Dev', 'Analytics QA'], quota(5120, 6000)),
    team('t3', 'CRM', 'CC-4300', 4, ['CRM UAT'], quota(2890, 3000)),
    team('t4', 'People', 'CC-4510', 3, [], [])];
  const ENVS = ['Payments Dev', 'Data Lake Dev', 'Analytics QA', 'CRM UAT'].map(e => ({ environmentId: 'env-' + e.toLowerCase().replace(/ /g, '-'),
    name: e.toLowerCase(), displayName: e, isActive: true, groupStates: [{ groupName: 'app', running: 2, stopped: 1, transitioning: 0, failed: 0, unknown: 0, total: 3 }] }));

  async function openCards(page: Page, quotasOn: boolean) {
    await page.route(/\/api\/v1\/users\/me\/capabilities$/, async route => {
      const response = await route.fetch();
      const caps = await response.json();
      return route.fulfill({ response, body: JSON.stringify({ ...caps, features: { ...caps.features, quotas: quotasOn } }) });
    });
    await page.route(/\/api\/v1\/teams\/overview$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify(quotasOn ? TEAMS : TEAMS.map(t => ({ ...t, quotas: [] }))) }));
    await page.route(/\/api\/v1\/environments$/, route => route.request().method() === 'GET'
      ? route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ENVS) }) : route.continue());
    await openTeams(page);
  }

  test('four cards: cost centre, members, environments with strips, VM hours', async ({ page }) => {
    await openCards(page, true);
    const cards = page.locator('#teams-cards .tm-card');
    await expect(cards).toHaveCount(4);
    const payments = cards.filter({ hasText: 'Payments' });
    await expect(payments.locator('.tm-cc')).toHaveText('CC-4105');
    await expect(payments.locator('.tm-members')).toHaveText('6 people · synced from Entra group "Payments Engineering"');
    await expect(payments.locator('.tm-env a')).toHaveText('Payments Dev');
    await expect(payments.locator('.tm-env .power-strip, .tm-env [class*="strip"]').first()).toBeVisible();
    await expect(payments.locator('.tm-quota-line')).toContainText('1,840 / 4,000');
    await expect(payments.locator('.tm-bar')).toHaveAttribute('aria-label', '46% of the monthly VM hours used');
    await expect(cards.filter({ hasText: 'Data' }).locator('.tm-env')).toHaveCount(2);
    // Acceptance: 96% of the hours: the red bar.
    await expect(cards.filter({ hasText: 'CRM' }).locator('.tm-bar')).toHaveClass(/tm-bar-over/);
    const people = cards.filter({ hasText: 'People' });
    await expect(people.locator('.tm-env-none')).toContainText('No environments yet.');
    await expect(people.locator('.tm-quota-none')).toHaveText('No monthly limit');
    await expect(payments.getByRole('link', { name: 'Members of Payments' })).toHaveAttribute('href', '#/teams/t1'); // U13-T08
  });

  test('with quotas off there is no bar and no quota request', async ({ page }) => {
    const quotaCalls: string[] = [];
    page.on('request', r => { if (/\/quotas|quota-usage/.test(r.url())) quotaCalls.push(r.url()); });
    await openCards(page, false);
    await expect(page.locator('#teams-cards .tm-card')).toHaveCount(4);
    await expect(page.locator('.tm-quota')).toHaveCount(0);
    expect(quotaCalls).toEqual([]);
  });
});

/**
 * U13-T08 (TeamDetail.dc.html): the team's page. Members with their source (Remove only for people
 * added by hand), 8 a page, Sync now, the monthly limits aside (hidden while quotas are off) and the
 * environments dialog. The team and its members are faked; the requests the page sends are checked.
 */
test.describe('Team page', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  const TEAM = { teamId: 'tp1', name: 'payments', displayName: 'Payments', costCenter: 'CC-4410', active: true, memberCount: 14,
    entraGroupId: 'g-1', entraGroupName: 'SG-Payments', entraMemberCount: 12, lastSyncedAt: null,
    environments: [{ environmentId: 'env-pay-dev', name: 'pay-dev', displayName: 'Payments Dev', production: false }],
    quotas: [{ quotaId: 'q1', metric: 'VM_HOURS', limit: 4000, enforcement: 'WARN', warnPercent: 80, active: true, used: 3400, percent: 85 }] };
  const MEMBERS = Array.from({ length: 14 }, (_, i) => ({ userId: `u${String(i).padStart(2, '0')}`, displayName: `Person ${String(i).padStart(2, '0')}`,
    email: `p${i}@example.com`, source: i < 12 ? 'ENTRA' : 'MANUAL' }));

  async function openTeam(page: Page, opts: { quotasOn?: boolean } = {}) {
    const calls = { overview: 0, quotas: [] as string[] };
    await page.route(/\/api\/v1\/users\/me\/capabilities$/, async route => {
      const response = await route.fetch();
      const caps = await response.json();
      return route.fulfill({ response, body: JSON.stringify({ ...caps, features: { ...caps.features, quotas: opts.quotasOn !== false } }) });
    });
    await page.route(/\/api\/v1\/teams\/tp1\/overview$/, route => { calls.overview++;
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(TEAM) }); });
    await page.route(/\/api\/v1\/teams\/tp1\/members$/, route => route.request().method() === 'GET'
      ? route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(MEMBERS) }) : route.continue());
    page.on('request', r => { if (/\/quotas|quota-usage/.test(r.url())) calls.quotas.push(r.method() + ' ' + r.url()); });
    await signIn(page, 'admin');
    await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
    await page.evaluate(() => { location.hash = '#/teams/tp1'; });
    await expect(page.locator('#team-detail-view h1')).toHaveText('Payments', { timeout: 15_000 });
    return calls;
  }

  test('header, Entra line, 8 members a page, Remove only for people added by hand', async ({ page }) => {
    await openTeam(page);
    const view = page.locator('#team-detail-view');
    await expect(view.locator('.content-header')).toContainText('payments · cost centre CC-4410 · 1 environment · 14 members');
    await expect(page.locator('#td-entra')).toContainText('Linked to Entra group SG-Payments · 12 members from it');
    const rows = page.locator('#td-member-table tbody tr');
    await expect(rows).toHaveCount(8);
    await expect(page.locator('#td-members .pager-range')).toHaveText('1–8 of 14');
    await expect(page.locator('#td-members')).toContainText('People from the Entra group are removed there.');
    // Acceptance: an Entra member's row has no Remove.
    await expect(rows.first().locator('.td-tag')).toHaveText('Entra');
    await expect(rows.first().locator('[data-td-remove]')).toHaveCount(0);
    await page.locator('#td-members .pager-btn', { hasText: 'Next' }).click();
    await expect(rows).toHaveCount(6);
    await expect(page.locator('#td-member-table .td-tag-manual')).toHaveCount(2);
    await expect(page.getByRole('button', { name: 'Remove Person 12' })).toBeVisible();
    await expect(page.locator('#td-member-table [data-td-remove]')).toHaveCount(2);
    // The sidebar keeps Teams selected.
    await expect(page.locator('#teams-link .sidebar-menu-link')).toHaveClass(/active/);
  });

  test('Sync now shows the counts and reloads the team', async ({ page }) => {
    const calls = await openTeam(page);
    await page.route(/\/api\/v1\/teams\/tp1\/sync$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ added: 2, removed: 1, skippedUnknown: 0 }) }));
    const before = calls.overview;
    await page.locator('#td-sync').click();
    await expect(page.locator('.notification-toast', { hasText: 'Synced: 2 added, 1 removed.' })).toBeVisible();
    await expect.poll(() => calls.overview).toBeGreaterThan(before);
  });

  test('the limits: used of limit with the warning point; Save sends the chosen rule', async ({ page }) => {
    await openTeam(page);
    const hours = page.locator('#td-limits .td-limit[data-metric="VM_HOURS"]');
    await expect(hours).toContainText('3,400 h of 4,000 h');
    await expect(hours.locator('.td-bar')).toHaveAttribute('aria-label', '85% of the vm hours limit used; warning at 80%');
    await expect(hours.locator('.td-bar-fill')).toHaveClass(/td-bar-warn/);
    await expect(page.locator('#td-limits .td-limit[data-metric="COST"]')).toContainText('No limit');
    let sent: any = null;
    await page.route(/\/api\/v1\/teams\/tp1\/quotas$/, route => { sent = route.request().postDataJSON();
      return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }); });
    await hours.locator('[data-td-limit="limit"]').fill('3000');
    await hours.locator('[data-td-limit="enforcement"]').selectOption({ label: 'Block new starts' });
    await page.locator('#td-limits button[type="submit"]').click();
    await expect.poll(() => sent).toEqual([{ metric: 'VM_HOURS', limit: 3000, enforcement: 'BLOCK', warnPercent: 80 }]);
  });

  test('with quotas off there is no limits block and no quota request', async ({ page }) => {
    const calls = await openTeam(page, { quotasOn: false });
    await expect(page.locator('#td-envs-title')).toBeVisible();
    await expect(page.locator('#td-limits')).toHaveCount(0);
    expect(calls.quotas).toEqual([]);
  });

  test('the environments dialog sends the ticked environments', async ({ page }) => {
    await page.route(/\/api\/v1\/environments$/, route => route.request().method() === 'GET'
      ? route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([
        { environmentId: 'env-pay-dev', name: 'pay-dev', displayName: 'Payments Dev', isActive: true, teamId: 'tp1', teamName: 'Payments' },
        { environmentId: 'env-crm', name: 'crm', displayName: 'CRM UAT', isActive: true, teamId: 'other', teamName: 'CRM' }]) }) : route.continue());
    await openTeam(page);
    await expect(page.locator('.td-envs a')).toHaveText('Payments Dev');
    let sent: any = null;
    await page.route(/\/api\/v1\/teams\/tp1\/environments$/, route => { sent = route.request().postDataJSON();
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(TEAM) }); });
    await page.locator('#td-change-envs').click();
    const dialog = page.locator('#teamEnvsModal');
    await expect(dialog.locator('input[value="env-pay-dev"]')).toBeChecked();
    await expect(dialog).toContainText('now in CRM');
    await dialog.locator('input[value="env-crm"]').check();
    await page.locator('#td-envs-save').click();
    await expect.poll(() => sent).toEqual({ environmentIds: ['env-crm', 'env-pay-dev'] });
  });
});

