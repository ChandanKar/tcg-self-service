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
    // U20-T08: Cost centre explains itself, on hover and on focus; Escape hides it.
    const tip = page.locator('#team-cc-tip');
    await expect(tip).toBeHidden();
    await page.getByRole('button', { name: 'What Cost centre means' }).hover();
    await expect(tip).toBeVisible();
    await expect(tip).toContainText('The finance code this team');
    await expect(tip).toContainText('cost-center tag');
    await page.mouse.move(0, 0);
    await expect(tip).toBeHidden();
    await page.getByRole('button', { name: 'What Cost centre means' }).focus();
    await expect(tip).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(tip).toBeHidden();
    await expect(page.locator('#teamModal')).toBeVisible(); // the first Escape closes the tip only
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
    await expect(page.locator('#teams-table-body tr').first()).toBeVisible(); // back on the table (U25-T05)
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
 * U25-T05: the Teams table, like Users: counts, search and Show filter, one row per team with its
 * cost centre, members (and Entra group), environments ("1 · 1 running" with a strip while it fits),
 * this month's cost and the VM-hours limit, and Open / Edit / Deactivate. The overview, the
 * environment list and the month by team are faked.
 */
test.describe('Teams table', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  const quota = (used: number, limit: number) => [{ quotaId: 'q', metric: 'VM_HOURS', limit, enforcement: 'WARN', warnPercent: 80, active: true,
    used, percent: Math.round(used / limit * 100) }];
  const envId = (e: string) => 'env-' + e.toLowerCase().replace(/ /g, '-');
  const team = (id: string, name: string, cc: string | null, members: number, envs: string[], quotas: any[], entra?: string) => ({
    teamId: id, name: name.toLowerCase(), displayName: name, costCenter: cc, active: true, memberCount: members,
    entraGroupId: entra ? 'g-' + id : null, entraGroupName: entra || null, entraMemberCount: entra ? members : 0, lastSyncedAt: null,
    environments: envs.map(e => ({ environmentId: envId(e), name: e.toLowerCase(), displayName: e, production: false })),
    quotas });
  const TEAMS = [
    team('t1', 'Payments', 'CC-4105', 6, ['Payments Dev'], quota(1840, 4000), 'Payments Engineering'),
    team('t2', 'Data', 'CC-4410', 9, ['Data Lake Dev', 'Analytics QA'], quota(5120, 6000)),
    team('t3', 'CRM', 'CC-4300', 4, ['CRM UAT'], quota(2890, 3000)),
    team('t4', 'People', null, 0, [], [])];
  const TEAM_OF: Record<string, string> = { 'Payments Dev': 't1', 'Data Lake Dev': 't2', 'Analytics QA': 't2', 'CRM UAT': 't3' };
  const ENVS = ['Payments Dev', 'Data Lake Dev', 'Analytics QA', 'CRM UAT', 'Legacy'].map((e, i) => ({ environmentId: envId(e),
    name: e.toLowerCase(), displayName: e, isActive: true, teamId: TEAM_OF[e] || null, vmCount: 3, runningVmCount: i === 1 ? 0 : 2,
    groupStates: [{ groupId: 'g' + i, name: 'app', sequencePosition: 1, total: 3, running: i === 1 ? 0 : 2, busy: 0, stopped: i === 1 ? 3 : 1, problem: 0, unknown: 0 }] }));
  const MONTH = [{ dimensionKey: 'Payments', dimensionLabel: 'Payments', cost: 1234.5, vmCount: null },
    { dimensionKey: 'Unassigned', dimensionLabel: 'Unassigned', cost: 12, vmCount: null }];

  async function openTable(page: Page, quotasOn: boolean, teams: any[] = TEAMS) {
    await page.route(/\/api\/v1\/users\/me\/capabilities$/, async route => {
      const response = await route.fetch();
      const caps = await response.json();
      return route.fulfill({ response, body: JSON.stringify({ ...caps, features: { ...caps.features, quotas: quotasOn } }) });
    });
    await page.route(/\/api\/v1\/teams\/overview$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify(quotasOn ? teams : teams.map(t => ({ ...t, quotas: [] }))) }));
    await page.route(/\/api\/v1\/environments(\?includeGroups=true)?$/, route => route.request().method() === 'GET'
      ? route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ENVS) }) : route.continue());
    await page.route(/\/api\/v1\/cost-management\/month-by-team$/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify(MONTH) }));
    await openTeams(page);
  }

  test('counts and one row per team: cost centre, members, environments, this month, VM hours', async ({ page }) => {
    await openTable(page, true);
    const counts = page.locator('#teams-counts');
    await expect(counts).toContainText('4 teams');
    await expect(counts).toContainText('1 without a cost centre');
    await expect(counts).toContainText('1 with no members');
    await expect(counts).toContainText('1 environment without a team');

    const rows = page.locator('#teams-table-body tr');
    await expect(rows).toHaveCount(4);
    const payments = rows.filter({ hasText: 'Payments' });
    await expect(payments.locator('.tm-name')).toHaveAttribute('href', '#/teams/t1');
    await expect(payments.locator('td[data-label="Cost centre"]')).toHaveText('CC-4105');
    await expect(payments.locator('td[data-label="Members"]')).toContainText('6 people');
    await expect(payments.locator('td[data-label="Members"]')).toContainText('synced from Entra group "Payments Engineering"');
    await expect(payments.locator('td[data-label="Environments"]')).toContainText('1 · 1 running');
    await expect(payments.locator('td[data-label="Environments"] .power-strip')).toBeVisible();
    await expect(payments.locator('td[data-label="This month"]')).toHaveText('$1,234.50');
    await expect(payments.locator('td[data-label="Monthly limit"]')).toContainText('1,840 / 4,000 h');
    await expect(payments.getByRole('link', { name: 'Open Payments' })).toHaveAttribute('href', '#/teams/t1');
    await expect(rows.filter({ hasText: 'Data Lake' })).toHaveCount(0); // environments are counted, not listed
    await expect(rows.filter({ hasText: 'CC-4410' }).locator('td[data-label="Environments"]')).toContainText('2 · 1 running');
    // 96% of the hours: red.
    await expect(rows.filter({ hasText: 'CRM' }).locator('.tm-over')).toHaveText('2,890 / 3,000 h');
    const people = rows.filter({ has: page.locator('.tm-name', { hasText: 'People' }) }); // not "6 people" in other rows
    await expect(people.locator('td[data-label="Cost centre"]')).toHaveText('—');
    await expect(people.locator('td[data-label="Environments"]')).toHaveText('None yet');
    await expect(people.locator('td[data-label="This month"]')).toHaveText('—');
    await expect(people.locator('td[data-label="Monthly limit"]')).toHaveText('No limit');
  });

  test('search and Show filter the rows; Show all teams clears them', async ({ page }) => {
    await openTable(page, true);
    const rows = page.locator('#teams-table-body tr');
    await page.locator('#teams-show').selectOption({ label: 'Linked to Entra' });
    await expect(rows).toHaveCount(1);
    await expect(rows.first()).toContainText('Payments');
    await page.locator('#teams-show').selectOption({ label: 'All teams' });
    await page.locator('#teams-search').fill('cc-43');
    await expect(rows).toHaveCount(1);
    await expect(rows.first()).toContainText('CRM');
    await page.locator('#teams-search').fill('nothing like it');
    await expect(page.locator('#teams-table-body')).toContainText('No teams match these filters');
    await page.getByRole('button', { name: 'Show all teams' }).click();
    await expect(rows).toHaveCount(4);
  });

  test('Edit opens the team dialog; Deactivate asks first', async ({ page }) => {
    await openTable(page, true);
    await page.getByRole('button', { name: 'Edit CRM' }).click();
    await expect(page.locator('#teamModal')).toBeVisible();
    await expect(page.locator('#team-cost-center')).toHaveValue('CC-4300');
    // Escape closes the dialog once it has focus (Bootstrap moves focus in when the fade-in ends).
    await expect(page.locator('#team-display')).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(page.locator('#teamModal')).toBeHidden();
    await page.getByRole('button', { name: 'Deactivate People' }).click();
    await expect(page.locator('.modal.show')).toContainText('Deactivate People? It must own no active environments.');
  });

  test('many teams page to fit the window, with no page scrollbar at 1440 x 900', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    const many = Array.from({ length: 25 }, (_, i) => team(`m${i}`, `Team ${String(i).padStart(2, '0')}`, `CC-${i}`, 1, [], []));
    await openTable(page, false, many);
    await expect(page.locator('#teams-pagination .pager-range')).toContainText('of 25');
    await page.waitForTimeout(500); // the rows a page settle after a resize
    const fit = await page.evaluate(() => {
      const main = document.getElementById('mainContent')!;
      return { page: document.scrollingElement!.scrollHeight, window: window.innerHeight, main: main.scrollHeight, mainBox: main.clientHeight,
        bottom: document.getElementById('teams-panel')!.getBoundingClientRect().bottom };
    });
    expect(fit.page).toBeLessThanOrEqual(fit.window + 1);
    expect(fit.main).toBeLessThanOrEqual(fit.mainBox + 1);
    expect(fit.bottom).toBeLessThanOrEqual(fit.window);
  });

  test('with quotas off every row says No limit and no quota request is sent', async ({ page }) => {
    const quotaCalls: string[] = [];
    page.on('request', r => { if (/\/quotas|quota-usage/.test(r.url())) quotaCalls.push(r.url()); });
    await openTable(page, false);
    await expect(page.locator('#teams-table-body tr')).toHaveCount(4);
    await expect(page.locator('#teams-table-body td[data-label="Monthly limit"]', { hasText: 'No limit' })).toHaveCount(4);
    await expect(page.locator('.tm-over')).toHaveCount(0);
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

  async function openTeam(page: Page, opts: { quotasOn?: boolean, team?: any } = {}) {
    const calls = { overview: 0, quotas: [] as string[] };
    await page.route(/\/api\/v1\/users\/me\/capabilities$/, async route => {
      const response = await route.fetch();
      const caps = await response.json();
      return route.fulfill({ response, body: JSON.stringify({ ...caps, features: { ...caps.features, quotas: opts.quotasOn !== false } }) });
    });
    await page.route(/\/api\/v1\/teams\/tp1\/overview$/, route => { calls.overview++;
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(opts.team || TEAM) }); });
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

  test('the environments table: VMs running and this month, search, paged (U25-T05)', async ({ page }) => {
    const many = Array.from({ length: 30 }, (_, i) => ({ environmentId: `env-${i}`, name: `env-${i}`,
      displayName: `Env ${String(i).padStart(2, '0')}`, production: false }));
    await page.route(/\/api\/v1\/environments\/page\?/, route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ content: [{ environmentId: 'env-pay-dev', name: 'pay-dev', displayName: 'Payments Dev', vmCount: 5, runningVmCount: 3,
        monthToDateCost: 412.4 }], page: { size: 100, number: 0, totalElements: 1, totalPages: 1 } }) }));
    await openTeam(page, { quotasOn: false, team: { ...TEAM, environments: [TEAM.environments[0], ...many] } });
    const rows = page.locator('#td-env-body tr');
    const pay = rows.filter({ hasText: 'Payments Dev' });
    await expect(pay.locator('td[data-label="VMs running"]')).toHaveText('3 of 5');
    await expect(pay.locator('td[data-label="This month"]')).toHaveText('$412.40');
    await expect(pay.locator('a')).toHaveAttribute('href', '#/environments/env-pay-dev');
    await expect(page.locator('#td-env-pager .pager-range')).toContainText('of 31');
    await page.locator('#td-env-search').fill('env 07');
    await expect(rows).toHaveCount(1);
    await expect(rows.first()).toContainText('Env 07');
    await page.locator('#td-env-search').fill('nothing like it');
    await expect(page.locator('#td-env-body')).toContainText('No environments match');
    await page.getByRole('button', { name: 'Show all environments' }).click();
    await expect(page.locator('#td-env-pager .pager-range')).toContainText('of 31');
    await expect(page.locator('#td-change-envs')).toBeVisible();
  });

  test('the environments dialog sends the ticked environments', async ({ page }) => {
    await page.route(/\/api\/v1\/environments$/, route => route.request().method() === 'GET'
      ? route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([
        { environmentId: 'env-pay-dev', name: 'pay-dev', displayName: 'Payments Dev', isActive: true, teamId: 'tp1', teamName: 'Payments' },
        { environmentId: 'env-crm', name: 'crm', displayName: 'CRM UAT', isActive: true, teamId: 'other', teamName: 'CRM' }]) }) : route.continue());
    await openTeam(page);
    await expect(page.locator('#td-env-table a')).toHaveText('Payments Dev');
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

