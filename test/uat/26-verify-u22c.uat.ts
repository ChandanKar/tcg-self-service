import { test, signInAdmin, signInAs, PEOPLE, readState, writeState, visit } from './guard';

/** U22 follow-up on staging, read only: System health's figure, the start dialog's hours, the request form's fit. */

const facts: Record<string, any> = {};
const note = (k: string, v: any) => { facts[k] = v; writeState('verify-u22c', facts); };
const ise = () => readState<any>('preflight')!.ise.environmentId as string;

test('admin: System health matches Home; the ISE start dialog hours agree (opened, cancelled)', async ({ page }) => {
  test.setTimeout(300_000);
  await page.setViewportSize({ width: 1440, height: 900 });
  await signInAdmin(page);
  await visit(page, '#/dashboard', 'home');
  await page.locator('.ah-health').waitFor({ timeout: 60_000 }).catch(() => {});
  note('home', await page.locator('.ah-health').innerText().catch(() => null));
  await visit(page, '#/system-health', 'system-health');
  await page.waitForTimeout(4000);
  note('health', (await page.locator('#sh-tiles').innerText().catch(() => '')).replace(/\s+/g, ' '));
  await visit(page, `#/environments/${ise()}`, 'ise');
  const btn = page.locator('#btn-env-action');
  note('button', await btn.innerText().catch(() => null));
  if (/Start/.test(await btn.innerText().catch(() => ''))) {
    await btn.click();
    const dialog = page.locator('#operationConfirmModal');
    await dialog.waitFor({ timeout: 20_000 });
    await page.waitForTimeout(4000);
    note('dialog', (await dialog.innerText()).replace(/\n+/g, ' | ').slice(0, 700));
    await page.screenshot({ path: test.info().outputPath('ise-dialog.png') });
    await dialog.getByRole('button', { name: 'Cancel' }).click();
  }
});

test('operator: the request form with an environment picked fits 1366 x 657', async ({ page }) => {
  test.setTimeout(300_000);
  await page.setViewportSize({ width: 1366, height: 657 });
  await signInAs(page, PEOPLE.operator.user, PEOPLE.operator.pass);
  await visit(page, '#/request-access', 'request-access');
  await page.locator('.ra-env, [data-action*="request"], #content-area li, #content-area [role="option"]').first().click().catch(() => {});
  await page.getByText('adma poc aws', { exact: true }).first().click().catch(() => {});
  await page.waitForTimeout(2000);
  const send = page.getByRole('button', { name: /^Send/ }).first();
  const box = await send.boundingBox().catch(() => null);
  note('form', { sendBottom: box ? Math.round(box.y + box.height) : null, viewport: 657,
    scroll: await page.evaluate(() => document.documentElement.scrollHeight > window.innerHeight) });
  await page.screenshot({ path: test.info().outputPath('request-form.png') });
});
