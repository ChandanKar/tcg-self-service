import { type Page } from '@playwright/test';
import { test, expect, signInAdmin, apiJson, readState, writeState, visit, verdict } from './guard';

/**
 * L03, recovery after a restart mid-operation (owner's go, 2026-11-12), in three phases run one at a time
 * (L03_PHASE): 'start' starts ise's stopped VMs and returns while the operation runs; the tester then
 * restarts the staging app; 'check' reads that operation after the app is back; 'cleanup' stops only the
 * VMs this run started (group-2 and group-3), leaving group-1, which someone else started, running.
 */
const PHASE = process.env.L03_PHASE || '';
const iseId = () => readState<any>('preflight')!.ise.environmentId as string;
const TERMINAL = ['COMPLETED', 'FAILED', 'CANCELLED', 'PARTIALLY_COMPLETED', 'PARTIAL', 'COMPLETED_WITH_ERRORS'];

async function confirmOp(page: Page, click: () => Promise<void>): Promise<string> {
  const posted = page.waitForResponse(r => r.request().method() === 'POST' && /\/operations$/.test(new URL(r.url()).pathname), { timeout: 45_000 });
  await click();
  const confirm = page.locator('#op-confirm-btn');
  await expect(confirm).toBeEnabled({ timeout: 20_000 });
  await page.waitForTimeout(1500);
  expect((await confirm.innerText()).trim()).not.toMatch(/approval/i);
  await confirm.click();
  const resp = await posted;
  expect(resp.status()).toBeLessThan(300);
  return (await resp.json()).executionId;
}

async function states(page: Page): Promise<Record<string, string>> {
  const groups = await apiJson(page, `/api/v1/environments/${iseId()}/vms`);
  const out: Record<string, string> = {};
  groups.forEach((g: any) => g.vms.forEach((v: any) => { out[`${g.group.name}:${v.name}`] = v.status; }));
  return out;
}

test.describe.configure({ timeout: 20 * 60_000 });

test('L03 start: start the stopped ise VMs and leave the operation running', async ({ page }) => {
  test.skip(PHASE !== 'start');
  await signInAdmin(page);
  const before = await states(page);
  await visit(page, '#/environments/' + iseId(), 'l03-before');
  const execId = await confirmOp(page, () => page.locator('#btn-env-action[data-action-type="start"]').click());
  await page.waitForTimeout(5000);
  const op = await apiJson(page, `/api/v1/environments/${iseId()}/operations/${execId}`);
  writeState('l03', { execId, before, startedAt: new Date().toISOString(), statusAtStart: op.status,
    steps: (op.details || []).map((d: any) => ({ vm: d.targetName, status: d.status })) });
  expect(TERMINAL).not.toContain(op.status);
});

test('L03 check: the interrupted operation is not left In progress', async ({ page }) => {
  test.skip(PHASE !== 'check');
  await signInAdmin(page);
  const s = readState<any>('l03')!;
  const op = await apiJson(page, `/api/v1/environments/${iseId()}/operations/${s.execId}`);
  await visit(page, '#/environments/' + iseId(), 'l03-after');
  const now = await states(page);
  const steps = (op.details || []).map((d: any) => ({ vm: d.targetName, status: d.status, error: d.errorMessage }));
  writeState('l03', { ...s, after: { status: op.status, error: op.errorMessage, steps, states: now, at: new Date().toISOString() } });
  const ok = TERMINAL.includes(op.status) && (op.status === 'COMPLETED' || !!(op.errorMessage || steps.some((x: any) => x.error)));
  verdict(ok ? 'accepted' : 'rejected', ok
    ? `Start of ise's two stopped VMs (op ${s.execId}) was running (${s.statusAtStart}) when the staging app was restarted; after the restart the operation is ${op.status}: ${(op.errorMessage || steps.map((x: any) => x.error).filter(Boolean)[0] || '').slice(0, 200)}. Steps: ${JSON.stringify(steps).slice(0, 300)}. It does not stay In progress.`
    : `After the restart op ${s.execId} is ${op.status} with ${op.errorMessage || 'no reason'}; steps ${JSON.stringify(steps).slice(0, 300)}`);
  expect(ok).toBeTruthy();
});

test('L03 cleanup: stop only the VMs this run started', async ({ page }) => {
  test.skip(PHASE !== 'cleanup');
  await signInAdmin(page);
  const s = readState<any>('l03')!;
  const preflight = readState<any>('preflight')!;
  const ours = (preflight.iseVms as any[]).filter(g => s.before[`${g.group.name}:${g.vms[0]?.name}`] === 'STOPPED');
  const stopped: string[] = [];
  for (const g of ours.reverse()) {          // bottom to top
    const now = await states(page);
    const vm = g.vms[0];
    if (now[`${g.group.name}:${vm.name}`] === 'STOPPED') continue;
    await visit(page, '#/environments/' + iseId(), `l03-cleanup-${g.group.name}`);
    const id = await confirmOp(page, () => page.locator(`#env-vms [data-action="stop-vm"][data-vm-id="${vm.vmId}"]:visible`).first().click());
    for (let i = 0; i < 60; i++) {
      const op = await apiJson(page, `/api/v1/environments/${iseId()}/operations/${id}`);
      if (TERMINAL.includes(op.status)) break;
      await page.waitForTimeout(10_000);
    }
    await page.goto('/home#/dashboard');
    stopped.push(vm.name);
  }
  const final = await states(page);
  writeState('l03-cleanup', { stopped, final });
  test.info().annotations.push({ type: 'final', description: JSON.stringify(final) });
  for (const g of ours) expect(final[`${g.group.name}:${g.vms[0].name}`]).toBe('STOPPED');
});
