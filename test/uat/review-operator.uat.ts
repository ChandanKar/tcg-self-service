import { type Page, type Locator } from '@playwright/test';
import * as fs from 'fs';
import { test, signInAs, PEOPLE, apiJson, readState, visit, describeVisit } from './guard';

/**
 * Operator review (read-only): walks an operator's day on ise, opens every dialog and closes it with
 * Cancel/Escape. Never clicks a confirm, Save, Submit or Request button. Run with UAT_DRY=1.
 */
const iseId = () => readState<any>('preflight')!.ise.environmentId as string;

type Notes = Record<string, unknown>;
function recorder(prefix: string) {
  const notes: Notes = {};
  const file = () => test.info().outputPath(prefix + '-notes.json');
  return {
    notes,
    put(k: string, v: unknown) { notes[k] = v; fs.writeFileSync(file(), JSON.stringify(notes, null, 2)); },
  };
}

async function shot(page: Page, name: string) {
  await page.screenshot({ path: test.info().outputPath(name + '.png'), fullPage: false });
}

async function text(l: Locator, max = 2500): Promise<string> {
  try { return (await l.innerText({ timeout: 5000 })).replace(/\n{2,}/g, '\n').slice(0, max); } catch { return '(none)'; }
}

/** The open dialog or panel, whichever is on top. */
function topLayer(page: Page): Locator {
  return page.locator('.modal.show, #dynamicSlideoutPanel.open, #dynamicSlideoutPanel.show, .slideout.open, [role="dialog"]:visible').last();
}

/** Closes a dialog with its Cancel/Close button, else Escape. Never a primary action. */
async function closeLayer(page: Page) {
  const layer = topLayer(page);
  if (await layer.count()) {
    const cancel = layer.locator('button').filter({ hasText: /^\s*(cancel|close|not now|keep|no)\s*$/i }).first();
    if (await cancel.count() && await cancel.isVisible()) { await cancel.click(); await page.waitForTimeout(600); return 'cancel'; }
    const x = layer.locator('.btn-close, [aria-label="Close"], [data-bs-dismiss="modal"]').first();
    if (await x.count() && await x.isVisible()) { await x.click(); await page.waitForTimeout(600); return 'x'; }
  }
  await page.keyboard.press('Escape');
  await page.waitForTimeout(600);
  return 'escape';
}

async function step(rec: ReturnType<typeof recorder>, name: string, fn: () => Promise<void>) {
  try { await test.step(name, fn); } catch (e: any) { rec.put('ERROR ' + name, String(e?.message || e).slice(0, 400)); }
}

async function openAndRead(page: Page, rec: ReturnType<typeof recorder>, key: string, trigger: Locator, shotName: string, wait = 2500) {
  if (!(await trigger.count())) { rec.put(key, 'trigger not found'); return; }
  rec.put(key + ' trigger', await text(trigger, 200));
  await trigger.first().click();
  await page.waitForTimeout(wait);
  await page.waitForLoadState('networkidle', { timeout: 20_000 }).catch(() => {});
  const layer = topLayer(page);
  rec.put(key, await text(layer));
  rec.put(key + ' buttons', await layer.locator('button:visible').allInnerTexts().catch(() => []));
  await shot(page, shotName);
  rec.put(key + ' closed by', await closeLayer(page));
}

async function walk(page: Page, prefix: string, phone: boolean) {
  const rec = recorder(prefix);
  const p = (n: string) => `${prefix}-${n}`;
  const t0 = Date.now();
  await signInAs(page, PEOPLE.operator.user, PEOPLE.operator.pass);
  rec.put('sign-in seconds', Math.round((Date.now() - t0) / 1000));

  await step(rec, 'home', async () => {
    const v = await visit(page, '#/dashboard', p('01-home'));
    await shot(page, p('01-home-fold'));
    rec.put('home visit', describeVisit(v) || 'clean');
    rec.put('home text', await text(page.locator('#content-area'), 3000));
  });

  await step(rec, 'environments', async () => {
    const v = await visit(page, '#/my-environments', p('02-environments'));
    rec.put('environments visit', describeVisit(v) || 'clean');
    rec.put('environments text', await text(page.locator('#content-area'), 2000));
  });

  await step(rec, 'api facts', async () => {
    const env: any = await apiJson(page, `/api/v1/environments/${iseId()}`).catch(e => ({ err: String(e) }));
    rec.put('api env', JSON.stringify(env).slice(0, 3000));
  });

  const envHash = '#/environments/' + iseId();
  await step(rec, 'ise detail', async () => {
    const v = await visit(page, envHash, p('03-ise'));
    await page.waitForTimeout(2500);
    await shot(page, p('03-ise-fold'));
    await page.screenshot({ path: test.info().outputPath(p('03-ise-full') + '.png'), fullPage: true });
    rec.put('ise visit', describeVisit(v) || 'clean');
    rec.put('ise text', await text(page.locator('#content-area'), 4000));
    rec.put('ise header buttons', await page.locator('#content-area button:visible').allInnerTexts());
  });

  if (phone) {
    await step(rec, 'phone open groups', async () => {
      const rows = page.locator('[data-action="env-phone-group"]');
      for (let i = 0; i < await rows.count(); i++) { await rows.nth(i).click(); await page.waitForTimeout(400); }
      await page.screenshot({ path: test.info().outputPath(p('03b-ise-groups-open') + '.png'), fullPage: true });
      rec.put('ise phone groups open', await text(page.locator('#env-vms'), 2000));
    });
  }

  await step(rec, 'vm details', async () => {
    await openAndRead(page, rec, 'vm details', page.locator('[data-action="vm-details"]:visible').first(), p('04-vm-details'));
  });
  await step(rec, 'insights', async () => {
    await openAndRead(page, rec, 'insights', page.locator('#btn-env-insights:visible'), p('05-insights'), 5000);
  });
  await step(rec, 'history', async () => {
    await openAndRead(page, rec, 'history', page.locator('#btn-operation-history:visible'), p('06-history'), 4000);
  });

  await step(rec, 'start env dialog', async () => {
    await openAndRead(page, rec, 'start env dialog', page.locator('#btn-env-action[data-action-type="start"]:visible'), p('07-start-dialog'), 4000);
  });
  await step(rec, 'start vm dialog', async () => {
    await openAndRead(page, rec, 'start vm dialog', page.locator('[data-action="start-vm"]:visible').first(), p('08-start-vm-dialog'), 4000);
  });
  await step(rec, 'start group dialog', async () => {
    await openAndRead(page, rec, 'start group dialog', page.locator('[data-action="group-action"][data-action-type="start"]:visible').first(), p('08b-start-group-dialog'), 4000);
  });
  await step(rec, 'stop group dialog', async () => {
    await openAndRead(page, rec, 'stop group dialog', page.locator('[data-action="group-action"][data-action-type="stop"]:visible').first(), p('09-stop-group-dialog'), 4000);
  });
  await step(rec, 'stop vm dialog', async () => {
    await openAndRead(page, rec, 'stop vm dialog', page.locator('[data-action="stop-vm"]:visible').first(), p('09b-stop-vm-dialog'), 4000);
  });
  await step(rec, 'stop all dialog', async () => {
    await openAndRead(page, rec, 'stop all dialog', page.locator('#btn-env-action-stop:visible'), p('09c-stop-all-dialog'), 4000);
  });
  await step(rec, 'lock dialog', async () => {
    await openAndRead(page, rec, 'lock dialog', page.locator('[data-lock-action="acquire"]:visible'), p('10-lock-dialog'), 2500);
  });
  await step(rec, 'after dialogs', async () => {
    await shot(page, p('11-ise-after-dialogs'));
    rec.put('ise after dialogs', await text(page.locator('#env-aside, .phone-env-details'), 1500));
  });

  await step(rec, 'request access', async () => {
    const v = await visit(page, '#/request-access', p('12-request-access'));
    rec.put('request visit', describeVisit(v) || 'clean');
    rec.put('request text', await text(page.locator('#content-area'), 3000));
  });

  await step(rec, 'activity', async () => {
    const v = await visit(page, '#/activity-logs', p('13-activity'));
    rec.put('activity visit', describeVisit(v) || 'clean');
    rec.put('activity text', await text(page.locator('#content-area'), 3000));
  });

  await step(rec, 'bell', async () => {
    const bell = page.locator('#notification-bell-btn:visible').first();
    if (!(await bell.count())) { rec.put('bell', 'not visible'); return; }
    await bell.click();
    await page.waitForTimeout(2500);
    rec.put('bell text', await text(page.locator('#notification-dropdown, .phone-notifications:visible').first(), 2000));
    await shot(page, p('14-bell'));
    await page.keyboard.press('Escape');
    await page.waitForTimeout(500);
  });

  await step(rec, 'my account', async () => {
    await visit(page, '#/dashboard', p('15-home-again'));
    await page.locator('.user-profile-trigger:visible').first().click();
    await page.waitForTimeout(800);
    await shot(page, p('15a-user-menu'));
    rec.put('user menu', await text(page.locator('#user-dropdown:visible, .phone-menu:visible').first(), 600));
    const acct = page.locator('#my-account-btn:visible');
    if (await acct.count()) await acct.click();
    await page.waitForTimeout(3000);
    for (const tab of ['access', 'cost', 'notifications', 'profile']) {
      const t = page.locator(`[data-ma-tab="${tab}"]:visible`);
      if (!(await t.count())) { rec.put('account ' + tab, 'tab not found'); continue; }
      await t.click();
      await page.waitForTimeout(2500);
      rec.put('account ' + tab, await text(page.locator('#dynamicSlideoutPanel'), 2500));
      await shot(page, p('16-account-' + tab));
    }
    rec.put('account closed by', await closeLayer(page));
  });

  await step(rec, 'help', async () => {
    const v = await visit(page, '#/help', p('17-help'));
    rec.put('help visit', describeVisit(v) || 'clean');
    rec.put('help text', await text(page.locator('#content-area'), 2500));
    await visit(page, envHash, p('17b-ise-for-help'));
    const q = page.locator('#topbar-help-btn:visible');
    if (await q.count()) {
      await q.click();
      await page.waitForTimeout(600);
      rec.put('help menu', await text(page.locator('#help-menu'), 600));
      await shot(page, p('18-help-menu'));
      const pageHelp = page.locator('#help-menu [data-help-menu="page"]');
      if (await pageHelp.count()) {
        await pageHelp.click();
        await page.waitForTimeout(2500);
        rec.put('page help', await text(page.locator('#content-area'), 2500));
        await shot(page, p('19-page-help'));
      } else { await page.keyboard.press('Escape'); }
    } else rec.put('help menu', 'no topbar ? visible');
  });

  await step(rec, 'admin url', async () => {
    const v = await visit(page, '#/user-management', p('20-admin-refusal'));
    rec.put('admin visit', describeVisit(v) || 'clean');
    rec.put('admin text', await text(page.locator('#content-area'), 1500));
    rec.put('admin hash after', await page.evaluate(() => location.hash));
  });
}

test.describe.configure({ mode: 'serial' });

test('operator day on a laptop (1366x657)', async ({ page }) => {
  test.setTimeout(30 * 60_000);
  await page.setViewportSize({ width: 1366, height: 657 });
  await walk(page, 'laptop', false);
});

test('follow-up: phone Insights, More menu, cost card; who started the running VM', async ({ page }) => {
  test.skip(process.env.REVIEW_FOLLOWUP !== '1', 'follow-up only');
  test.setTimeout(30 * 60_000);
  const rec = recorder('followup');
  await page.setViewportSize({ width: 390, height: 844 });
  await signInAs(page, PEOPLE.operator.user, PEOPLE.operator.pass);
  await visit(page, '#/environments/' + iseId(), 'fu-01-ise');
  await page.waitForTimeout(2500);
  await step(rec, 'insights hit test', async () => {
    const b = page.locator('#btn-env-insights');
    await b.scrollIntoViewIfNeeded();
    const box = await b.boundingBox();
    rec.put('insights box', box);
    rec.put('viewport', page.viewportSize());
    if (box) rec.put('element at insights centre', await page.evaluate(([x, y]) => {
      const el = document.elementFromPoint(x, y) as HTMLElement | null;
      return el ? `${el.tagName}#${el.id}.${el.className}` : null;
    }, [box.x + box.width / 2, box.y + box.height / 2]));
    await page.screenshot({ path: test.info().outputPath('fu-02-insights-scrolled.png') });
    await b.click({ timeout: 8000 }).catch(e => rec.put('insights click', String(e.message).split('\n').slice(0, 8).join(' | ')));
    await page.waitForTimeout(4000);
    await page.screenshot({ path: test.info().outputPath('fu-03-insights-after-click.png') });
    await closeLayer(page);
  });
  await step(rec, 'cost card', async () => {
    const c = page.locator('.env-cost-fact');
    await c.scrollIntoViewIfNeeded();
    await c.screenshot({ path: test.info().outputPath('fu-04-cost-card.png') });
    rec.put('cost card text', await text(c, 600));
    rec.put('schedule fact', await text(page.locator('#env-fact-schedule'), 400));
    rec.put('schedule fact html', (await page.locator('#env-fact-schedule').innerHTML()).slice(0, 400));
  });
  await step(rec, 'more menu', async () => {
    await page.locator('#mobile-more-btn').click();
    await page.waitForTimeout(1000);
    await page.screenshot({ path: test.info().outputPath('fu-05-more.png') });
    rec.put('more text', await text(page.locator('#sidebar'), 800));
    await page.keyboard.press('Escape');
  });
  await step(rec, 'request access phone form', async () => {
    await visit(page, '#/request-access', 'fu-06-request');
    await page.screenshot({ path: test.info().outputPath('fu-06b-request-full.png'), fullPage: true });
  });
  await page.setViewportSize({ width: 1366, height: 657 });
  await step(rec, 'who started master', async () => {
    await visit(page, '#/environments/' + iseId(), 'fu-07-ise-laptop');
    await page.locator('#btn-operation-history:visible').click();
    await page.waitForTimeout(3000);
    const pages: string[] = [];
    for (let i = 0; i < 7; i++) {
      const t = await text(topLayer(page), 3000);
      pages.push(t);
      if (/Start(, whole environment| mcube_dev_poc_master| Group-1)[\s\S]*?\n/.test(t) && /07:0\d/.test(t)) break;
      const next = topLayer(page).getByRole('button', { name: 'Next' });
      if (!(await next.isEnabled())) break;
      await next.click();
      await page.waitForTimeout(2000);
    }
    rec.put('history pages', pages);
    await page.screenshot({ path: test.info().outputPath('fu-08-history-last-page.png') });
    await closeLayer(page);
  });
  await step(rec, 'laptop scroll on ise', async () => {
    await visit(page, '#/environments/' + iseId(), 'fu-09-ise-laptop');
    rec.put('scroll', await page.evaluate(() => {
      const main = document.querySelector('#content-area') as HTMLElement;
      const scrollers = [...document.querySelectorAll('*')].filter(e => { const s = getComputedStyle(e); return /(auto|scroll)/.test(s.overflowY) && (e as HTMLElement).scrollHeight > (e as HTMLElement).clientHeight + 2; })
        .map(e => `${e.tagName}#${e.id}.${String((e as HTMLElement).className).slice(0, 40)} ${(e as HTMLElement).scrollHeight}/${(e as HTMLElement).clientHeight}`);
      return { page: document.scrollingElement!.scrollHeight - document.scrollingElement!.clientHeight, main: main.scrollHeight - main.clientHeight, scrollers };
    }));
  });
});

test('operator day on a phone (390x844)', async ({ page }) => {
  test.setTimeout(30 * 60_000);
  await page.setViewportSize({ width: 390, height: 844 });
  await walk(page, 'phone', true);
});
