import { test, signInAdmin, apiGet, readState, writeState, visit } from './guard';

/** U22 on staging, read only: the checks 24-verify-u22 could not reach (routes, split labels). */

const facts: Record<string, any> = {};
const note = (k: string, v: any) => { facts[k] = v; writeState('verify-u22b', facts); };
const ise = () => readState<any>('preflight')!.ise.environmentId as string;
const text = async (page: any) => (await page.locator('#content-area').innerText()).replace(/\s+/g, ' ');

test('admin follow-up: Home, My account, Users, Teams, Audit, System health, ISE in the list, Budgets', async ({ page }) => {
  test.setTimeout(600_000);
  await page.setViewportSize({ width: 1440, height: 900 });
  await signInAdmin(page);

  await visit(page, '#/dashboard', 'home');
  await page.locator('.ah-tile').first().waitFor({ timeout: 60_000 }).catch(() => {});
  note('home_tiles', await page.locator('.ah-tile').evaluateAll(ts => ts.map(t => [(t as HTMLElement).dataset.tile, (t as HTMLElement).innerText.replace(/\s+/g, ' '), t.getAttribute('href')])));
  note('home_health', await page.locator('.ah-health').innerText().catch(() => null));

  await page.evaluate(() => eval("MyAccount.open('access')"));
  await page.waitForTimeout(4000);
  note('my_access', (await page.locator('body').innerText()).match(/You manage every environment[^\n]*/)?.[0] ?? null);
  await page.screenshot({ path: test.info().outputPath('my-account.png') });
  await page.keyboard.press('Escape');

  await visit(page, '#/user-management', 'users');
  await page.waitForTimeout(3000);
  note('users', (await text(page)).match(/.{0,120}signed in yet.{0,40}/i)?.[0] ?? null);

  await visit(page, '#/teams', 'teams');
  await page.waitForTimeout(6000);
  note('teams', (await text(page)).slice(0, 900));

  await visit(page, '#/audit-logs-all', 'audit');
  await page.waitForTimeout(4000);
  note('audit', (await text(page)).match(/.{0,80}succeeded.{0,60}/)?.[0] ?? null);

  await visit(page, '#/system-health', 'system-health');
  await page.waitForTimeout(4000);
  note('health', (await text(page)).match(/Changed outside the platform, 24 h.{0,80}/)?.[0] ?? null);

  const r = await apiGet(page, '/api/v1/environments/page?search=ise&size=5');
  const pageBody: any = await r.json();
  note('ise_list', (pageBody.content || []).filter((e: any) => e.environmentId === ise()).map((e: any) => [e.monthToDateCost, e.scheduleRuleCount, e.nextScheduledStop]));

  await visit(page, '#/budgets', 'budgets');
  await page.waitForTimeout(3000);
  note('budgets', (await text(page)).match(/.{0,60}of the \$.{0,60}/)?.[0] ?? (await text(page)).slice(0, 400));

  await visit(page, '#/request-access', 'request-access-admin');
  note('request_admin', (await text(page)).slice(0, 300));
});
