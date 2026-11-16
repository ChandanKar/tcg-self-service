import { test, signInAdmin, apiGet, writeState, visit } from './guard';

/** Users and Teams on staging, read only: the data behind the pages and how the pages hold it. Dialogs are opened and cancelled. */

const facts: Record<string, any> = {};
const note = (k: string, v: any) => { facts[k] = v; writeState('users-teams', facts); };

test('users and teams, read only', async ({ page }) => {
  test.setTimeout(600_000);
  await page.setViewportSize({ width: 1366, height: 657 });
  await signInAdmin(page);

  // ---- data
  const users: any[] = await (await apiGet(page, '/api/v1/users?includeInactive=true')).json();
  const list = Array.isArray(users) ? users : (users as any).content || [];
  const domain = (e: string) => String(e || '').split('@')[1]?.toLowerCase() || '(none)';
  const byDomain: Record<string, number> = {};
  list.forEach(u => { byDomain[domain(u.email)] = (byDomain[domain(u.email)] || 0) + 1; });
  const emails = list.map(u => String(u.email || '').toLowerCase());
  note('users', {
    total: list.length, keys: Object.keys(list[0] || {}),
    active: list.filter(u => u.isActive !== false && u.active !== false).length,
    neverSignedIn: list.filter(u => !u.lastLoginAt).length,
    serviceAccounts: list.filter(u => u.serviceAccount).length,
    roles: list.reduce((m: any, u) => { m[u.role] = (m[u.role] || 0) + 1; return m; }, {}),
    authProviders: list.reduce((m: any, u) => { const k = u.authProvider || u.signInMethod || u.provider || '?'; m[k] = (m[k] || 0) + 1; return m; }, {}),
    domains: Object.entries(byDomain).sort((a, b) => b[1] - a[1]).slice(0, 15),
    duplicateEmails: emails.filter((e, i) => e && emails.indexOf(e) !== i).slice(0, 10),
    noName: list.filter(u => !u.displayName || u.displayName === u.email || /^[0-9a-f-]{20,}$/i.test(u.displayName)).length,
    createdBy: list.reduce((m: any, u) => { const k = u.createdVia || u.source || u.origin || '?'; m[k] = (m[k] || 0) + 1; return m; }, {}),
    sample: list.slice(0, 3),
  });
  const teams: any = await (await apiGet(page, '/api/v1/teams/overview')).json();
  const tl = Array.isArray(teams) ? teams : teams.content || teams.teams || [];
  note('teams', tl.map((t: any) => ({ name: t.name, keys: Object.keys(t), memberCount: t.memberCount, envs: (t.environments || []).length,
    envsNoVms: (t.environments || []).filter((e: any) => !(e.vmCount || e.totalVms)).length,
    costCentre: t.costCentre ?? t.costCenter, quota: t.quota ?? t.monthlyVmHourLimit ?? t.vmHoursLimit, entra: t.entraGroupName ?? t.entraGroupId })));
  const envs: any[] = await (await apiGet(page, '/api/v1/environments')).json();
  note('envsWithoutTeam', envs.filter(e => !e.teamId && !e.teamName).length + ' of ' + envs.length);

  // ---- Users page
  await visit(page, '#/user-management', 'users');
  await page.waitForTimeout(2500);
  note('usersPage', { rowsShown: await page.locator('table tbody tr').count(),
    scroll: await page.evaluate(() => document.documentElement.scrollHeight > window.innerHeight),
    filters: await page.locator('#content-area select').evaluateAll(ss => ss.map(s => Array.from((s as HTMLSelectElement).options).map(o => o.text))) });
  // the "haven't signed in yet" counter: does it filter?
  const counter = page.getByText(/haven't signed in yet/).first();
  await counter.click().catch(() => {});
  await page.waitForTimeout(1500);
  note('usersCounterClick', { hash: await page.evaluate(() => location.hash), pager: await page.locator('#content-area').getByText(/of \d+/).first().innerText().catch(() => null) });
  await page.screenshot({ path: test.info().outputPath('users-after-counter.png') });
  await visit(page, '#/user-management', 'users-2');
  await page.waitForTimeout(2000);
  // a Microsoft user's Access, then Change role (Cancel)
  const access = page.getByRole('button', { name: /^Access/ }).first();
  if (await access.count()) {
    await access.click(); await page.waitForTimeout(2500);
    await page.screenshot({ path: test.info().outputPath('user-access.png') });
    note('userAccess', (await page.locator('.slideout, [role="dialog"]').last().innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 600));
    await page.keyboard.press('Escape'); await page.waitForTimeout(800);
  }
  const role = page.getByRole('button', { name: /^Change role/ }).first();
  if (await role.count()) {
    await role.click(); await page.waitForTimeout(1500);
    await page.screenshot({ path: test.info().outputPath('user-change-role.png') });
    note('changeRole', (await page.locator('[role="dialog"]').last().innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 500));
    await page.getByRole('button', { name: 'Cancel' }).last().click().catch(() => page.keyboard.press('Escape'));
  }
  const add = page.getByRole('button', { name: /Add people/ });
  if (await add.count()) {
    await add.click(); await page.waitForTimeout(1500);
    await page.screenshot({ path: test.info().outputPath('user-add.png') });
    note('addPeople', (await page.locator('[role="dialog"], .slideout').last().innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 500));
    await page.keyboard.press('Escape');
  }

  // ---- Teams page
  await visit(page, '#/teams', 'teams');
  await page.waitForTimeout(5000);
  const card = page.locator('#content-area .team-card, #content-area article').first();
  note('teamsPage', { docScroll: await page.evaluate(() => document.documentElement.scrollHeight > window.innerHeight),
    inner: await page.evaluate(() => { const els = Array.from(document.querySelectorAll('#content-area *')) as HTMLElement[];
      return els.filter(e => e.scrollHeight > e.clientHeight + 4 && getComputedStyle(e).overflowY !== 'visible').map(e => [e.className, e.clientHeight, e.scrollHeight]).slice(0, 5); }),
    cardHeight: await card.evaluate(e => (e as HTMLElement).scrollHeight).catch(() => null) });
  await card.evaluate(e => e.scrollIntoView({ block: 'end' })).catch(() => {});
  await page.waitForTimeout(800);
  await page.screenshot({ path: test.info().outputPath('teams-card-end.png') });
  note('teamsCardText', (await card.innerText().catch(() => '')).split('\n').slice(-12));
  // the team's own page
  await card.getByRole('link').first().click().catch(() => card.click().catch(() => {}));
  await page.waitForTimeout(3000);
  await page.screenshot({ path: test.info().outputPath('team-detail.png') });
  note('teamDetail', { hash: await page.evaluate(() => location.hash), text: (await page.locator('#content-area').innerText()).replace(/\s+/g, ' ').slice(0, 700) });
});
