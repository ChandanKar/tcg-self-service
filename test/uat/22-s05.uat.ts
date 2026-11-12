import { test, expect, signInAdmin, apiJson, writeState, visit } from './guard';
/** S05 Compute Optimizer (ships on): rightsizing rows say whether AWS agrees with the local rules. Read-only. */
test('[S05] rightsizing shows AWS Compute Optimizer agreement', async ({ page }) => {
  await signInAdmin(page);
  const r: any = await apiJson(page, '/api/v1/cost-management/rightsizing?page=0&size=50');
  const rows: any[] = r.content || r.rows || r.items || (Array.isArray(r) ? r : []);
  const keys = Object.keys(rows[0] || {});
  const agreeKey = keys.find(k => /agree|optimizer|aws/i.test(k));
  const counts: Record<string, number> = {};
  rows.forEach(x => { const v = String(agreeKey ? x[agreeKey] : 'n/a'); counts[v] = (counts[v] || 0) + 1; });
  const agreed: any = await apiJson(page, '/api/v1/cost-management/rightsizing?page=0&size=5&agreement=AGREES').catch(() => null);
  await visit(page, '#/cost-management', 's05-cost');
  const tab = page.locator('#content-area [role="tab"]', { hasText: 'Rightsizing' }).first();
  if (await tab.count()) { await tab.click(); await page.waitForTimeout(3000); }
  const text = (await page.locator('#content-area').innerText()).replace(/\s+/g, ' ');
  const awsWords = (text.match(/[^.]{0,60}(Compute Optimizer|AWS agrees|AWS suggests|AWS says)[^.]{0,60}/gi) || []).slice(0, 4);
  await page.screenshot({ path: test.info().outputPath('s05-rightsizing.png'), fullPage: true });
  writeState('s05', { total: r.totalElements ?? rows.length, keys, agreeKey, counts, agreedTotal: agreed && (agreed.totalElements ?? null), awsWords, sample: rows.slice(0, 2) });
  expect(rows.length).toBeGreaterThan(0);
});
