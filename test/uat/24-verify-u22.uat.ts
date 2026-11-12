import { test, expect, signInAdmin, signInAs, PEOPLE, apiJson, apiGet, readState, writeState, visit, describeVisit, clean } from './guard';

/**
 * U22 on staging, read only (run with UAT_DRY=1): each review fix checked against live data. Facts
 * go to state/verify-u22.json, screenshots to the output folder. Dialogs are opened and cancelled;
 * nothing starts or stops.
 */

const facts: Record<string, any> = {};
const note = (k: string, v: any) => { facts[k] = v; writeState('verify-u22', facts); };
const ise = () => readState<any>('preflight')!.ise.environmentId as string;
const get = async (page: any, u: string) => { const r = await apiGet(page, u); return r.ok() ? r.json() : { status: r.status() }; };

test.describe.configure({ mode: 'serial' });

test('admin: cost, savings, rightsizing, lists, health, drift (T01-T04, T06, T07, T09, T12-T14)', async ({ page }) => {
  test.setTimeout(600_000);
  await page.setViewportSize({ width: 1440, height: 900 });
  await signInAdmin(page);

  // T01 idle waste a month = hourly x 720; the words.
  const idle: any = await get(page, '/api/v1/cost-management/idle-waste?page=0&size=5');
  note('t01_idleSample', JSON.stringify(idle).slice(0, 700));
  let v = await visit(page, '#/cost-management', 'cost-overview');
  note('t01_kpiIdle', await page.locator('[data-kpi="idle"]').innerText().catch(() => null));
  // T14 the line under the title.
  await page.locator('#cost-unregistered button').waitFor({ timeout: 90_000 }).catch(() => {});
  note('t14_line', await page.locator('#cost-unregistered').innerText().catch(() => null));
  note('t14_visit', describeVisit(v));

  // T03 rightsizing: no shrinking a VM that peaks high, no stopped VMs; T09 the counts in words.
  const rs: any = await get(page, '/api/v1/cost-management/rightsizing?page=0&size=100');
  const rows: any[] = rs.content || [];
  note('t03', { total: rs.totalElements ?? rs.page?.totalElements,
    downHot: rows.filter(r => r.direction === 'SCALE_DOWN' && (r.peakCpuUtilization ?? 0) >= 80).map(r => [r.vmName, r.peakCpuUtilization]),
    stopped: rows.filter(r => r.vmStatus === 'STOPPED').length });
  await visit(page, '#/cost-management?tab=rightsizing', 'rightsizing');
  note('t09_rightsizingHead', (await page.locator('#content-area').innerText()).match(/\d+ suggestions? in all[^\n]*/)?.[0] ?? null);

  // T02 savings: no Other, automation named.
  await visit(page, '#/savings', 'savings');
  const savingsText = await page.locator('#content-area').innerText();
  note('t02', { automation: /Automation and budgets/.test(savingsText), other: /\bOther\b/.test(savingsText),
    note: savingsText.match(/Counts only stops the platform made[^\n]*/)?.[0] ?? null });

  // T07 switched-off features say so.
  const caps: any = await get(page, '/api/v1/users/me/capabilities');
  note('t07_caps', { chargeback: caps.chargeback, costAnomalies: caps.costAnomalies, wasteSweep: caps.wasteSweep, email: caps.email });
  await visit(page, '#/chargeback', 'chargeback');
  note('t07_chargeback', (await page.locator('#content-area').innerText()).match(/[^\n]*switched off[^\n]*/i)?.[0] ?? null);
  await visit(page, '#/waste', 'waste');
  note('t07_waste', (await page.locator('#content-area').innerText()).match(/[^\n]*(switched off|Not checked)[^\n]*/i)?.[0] ?? null);

  // T04 ISE: the list's month to date = the environment card's.
  const envs: any[] = await apiJson(page, '/api/v1/environments');
  const iseRow = envs.find(e => e.environmentId === ise());
  const iseCost: any = await get(page, `/api/v1/environments/${ise()}/cost`);
  note('t04', { list: iseRow?.monthToDateCost, card: JSON.stringify(iseCost).slice(0, 400) });
  await visit(page, '#/budgets', 'budgets');
  note('t04_budgets', (await page.locator('#content-area').innerText()).match(/[^\n]*of the \$[^\n]*budget[^\n]*/)?.[0] ?? null);

  // T06 My account as admin.
  await visit(page, '#/my-account', 'my-account');
  note('t06', (await page.locator('#content-area').innerText()).match(/[^\n]*administrator[^\n]*/i)?.[0] ?? null);

  // T08 admin: nothing to request.
  note('t08_availableAdmin', await get(page, '/api/v1/environments/available').then((a: any) => Array.isArray(a) ? a.length : a));

  // T09 users counter, teams, audit rate, Home failed link.
  await visit(page, '#/user-management', 'users');
  note('t09_users', (await page.locator('#content-area').innerText()).match(/[^\n]*signed in yet[^\n]*/i)?.[0] ?? null);
  await visit(page, '#/teams', 'teams');
  note('t09_teams', (await page.locator('#content-area').innerText()).slice(0, 600));
  await visit(page, '#/audit-logs-all', 'audit');
  note('t09_audit', (await page.locator('#content-area').innerText()).match(/[^\n]*succeeded[^\n]*/i)?.[0] ?? null);

  // T12 one drift figure: Home and System health; admin's bell.
  const att: any = await get(page, '/api/v1/dashboard/attention');
  note('t12_attention', { vmsToReview: att.vmsToReview, drift24h: att.health?.driftEvents24h });
  v = await visit(page, '#/home', 'home');
  note('t12_homeTile', await page.locator('.ah-tile[data-tile="vms"]').innerText().catch(() => null));
  note('t09_homeFailedHref', await page.locator('.ah-tile[data-tile="failed"]').getAttribute('href').catch(() => null));
  await visit(page, '#/system-health', 'system-health');
  note('t12_health', (await page.locator('#content-area').innerText()).match(/Changed outside the platform, 24 h\s*\n?\s*\d+/)?.[0] ?? null);
  const notes: any = await get(page, '/api/v1/notifications?page=0&size=50');
  const list: any[] = notes.content || notes.notifications || (Array.isArray(notes) ? notes : []);
  const since = Date.parse('2026-11-12T17:44:00Z');
  const recent = list.filter(n => Date.parse(n.createdAt) >= since);
  note('t12_bell', { recent: recent.map(n => [n.type, n.title, (n.message || '').slice(0, 160)]).slice(0, 10),
    oldStyleSinceDeploy: recent.filter(n => /State drift detected/.test(n.title || '')).length });

  // T13 environments list.
  await visit(page, '#/my-environments', 'env-list');
  const order = page.getByRole('combobox', { name: 'Order' });
  note('t13_orders', await order.locator('option').allInnerTexts());
  await order.selectOption('running');
  await page.waitForTimeout(3000);
  note('t13_runningFirst', await page.locator('#env-list-body tr').evaluateAll(trs => trs.slice(0, 5).map(t => (t as HTMLElement).innerText.replace(/\s+/g, ' ').slice(0, 120))));
  await page.screenshot({ path: test.info().outputPath('env-list-running.png') });
  const csv = await apiGet(page, '/api/v1/environments/export?state=RUNNING');
  const text = await csv.text();
  note('t13_csv', { status: csv.status(), lines: text.split('\r\n').filter(Boolean).length, head: text.split('\r\n').slice(0, 3) });
  note('t13_schedules', await page.locator('#env-list-body td.col-schedule').allInnerTexts());
});

test('admin: ISE start/stop dialog prices the run and names who started (T05, T10), opened and cancelled', async ({ page }) => {
  test.setTimeout(300_000);
  await page.setViewportSize({ width: 1440, height: 900 });
  await signInAdmin(page);
  const groups: any[] = await apiJson(page, `/api/v1/environments/${ise()}/vms`);
  const vms = groups.flatMap(g => g.vms || []);
  note('t10_vms', vms.map(v => [v.displayName || v.name, v.status, v.startedBy ?? null, v.startedAt ?? null]));
  await visit(page, `#/environments/${ise()}`, 'ise');
  const btn = page.locator('#btn-env-action');
  note('t05_button', await btn.innerText().catch(() => null));
  if (await btn.isVisible().catch(() => false)) {
    await btn.click();
    const dialog = page.locator('#operationConfirmModal');
    await dialog.waitFor({ timeout: 20_000 });
    await page.waitForTimeout(4000);
    note('t05_dialog', (await dialog.innerText()).replace(/\n+/g, ' | ').slice(0, 700));
    await page.screenshot({ path: test.info().outputPath('ise-dialog.png') });
    await dialog.getByRole('button', { name: 'Cancel' }).click();
  }
});

test('operator: request access form at 1366 x 657, phone environment page, no drift notices for viewers (T08, T11, T12)', async ({ page }) => {
  test.setTimeout(300_000);
  await page.setViewportSize({ width: 1366, height: 657 });
  await signInAs(page, PEOPLE.operator.user, PEOPLE.operator.pass);
  await visit(page, '#/request-access', 'request-access-1366');
  const send = page.getByRole('button', { name: /Send/ }).first();
  const box = await send.boundingBox().catch(() => null);
  note('t08_form', { sendBottom: box ? box.y + box.height : null, viewport: 657,
    preselected: await page.locator('input[type=radio]:checked').count().catch(() => null) });
  await page.setViewportSize({ width: 390, height: 844 });
  await visit(page, `#/environments/${ise()}`, 'ise-phone');
  const body = await page.locator('#content-area').innerText();
  note('t11_phone', { schedule: /Schedule/.test(body), budgetLine: body.match(/[^\n]*budget[^\n]*/i)?.[0] ?? null,
    overflow: await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth) });
  await page.screenshot({ path: test.info().outputPath('ise-phone.png'), fullPage: true });
});

test('viewer: no drift notices (T12)', async ({ page }) => {
  await signInAs(page, PEOPLE.viewer.user, PEOPLE.viewer.pass);
  const notes: any = await get(page, '/api/v1/notifications?page=0&size=50');
  const list: any[] = notes.content || notes.notifications || (Array.isArray(notes) ? notes : []);
  const since = Date.parse('2026-11-12T17:44:00Z');
  note('t12_viewer', list.filter(n => Date.parse(n.createdAt) >= since && n.type === 'STATE_DRIFT_DETECTED').length);
  expect(true).toBeTruthy();
});
