import { test, expect, signInAdmin, apiJson, writeState } from './guard';
/** J12 (read-only): the bell has one 'failed' notification per failed operation, and each notification leads to its page. */
test('[J12] bell: failed operations and where notifications lead', async ({ page }) => {
  await signInAdmin(page);
  const list: any = await apiJson(page, '/api/v1/notifications?size=100');
  const items: any[] = (Array.isArray(list) ? list : list.content || []).filter(n => String(n.createdAt).startsWith('2026-11-12'));
  const failed = items.filter(n => /FAIL/i.test(n.type));
  const sample = ['OPERATION_FAILED', 'OPERATION_COMPLETED', 'LOCK_BROKEN', 'ACCESS_REQUESTED'].map(t => items.find(n => n.type === t)).filter(Boolean);
  const leads: any[] = [];
  await page.locator('#notification-bell-btn').first().click();
  await page.waitForTimeout(1500);
  for (const n of sample) {
    const item = page.locator('#notification-dropdown .notification-item').filter({ hasText: n.title }).first();
    let hash = '';
    if (await item.count()) {
      await item.click();
      await page.waitForTimeout(2500);
      hash = await page.evaluate(() => location.hash);
      await page.locator('#notification-bell-btn').first().click();
      await page.waitForTimeout(1200);
    }
    leads.push({ type: n.type, title: n.title, link: n.link || n.actionUrl || n.url || null, opened: hash });
  }
  await page.screenshot({ path: test.info().outputPath('bell.png') });
  writeState('bell', { failed: failed.map(n => ({ type: n.type, title: n.title, at: n.createdAt })), leads, keys: Object.keys(items[0] || {}) });
  expect(items.length).toBeGreaterThan(0);
});
