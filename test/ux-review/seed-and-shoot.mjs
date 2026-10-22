// UX review helper: seeds sample data on the smoke app (test schema) and screenshots each page per persona.
// Usage: node test/ux-review/seed-and-shoot.mjs <outDir> [--no-seed] [--pages a,b]
import { chromium } from '@playwright/test';
import { BASE, seed as seedAll } from './seed.mjs';
import fs from 'node:fs';
import path from 'node:path';

const OUT = process.argv[2] || 'ux-shots';
const SEED = !process.argv.includes('--no-seed');
const pagesArg = process.argv.find(a => a.startsWith('--pages='));
fs.mkdirSync(OUT, { recursive: true });

const PERSONAS = [
  { key: 'admin', id: 'admin-001' },
  { key: 'envadmin', id: 'dev-user-001' },
  { key: 'user', id: 'user-001' },
  { key: 'viewer', id: 'user-002' },
];
const ALL_PAGES = ['dashboard', 'my-environments', 'ENV', 'request-access', 'approvals', 'pending-requests', 'activity-logs',
  'user-management', 'access-management', 'vm-registry', 'automation-rules', 'audit-logs-all', 'cost-management',
  'budgets', 'savings', 'waste', 'system-health', 'settings', 'help'];

async function shoot(ids) {
  const browser = await chromium.launch();
  const wanted = pagesArg ? pagesArg.slice(8).split(',') : ALL_PAGES;
  for (const p of PERSONAS) {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, extraHTTPHeaders: { 'X-User-Id': p.id } });
    const page = await ctx.newPage();
    for (const route of wanted) {
      const hash = route === 'ENV' ? `#/environments/${ids[0]}` : `#/${route}`;
      await page.goto(`${BASE}/home${hash}`);
      await page.waitForTimeout(2500);
      await page.screenshot({ path: path.join(OUT, `${p.key}-${route}.png`), fullPage: false });
    }
    // sidebar with admin section expanded
    await page.goto(`${BASE}/home#/dashboard`);
    await page.waitForTimeout(1500);
    const adminTitle = page.locator('#admin-title');
    if (await adminTitle.isVisible()) await adminTitle.click();
    await page.waitForTimeout(500);
    await page.screenshot({ path: path.join(OUT, `${p.key}-sidebar.png`), clip: { x: 0, y: 0, width: 300, height: 900 } });
    // user menu
    await page.locator('.user-profile-trigger').click().catch(() => {});
    await page.waitForTimeout(400);
    await page.screenshot({ path: path.join(OUT, `${p.key}-usermenu.png`) });
    // phone
    const phone = await browser.newContext({ viewport: { width: 390, height: 844 }, extraHTTPHeaders: { 'X-User-Id': p.id } });
    const pp = await phone.newPage();
    for (const route of ['dashboard', 'ENV']) {
      const hash = route === 'ENV' ? `#/environments/${ids[0]}` : `#/${route}`;
      await pp.goto(`${BASE}/home${hash}`);
      await pp.waitForTimeout(2500);
      await pp.screenshot({ path: path.join(OUT, `${p.key}-phone-${route}.png`) });
    }
    await phone.close();
    await ctx.close();
  }
  await browser.close();
}

let ids;
if (SEED) {
  ids = (await seedAll()).envs;
  fs.writeFileSync(path.join(OUT, 'env-ids.json'), JSON.stringify(ids));
} else {
  ids = JSON.parse(fs.readFileSync(path.join(OUT, 'env-ids.json'), 'utf8'));
}
await shoot(ids);
console.log('done', ids);
