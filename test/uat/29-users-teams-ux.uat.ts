import { test, signInAdmin, writeState, visit } from './guard';

/** Users and Teams UX on staging, read only: every panel and dialog opened and closed with Cancel / Escape. */

const facts: Record<string, any> = {};
const note = (k: string, v: any) => { facts[k] = v; writeState('users-teams-ux', facts); };
const shot = (page: any, name: string, full = false) => page.screenshot({ path: test.info().outputPath(name + '.png'), fullPage: full });
const dialogText = async (page: any) => (await page.locator('[role="dialog"]:visible, .slideout:visible').last().innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 900);
const close = async (page: any) => {
  const c = page.getByRole('button', { name: /^(Cancel|Close)$/ }).last();
  if (await c.isVisible().catch(() => false)) await c.click(); else await page.keyboard.press('Escape');
  await page.waitForTimeout(700);
};

test('users and teams, laptop 1366x657', async ({ page }) => {
  test.setTimeout(600_000);
  await page.setViewportSize({ width: 1366, height: 657 });
  await signInAdmin(page);

  await visit(page, '#/user-management', 'u-01-list');
  await page.waitForTimeout(2500);
  note('usersRows', await page.locator('table tbody tr').evaluateAll(trs => trs.map(t => (t as HTMLElement).innerText.replace(/\s+/g, ' '))));
  // filters
  for (const [label, value] of [['role', 'Administrator'], ['status', 'Deactivated']]) {
    const sel = page.locator('#content-area select').nth(label === 'role' ? 0 : 1);
    await sel.selectOption({ label: value }).catch(() => {});
    await page.waitForTimeout(1500);
    await shot(page, `u-02-filter-${label}`);
    note(`filter_${label}`, (await page.locator('#content-area').innerText()).match(/\d+–\d+ of \d+|No [^\n]*/)?.[0] ?? null);
    await sel.selectOption({ index: 0 }).catch(() => {});
    await page.waitForTimeout(1000);
  }
  await page.getByPlaceholder(/Name or email/).fill('sayan');
  await page.waitForTimeout(2000);
  await shot(page, 'u-03-search');
  note('search', await page.locator('table tbody tr').evaluateAll(trs => trs.map(t => (t as HTMLElement).innerText.replace(/\s+/g, ' '))));
  await page.getByPlaceholder(/Name or email/).fill('');
  await page.waitForTimeout(1500);

  // Add people
  await page.getByRole('button', { name: /Add people/ }).or(page.getByRole('link', { name: /Add people/ })).first().click();
  await page.waitForTimeout(1500);
  await shot(page, 'u-04-add-people');
  note('addPeople', await dialogText(page));
  await close(page);

  // a Microsoft user's Access, Change role, Deactivate (cancel)
  const msRow = page.locator('table tbody tr', { hasText: 'Not signed in yet' }).first();
  for (const [name, key] of [['Access', 'access'], ['Change role', 'role'], ['Deactivate', 'deactivate']]) {
    const b = msRow.getByRole('button', { name }).or(msRow.getByRole('link', { name }));
    if (await b.count()) {
      await b.first().click();
      await page.waitForTimeout(2000);
      await shot(page, `u-05-${key}`);
      note(`row_${key}`, await dialogText(page) || (await page.locator('#content-area').innerText()).replace(/\s+/g, ' ').slice(0, 500));
      if (key === 'access' && !(await page.locator('[role="dialog"]:visible, .slideout:visible').count())) {
        note('access_hash', await page.evaluate(() => location.hash));
        await visit(page, '#/user-management', 'u-01b-list'); await page.waitForTimeout(2000);
      } else await close(page);
    }
  }
  // a password user's Set password (cancel)
  const pwRow = page.locator('table tbody tr', { hasText: 'Password' }).first();
  const sp = pwRow.getByRole('button', { name: /Set password/ });
  if (await sp.count()) { await sp.click(); await page.waitForTimeout(1500); await shot(page, 'u-06-set-password'); note('setPassword', await dialogText(page)); await close(page); }

  // Teams
  await visit(page, '#/teams', 't-01-list');
  await page.waitForTimeout(4000);
  await shot(page, 't-01-list-full', true);
  await page.getByRole('button', { name: /New team/ }).or(page.getByRole('link', { name: /New team/ })).first().click().catch(() => {});
  await page.waitForTimeout(1500);
  await shot(page, 't-02-new-team');
  note('newTeam', await dialogText(page));
  await close(page);
  const card = page.locator('#content-area .team-card, #content-area article').first();
  const edit = card.getByRole('button', { name: /^Edit/ }).or(card.getByRole('link', { name: /^Edit/ }));
  if (await edit.count()) { await edit.first().click(); await page.waitForTimeout(1500); await shot(page, 't-03-edit-team'); note('editTeam', await dialogText(page)); await close(page); }
  const members = card.getByRole('link', { name: /^Members/ }).or(card.getByRole('button', { name: /^Members/ }));
  if (await members.count()) { await members.first().click(); await page.waitForTimeout(3000); }
  await shot(page, 't-04-team-detail');
  await shot(page, 't-04-team-detail-full', true);
  note('teamDetail', { hash: await page.evaluate(() => location.hash), text: (await page.locator('#content-area').innerText()).replace(/\s+/g, ' ').slice(0, 1200),
    scrollers: await page.evaluate(() => (Array.from(document.querySelectorAll('#content-area *')) as HTMLElement[])
      .filter(e => e.scrollHeight > e.clientHeight + 4 && /(auto|scroll)/.test(getComputedStyle(e).overflowY)).map(e => [e.className.slice(0, 40), e.clientHeight, e.scrollHeight])) });
  const addPeople = page.getByRole('button', { name: /Add people/ }).or(page.getByRole('link', { name: /Add people/ }));
  if (await addPeople.count()) { await addPeople.first().click(); await page.waitForTimeout(1500); await shot(page, 't-05-team-add-people'); note('teamAddPeople', await dialogText(page)); await close(page); }
  const link = page.getByRole('button', { name: /Link a group/ });
  if (await link.count()) { await link.first().click(); await page.waitForTimeout(1500); await shot(page, 't-06-link-group'); note('linkGroup', await dialogText(page)); await close(page); }
});

test('users and teams, phone 390x844', async ({ page }) => {
  test.setTimeout(300_000);
  await page.setViewportSize({ width: 390, height: 844 });
  await signInAdmin(page);
  await visit(page, '#/user-management', 'p-users');
  await page.waitForTimeout(2500);
  await shot(page, 'p-users-full', true);
  note('phoneUsers', { overflow: await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth) });
  await visit(page, '#/teams', 'p-teams');
  await page.waitForTimeout(4000);
  await shot(page, 'p-teams-full', true);
  note('phoneTeams', { overflow: await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth),
    height: await page.evaluate(() => document.documentElement.scrollHeight) });
});
