import { test, signInAdmin, readState, visit } from './guard';

/**
 * Dry run (UAT_DRY=1): clicks Start All on ise and records the dialogs it shows; the guard refuses
 * the start request itself, so nothing is started. Used to learn the flow before the real run.
 */
test("dry: what Start All on ise shows", async ({ page, guard }) => {
  test.skip(process.env.UAT_DRY !== '1', 'dry run only');
  await signInAdmin(page);
  const ise = readState<any>('preflight')!.ise.environmentId;
  await visit(page, '#/environments/' + ise, 'dry-ise');
  const btn = page.locator('#btn-env-action');
  test.info().annotations.push({ type: 'button', description: (await btn.innerText()) + ' / ' + (await btn.getAttribute('data-action-type')) });
  await btn.click();
  await page.waitForTimeout(2500);
  const modal = page.locator('.modal.show').last();
  if (await modal.count()) {
    test.info().annotations.push({ type: 'modal', description: (await modal.innerText()).slice(0, 800) });
    test.info().annotations.push({ type: 'modal-buttons', description: (await modal.locator('button').allInnerTexts()).join(' | ') + ' || ids: ' + (await modal.getAttribute('id')) });
  }
  await page.screenshot({ path: test.info().outputPath('dry-dialog.png'), fullPage: true });
  const primary = modal.locator('.modal-footer .btn-primary, .modal-footer .btn-success, .modal-footer .btn-danger').last();
  if (await primary.count()) { await primary.click(); await page.waitForTimeout(2500); }
  await page.screenshot({ path: test.info().outputPath('dry-after-confirm.png'), fullPage: true });
});
