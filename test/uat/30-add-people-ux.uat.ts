import { test, signInAdmin, writeState, visit } from './guard';

/**
 * Add people page on staging, read only: typed into, never submitted. The server preview
 * (POST /users/bulk-onboard?dryRun=true) is refused by the guard on purpose, so the confirm bar
 * shows its "couldn't check" state; the refusal is expected here and recorded, not a failure.
 */
const facts: Record<string, any> = {};
const note = (k: string, v: any) => { facts[k] = v; writeState('add-people-ux', facts); };
const shot = (page: any, name: string, full = false) => page.screenshot({ path: test.info().outputPath(name + '.png'), fullPage: full });

test('add people, laptop 1366x657 and phone', async ({ page, guard }) => {
  test.setTimeout(300_000);
  guard.allow(/\/api\/v1\/users\/bulk-onboard$/);   // only reached with ?dryRun=true below; Add people is never clicked
  page.on('request', r => { if (r.method() === 'POST' && /bulk-onboard/.test(r.url()) && !/dryRun=true/.test(r.url())) throw new Error('real submit attempted'); });
  await page.route(/\/api\/v1\/users\/bulk-onboard(?!\?dryRun=true)/, route => route.request().method() === 'POST' ? route.abort() : route.continue());
  await page.setViewportSize({ width: 1366, height: 657 });
  await signInAdmin(page);
  await visit(page, '#/user-management/add', 'a-01-empty');
  await page.locator('#adp-email').waitFor({ timeout: 60_000 });
  await page.waitForTimeout(1500);
  await shot(page, 'a-01-empty');
  await shot(page, 'a-01-empty-full', true);
  note('empty', { text: (await page.locator('#content-area').innerText()).replace(/\s+/g, ' ').slice(0, 1500),
    directory: await page.locator('#adp-search').count(), teams: await page.locator('#adp-team option').allInnerTexts() });

  await page.locator('#adp-email').fill('pat.example@tcgdigital.com, sam.example@tcgdigital.com');
  await page.locator('#adp-name').fill('');
  await page.getByRole('button', { name: 'Add to list' }).click();
  await page.waitForTimeout(2500);
  await shot(page, 'a-02-two-people');
  note('listed', (await page.locator('#adp-people tbody').innerText().catch(() => '')).replace(/\s+/g, ' '));
  await page.locator('[data-adp-role="ENV_ADMIN"]').click();
  await page.waitForTimeout(1500);
  await page.locator('#adp-add-env').click();
  await page.waitForTimeout(2500);
  await shot(page, 'a-03-picker');
  note('picker', (await page.locator('#adp-picker').innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 600));
  note('confirm', (await page.locator('.adp-confirm').innerText().catch(() => '')).replace(/\s+/g, ' '));
  // password route
  const pw = page.locator('input[name="adp-signin"][value="PASSWORD"]');
  if (await pw.count()) { await pw.check(); await page.waitForTimeout(800); await shot(page, 'a-04-password'); }
  note('scroll', await page.evaluate(() => document.documentElement.scrollHeight > window.innerHeight));

  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(1500);
  await shot(page, 'p-01-phone-full', true);
  note('phone', { overflow: await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth),
    height: await page.evaluate(() => document.documentElement.scrollHeight) });
  // leave without adding anyone
  await page.evaluate(() => { location.hash = '#/user-management'; });
});
