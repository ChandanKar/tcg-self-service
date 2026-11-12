import { type Page } from '@playwright/test';
import { test, expect, signInAdmin, signInAs, PEOPLE, apiJson, apiGet, readState, visit, verdict } from './guard';

/** Read-only acceptance checks on 2026-11-12 (dbb1206): J11, J17, J19, J22, L05, L10 (Help part), L16. */
const iseId = () => readState<any>('preflight')!.ise.environmentId as string;
const note = (type: string, v: unknown) => test.info().annotations.push({ type, description: typeof v === 'string' ? v : JSON.stringify(v) });

async function download(page: Page, click: () => Promise<void>): Promise<{ name: string; bytes: number; xlsx: boolean } | null> {
  const dl = page.waitForEvent('download', { timeout: 30_000 }).catch(() => null);
  await click();
  const d = await dl;
  if (!d) return null;
  const path = test.info().outputPath(d.suggestedFilename());
  await d.saveAs(path);
  const buf = require('fs').readFileSync(path);
  return { name: d.suggestedFilename(), bytes: buf.length, xlsx: buf.slice(0, 2).toString() === 'PK' };
}

test('[J11] the audit log has today\'s ise actions with names; filters narrow; export opens', async ({ page }) => {
  await signInAdmin(page);
  const all: any = await apiJson(page, `/api/v1/audit/logs?environmentId=${iseId()}&size=200`);
  const rows: any[] = all.content || [];
  const today = rows.filter(r => String(r.createdAt || r.timestamp || '').startsWith('2026-11-12'));
  const actions = [...new Set(today.map(r => r.action))];
  const named = today.filter(r => r.userDisplayName || r.actorName || r.userName || r.performedByName).length;
  const lockOnly: any = await apiJson(page, `/api/v1/audit/logs?environmentId=${iseId()}&action=LOCK_ACQUIRED&size=50`);
  const lockRows: any[] = lockOnly.content || [];
  note('today', { count: today.length, actions, named, sample: today.slice(0, 4).map(r => ({ a: r.action, who: r.userDisplayName || r.actorName || r.userName, at: r.createdAt || r.timestamp, d: String(r.details || '').slice(0, 80) })) });
  note('filter', { lockRows: lockRows.length, allLock: lockRows.every(r => r.action === 'LOCK_ACQUIRED') });
  await visit(page, '#/audit-logs-all', 'j11-audit');
  const exportBtn = page.locator('#content-area').getByRole('button', { name: /export/i }).first();
  let file = null;
  if (await exportBtn.count()) {
    file = await download(page, async () => {
      await exportBtn.click();
      const item = page.locator('.dropdown-menu.show, [role="menu"]').getByText(/excel|xlsx|csv/i).first();
      if (await item.count()) await item.click();
    });
  }
  note('export', file);
  const ok = today.length > 0 && named === today.length && lockRows.length > 0 && lockRows.every(r => r.action === 'LOCK_ACQUIRED') && !!file && file.bytes > 0;
  verdict(ok ? 'accepted' : 'rejected', `ise on 2026-11-12: ${today.length} audit rows today (${actions.join(', ')}), ${named} with a name; filtering by LOCK_ACQUIRED gives ${lockRows.length} rows, all that action; export ${file ? `${file.name}, ${file.bytes} bytes${file.xlsx ? ', a real xlsx' : ''}` : 'not found'}.`);
  expect(ok).toBeTruthy();
});

const LAPTOP_ROUTES: Record<string, string[]> = {
  admin: ['#/dashboard', '#/my-environments', '#/environments/ISE', '#/cost-management', '#/access-management', '#/audit-logs-all', '#/system-health'],
  operator: ['#/dashboard', '#/my-environments', '#/environments/ISE', '#/activity-logs', '#/request-access'],
  viewer: ['#/dashboard', '#/my-environments', '#/environments/ISE', '#/activity-logs'],
};

test('[J17] menu and pages on a laptop screen (1366 x 657) for admin, operator and viewer', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 657 });
  const problems: string[] = [];
  const checked: Record<string, number> = {};
  for (const who of ['admin', 'operator', 'viewer']) {
    if (who === 'admin') await signInAdmin(page);
    else await signInAs(page, (PEOPLE as any)[who].user, (PEOPLE as any)[who].pass);
    for (const r of LAPTOP_ROUTES[who]) {
      const route = r.replace('ISE', iseId());
      await visit(page, route, `j17-${who}-${route.replace(/[#/]/g, '_').slice(0, 40)}`);
      await page.waitForTimeout(800);
      const m = await page.evaluate(() => {
        const doc = document.scrollingElement!;
        const main = document.querySelector('#content-area') as HTMLElement;
        const side = document.querySelector('.sidebar') as HTMLElement;
        const links = [...document.querySelectorAll('.sidebar .sidebar-menu-link')].filter(l => (l as HTMLElement).offsetParent);
        const sb = side.getBoundingClientRect();
        const cut = [...main.querySelectorAll('*')].filter(el => {
          const s = getComputedStyle(el); if (!/(hidden|clip)/.test(s.overflowY + s.overflowX) || s.textOverflow === 'ellipsis') return false;
          const e = el as HTMLElement; return e.clientHeight > 0 && (e.scrollHeight > e.clientHeight + 2 && /(hidden|clip)/.test(s.overflowY)) ;
        }).length;
        return { pageScroll: doc.scrollHeight - doc.clientHeight, mainScroll: main.scrollHeight - main.clientHeight,
          menuScroll: side.scrollHeight - side.clientHeight, linksOut: links.filter(l => l.getBoundingClientRect().bottom > sb.bottom + 1).length, cut };
      });
      checked[who] = (checked[who] || 0) + 1;
      if (m.pageScroll > 1 || m.menuScroll > 1 || m.linksOut > 0) problems.push(`${who} ${r}: ${JSON.stringify(m)}`);
      else if (m.mainScroll > 1 || m.cut > 0) note(`${who} ${r}`, m);
    }
    await page.context().clearCookies();
  }
  note('checked', checked);
  note('problems', problems);
  verdict(problems.length ? 'rejected' : 'accepted', problems.length
    ? `At 1366 x 657: ${problems.join(' | ').slice(0, 900)}`
    : `At 1366 x 657 (the owner's laptop at 100%), ${Object.values(checked).reduce((a, b) => a + b, 0)} pages for admin, operator and viewer: every menu item inside the menu, no menu scrollbar and no page scrollbar.`);
  expect(problems).toEqual([]);
});

test('[J19] Cost: one menu entry, four pages', async ({ page }) => {
  await signInAdmin(page);
  await visit(page, '#/cost-management', 'j19-spend');
  const costLinks = await page.locator('.sidebar .sidebar-menu-link:visible').filter({ hasText: /cost|savings|waste|chargeback/i }).allInnerTexts();
  const switcher = (await page.locator('.page-section-item').allInnerTexts()).map(s => s.trim());
  const marks: Record<string, any> = {};
  for (const [label, hash] of [['Savings', '#/savings'], ['Waste', '#/waste'], ['Chargeback', '#/chargeback'], ['Spend', '#/cost-management']]) {
    await page.locator('.page-section-item', { hasText: label }).first().click();
    await page.waitForTimeout(1500);
    marks[label] = {
      hash: await page.evaluate(() => location.hash.split('?')[0]),
      current: (await page.locator('.page-section-item[aria-current="page"]').allInnerTexts()).map(s => s.trim()),
      menu: (await page.locator('.sidebar .sidebar-menu-link.active:visible, .sidebar .sidebar-menu-link[aria-current="page"]:visible').allInnerTexts()).map(s => s.trim()),
    };
    await page.screenshot({ path: test.info().outputPath(`j19-${label}.png`) });
  }
  const firstTab = (await page.locator('#content-area [role="tab"]').first().innerText().catch(() => '')).trim();
  note('j19', { costLinks, switcher, marks, firstTab });
  const ok = costLinks.length === 1 && switcher.join('|') === 'Spend|Savings|Waste|Chargeback'
    && Object.entries(marks).every(([l, m]: any) => m.current.includes(l) && m.menu.some((x: string) => /cost/i.test(x)));
  verdict(ok ? 'accepted' : 'rejected', `Menu Cost entries: ${JSON.stringify(costLinks)}; switcher ${switcher.join(' · ')}; each page marks itself in the switcher and keeps 'Cost management' marked in the menu: ${JSON.stringify(marks).slice(0, 400)}; Cost management's first tab '${firstTab}'.`);
  expect(ok).toBeTruthy();
});

test('[J22] regions and machines found in AWS', async ({ page }) => {
  await signInAdmin(page);
  const envs: any[] = await apiJson(page, '/api/v1/environments');
  const withRegion = envs.filter(e => e.region || e.awsRegion || (e.metadata && e.metadata.region)).length;
  const regions: any = await apiJson(page, '/api/v1/environments/regions');
  const mon: any = await apiJson(page, '/api/v1/monitoring/regions').catch(() => null);
  const found: any = await apiJson(page, '/api/v1/discovery/found').catch(() => null);
  const foundList = Array.isArray(found) ? found : (found?.instances || found?.content || []);
  await visit(page, '#/my-environments', 'j22-environments');
  const filter = page.locator('#content-area select, #content-area [data-filter="region"], #content-area button').filter({ hasText: /region/i }).first();
  const filterShown = await filter.count();
  await visit(page, '#/system-health', 'j22-health');
  await visit(page, '#/vm-registry', 'j22-registry');
  note('j22', { envs: envs.length, withRegion, regions, monRegions: mon ? (mon.regions || mon).length ?? 'n/a' : null, found: foundList.length, filterShown });
  const ok = envs.length > 0 && withRegion > 0 && (Array.isArray(regions) ? regions.length : 0) > 0 && found !== null;
  verdict(ok ? 'accepted' : 'blocked', `${withRegion} of ${envs.length} environments carry a region; regions in use ${JSON.stringify(regions).slice(0, 120)}; System health regions ${mon ? 'listed' : 'not answered'}; Found in AWS lists ${foundList.length} unregistered instances; region filter on Environments ${filterShown ? 'shown' : 'not found by the check (see screenshot)'}.`);
  expect(ok).toBeTruthy();
});

test('[L05] Cost Management page and its Excel exports', async ({ page }) => {
  await signInAdmin(page);
  await visit(page, '#/cost-management', 'l05-cost');
  const files: Record<string, any> = {};
  for (const [name, url] of [['vm-detail', '/api/v1/cost-management/vm-detail/export'], ['rightsizing', '/api/v1/cost-management/rightsizing/export'], ['idle-waste', '/api/v1/cost-management/idle-waste/export']]) {
    const r = await apiGet(page, url);
    const body = await r.body();
    files[name] = { status: r.status(), type: r.headers()['content-type'], bytes: body.length, xlsx: body.slice(0, 2).toString() === 'PK' };
  }
  const menuFile = await download(page, async () => {
    await page.locator('#content-area').getByRole('button', { name: /export/i }).first().click();
    await page.locator('.dropdown-menu.show, [role="menu"]').getByText(/VM cost detail/i).first().click();
  });
  note('l05', { files, menuFile });
  const ok = Object.values(files).every((f: any) => f.status === 200 && f.xlsx) && !!menuFile && menuFile.xlsx;
  verdict(ok ? 'accepted' : 'rejected', `Cost management loads its panels (estimates; actuals off). Exports: ${Object.entries(files).map(([k, f]: any) => `${k} ${f.status} ${f.bytes} bytes${f.xlsx ? ' xlsx' : ''}`).join('; ')}; Export menu › VM cost detail downloads ${menuFile ? `${menuFile.name} (${menuFile.bytes} bytes${menuFile.xlsx ? ', xlsx' : ''})` : 'nothing'}.`);
  expect(ok).toBeTruthy();
});

test('[L16] EKS environments (view only)', async ({ page }) => {
  await signInAdmin(page);
  const envs: any[] = await apiJson(page, '/api/v1/environments');
  const eks = envs.filter(e => String(e.cloudProvider || e.provider || '').includes('EKS'));
  note('eks', eks.map(e => e.name));
  if (!eks.length) { verdict('blocked', 'No EKS environment on staging.'); return; }
  await visit(page, '#/environments/' + eks[0].environmentId, 'l16-eks');
  const rows = await page.locator('.env-eks-table .env-vm-row').count();
  const text = (await page.locator('.env-eks-table').innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 300);
  verdict('blocked', `EKS environment ${eks[0].name}: ${rows} node group rows (${text}). Scaling to 0 and back not exercised: no EKS start/stop in acceptance (test VM allowlist).`);
});
