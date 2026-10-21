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

    await expect(page.locator('#team-detail-title')).toHaveText(name, { timeout: 10_000 });
    await expect(page.locator('#teams-cards .tm-card', { hasText: name })).toHaveCount(1); // U13-T07: cards
    await expect(page.locator('#team-detail')).toContainText('cost center CC-42');

    const firstUser = await page.locator('#team-add-user option:not([value=""])').first().getAttribute('value');
    expect(firstUser).toBeTruthy();
    await page.locator('#team-add-user').selectOption(firstUser!);
    await page.locator('[data-action="team-add-member"]').click();
    await expect(page.locator('.team-members')).toContainText('Manual');
  });

  test('the environment modal offers teams instead of a free-text owner', async ({ page }) => {
    await openTeams(page);
    const name = `Picker ${Date.now()}`;
    await page.locator('[data-action="team-new"]').click();
    await page.locator('#team-display').fill(name);
    await page.locator('#team-save-btn').click();
    await expect(page.locator('#team-detail-title')).toHaveText(name, { timeout: 10_000 });

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
    await expect(payments.getByRole('button', { name: 'Members of Payments' })).toBeVisible();
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

