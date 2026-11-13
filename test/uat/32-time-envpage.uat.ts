import { test, signInAdmin, apiGet, writeState } from './guard';
test('time the environment list pages the team page reads (read only)', async ({ page }) => {
  test.setTimeout(300_000);
  await signInAdmin(page);
  const out: any[] = [];
  for (const u of ['/api/v1/environments/page?page=0&size=100', '/api/v1/environments/page?page=1&size=100', '/api/v1/environments/page?page=0&size=15']) {
    const t = Date.now(); const r = await apiGet(page, u); const body = await r.text();
    out.push([u, r.status(), Date.now() - t, body.length]);
  }
  await page.evaluate(() => { location.hash = '#/teams'; });
  await page.waitForTimeout(3000);
  await page.locator('#teams-table-body a').first().click();
  const t0 = Date.now();
  await page.waitForFunction(() => { const c = document.querySelector('#content-area table tbody tr td:nth-child(2)'); return c && !/—|–/.test(c.textContent || ''); }, null, { timeout: 120_000 }).catch(() => {});
  out.push(['team page figures shown after ms', Date.now() - t0, await page.locator('#content-area table tbody tr').first().innerText().catch(() => '')]);
  writeState('time-envpage', out);
});
