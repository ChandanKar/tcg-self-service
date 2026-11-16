// UX audit crawler: per persona, visit every route, inventory every visible control, click the safe
// ones and record what each does. Runs against the smoke app on the test schema only.
// Usage: node test/ux-review/crawl.mjs <outDir> [--personas=admin,user] [--no-click] [--routes=a,b]
import { chromium } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';

const BASE = process.env.BASE_URL || 'http://localhost:18080';
const OUT = process.argv[2] || 'ux-audit';
const arg = (k) => (process.argv.find(a => a.startsWith(`--${k}=`)) || '').split('=')[1];
const NO_CLICK = process.argv.includes('--no-click');
fs.mkdirSync(OUT, { recursive: true });

const PERSONAS = [
  { key: 'admin', id: 'admin-001', click: true },
  { key: 'envadmin', id: 'dev-user-001', click: false },
  { key: 'user', id: 'user-001', click: true },
  { key: 'viewer', id: 'user-002', click: false },
].filter(p => !arg('personas') || arg('personas').split(',').includes(p.key));

const ROUTES = ['dashboard', 'my-environments', 'ENV', 'request-access', 'approvals', 'pending-requests',
  'activity-logs', 'user-management', 'access-management', 'vm-registry', 'automation-rules', 'approval-policies',
  'audit-logs-all', 'cost-management', 'budgets', 'savings', 'waste', 'chargeback', 'cost-setup', 'teams',
  'aws-accounts', 'api-access', 'settings', 'system-health', 'help', 'templates']
  .filter(r => !arg('routes') || arg('routes').split(',').includes(r));

// Never clicked: anything that changes state on the server or in AWS.
const UNSAFE = /delete|remove|revoke|deactivat|\bstop\b|\bstart\b|restart|break|deny|reject|approve|purge|apply|ingest|backfill|reconcile|sync|run now|send|notify|log ?out|sign out|release|extend|acquire|lock env|reset|disable|enable|save|submit|grant|import|fetch|discover|resize|convert|destroy|provision|mark all|create|add |^add$|refresh inventory|acknowledge|reactivat|move|test|snooze|confirm|cancel|recalculate|rotate|regenerate|finali[sz]e|close month|suggest|evaluate/i;

const envIds = JSON.parse(fs.readFileSync(path.join(process.argv[2].replace(/[\\/]audit.*$/, ''), 'shots-after', 'env-ids.json'), 'utf8'));

function hashFor(route) { return route === 'ENV' ? `#/environments/${envIds[0]}` : `#/${route}`; }

const INVENTORY_JS = () => {
  const sel = 'button, a[href], [role="button"], [role="tab"], select, input[type="checkbox"], input[type="radio"], input[type="search"], input[type="text"]';
  const vis = el => {
    if (!(el instanceof HTMLElement)) return false;
    const r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    const cs = getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none' || Number(cs.opacity) === 0) return false;
    return el.checkVisibility ? el.checkVisibility() : true;
  };
  const scope = document.querySelector('#content-area');
  const list = Array.from(scope.querySelectorAll(sel)).filter(vis);
  return list.map((el, i) => {
    const text = (el.innerText || el.value || '').replace(/\s+/g, ' ').trim().slice(0, 70);
    const icon = el.querySelector('i[class*="fa-"]');
    return {
      i, tag: el.tagName.toLowerCase(), type: el.getAttribute('type') || '', role: el.getAttribute('role') || '',
      text, aria: el.getAttribute('aria-label') || '', title: el.getAttribute('title') || el.getAttribute('data-bs-original-title') || '',
      placeholder: el.getAttribute('placeholder') || '', id: el.id || '', action: el.dataset.action || el.dataset.lockAction || el.dataset.maAction || '',
      href: el.tagName === 'A' ? el.getAttribute('href') : '', disabled: !!el.disabled || el.getAttribute('aria-disabled') === 'true',
      iconOnly: !text && !!icon, icon: icon ? Array.from(icon.classList).filter(c => c.startsWith('fa-') && c !== 'fa-fw').join(' ') : '',
      cls: (el.className && typeof el.className === 'string') ? el.className.slice(0, 80) : '',
    };
  });
};

function label(c) { return c.text || c.aria || c.title || c.placeholder || c.id || c.icon || c.tag; }

async function snapshot(page) {
  return page.evaluate(() => ({
    hash: location.hash,
    modal: Array.from(document.querySelectorAll('.modal.show, .slideout-panel.open, .slideout-panel.active, .slideout.open, [role="dialog"]'))
      .filter(m => m.getBoundingClientRect().width > 0 && getComputedStyle(m).display !== 'none' && getComputedStyle(m).visibility !== 'hidden')
      .map(m => (m.querySelector('.modal-title, h2, h3, h4, h5, .slideout-title')?.innerText || m.getAttribute('aria-label') || 'dialog').trim().slice(0, 60)),
    toasts: Array.from(document.querySelectorAll('#toast-container .toast, #notification-container > *, .toast.show'))
      .map(t => t.innerText.replace(/\s+/g, ' ').trim().slice(0, 100)),
    content: (document.querySelector('#content-area')?.innerText || '').length,
    dropdown: Array.from(document.querySelectorAll('.dropdown-menu.show')).length,
    expanded: Array.from(document.querySelectorAll('[aria-expanded="true"]')).length,
  }));
}

async function openRoute(page, route) {
  await page.goto(`${BASE}/home${hashFor(route)}`);
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(1200);
}

const browser = await chromium.launch();
const report = {};
for (const p of PERSONAS) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, acceptDownloads: true,
    extraHTTPHeaders: { 'X-User-Id': p.id } });
  const page = await ctx.newPage();
  let consoleErrors = [];
  let badResponses = [];
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 200)); });
  page.on('pageerror', e => consoleErrors.push('pageerror: ' + String(e.message).slice(0, 200)));
  page.on('response', r => { if (r.status() >= 400 && r.url().includes('/api/')) badResponses.push(`${r.status()} ${r.request().method()} ${r.url().replace(BASE, '')}`); });
  page.on('dialog', d => d.dismiss().catch(() => {}));
  report[p.key] = {};
  for (const route of ROUTES) {
    consoleErrors = []; badResponses = [];
    await openRoute(page, route);
    const h1 = await page.evaluate(() => (document.querySelector('#content-area h1')?.innerText || '').trim());
    const controls = await page.evaluate(INVENTORY_JS);
    const entry = { h1, controls, load: { consoleErrors: [...consoleErrors], badResponses: [...badResponses] }, clicks: [] };
    await page.screenshot({ path: path.join(OUT, `${p.key}-${route}.png`) });
    if (p.click && !NO_CLICK) {
      const seen = new Set();
      for (const c of controls) {
        const name = label(c);
        // Rows repeat the same control: click one of each kind.
        const kind = c.action ? `a:${c.action}` : c.href ? `h:${c.href.replace(/[0-9a-f-]{36}/g, 'ID')}` : `t:${c.text}|${c.icon}`;
        if ((c.action || /\/environments\/ID|\/users\/ID/.test(kind)) && seen.has(kind)) {
          entry.clicks.push({ i: c.i, name, skipped: 'repeat' });
          continue;
        }
        seen.add(kind);
        if (c.disabled || UNSAFE.test(`${c.text} ${c.aria} ${c.title} ${c.action}`) || ['text', 'search'].includes(c.type) || c.tag === 'select'
            || (c.href && /^https?:/.test(c.href))) {
          entry.clicks.push({ i: c.i, name, skipped: c.disabled ? 'disabled' : 'unsafe/input' });
          continue;
        }
        consoleErrors = []; badResponses = [];
        const before = await snapshot(page);
        const handle = (await page.$$(':is(#content-area) :is(button, a[href], [role="button"], [role="tab"], select, input[type="checkbox"], input[type="radio"], input[type="search"], input[type="text"])'))
        const visible = [];
        for (const h of handle) { if (await h.isVisible()) visible.push(h); }
        const el = visible[c.i];
        if (!el) { entry.clicks.push({ i: c.i, name, error: 'not found after reload' }); continue; }
        let err = null;
        try { await el.click({ timeout: 3000 }); } catch (e) { err = String(e.message).split('\n')[0].slice(0, 120); }
        await page.waitForTimeout(900);
        const after = await snapshot(page);
        const effects = [];
        if (after.hash !== before.hash) effects.push(`navigates ${after.hash}`);
        const newModals = after.modal.filter(m => !before.modal.includes(m));
        if (newModals.length) effects.push(`opens "${newModals.join(' | ')}"`);
        const newToasts = after.toasts.filter(t => !before.toasts.includes(t));
        if (newToasts.length) effects.push(`toast "${newToasts.join(' | ')}"`);
        if (after.dropdown > before.dropdown) effects.push('opens menu');
        if (after.expanded !== before.expanded) effects.push('expands/collapses');
        if (Math.abs(after.content - before.content) > 20 && !after.hash.startsWith(before.hash.split('?')[0]) === false && after.hash === before.hash) effects.push(`content ${after.content - before.content > 0 ? '+' : ''}${after.content - before.content} chars`);
        entry.clicks.push({ i: c.i, name, err, effects, consoleErrors: [...consoleErrors], badResponses: [...badResponses] });
        const changed = effects.length || err;
        if (changed) {
          await page.keyboard.press('Escape').catch(() => {});
          await page.waitForTimeout(300);
          await openRoute(page, route);
        }
      }
    }
    report[p.key][route] = entry;
    fs.writeFileSync(path.join(OUT, 'audit.json'), JSON.stringify(report, null, 1));
    console.log(p.key, route, controls.length, 'controls');
  }
  await ctx.close();
}
await browser.close();
console.log('done');
