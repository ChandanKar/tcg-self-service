import { test, signInAdmin, apiGet, readState, writeState, visit } from './guard';

/**
 * U24 + U25 on staging, read only (UAT_DRY=1). Add people is typed into and previewed (dryRun) but
 * never applied; Reset password is opened and cancelled; Teams and the U24 figures are only read.
 */
const facts: Record<string, any> = {};
const note = (k: string, v: any) => { facts[k] = v; writeState('verify-u25', facts); };
const ise = () => readState<any>('preflight')!.ise.environmentId as string;
const get = async (page: any, u: string) => { const r = await apiGet(page, u); return r.ok() ? r.json() : { status: r.status() }; };
const text = async (page: any, sel = '#content-area') => (await page.locator(sel).innerText().catch(() => '')).replace(/\s+/g, ' ');
const shot = (page: any, name: string) => page.screenshot({ path: test.info().outputPath(name + '.png') });

test.describe.configure({ mode: 'serial' });

test('U25: Add people (previewed, never applied), Reset password (cancelled), Teams tables', async ({ page, guard }) => {
  test.setTimeout(600_000);
  guard.allow(/\/api\/v1\/users\/bulk-onboard$/);
  // Only the preview may go out: any apply (dryRun=false) is aborted here, before the guard.
  await page.route(/\/api\/v1\/users\/bulk-onboard\?dryRun=false/, route => route.abort());
  let applied = 0;
  page.on('request', r => { if (/bulk-onboard\?dryRun=false/.test(r.url()) || /\/password\/reset$/.test(r.url())) applied++; });
  await page.setViewportSize({ width: 1366, height: 657 });
  await signInAdmin(page);

  await visit(page, '#/user-management/add', 'a-01');
  await page.locator('#adp-paste').waitFor({ timeout: 60_000 });
  await page.locator('#adp-paste').fill('pat.example@tcgdigital.com, viraj.gore@lummustech.com, someone@tcgdigitalc.com, notanemail, ');
  await page.waitForTimeout(3000);
  note('chips', await page.locator('#adp-chips .adp-chip, .adp-chips .adp-chip').allInnerTexts().catch(() => []));
  await page.getByRole('radio', { name: /Environment admin/ }).click().catch(() => {});
  await page.waitForTimeout(1500);
  note('warn', await page.locator('#adp-envadmin-warn').isVisible().catch(() => null));
  await page.waitForTimeout(2500);
  note('summary', await text(page, '.adp-bar, #adp-summary, [aria-label="Summary"]'));
  note('scroll', await page.evaluate(() => document.documentElement.scrollHeight > window.innerHeight));
  await shot(page, 'a-02-add-people');
  await page.getByRole('radio', { name: /Email and a password/ }).click().catch(() => {});
  await page.waitForTimeout(1000);
  await shot(page, 'a-03-password');
  note('pwNote', (await text(page)).match(/Each person gets their own generated password[^.]*\./)?.[0] ?? null);

  // Users: Reset password opened and cancelled
  await page.evaluate(() => { location.hash = '#/user-management'; });
  await page.waitForTimeout(1500);
  const leave = page.getByRole('button', { name: /^(Leave|Discard|Leave the page)/ });
  if (await leave.count()) await leave.first().click();
  await page.waitForTimeout(3000);
  const reset = page.getByRole('button', { name: /^Reset password/ }).first();
  note('resetButtons', await page.getByRole('button', { name: /^Reset password/ }).count());
  if (await reset.count()) {
    await reset.click(); await page.waitForTimeout(1500);
    note('resetDialog', await text(page, '.modal.show'));
    await shot(page, 'u-01-reset-confirm');
    await page.locator('.modal.show').getByRole('button', { name: 'Cancel' }).click();
    await page.waitForTimeout(800);
  }

  // Teams
  await visit(page, '#/teams', 't-01');
  await page.waitForTimeout(4000);
  note('teams', { counts: (await text(page)).match(/\d+ teams?[^.]*?environments? without a team/)?.[0] ?? null,
    rows: await page.locator('#teams-table-body tr').evaluateAll(trs => trs.map(t => (t as HTMLElement).innerText.replace(/\s+/g, ' '))),
    scroll: await page.evaluate(() => document.documentElement.scrollHeight > window.innerHeight) });
  await shot(page, 't-01-teams');
  await page.locator('#teams-table-body a').first().click().catch(() => {});
  await page.waitForTimeout(5000);
  note('teamPage', { text: (await text(page)).slice(0, 600), pager: (await text(page)).match(/\d+–\d+ of \d+/)?.[0] ?? null,
    scroll: await page.evaluate(() => document.documentElement.scrollHeight > window.innerHeight) });
  await shot(page, 't-02-team-page');
  note('appliedOrReset', applied);
});

test('U24: figures', async ({ page }) => {
  test.setTimeout(300_000);
  await page.setViewportSize({ width: 1366, height: 657 });
  await signInAdmin(page);
  const month: any = await get(page, '/api/v1/cost-management/month');
  const byTeam: any = await get(page, '/api/v1/cost-management/month-by-team');
  const budgets: any = await get(page, '/api/v1/budgets');
  const iseCost: any = await get(page, `/api/v1/environments/${ise()}/cost`);
  note('u24', { fleet: month.monthToDate, forecast: month.forecast, byTeam: JSON.stringify(byTeam).slice(0, 300),
    iseCard: iseCost.monthToDateEstimated, iseForecast: iseCost.forecastMonthEnd,
    budgets: JSON.stringify(Array.isArray(budgets) ? budgets.slice(0, 2) : budgets).slice(0, 500) });
  await visit(page, '#/savings', 'savings');
  note('savings', (await text(page)).match(/Still on the table.{0,160}/)?.[0] ?? null);
  await visit(page, '#/system-health?tab=reports', 'reports');
  await page.waitForTimeout(4000);
  note('reports', (await text(page)).match(/\d[\d,+]* changes? in 24 hours[^.]{0,60}|\d[\d,]* changes? outside the platform in 24 hours/)?.[0] ?? null);
});
