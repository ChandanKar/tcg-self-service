import * as fs from 'fs';
import { test, expect, signInAdmin, apiGet, readState, visit, describeVisit, type Visit } from './guard';
import type { Page } from '@playwright/test';

/**
 * CloudOps review (read-only): the daily jobs of the team that runs the AWS fleet, as the
 * administrator on the owner's laptop size. Screenshots every step; notes (load times, on-page
 * text, API cross-checks) go to notes.json in this run's output folder. Nothing is changed:
 * dialogs and menus are opened and closed with Cancel / Escape only.
 */
test.use({ viewport: { width: 1366, height: 657 } });

const notes: Record<string, any> = {};
const problems: string[] = [];

async function step(name: string, fn: () => Promise<any>) {
  const t0 = Date.now();
  try {
    const r = await fn();
    notes[name] = Object.assign({ ms: Date.now() - t0 }, r && typeof r === 'object' ? r : { value: r });
  } catch (e: any) {
    notes[name] = { ms: Date.now() - t0, error: String(e?.message || e).slice(0, 400) };
  }
}

async function go(page: Page, hash: string, shot: string) {
  const t0 = Date.now();
  const v: Visit = await visit(page, hash, shot);
  const ms = Date.now() - t0;
  const settled = await settle(page);
  await page.screenshot({ path: test.info().outputPath(shot + '.png'), fullPage: true });
  const issues = describeVisit(v);
  if (issues) problems.push(`${hash}: ${issues}`);
  const h1 = await page.locator('#content-area h1').first().innerText().catch(() => '');
  const text = (await page.locator('#content-area').innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 2500);
  const scroll = await page.evaluate(() => ({ docH: document.documentElement.scrollHeight, winH: innerHeight, docW: document.documentElement.scrollWidth })).catch(() => null);
  return { loadMs: ms, settleMs: Date.now() - t0, stillLoading: !settled, h1, issues, scroll, text };
}

/** Waits until the page stops saying it is loading (spinners, 'Loading…'), up to 60 s. */
async function settle(page: Page, max = 60_000): Promise<boolean> {
  const end = Date.now() + max;
  while (Date.now() < end) {
    const busy = await page.evaluate(() => {
      const c = document.querySelector('#content-area') as HTMLElement | null;
      const panels = Array.from(document.querySelectorAll('[role="dialog"]')).filter(d => (d as HTMLElement).offsetParent !== null && d.getBoundingClientRect().width > 0) as HTMLElement[];
      const txt = (c?.innerText || '') + ' ' + panels.map(p => p.innerText).join(' ');
      return /Loading/i.test(txt) || !!document.querySelector('#content-area .spinner-border:not([hidden]), #content-area .loading-spinner');
    }).catch(() => false);
    if (!busy) { await page.waitForTimeout(600); return true; }
    await page.waitForTimeout(1000);
  }
  return false;
}

/** Closes any slideout, dialog, menu or the bell without choosing anything. */
async function closeAll(page: Page) {
  for (let i = 0; i < 3; i++) {
    const btn = page.locator('.slideout-panel .close-btn:visible, .modal.show .btn-close:visible, .modal.show button:has-text("Cancel"):visible').first();
    if (await btn.count()) { await btn.click().catch(() => {}); await page.waitForTimeout(600); continue; }
    break;
  }
  if (await page.locator('#notification-dropdown').isVisible().catch(() => false)) {
    await page.locator('#notification-bell-btn').first().click().catch(() => {});
    await page.waitForTimeout(400);
  }
  await page.keyboard.press('Escape').catch(() => {});
  await page.waitForTimeout(300);
}

async function snap(page: Page, shot: string) {
  await settle(page, 30_000);
  await page.waitForTimeout(700);
  await page.screenshot({ path: test.info().outputPath(shot + '.png'), fullPage: true });
}

async function api(page: Page, url: string) {
  const t0 = Date.now();
  const r = await apiGet(page, url);
  let body: any = null;
  try { body = await r.json(); } catch { body = (await r.text().catch(() => '')).slice(0, 200); }
  return { status: r.status(), ms: Date.now() - t0, body };
}

const list = (b: any): any[] => Array.isArray(b) ? b : (b?.content || b?.items || b?.jobs || []);

test('CloudOps review, read-only', async ({ page, guard }) => {
  // The schedule editor's preview: 'Validated like a create but not saved' (readOnly transaction).
  guard.allow(/\/api\/v1\/automation-rules\/preview$/);
  test.setTimeout(30 * 60_000);
  const iseId = readState('preflight')?.ise?.environmentId as string;
  expect(iseId, 'preflight state has the ise id').toBeTruthy();

  await signInAdmin(page);

  // ---------- Morning check ----------
  await step('home', async () => {
    const r = await go(page, '#/dashboard', '01-home');
    const sum = await api(page, '/api/v1/dashboard/summary');
    const att = await api(page, '/api/v1/dashboard/attention');
    return { ...r, apiSummary: sum.body, apiAttention: JSON.stringify(att.body).slice(0, 2000) };
  });

  await step('system-health', async () => {
    const r = await go(page, '#/system-health', '02-system-health');
    const h = await api(page, '/api/v1/monitoring/scheduler-health');
    const jobs = list(h.body);
    const byState: Record<string, number> = {};
    jobs.forEach((j: any) => { const s = j.status || j.state || j.health; byState[s] = (byState[s] || 0) + 1; });
    return { ...r, apiMs: h.ms, apiJobCount: jobs.length, apiByState: byState, apiKeys: Object.keys(jobs[0] || {}),
      apiJobs: jobs.map((j: any) => [j.name || j.jobName, j.status || j.state, j.lastRunAt || j.lastSuccessAt || j.last, j.lastError || j.error || null]) };
  });

  await step('bell', async () => {
    const n = await api(page, '/api/v1/notifications?size=50');
    const unread = await api(page, '/api/v1/notifications/unread-count');
    await page.locator('#notification-bell-btn').first().click();
    await page.waitForTimeout(2000);
    await snap(page, '03-bell');
    const dd = (await page.locator('#notification-dropdown').innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 1500);
    const badge = await page.locator('#notification-bell-btn').first().innerText().catch(() => '');
    await closeAll(page);
    const items = list(n.body);
    const types: Record<string, number> = {};
    items.forEach((i: any) => { types[i.type] = (types[i.type] || 0) + 1; });
    return { dropdown: dd, badge, apiUnread: unread.body, apiTypes: types, apiTotal: n.body?.totalElements ?? items.length };
  });

  // ---------- Fleet ----------
  let largeId = '';
  let otherId = '';
  await step('environments', async () => {
    const r = await go(page, '#/my-environments', '10-environments');
    const envs = await api(page, '/api/v1/environments');
    const arr = list(envs.body);
    const vms = arr.reduce((n, e) => n + (e.vmCount || 0), 0);
    const running = arr.reduce((n, e) => n + (e.runningVmCount || 0), 0);
    const envsRunning = arr.filter(e => (e.runningVmCount || 0) > 0).length;
    const sorted = [...arr].sort((a, b) => (b.vmCount || 0) - (a.vmCount || 0));
    largeId = sorted[0]?.environmentId;
    otherId = (arr.find(e => (e.runningVmCount || 0) > 0 && e.environmentId !== iseId && e.environmentId !== largeId) || sorted[5])?.environmentId;
    return { ...r, apiMs: envs.ms, apiEnvCount: arr.length, apiVms: vms, apiRunning: running, apiEnvsRunning: envsRunning,
      largest: sorted.slice(0, 5).map(e => [e.name, e.vmCount, e.runningVmCount, e.groupCount]),
      keys: Object.keys(arr[0] || {}) };
  });

  await step('environments-running-filter', async () => {
    await page.locator('#env-list-filter button:has-text("Running")').first().click();
    await page.waitForTimeout(3000);
    await snap(page, '11-environments-running');
    const text = (await page.locator('#content-area').innerText()).replace(/\s+/g, ' ').slice(0, 1500);
    await page.locator('#env-list-filter button:has-text("Stopped")').first().click().catch(() => {});
    await page.waitForTimeout(3000);
    await snap(page, '12-environments-stopped');
    const text2 = (await page.locator('#content-area').innerText()).replace(/\s+/g, ' ').slice(0, 600);
    await page.locator('#env-list-filter button:has-text("All")').first().click().catch(() => {});
    await page.waitForTimeout(1500);
    // the search
    await page.locator('#env-list-search').fill('ise').catch(() => {});
    await page.waitForTimeout(2500);
    await snap(page, '13-environments-search');
    const text3 = (await page.locator('#content-area').innerText()).replace(/\s+/g, ' ').slice(0, 600);
    await page.locator('#env-list-search').fill('').catch(() => {});
    return { running: text, stopped: text2, search: text3 };
  });

  await step('ise', async () => {
    const r = await go(page, `#/environments/${iseId}`, '20-ise');
    const e = await api(page, `/api/v1/environments/${iseId}`);
    return { ...r, api: JSON.stringify(e.body).slice(0, 1500) };
  });

  await step('ise-insights', async () => {
    await page.locator('#btn-env-insights').first().click();
    await page.waitForTimeout(4000);
    await snap(page, '21-ise-insights');
    const panel = (await page.locator('.slideout-panel[role="dialog"]').last().innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 2000);
    await closeAll(page);
    return { panel };
  });

  await step('ise-history', async () => {
    await page.locator('#btn-operation-history').first().click();
    await page.waitForTimeout(4000);
    await snap(page, '22-ise-history');
    const panel = (await page.locator('.slideout-panel[role="dialog"]').last().innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 2000);
    await closeAll(page);
    await snap(page, '23-ise-after-close');
    return { panel };
  });

  await step('large-env', async () => {
    const r = await go(page, `#/environments/${largeId}`, '24-large-env');
    const e = await api(page, `/api/v1/environments/${largeId}`);
    return { ...r, id: largeId, api: JSON.stringify(e.body).slice(0, 800) };
  });

  await step('other-env', async () => {
    const r = await go(page, `#/environments/${otherId}`, '25-other-env');
    return { ...r, id: otherId };
  });

  // ---------- Cost ----------
  await step('cost-overview', async () => {
    const r = await go(page, '#/cost-management', '30-cost-overview');
    const s = await api(page, '/api/v1/cost-management/summary');
    const byEnv = await api(page, '/api/v1/cost-management/spend-by-environment');
    const top = list(byEnv.body).slice(0, 6);
    return { ...r, apiSummary: s.body, apiSummaryMs: s.ms, apiByEnvTop: top, apiByEnvCount: list(byEnv.body).length };
  });

  await step('cost-by-dropdown', async () => {
    const opts = await page.locator('#cost-by option').allInnerTexts();
    const out: Record<string, string> = {};
    for (const v of ['team', 'vmType', 'vm-type', 'account', 'environment']) {
      const ok = await page.locator(`#cost-by option[value="${v}"]`).count();
      if (!ok) continue;
      await page.locator('#cost-by').selectOption(v);
      await page.waitForTimeout(2500);
      await snap(page, `31-cost-by-${v}`);
      out[v] = (await page.locator('.cost-breakdown').innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 600);
    }
    return { opts, out };
  });

  await step('cost-export-menu', async () => {
    await page.locator('#cost-export-menu-btn').click();
    await page.waitForTimeout(800);
    await snap(page, '32-cost-export-menu');
    const items = await page.locator('#cost-export-menu [role="menuitem"]').allInnerTexts();
    await page.keyboard.press('Escape');
    const tabs = await page.locator('[role="tab"]').allInnerTexts();
    return { items, tabs };
  });

  for (const [tab, shot] of [['Rightsizing', '33-cost-rightsizing'], ['Unusual spend', '34-cost-unusual'], ['Commitments', '35a-cost-commitments'], ['Carbon', '35b-cost-carbon'], ['Setup', '36-cost-setup']] as const) {
    await step('cost-tab-' + tab, async () => {
      await closeAll(page);
      const t = page.locator('[role="tab"]').filter({ hasText: new RegExp('^\\s*' + tab) });
      if (!(await t.count())) return { present: false };
      await t.first().click();
      await page.waitForLoadState('networkidle', { timeout: 30_000 }).catch(() => {});
      await page.waitForTimeout(2500);
      await snap(page, shot);
      const text = (await page.locator('#content-area').innerText()).replace(/\s+/g, ' ').slice(0, 2500);
      return { present: true, text };
    });
  }

  await step('rightsizing-api', async () => {
    const r = await api(page, '/api/v1/cost-management/rightsizing?page=0&size=20');
    const rows = list(r.body);
    const counts: Record<string, number> = {};
    rows.forEach((x: any) => { counts[x.agreement] = (counts[x.agreement] || 0) + 1; });
    const savings = rows.reduce((n: number, x: any) => n + (x.estimatedMonthlySavings || 0), 0);
    return { ms: r.ms, total: r.body?.totalElements, pageCount: rows.length, agreement: counts, savingsOnPage: savings,
      top: rows.slice(0, 6).map((x: any) => [x.vmName, x.environmentName, x.currentInstanceType, x.suggestedInstanceType, x.estimatedMonthlySavings, x.agreement, x.vmStatus, x.direction]) };
  });

  await step('savings', async () => {
    const r = await go(page, '#/savings', '37-savings');
    const s = await api(page, '/api/v1/savings/summary');
    return { ...r, apiSummary: s.body };
  });

  await step('waste', async () => {
    const r = await go(page, '#/waste', '38-waste');
    const s = await api(page, '/api/v1/waste/summary');
    const iw = await api(page, '/api/v1/cost-management/idle-waste?page=0&size=10');
    return { ...r, apiSummary: s.body, apiStatus: s.status, idleWaste: JSON.stringify(iw.body).slice(0, 1200) };
  });

  await step('chargeback', async () => go(page, '#/chargeback', '39-chargeback'));

  // ---------- Control ----------
  await step('automation-rules', async () => {
    const r = await go(page, '#/automation-rules', '40-automation-rules');
    const rules = await api(page, '/api/v1/automation-rules');
    return { ...r, apiRules: list(rules.body).map((x: any) => JSON.stringify(x).slice(0, 600)) };
  });

  await step('automation-new-schedule', async () => {
    await page.getByText('New schedule', { exact: false }).first().click();
    await page.waitForTimeout(3000);
    await snap(page, '41-new-schedule');
    const text = (await page.locator('#content-area').first().innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 1500);
    const hash = await page.evaluate(() => location.hash);
    const cancel = page.getByRole('button', { name: /^cancel$/i }).first();
    if (await cancel.isVisible().catch(() => false)) await cancel.click(); else await page.keyboard.press('Escape');
    await page.waitForTimeout(1500);
    await closeAll(page);
    if ((await page.evaluate(() => location.hash)).includes('/new')) await go(page, '#/automation-rules', '42-after-cancel');
    else await snap(page, '42-after-cancel');
    return { hash, text, after: await page.evaluate(() => location.hash) };
  });

  await step('rule-detail', async () => {
    const row = page.getByText('ise start-stop').first();
    if (!(await row.count())) return { found: false };
    await row.click();
    await page.waitForTimeout(3000);
    await snap(page, '43-rule-ise');
    const text = (await page.locator('#content-area').first().innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 1500);
    const cancel = page.getByRole('button', { name: /^(cancel|close)$/i }).first();
    if (await cancel.isVisible().catch(() => false)) await cancel.click(); else await page.keyboard.press('Escape');
    await page.waitForTimeout(1000);
    return { found: true, text, hash: await page.evaluate(() => location.hash) };
  });

  await step('budgets', async () => {
    const r = await go(page, '#/budgets', '44-budgets');
    const b = await api(page, '/api/v1/budgets');
    return { ...r, api: JSON.stringify(b.body).slice(0, 1500) };
  });
  await step('approval-policies', async () => go(page, '#/approval-policies', '45-approval-policies'));

  // ---------- Registry and access ----------
  await step('vm-registry', async () => go(page, '#/vm-registry', '50-vm-registry'));
  await step('vm-registry-ise', async () => {
    await page.locator('#searchEnvironments').fill('ise');
    await page.waitForTimeout(2500);
    await page.locator('#vr-env-list').getByText(/^ise$/i).first().click().catch(async () => {
      await page.locator('#vr-env-list [data-env-id], #vr-env-list button, #vr-env-list li').first().click();
    });
    await page.waitForTimeout(3000);
    await snap(page, '51-vm-registry-ise');
    const text = (await page.locator('#vr-detail').innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 1500);
    return { text };
  });
  await step('vm-registry-large', async () => {
    await page.locator('#searchEnvironments').fill('');
    await page.waitForTimeout(2500);
    await page.locator('#vr-env-list').locator('button, [data-action], li, tr').nth(2).click().catch(() => {});
    await page.waitForTimeout(3000);
    await snap(page, '52-vm-registry-other');
    return { text: (await page.locator('#vr-detail').innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 1200) };
  });
  await step('found-in-aws', async () => {
    const b = page.locator('[data-action="vr-found-in-aws"]');
    if (!(await b.count())) return { present: false };
    await b.first().click();
    await page.waitForLoadState('networkidle', { timeout: 60_000 }).catch(() => {});
    await page.waitForTimeout(4000);
    await snap(page, '53-found-in-aws');
    const text = (await page.locator('[role="dialog"], .slideout, #content-area').last().innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 2000);
    const cancel = page.getByRole('button', { name: /^(cancel|close)$/i }).first();
    if (await cancel.isVisible().catch(() => false)) await cancel.click(); else await page.keyboard.press('Escape');
    await page.waitForTimeout(1000);
    return { present: true, text, hash: await page.evaluate(() => location.hash) };
  });
  await step('needs-review', async () => {
    await go(page, '#/vm-registry', '54-vm-registry-again');
    const banner = page.locator('[data-action="vr-review-them"]');
    const has = await banner.count();
    const bannerText = has ? await banner.first().innerText() : '';
    if (has) {
      await banner.first().click();
      await page.waitForTimeout(4000);
      await snap(page, '55-needs-review');
    }
    const text = (await page.locator('#content-area').innerText()).replace(/\s+/g, ' ').slice(0, 2000);
    return { has, bannerText, text };
  });

  await step('access-management', async () => go(page, '#/access-management', '60-access-management'));
  await step('access-history', async () => {
    const t = page.locator('#access-tab-history');
    if (!(await t.count())) return { present: false };
    await t.click();
    await page.waitForTimeout(3000);
    await snap(page, '61-access-history');
    return { text: (await page.locator('#content-area').innerText()).replace(/\s+/g, ' ').slice(0, 1200) };
  });
  await step('pending-requests', async () => go(page, '#/pending-requests', '62-pending-requests'));
  await step('audit-logs', async () => {
    const r = await go(page, '#/audit-logs-all', '63-audit-logs');
    const a = await api(page, '/api/v1/audit/logs?size=5&page=0');
    return { ...r, apiTotal: a.body?.totalElements };
  });
  await step('audit-filter', async () => {
    const opts = await page.locator('#category-filter option').allInnerTexts().catch(() => []);
    const pick = opts.find(o => /start|operation/i.test(o)) || opts[1];
    if (pick) await page.locator('#category-filter').selectOption({ label: pick });
    await page.waitForTimeout(3500);
    await snap(page, '64-audit-filter-action');
    const t1 = (await page.locator('#content-area').innerText()).replace(/\s+/g, ' ').slice(0, 1200);
    const envOpts = await page.locator('#environment-filter option').allInnerTexts().catch(() => []);
    const ise = envOpts.find(o => /^ise$/i.test(o.trim())) || envOpts.find(o => /ise/i.test(o));
    if (ise) await page.locator('#environment-filter').selectOption({ label: ise });
    await page.waitForTimeout(3500);
    await snap(page, '65-audit-filter-ise');
    const t2 = (await page.locator('#content-area').innerText()).replace(/\s+/g, ' ').slice(0, 1500);
    const periods = await page.locator('#time-range-filter option').allInnerTexts().catch(() => []);
    return { actionOptions: opts, picked: pick, envOptionsCount: envOpts.length, periods, t1, t2 };
  });
  await step('user-management', async () => {
    const r = await go(page, '#/user-management', '66-users');
    const u = await api(page, '/api/v1/users?size=2000');
    const arr = list(u.body);
    const roles: Record<string, number> = {};
    arr.forEach((x: any) => { roles[x.role] = (roles[x.role] || 0) + 1; });
    return { ...r, apiUsers: arr.length, apiRoles: roles, apiActive: arr.filter((x: any) => x.isActive !== false && x.active !== false).length };
  });
  await step('teams', async () => go(page, '#/teams', '67-teams'));
  await step('aws-accounts', async () => go(page, '#/aws-accounts', '68-aws-accounts'));
  await step('settings', async () => go(page, '#/settings', '69-settings'));
  await step('api-access', async () => go(page, '#/api-access', '70-api-access'));

  fs.writeFileSync(test.info().outputPath('notes.json'), JSON.stringify({ problems, notes }, null, 2));
});

test('CloudOps follow-ups, read-only', async ({ page }) => {
  test.setTimeout(30 * 60_000);
  const out: Record<string, any> = {};
  await signInAdmin(page);

  await step('fu-home-review', async () => {
    await go(page, '#/dashboard', '80-home');
    const card = page.locator('#content-area').getByText('VMs to review').first();
    await card.click();
    await settle(page);
    await page.waitForTimeout(2500);
    await snap(page, '81-home-vms-to-review');
    return { hash: await page.evaluate(() => location.hash), text: (await page.locator('#content-area').innerText()).replace(/\s+/g, ' ').slice(0, 1500) };
  });
  await step('fu-home-failed', async () => {
    await go(page, '#/dashboard', '82-home');
    await page.locator('#content-area').getByText('Failed operations, 24 h').first().click();
    await settle(page);
    await page.waitForTimeout(2500);
    await snap(page, '83-home-failed-ops');
    return { hash: await page.evaluate(() => location.hash), text: (await page.locator('#content-area').innerText()).replace(/\s+/g, ' ').slice(0, 1500) };
  });
  await step('fu-health-all', async () => {
    await go(page, '#/system-health', '84-health');
    await page.locator('#content-area').getByRole('button', { name: /All 35/ }).first().click().catch(() => {});
    await page.waitForTimeout(1500);
    await snap(page, '85-health-all');
    return { text: (await page.locator('#content-area').innerText()).replace(/\s+/g, ' ').slice(0, 1500) };
  });
  await step('fu-found-in-aws', async () => {
    await go(page, '#/vm-registry', '86-registry');
    const t0 = Date.now();
    await page.locator('[data-action="vr-found-in-aws"]').first().click();
    let done = false;
    while (Date.now() - t0 < 180_000) {
      const txt = await page.locator('.slideout-panel[role="dialog"]').last().innerText().catch(() => '');
      if (txt.replace(/Found in AWS|×|\s/g, '').length > 20 && !/Loading/i.test(txt)) { done = true; break; }
      await page.waitForTimeout(2000);
    }
    const ms = Date.now() - t0;
    await page.waitForTimeout(1000);
    await page.screenshot({ path: test.info().outputPath('87-found-in-aws.png'), fullPage: true });
    const text = (await page.locator('.slideout-panel[role="dialog"]').last().innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 2500);
    await closeAll(page);
    return { done, ms, text };
  });
  await step('fu-settings-cost', async () => {
    await go(page, '#/settings', '88-settings');
    await page.locator('#content-area').getByText(/^Cost\b/).first().click().catch(() => {});
    await page.waitForTimeout(2000);
    await snap(page, '89-settings-cost');
    return { text: (await page.locator('#content-area').innerText()).replace(/\s+/g, ' ').slice(0, 1500) };
  });
  await step('fu-api', async () => {
    const rs = await api(page, '/api/v1/cost-management/rightsizing?page=0&size=100');
    const rows = list(rs.body);
    const dirs: Record<string, number> = {};
    rows.forEach((x: any) => { dirs[x.direction] = (dirs[x.direction] || 0) + 1; });
    const highCpu = rows.filter((x: any) => (x.peakCpuUtilization || 0) >= 85 && x.direction === 'SCALE_DOWN').map((x: any) => [x.vmName, x.peakCpuUtilization, x.currentInstanceType, x.suggestedInstanceType, x.agreement]);
    const stopped = rows.filter((x: any) => x.vmStatus !== 'RUNNING').length;
    const teams = await api(page, '/api/v1/teams/overview');
    const envs = await api(page, '/api/v1/environments');
    const withGroupStates = list(envs.body).filter((e: any) => e.groupStates).length;
    const drift = await api(page, '/api/v1/monitoring/drift?hours=24').catch(() => null);
    return { rsKeys: Object.keys(rs.body || {}), rsTotal: rs.body?.totalElements ?? rs.body?.page?.totalElements, rsRows: rows.length, dirs, highCpu, stoppedSuggestions: stopped,
      teams: JSON.stringify(teams.body).slice(0, 600), envsWithGroupStates: withGroupStates, drift: drift && { status: drift.status, body: JSON.stringify(drift.body).slice(0, 300) } };
  });
  fs.writeFileSync(test.info().outputPath('notes.json'), JSON.stringify({ problems, notes }, null, 2));
});
