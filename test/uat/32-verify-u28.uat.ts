import { test, signInAdmin, signInAs, PEOPLE, apiGet, writeState, visit, describeVisit } from './guard';

/**
 * U27 help + U28 fixes on staging, read only (UAT_DRY=1): every request that changes something is
 * refused by the guard. Operator and viewer see the help for their role; the admin sees all six
 * groups, every topic has content, every page's "?" opens a topic, the Drift list explains itself,
 * and the account menu and sign-in page use the new labels. Facts go to state/verify-u28.json.
 */
const facts: Record<string, any> = {};
const note = (k: string, v: any) => { facts[k] = v; writeState('verify-u28', facts); };
const shot = (page: any, name: string) => page.screenshot({ path: test.info().outputPath(name + '.png') });

declare const HelpContent: any;
declare const Help: any;

async function helpFacts(page: any, who: string) {
  const v = await visit(page, '#/help/getting-started', `${who}-help`);
  await page.locator('#help-topic-title').waitFor({ timeout: 60_000 });
  note(`${who}.visit`, describeVisit(v) || 'clean');
  note(`${who}.groups`, await page.locator('.help-group-title').allInnerTexts());
  note(`${who}.topics`, await page.locator('.help-topic-link').allInnerTexts());
  // Every visible topic opens with a title, a summary and at least one section; none is "not found".
  const empty: string[] = [];
  for (const id of await page.locator('.help-topic-link').evaluateAll((els: any[]) => els.map(e => e.dataset.topic))) {
    await page.evaluate((h: string) => { location.hash = h; }, '#/help/' + id);
    await page.waitForFunction((t: string) => document.querySelector('.help-topic-link.active')?.getAttribute('data-topic') === t, id, { timeout: 15_000 });
    const ok = await page.evaluate(() => !!document.querySelector('#help-topic .help-summary')
      && document.querySelectorAll('#help-topic .help-section').length > 0
      && !/Topic not found/.test(document.getElementById('help-topic')!.innerText));
    if (!ok) empty.push(id);
  }
  note(`${who}.emptyTopics`, empty);
  // Layout: no page or main scrollbar at this size; a long topic scrolls inside its panel.
  await page.evaluate(() => { location.hash = '#/help/locks'; });
  await page.locator('#help-topic-title').filter({ hasText: 'Starting and stopping' }).waitFor({ timeout: 15_000 });
  note(`${who}.layout`, await page.evaluate(() => {
    const main = document.getElementById('mainContent');
    const t = document.getElementById('help-topic')!;
    return { page: document.scrollingElement!.scrollHeight - innerHeight, main: main ? main.scrollHeight - main.clientHeight : 0,
      topicScrolls: t.scrollHeight > t.clientHeight };
  }));
  await shot(page, `${who}-locks`);
}

test.describe.configure({ mode: 'serial' });

test('Operator: help for their role, account menu label', async ({ page }) => {
  test.setTimeout(600_000);
  await page.setViewportSize({ width: 1366, height: 657 });
  await signInAs(page, PEOPLE.operator.user, PEOPLE.operator.pass);
  await helpFacts(page, 'operator');
  note('operator.adminTopicHidden', await page.evaluate(async () => { location.hash = '#/help/automation-rules'; await new Promise(r => setTimeout(r, 800));
    return /Topic not found/.test(document.getElementById('help-topic')!.innerText); }));
  await page.locator('.user-profile-trigger').click();
  note('operator.accountMenu', (await page.locator('#my-account-btn').innerText().catch(() => '')).split('\n')[0]);
  await page.keyboard.press('Escape');
  // Top bar "?" on the environments page offers this page's topic.
  await visit(page, '#/my-environments', 'operator-envs');
  await page.locator('#topbar-help-btn').click();
  note('operator.helpMenu', await page.getByRole('menu', { name: 'Help' }).getByRole('menuitem').allInnerTexts());
  await page.keyboard.press('Escape');
  // Phone width: nothing wider than the screen on the help page.
  await page.setViewportSize({ width: 390, height: 844 });
  await visit(page, '#/help/locks', 'operator-phone');
  note('operator.phoneOverflow', await page.evaluate(() => document.documentElement.scrollWidth - innerWidth));
});

test('Viewer: help for their role', async ({ page }) => {
  test.setTimeout(600_000);
  await page.setViewportSize({ width: 1440, height: 900 });
  await signInAs(page, PEOPLE.viewer.user, PEOPLE.viewer.pass);
  await helpFacts(page, 'viewer');
});

test('Admin: all groups, every "?" resolves, Drift explains itself, signed-out text', async ({ page }) => {
  test.setTimeout(900_000);
  await page.setViewportSize({ width: 1440, height: 900 });
  await signInAdmin(page);
  await helpFacts(page, 'admin');
  note('admin.pageTopicsMissing', await page.evaluate(() => Object.entries(HelpContent.PAGE_TOPICS)
    .filter(([, id]) => !HelpContent.find(id)).map(([p]) => p)));
  // 0e5299e2: every page's "?" topic opens, even when its feature is switched off (it is then hidden from the list).
  const notFound: string[] = [];
  const hiddenButOpens: string[] = [];
  for (const id of await page.evaluate(() => Array.from(new Set(Object.values(HelpContent.PAGE_TOPICS))) as string[])) {
    await page.evaluate((h: string) => { location.hash = h; }, '#/help/' + id);
    await page.waitForTimeout(600);
    const r = await page.evaluate((t: string) => ({
      missing: /Topic not found/.test(document.getElementById('help-topic')!.innerText),
      listed: !!document.querySelector(`.help-topic-link[data-topic="${t}"]`),
    }), id);
    if (r.missing) notFound.push(id); else if (!r.listed) hiddenButOpens.push(id);
  }
  note('admin.pageTopicNotFound', notFound);
  note('admin.switchedOffTopicsOpenFromQuestionMark', hiddenButOpens);

  // VM registry: open an environment with drift, if any, and its Drift list.
  const summary = await apiGet(page, '/api/v1/environments/review-summary');
  const rows = summary.ok() ? await summary.json() : [];
  const withDrift = (Array.isArray(rows) ? rows : rows.content || []).find((r: any) => (r.drift || 0) > 0);
  note('admin.driftEnvironments', (Array.isArray(rows) ? rows : rows.content || []).filter((r: any) => (r.drift || 0) > 0).length);
  await visit(page, '#/vm-registry', 'admin-registry');
  if (withDrift) {
    await page.evaluate((id: string) => (window as any).VmRegistry?.selectEnvironment?.(id, { reviewState: 'DRIFT' }), withDrift.environmentId)
      .catch(() => {});
    await page.waitForTimeout(3000);
    const chip = page.locator('#vrReviewArea [data-action="vr-review-state"][data-state="DRIFT"]');
    if (await chip.count() && (await chip.getAttribute('aria-pressed')) !== 'true') await chip.click();
    await page.locator('#vrReviewHint').waitFor({ timeout: 30_000 }).catch(() => {});
    note('admin.driftHint', await page.locator('#vrReviewHint').innerText().catch(() => null));
    await shot(page, 'admin-drift');
  }
  note('admin.driftHelp', await page.evaluate(() => {
    const t = HelpContent.find('vm-registry');
    return t.sections.flatMap((s: any) => s.terms || []).find((x: any) => /Drift/.test(x[0]))?.[1] || null;
  }));
  note('admin.accountMenu', await page.evaluate(() => document.querySelector('#my-account-btn .dropdown-item-text span')?.textContent));

  // Sign out (allowed by the guard), then the sign-in page's note.
  await page.goto('/login?logout');
  note('signedOutNote', await page.locator('#logoutMessage').innerText().catch(() => null));
});
