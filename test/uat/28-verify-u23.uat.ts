import { test, signInAdmin, signInAs, PEOPLE, apiGet, readState, writeState, visit } from './guard';

/** U23 on staging, read only (UAT_DRY=1): each second-review fix against live data. Dialogs opened and cancelled. */

const facts: Record<string, any> = {};
const note = (k: string, v: any) => { facts[k] = v; writeState('verify-u23', facts); };
const ise = () => readState<any>('preflight')!.ise.environmentId as string;
const get = async (page: any, u: string) => { const r = await apiGet(page, u); return r.ok() ? r.json() : { status: r.status() }; };
const text = async (page: any, sel = '#content-area') => (await page.locator(sel).innerText().catch(() => '')).replace(/\s+/g, ' ');

test.describe.configure({ mode: 'serial' });

test('admin: numbers, schedules, bell, drift, savings, waste (T01-T10, T13)', async ({ page }) => {
  test.setTimeout(600_000);
  await page.setViewportSize({ width: 1440, height: 900 });
  await signInAdmin(page);

  // T01 one fleet month to date
  const month: any = await get(page, '/api/v1/cost-management/month');
  const summary: any = await get(page, '/api/v1/dashboard/summary');
  const myCost: any = await get(page, '/api/v1/users/me/cost');
  note('t01_api', { month: JSON.stringify(month).slice(0, 300), homeMtd: summary.monthToDateCost ?? summary.home?.monthToDateCost, myTotal: myCost.monthToDateTotal });
  await visit(page, '#/dashboard', 'home');
  await page.waitForTimeout(3000);
  note('t01_home', (await text(page)).match(/\$[\d,]+ this month[^.]*/)?.[0] ?? null);
  note('t10_tiles', await page.locator('.ah-tile').evaluateAll(ts => ts.map(t => [(t as HTMLElement).dataset.tile, (t as HTMLElement).innerText.replace(/\s+/g, ' '), t.getAttribute('href')])));
  await visit(page, '#/cost-management', 'cost');
  await page.locator('#cost-unregistered button').waitFor({ timeout: 90_000 }).catch(() => {});
  note('t01_costKpis', await page.locator('[data-kpi]').evaluateAll(ks => ks.map(k => (k as HTMLElement).innerText.replace(/\s+/g, ' '))));
  note('t13_unregistered', await page.locator('#cost-unregistered').innerText().catch(() => null));

  // T03 idle: running only
  const idle: any = await get(page, '/api/v1/cost-management/idle-waste?page=0&size=100');
  const rows: any[] = idle.content || [];
  const groups: any = {};
  note('t03_idle', { count: idle.totalElements ?? idle.page?.totalElements, sum: Math.round(rows.reduce((a, r) => a + (r.monthlyIdleCost || 0), 0)),
    durations: rows.slice(0, 8).map(r => r.idleDurationMinutes), sample: rows.slice(0, 3).map(r => [r.vmName, r.monthlyIdleCost]) });

  // T05 rightsizing
  const rs: any = await get(page, '/api/v1/cost-management/rightsizing?page=0&size=100');
  const rsRows: any[] = rs.content || [];
  note('t05', { total: rs.totalElements ?? rs.page?.totalElements, sit55: rsRows.filter(r => /sit-55-(datanode2|morphex-worker1)/.test(r.vmName || '')).map(r => [r.vmName, r.currentInstanceType, r.recommendedInstanceType, r.peakCpuUtilization]) });

  // T02 chargeback current month line
  await visit(page, '#/chargeback', 'chargeback');
  note('t02', (await text(page)).match(/[^.]*from the daily cost snapshots[^.]*\.[^.]*\.?/)?.[0] ?? (await text(page)).slice(0, 400));

  // T06 savings, T07 waste
  await visit(page, '#/savings', 'savings');
  const sav = await text(page);
  note('t06', { onTable: sav.match(/Still on the table[^$]*\$[\d,]+[^·]*/)?.[0] ?? null, parts: sav.match(/Idle VMs \$[\d,]+[^R]*Rightsizing \$[\d,]+[^W]*(Waste \$[\d,]+)?/)?.[0] ?? null, median: /median/i.test(sav) });
  await visit(page, '#/waste', 'waste');
  const w = await text(page);
  note('t07', { everything: w.match(/\$[\d,]+ Everything[^$]{0,40}/)?.[0] ?? w.match(/Everything.{0,60}/)?.[0] ?? null, checkedDaily: /Checked daily/.test(w),
    checkNowDisabled: await page.getByRole('button', { name: /Check again now/ }).isDisabled().catch(() => null) });

  // T04 schedules
  const envPage: any = await get(page, '/api/v1/environments/page?search=ise&size=5');
  const iseRow = (envPage.content || []).find((e: any) => e.environmentId === ise());
  const iseDetail: any = await get(page, `/api/v1/environments/${ise()}`);
  note('t04_api', { list: iseRow && [iseRow.scheduleRuleCount, iseRow.nextScheduledStop, JSON.stringify(iseRow.partialScheduledStop)],
    detail: [iseDetail.scheduleRuleCount, iseDetail.nextScheduledStop, JSON.stringify(iseDetail.partialScheduledStop)] });
  await visit(page, '#/my-environments', 'env-list');
  await page.locator('#env-list-search').fill('ise');
  await page.waitForTimeout(3000);
  note('t04_list', await page.locator('#env-list-body tr').evaluateAll(trs => trs.map(t => (t as HTMLElement).innerText.replace(/\s+/g, ' ').slice(0, 140))));
  await visit(page, `#/environments/${ise()}`, 'ise');
  await page.waitForTimeout(2500);
  note('t04_page', (await text(page)).match(/Schedule.{0,120}/)?.[0] ?? null);
  const btn = page.locator('#btn-env-action');
  if (/Start/.test(await btn.innerText().catch(() => ''))) {
    await btn.click();
    const dialog = page.locator('#operationConfirmModal');
    await dialog.waitFor({ timeout: 20_000 });
    await page.waitForTimeout(4000);
    note('t04_dialog', (await dialog.innerText()).replace(/\n+/g, ' | ').slice(0, 700));
    await page.screenshot({ path: test.info().outputPath('ise-start-dialog.png') });
    await dialog.getByRole('button', { name: 'Cancel' }).click();
  }

  // T08 bell
  const notes: any = await get(page, '/api/v1/notifications?page=0&size=30');
  const list: any[] = notes.content || notes.notifications || (Array.isArray(notes) ? notes : []);
  const unread: any = await get(page, '/api/v1/notifications/unread/count');
  note('t08_bell', { unread: JSON.stringify(unread).slice(0, 100), latest: list.slice(0, 8).map(n => [n.createdAt?.slice(11, 16), n.title, (n.message || '').slice(0, 90)]) });

  // T09 drift
  await visit(page, '#/system-health?tab=reports&changes=outside', 'health-reports');
  await page.waitForTimeout(5000);
  const hr = await text(page);
  note('t09_reports', { header: hr.match(/\d[\d,]* changes? outside the platform[^.]{0,40}/)?.[0] ?? null, pager: hr.match(/\d+–\d+ of [\d,]+/)?.[0] ?? null,
    settled: hr.match(/Outside the platform, finished/) ? 'present' : 'none seen' });
  await page.screenshot({ path: test.info().outputPath('health-reports.png') });
  note('t09_summary', { drifted: summary.driftedVms, text: JSON.stringify(summary).match(/"[^"]*changed outside the platform[^"]*"/)?.[0] ?? null });
});

test('operator: request access speed, bell, phone group buttons (T08, T11, T12)', async ({ page }) => {
  test.setTimeout(300_000);
  await page.setViewportSize({ width: 1366, height: 657 });
  await signInAs(page, PEOPLE.operator.user, PEOPLE.operator.pass);
  const t0 = Date.now();
  await page.evaluate(() => { location.hash = '#/request-access'; });
  await page.locator('#content-area').getByText(/of \d+/).first().waitFor({ timeout: 30_000 }).catch(() => {});
  note('t12', { msToList: Date.now() - t0, pager: await page.locator('#content-area').getByText(/of \d+/).first().innerText().catch(() => null) });
  await page.screenshot({ path: test.info().outputPath('request-access.png') });
  const notes: any = await get(page, '/api/v1/notifications?page=0&size=15');
  const list: any[] = notes.content || notes.notifications || (Array.isArray(notes) ? notes : []);
  note('t08_operatorBell', list.slice(0, 8).map(n => [n.createdAt?.slice(5, 16), n.title]));
  await page.setViewportSize({ width: 390, height: 844 });
  await visit(page, `#/environments/${ise()}`, 'ise-phone');
  await page.waitForTimeout(2500);
  note('t11_phone', { groupButtons: await page.getByRole('button', { name: /(Start|Stop) group/ }).allInnerTexts().catch(() => []),
    overflow: await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth) });
  await page.screenshot({ path: test.info().outputPath('ise-phone.png'), fullPage: true });
});
