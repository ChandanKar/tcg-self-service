// U17-T07: the approved mockup next to the app, board by board, at the board's own size (1440 x 900,
// phones 390 x 844). Writes a PNG pair per board and report.html for review.
//
// Usage (from test/):
//   node ux-review/compare-mockup.mjs --runtime <dc-runtime.js> [--out <dir>] [--only Budgets,Settings] [--no-seed]
// --runtime: the boards' runtime (support.js). It belongs to the design tool, not to this repository:
//   export it from the mockup artifact (artifact-type/dc-runtime.js) and pass its path.
// The smoke app must run on the test schema (BASE_URL, default http://localhost:18080, dev X-User-Id).
import { chromium } from '@playwright/test';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { BASE, seed } from './seed.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const MOCKUP = path.resolve(HERE, '../../docs/design/mockup');
const arg = (name, dflt) => { const i = process.argv.indexOf(name); return i > 0 ? process.argv[i + 1] : dflt; };
const RUNTIME = arg('--runtime');
const OUT = path.resolve(arg('--out', path.resolve(HERE, '../test-results/mockup-compare')));
const ONLY = (arg('--only', '') || '').split(',').filter(Boolean);
const PERSONA = { admin: 'admin-001', envadmin: 'dev-user-001', user: 'user-001', viewer: 'user-002' };

if (!RUNTIME || !fs.existsSync(RUNTIME)) {
  console.error('Pass --runtime <path to the boards\' dc-runtime.js> (see the header of this file).');
  process.exit(2);
}
fs.mkdirSync(OUT, { recursive: true });

/** The boards over http (dc-import fetches its parts), with the runtime as support.js. */
function serveBoards() {
  const server = http.createServer((req, res) => {
    const name = decodeURIComponent(new URL(req.url, 'http://x').pathname).replace(/^\/+/, '');
    const file = name === 'support.js' ? RUNTIME : path.join(MOCKUP, path.basename(name));
    if (!fs.existsSync(file)) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'Content-Type': file.endsWith('.js') ? 'text/javascript' : file.endsWith('.json') ? 'application/json' : 'text/html' });
    fs.createReadStream(file).pipe(res);
  });
  return new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve(server)));
}

/** The boards ask Google for IBM Plex; serve the app's own font files instead. */
async function localFonts(ctx) {
  const face = (family, w, file) => `@font-face{font-family:'${family}';font-weight:${w};src:url(${BASE}/webfonts/${file}) format('woff2')}`;
  const css = ['400', '500', '600'].map(w => face('IBM Plex Sans', w, `ibm-plex-sans-latin-${w}-normal.woff2`)).join('')
    + ['400', '500'].map(w => face('IBM Plex Mono', w, `ibm-plex-mono-latin-${w}-normal.woff2`)).join('');
  await ctx.route(/fonts\.googleapis\.com|fonts\.gstatic\.com/, r => r.fulfill({ contentType: 'text/css', body: css }));
}

async function runSetup(page, steps) {
  for (const s of steps || []) {
    if (s.click) await page.locator(s.click).first().click({ timeout: 8000 });
    if (s.eval) await page.evaluate(code => (0, eval)(code), s.eval);
    if (s.wait) await page.locator(s.wait).first().waitFor({ timeout: 8000 });
    await page.waitForTimeout(400);
  }
}

const routes = JSON.parse(fs.readFileSync(path.join(HERE, 'board-routes.json'), 'utf8')).boards
  .filter(b => !ONLY.length || ONLY.includes(b.board));
const ids = process.argv.includes('--no-seed') ? JSON.parse(fs.readFileSync(path.join(OUT, 'seed.json'), 'utf8')) : await seed();
fs.writeFileSync(path.join(OUT, 'seed.json'), JSON.stringify(ids));
const fill = r => r.replace('{env0}', ids.envs[0]).replace('{env1}', ids.envs[1]).replace('{team}', ids.team || '');

const server = await serveBoards();
const port = server.address().port;
const browser = await chromium.launch();
const rows = [];
for (const b of routes) {
  if (b.skip) { rows.push({ board: b.board, skip: b.skip }); continue; }
  const [w, h] = b.viewport || [1440, 900];
  const row = { board: b.board, route: b.url || b.route, persona: b.persona, notes: [] };
  // The board.
  const bctx = await browser.newContext({ viewport: { width: w, height: h } });
  await localFonts(bctx);
  const bp = await bctx.newPage();
  await bp.goto(`http://127.0.0.1:${port}/${b.board}.dc.html`);
  await bp.waitForTimeout(2000);
  await bp.screenshot({ path: path.join(OUT, `${b.board}-board.png`) });
  await bctx.close();
  // The app.
  const actx = await browser.newContext({ viewport: { width: w, height: h },
    extraHTTPHeaders: b.persona ? { 'X-User-Id': PERSONA[b.persona] } : {} });
  const ap = await actx.newPage();
  try {
    if (b.url) {
      await ap.goto(BASE + b.url);
    } else {
      await ap.goto(`${BASE}/home`);
      await ap.locator('#content-area h1, #content-area .no-access').first().waitFor({ timeout: 20000 });
      await ap.evaluate(hash => { location.hash = hash; }, fill(b.route));
    }
    await ap.locator(b.ready).first().waitFor({ timeout: 15000 });
    await ap.waitForLoadState('networkidle');
    await ap.waitForTimeout(800);
    await runSetup(ap, b.setup);
  } catch (e) {
    row.notes.push(`Could not open as mapped: ${String(e.message).split('\n')[0]}`);
  }
  const scroll = await ap.evaluate(() => {
    const d = document.scrollingElement; const m = document.getElementById('mainContent');
    return (d && d.scrollHeight > innerHeight + 1) || (m && m.scrollHeight > m.clientHeight + 1);
  }).catch(() => false);
  if (scroll) row.notes.push('The page scrolls.');
  await ap.screenshot({ path: path.join(OUT, `${b.board}-app.png`) });
  await actx.close();
  rows.push(row);
  console.log(`${b.board}: ${row.notes.join(' ') || 'shot'}`);
}
await browser.close();
server.close();

const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
const html = `<!doctype html><meta charset="utf-8"><title>Mockup and app</title>
<style>body{font:14px system-ui,sans-serif;margin:24px;background:#F3F5F9;color:#141B2D}h2{margin:28px 0 4px}
.pair{display:grid;grid-template-columns:1fr 1fr;gap:12px}.pair img{width:100%;border:1px solid #D4DAE5;background:#fff}
.meta{color:#5B6478;margin-bottom:8px}.note{color:#A8331F}.skip{color:#5B6478}</style>
<h1>Mockup (left) and app (right)</h1><p>${rows.length} boards · ${new Date().toISOString()}</p>
${rows.map(r => r.skip ? `<h2>${esc(r.board)}</h2><p class="skip">Skipped: ${esc(r.skip)}</p>` : `<h2 id="${esc(r.board)}">${esc(r.board)}</h2>
<div class="meta">${esc(r.route)} · ${esc(r.persona || 'signed out')}</div>${r.notes.map(n => `<p class="note">${esc(n)}</p>`).join('')}
<div class="pair"><img src="${esc(r.board)}-board.png" alt="${esc(r.board)} board"><img src="${esc(r.board)}-app.png" alt="${esc(r.board)} in the app"></div>`).join('\n')}`;
fs.writeFileSync(path.join(OUT, 'report.html'), html);
console.log('report:', path.join(OUT, 'report.html'));
